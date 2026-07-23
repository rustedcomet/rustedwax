package com.rustedwax.app.scrobble

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.rustedwax.app.hive.HiveBroadcaster
import com.rustedwax.app.hive.HiveRpc
import com.rustedwax.app.hive.HiveScrobblePayload
import com.rustedwax.app.detect.ScrobbleBuilder
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.storage.KeyVault
import com.rustedwax.app.storage.Settings

/**
 * Turns finished tracks into on-chain scrobbles.
 *
 * The pipeline, mirroring what `hive-scrobbler.ts` does across its finalize and
 * broadcast paths:
 *
 *   finalize → identity check → rules (60%/160%) → dedup → sign → broadcast
 *                                                              ↘ queue on failure
 *
 * A singleton because the detection host ([com.rustedwax.app.detect.RustedWaxListenerService])
 * and the UI are separate processes-in-spirit that must share one ledger and
 * one queue. Initialised once from application context.
 */
object ScrobbleEngine {

	private lateinit var appContext: Context
	private lateinit var vault: KeyVault
	private lateinit var ledger: DedupLedger
	private lateinit var queue: BroadcastQueue
	private lateinit var settings: Settings
	private val broadcaster = HiveBroadcaster()
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val broadcastLock = Mutex()

	@Volatile
	private var initialised = false

	data class ScrobbleRecord(
		val title: String,
		val artist: String?,
		val percentPlayed: Int,
		val atEpochSec: Long,
		val status: String,
		val txId: String? = null,
		val queued: Boolean = false,
	)

	private val _recent = MutableStateFlow<List<ScrobbleRecord>>(emptyList())
	val recent: StateFlow<List<ScrobbleRecord>> = _recent.asStateFlow()

	private val _queueSize = MutableStateFlow(0)
	val queueSize: StateFlow<Int> = _queueSize.asStateFlow()

	@Synchronized
	fun init(context: Context) {
		if (initialised) return
		appContext = context.applicationContext
		vault = KeyVault(appContext)
		ledger = DedupLedger(appContext)
		queue = BroadcastQueue(appContext)
		settings = Settings(appContext)
		initialised = true
		ledger.prune()
		_queueSize.value = queue.size()
	}

	val isReady: Boolean get() = initialised

	fun autoScrobbleEnabled(): Boolean = initialised && settings.autoScrobble

	fun setAutoScrobble(enabled: Boolean) {
		settings.autoScrobble = enabled
		EventLog.append("engine", "auto-scrobble ${if (enabled) "on" else "off"}")
	}

	/**
	 * Entry point from the probe. Decides and broadcasts; never throws into the
	 * caller, which is a media-session callback on the main thread.
	 */
	fun onTrackFinalized(session: SessionSnapshot) {
		if (!initialised) return
		if (!settings.autoScrobble) {
			EventLog.append("engine", "auto-scrobble off — not scrobbling")
			return
		}
		if (!session.isTarget) return

		if (!session.isYouTube) {
			EventLog.append("engine", "skipped: source not proven YouTube")
			return
		}

		val decision = ScrobbleRules.decide(
			playedMs = session.playedMs,
			durationMs = session.durationMs,
			threshold = settings.scrobbleThreshold,
		)
		if (!decision.shouldScrobble) {
			EventLog.append("engine", "skipped: ${decision.skippedBecause}")
			return
		}

		val basePayload = ScrobbleBuilder.from(session)
		if (basePayload == null) {
			EventLog.append("engine", "skipped: payload not buildable")
			return
		}

		val dedupKey = DedupLedger.keyFor(
			basePayload.title,
			basePayload.artist,
			session.trackStartedAtEpochSec,
		)
		if (!ledger.claim(dedupKey)) {
			EventLog.append("engine", "skipped: already scrobbled [$dedupKey]")
			return
		}

		// One tx per entry — the 160% double-listen produces two.
		decision.percentages.forEach { percent ->
			enqueueAndSend(basePayload.copy(percentPlayed = percent))
		}
	}

	/** Retry anything waiting in the queue. Safe to call often. */
	fun flushQueue() {
		if (!initialised) return
		scope.launch {
			broadcastLock.withLock {
				val account = vault.account ?: return@withLock
				val key = vault.loadKey() ?: return@withLock
				for (entry in queue.due()) {
					val result = runCatching {
						broadcaster.broadcastJson(entry.username, key, entry.json)
					}.getOrElse { HiveRpc.BroadcastResult.NetworkFailure(it.message ?: "error") }

					when (result) {
						is HiveRpc.BroadcastResult.Success -> {
							queue.remove(entry.id)
							EventLog.append(
								"engine",
								"queued scrobble sent: ${entry.label} — tx ${result.txId}",
							)
							note(entry.label, "sent from queue", result.txId)
						}

						is HiveRpc.BroadcastResult.Rejected -> {
							// The chain will keep rejecting this; don't loop.
							queue.remove(entry.id)
							EventLog.append(
								"engine",
								"queued scrobble dropped (rejected): ${result.message}",
							)
						}

						is HiveRpc.BroadcastResult.NetworkFailure -> {
							queue.recordFailure(entry.id, result.message)
						}
					}
				}
				_queueSize.value = queue.size()
				if (account.username.isNotEmpty()) ledger.prune()
			}
		}
	}

	private fun enqueueAndSend(payload: HiveScrobblePayload) {
		val account = vault.account
		if (account == null) {
			EventLog.append("engine", "no key saved — scrobble dropped")
			return
		}
		val label = "${payload.artist?.plus(" — ") ?: ""}${payload.title}"
		val json = payload.toJson()

		scope.launch {
			broadcastLock.withLock {
				val key = vault.loadKey()
				if (key == null) {
					EventLog.append("engine", "key unreadable — scrobble dropped")
					return@withLock
				}
				EventLog.append("engine", "broadcasting: $json")
				val result = runCatching {
					broadcaster.broadcastJson(account.username, key, json)
				}.getOrElse { HiveRpc.BroadcastResult.NetworkFailure(it.message ?: "error") }

				when (result) {
					is HiveRpc.BroadcastResult.Success -> {
						EventLog.append("engine", "scrobbled: $label — tx ${result.txId}")
						note(label, "scrobbled", result.txId, payload.percentPlayed)
					}

					is HiveRpc.BroadcastResult.Rejected -> {
						// Bad auth, expired, out of RC — queuing would just
						// replay the same rejection.
						EventLog.append("engine", "rejected: ${result.message}")
						note(label, "rejected: ${result.message}", null, payload.percentPlayed)
					}

					is HiveRpc.BroadcastResult.NetworkFailure -> {
						queue.add(account.username, json, label)
						_queueSize.value = queue.size()
						EventLog.append("engine", "queued (offline): $label")
						note(label, "queued — offline", null, payload.percentPlayed, queued = true)
					}
				}
			}
		}
	}

	private fun note(
		label: String,
		status: String,
		txId: String?,
		percent: Int? = null,
		queued: Boolean = false,
	) {
		val artist = label.substringBefore(" — ", "").ifEmpty { null }
		val title = label.substringAfter(" — ", label)
		_recent.value = (
			listOf(
				ScrobbleRecord(
					title = title,
					artist = artist,
					percentPlayed = percent ?: 0,
					atEpochSec = System.currentTimeMillis() / 1000,
					status = status,
					txId = txId,
					queued = queued,
				),
			) + _recent.value
			).take(50)
	}
}
