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
import com.rustedwax.app.detect.TitleParser
import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.enrich.FactsCache
import com.rustedwax.app.enrich.MetadataResolver
import com.rustedwax.app.enrich.MusicBrainzVerifier
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.enrich.YouTubePageResolver
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
	private lateinit var resolver: MetadataResolver
	private lateinit var factsCache: FactsCache
	private lateinit var musicBrainz: MusicBrainzVerifier
	private val broadcaster = HiveBroadcaster()

	/**
	 * Video ids a prefetch has already been launched for this session. Never
	 * cleared on failure: a video that couldn't be resolved once (offline,
	 * markup drift) shouldn't be re-fetched every second by the UI tick that
	 * triggers identity checks.
	 */
	private val prefetched = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
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
		factsCache = FactsCache(appContext)
		resolver = YouTubePageResolver(factsCache)
		musicBrainz = MusicBrainzVerifier(appContext)
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
		// Belt and braces. With monitoring off the probe is torn down and this
		// can't be reached at all, but a finalize racing a Stop must lose.
		if (!settings.monitoringEnabled) {
			EventLog.append("engine", "monitoring stopped — not scrobbling")
			return
		}
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

		// Enrichment is a network call, so the payload can no longer be built on
		// this thread. Everything downstream of it moves into the coroutine —
		// including the dedup claim, which must be computed from the *corrected*
		// title and artist or a fixed parse would look like a new track.
		scope.launch {
			val facts = enrich(session)
			val mb = verifyMusic(session, facts)

			val basePayload = ScrobbleBuilder.from(session, facts, mb)
			if (basePayload == null) {
				EventLog.append("engine", "skipped: payload not buildable")
				return@launch
			}

			val dedupKey = DedupLedger.keyFor(
				basePayload.title,
				basePayload.artist,
				session.trackStartedAtEpochSec,
			)
			if (!ledger.claim(dedupKey)) {
				EventLog.append("engine", "skipped: already scrobbled [$dedupKey]")
				return@launch
			}

			// One tx per entry — the 160% double-listen produces two, songs only.
			val percentages = ScrobbleRules.capForKind(decision.percentages, basePayload.kind)
			if (percentages.size < decision.percentages.size) {
				EventLog.append(
					"engine",
					"double-listen capped to one tx (kind=${basePayload.kind})",
				)
			}
			percentages.forEach { percent ->
				enqueueAndSend(basePayload.copy(percentPlayed = percent))
			}
		}
	}

	/**
	 * Best-effort MusicBrainz confirmation of the artist/track the payload
	 * would carry. Null when disabled, unparseable, or the network couldn't
	 * answer — every path degrades to the pre-MusicBrainz behaviour.
	 */
	private suspend fun verifyMusic(
		session: SessionSnapshot,
		facts: VideoFacts?,
	): MusicBrainzVerifier.Match? {
		if (!settings.enrichment) return null
		val credits = ScrobbleBuilder.creditsOf(session, facts) ?: return null
		val artist = credits.artist ?: return null
		return runCatching { musicBrainz.verify(artist, credits.track) }.getOrElse {
			EventLog.append("musicbrainz", "verifier threw: ${it.message}")
			null
		}
	}

	/** Cache-only MusicBrainz verdict for the Now-tab preview. */
	fun cachedMusicMatch(
		session: SessionSnapshot,
		facts: VideoFacts?,
	): MusicBrainzVerifier.Match? {
		if (!initialised || !settings.enrichment) return null
		val credits = ScrobbleBuilder.creditsOf(session, facts) ?: return null
		val artist = credits.artist ?: return null
		return musicBrainz.cached(artist, credits.track)
	}

	/**
	 * Start resolving a video's facts the moment it's identified, instead of
	 * at finalize minutes later.
	 *
	 * Two reasons this matters beyond latency. The broadcast path stops
	 * depending on a live network at the exact moment a track ends. And the
	 * Now-tab preview reads the same cache via [cachedFacts], so the kind it
	 * shows is the kind that will be broadcast — during the field test the
	 * preview classified from the title alone while enrichment later said
	 * otherwise, and the mismatch made the filter look arbitrary.
	 */
	fun prefetch(videoId: String) {
		if (!initialised || !settings.enrichment) return
		if (!prefetched.add(videoId)) return
		scope.launch {
			val facts = runCatching { resolver.resolve(videoId) }
				.onFailure { EventLog.append("enrich", "prefetch failed: ${it.message}") }
				.getOrNull() ?: return@launch
			// Chain the MusicBrainz check so the Now card's verdict is warm by
			// the time anyone looks. Same credits derivation as the payload.
			val rawTitle = facts.title ?: return@launch
			val parsed = TitleParser.parse(rawTitle, facts.author)
			val artist = facts.originalArtist ?: parsed.artist ?: return@launch
			val track = facts.originalTitle?.let { TitleParser.clean(it) } ?: parsed.track
			runCatching { musicBrainz.verify(artist, track) }
		}
	}

	/** Already-resolved facts, memory/disk only — never the network. */
	fun cachedFacts(videoId: String): VideoFacts? =
		if (initialised) factsCache.get(videoId) else null

	/**
	 * Best-effort lookup of the video's own metadata.
	 *
	 * Returns null on every failure path — disabled, no video id, network down,
	 * markup changed. A scrobble is never lost because enrichment was, and the
	 * offline parse is always a complete answer on its own.
	 */
	private suspend fun enrich(session: SessionSnapshot): VideoFacts? {
		if (!settings.enrichment) return null
		val videoId = session.confirmed?.videoId ?: run {
			EventLog.append("enrich", "no video id — offline parse only")
			return null
		}
		return runCatching { resolver.resolve(videoId) }.getOrElse {
			EventLog.append("enrich", "resolver threw: ${it.message}")
			null
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
