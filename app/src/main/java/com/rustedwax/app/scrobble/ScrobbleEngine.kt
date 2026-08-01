package com.rustedwax.app.scrobble

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.rustedwax.app.hive.HiveBroadcaster
import com.rustedwax.app.hive.HiveRpc
import com.rustedwax.app.hive.HiveScrobblePayload
import com.rustedwax.app.detect.ScrobbleBuilder
import com.rustedwax.app.detect.SessionProbe
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.TitleParser
import com.rustedwax.app.detect.UrlEvidence
import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.enrich.FactsCache
import com.rustedwax.app.enrich.MetadataResolver
import com.rustedwax.app.enrich.MusicBrainzVerifier
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.enrich.VideoIdResolver
import com.rustedwax.app.enrich.YouTubePageResolver
import com.rustedwax.app.storage.KeyVault
import com.rustedwax.app.storage.MutedVideos
import com.rustedwax.app.storage.Settings

/**
 * Turns finished tracks into on-chain scrobbles.
 *
 * The pipeline, mirroring what `hive-scrobbler.ts` does across its finalize and
 * broadcast paths:
 *
 *   finalize → identity → prefilter ┊ enrich → rules → dedup → sign → broadcast
 *                                   ┊                                ↘ queue on failure
 *
 * The `┊` is the thread boundary. Everything left of it is cheap and
 * synchronous; everything right of it may touch the network. The rules run on
 * the far side because two of their inputs — the recovered duration and the
 * watch-page proof behind the short-clip floor — don't exist until enrichment
 * has answered. [ScrobbleRules.prefilter] is what keeps that from meaning "one
 * fetch per finalize".
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
	private lateinit var muted: MutedVideos
	private val idResolver = VideoIdResolver()
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
		/**
		 * The video this entry came from, when one was identified. Carried so the
		 * History row can offer "never scrobble this again" — see [MutedVideos].
		 */
		val videoId: String? = null,
	)

	/**
	 * A track that finished and did *not* become an entry, with the reason.
	 *
	 * The reasons were always computed — they went to the event log and nowhere
	 * else. From the user's side that made "watched 20 shorts, got 6 entries"
	 * indistinguishable from a broken app: there was no artifact anywhere in the
	 * UI saying the other 14 were seen and why each was declined. A gap you can
	 * explain is a policy; a silent one reads as a bug every time.
	 */
	data class SkipRecord(
		val title: String,
		val artist: String?,
		val reason: String,
		val atEpochSec: Long,
		val playedSeconds: Long,
		val durationSeconds: Long?,
	)

	/**
	 * Below this, a finalize is a metadata transition rather than a listen
	 * anyone will go looking for.
	 *
	 * Not a guess: the 2026-07-29 session produced 198 "no duration" skips, and
	 * 165 of them had played for under three seconds — the browser swapping a
	 * placeholder title for the real one as a page loads. Recording those would
	 * bury the 33 that a person might actually wonder about.
	 */
	private const val MIN_NOTABLE_PLAYED_MS = 3_000L

	private val _recent = MutableStateFlow<List<ScrobbleRecord>>(emptyList())
	val recent: StateFlow<List<ScrobbleRecord>> = _recent.asStateFlow()

	private val _skipped = MutableStateFlow<List<SkipRecord>>(emptyList())
	val skipped: StateFlow<List<SkipRecord>> = _skipped.asStateFlow()

	/**
	 * Consecutive finalized tracks that ended with no video id at all.
	 *
	 * This is the counter behind the "the address bar has gone quiet" warning, and
	 * it exists because of a 13-minute hole in the 2026-07-29 session. The watcher
	 * reported `connected`, then read the collapsed omnibox once —
	 * `host=m.youtube.com video=—` — and said nothing again for 13 minutes.
	 *
	 * The visible cost was five shorts scrobbled with no `url`. The invisible cost
	 * was larger: four more were **lost entirely**, because with no id there is no
	 * `/shorts/` proof, so the short-clip floor couldn't apply and 15 s, 21 s and
	 * 25 s clips were held to the 30-second minimum.
	 *
	 * Nothing told the user. The app knew the watcher was on and knew it hadn't
	 * identified a single track in nine finalizes — the same "silence reads as
	 * broken" failure the Not-logged tab was built for, one layer down.
	 *
	 * Counted per *finalize* rather than on a timer, because that measures the
	 * actual harm. A long watch-page video legitimately produces one id and then
	 * silence for an hour; that must not warn.
	 */
	private val _tracksWithoutVideoId = MutableStateFlow(0)
	val tracksWithoutVideoId: StateFlow<Int> = _tracksWithoutVideoId.asStateFlow()

	/** Consecutive misses before the UI says so. Three is a pattern, one is a page load. */
	const val QUIET_BAR_THRESHOLD = 3

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
		muted = MutedVideos(appContext)
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
			skip(session, "source not proven YouTube")
			return
		}

		// Stage 1, on this thread: reject what no lookup could rescue, so a
		// shorts feed's steady stream of two-second finalizations never reaches
		// the network. See [ScrobbleRules.prefilter].
		ScrobbleRules.prefilter(
			playedMs = session.playedMs,
			durationMs = session.durationMs,
			threshold = settings.scrobbleThreshold,
			explicitAdSignal = session.explicitAdSignal,
		)?.let { reason ->
			skip(session, reason)
			return
		}

		// Enrichment is a network call, so the payload can no longer be built on
		// this thread. Everything downstream of it moves into the coroutine —
		// including the dedup claim, which must be computed from the *corrected*
		// title and artist or a fixed parse would look like a new track.
		scope.launch {
			// The address bar is the exact source when it spoke; searching is
			// the fallback for when it never did (a playlist advancing behind a
			// hidden toolbar fires no accessibility event at all).
			val videoId = session.confirmed?.videoId ?: resolveVideoId(session)
			noteVideoIdOutcome(videoId)
			if (videoId == null) {
				skip(
					session,
					"video id could not be verified — no scrobble was broadcast " +
						"because every YouTube entry requires a hyperlink",
				)
				return@launch
			}

			// Asked before anything else is spent on it. An id the user has muted
			// is not a listen at all, so there is nothing to look up, verify or
			// measure. See [MutedVideos] for why this exists rather than a rule.
			if (muted.isMuted(videoId)) {
				skip(session, "muted video — you asked never to scrobble this one again")
				return@launch
			}

			val facts = enrich(videoId)
			val mb = verifyMusic(session, facts)

			// Stage 2: the duration may have arrived from the watch page, and
			// the short-clip floor needs the watch-page proof that only exists
			// now. Both the rules and the payload read the same value.
			val durationMs = ScrobbleBuilder.effectiveDurationMs(session, facts)
			if (session.durationMs == null && durationMs != null) {
				EventLog.append(
					"engine",
					"duration recovered from the watch page: ${durationMs / 1000}s",
				)
			}
			val decision = ScrobbleRules.decide(
				playedMs = session.playedMs,
				durationMs = durationMs,
				threshold = settings.scrobbleThreshold,
				isShort = session.confirmed?.isShort == true,
				videoResolved = facts?.resolvedOnWatchPage == true,
				videoUnlisted = facts?.isUnlisted,
				shortClipsEnabled = settings.shortClips,
				explicitAdSignal = session.explicitAdSignal,
			)
			if (!decision.shouldScrobble) {
				skip(session, decision.skippedBecause ?: "no reason given", durationMs)
				return@launch
			}

			val basePayload = ScrobbleBuilder.from(session, facts, mb, videoId, durationMs)
			if (basePayload == null) {
				skip(session, "payload not buildable", durationMs)
				return@launch
			}

			// Defense in depth. The video-id gate above makes this unreachable, but
			// payload construction is a separate component and the chain cannot be
			// edited if those two paths ever drift again.
			if (basePayload.url == null) {
				skip(
					session,
					"internal hyperlink invariant failed — nothing broadcast",
					durationMs,
				)
				return@launch
			}

			val dedupKey = DedupLedger.keyFor(
				basePayload.title,
				basePayload.artist,
				session.trackStartedAtEpochSec,
			)
			if (!ledger.claim(dedupKey)) {
				EventLog.append("engine", "skipped: already scrobbled [$dedupKey]")
				skip(session, "already scrobbled this listen", durationMs, log = false)
				return@launch
			}

				// One tx per entry — the 160% double-listen produces two, songs only.
				val percentages = ScrobbleRules.capForKind(
					decision.percentages,
					basePayload.kind,
					isShort = session.confirmed?.isShort == true,
					loopDetected = session.loopDetected,
				)
				if (session.loopDetected) {
					EventLog.append(
						"engine",
						"playback loop detected — first viewing kept; " +
							"continuous viewing capped to one scrobble",
					)
				} else if (decision.probableLoop) {
					EventLog.append(
						"engine",
						"probable short loop detected — first viewing kept; " +
							"video remains capped to one scrobble",
					)
				} else if (percentages.size < decision.percentages.size) {
					EventLog.append(
						"engine",
						"repeat playback capped to one tx (kind=${basePayload.kind})",
					)
				}
			percentages.forEach { percent ->
				enqueueAndSend(basePayload.copy(percentPlayed = percent), videoId)
			}
		}
	}

	/**
	 * The artist/track pairs worth asking MusicBrainz about, most likely
	 * first: the parsed pair, then the *swapped* pair. The swap exists because
	 * `Title | Channel`-shaped uploads split backwards (observed on-chain:
	 * artist "Michael Jackson MTV Awards 1995…" title "Remastered HD") — when
	 * the reversed pair is the real recording, MusicBrainz says so, and its
	 * canonical fields land in the payload the right way round.
	 */
	private fun mbCandidates(credits: ScrobbleBuilder.Parsed): List<Pair<String, String>> {
		val artist = credits.artist?.takeIf { it.isNotBlank() } ?: return emptyList()
		val out = mutableListOf(artist to credits.track)
		if (!credits.track.equals(artist, ignoreCase = true)) {
			out += credits.track to artist
		}
		return out
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
		var last: MusicBrainzVerifier.Match? = null
		for ((artist, track) in mbCandidates(credits)) {
			val match = runCatching { musicBrainz.verify(artist, track) }.getOrElse {
				EventLog.append("musicbrainz", "verifier threw: ${it.message}")
				null
			}
			if (match?.found == true) return match
			last = match ?: last
		}
		return last
	}

	/** Cache-only MusicBrainz verdict for the Now-tab preview. */
	fun cachedMusicMatch(
		session: SessionSnapshot,
		facts: VideoFacts?,
	): MusicBrainzVerifier.Match? {
		if (!initialised || !settings.enrichment) return null
		val credits = ScrobbleBuilder.creditsOf(session, facts) ?: return null
		var last: MusicBrainzVerifier.Match? = null
		for ((artist, track) in mbCandidates(credits)) {
			val match = musicBrainz.cached(artist, track)
			if (match?.found == true) return match
			last = match ?: last
		}
		return last
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
			val credits = ScrobbleBuilder.Parsed(
				artist = facts.originalArtist ?: parsed.artist,
				track = facts.originalTitle?.let { TitleParser.clean(it) } ?: parsed.track,
			)
			for ((artist, track) in mbCandidates(credits)) {
				val match = runCatching { musicBrainz.verify(artist, track) }.getOrNull()
				if (match?.found == true) break
			}
		}
	}

	/**
	 * Never scrobble this video again.
	 *
	 * The user's escape hatch for promoted content the rules can't see — see
	 * [MutedVideos]. It cannot unwrite what is already on-chain; it stops the
	 * same video counting again when the feed brings it back.
	 */
	fun mute(videoId: String, label: String) {
		if (!initialised) return
		muted.mute(videoId, label)
		EventLog.append("engine", "muted $videoId — \"$label\" will not scrobble again")
	}

	fun unmute(videoId: String) {
		if (!initialised) return
		muted.unmute(videoId)
		EventLog.append("engine", "unmuted $videoId")
	}

	fun isMuted(videoId: String): Boolean = initialised && muted.isMuted(videoId)

	/** Muted ids with the labels they were muted under, for the UI list. */
	fun mutedVideos(): Map<String, String> = if (initialised) muted.all() else emptyMap()

	/** Already-resolved facts, memory/disk only — never the network. */
	fun cachedFacts(videoId: String): VideoFacts? =
		if (initialised) factsCache.get(videoId) else null

	/**
	 * The two facts the probe uses to disprove a latched video id, cache-only.
	 *
	 * Kept here rather than in the probe so the probe never learns about
	 * `VideoFacts`: it corroborates identity, it doesn't consume metadata.
	 */
	fun knownVideo(videoId: String): SessionProbe.KnownVideo? =
		cachedFacts(videoId)?.let {
			SessionProbe.KnownVideo(title = it.title, lengthSeconds = it.lengthSeconds)
		}

	/**
	 * Best-effort lookup of the video's own metadata.
	 *
	 * Returns null on every metadata failure path — disabled, network down, or
	 * markup changed. Video identity is a separate mandatory gate that has
	 * already passed before this method runs.
	 */
	private suspend fun enrich(videoId: String): VideoFacts? {
		if (!settings.enrichment) return null
		return runCatching { resolver.resolve(videoId) }.getOrElse {
			EventLog.append("enrich", "resolver threw: ${it.message}")
			null
		}
	}

	/**
	 * Search-based recovery of a video id the address bar never supplied.
	 *
	 * Gated behind the same "Look videos up" switch — it is off-device traffic
	 * — and fails closed. If the match is not certain, finalization records the
	 * item in Not logged and never constructs an on-chain entry.
	 */
	private suspend fun resolveVideoId(session: SessionSnapshot): String? {
		if (!settings.enrichment) return null
		val title = session.title ?: return null
		val durationSec = session.durationMs?.div(1000)
		return runCatching {
			// The playlist is exact where search is only plausible, and after
			// the first fetch it costs nothing for the rest of the playlist.
			UrlEvidence.playlistId(session.packageName)?.let { list ->
				idResolver.resolveFromPlaylist(list, title, session.artist, durationSec)
			} ?: idResolver.resolve(title, session.artist, durationSec)
		}.getOrElse {
			EventLog.append("resolve", "resolver threw: ${it.message}")
			null
		}
	}

	/**
	 * Claim a listen on behalf of the Now card's **Broadcast this scrobble**
	 * button, so the two paths share one ledger.
	 *
	 * Until v0.5.4 the manual button bypassed the ledger entirely, which
	 * produced the duplicate pairs seen on-chain: a manual send left no claim,
	 * so the automatic finalize minutes later saw a free key and broadcast the
	 * same listen again (e.g. 29% manual, then 95% automatic). It also let two
	 * quick taps through, one second apart.
	 *
	 * Claimed *before* sending so concurrent taps can't both pass; the caller
	 * must [releaseManualClaim] if the send fails, since the manual path has no
	 * queue to fall back on.
	 *
	 * @return false when this listen is already in the ledger
	 */
	fun claimManual(payload: HiveScrobblePayload, startedAtEpochSec: Long): Boolean {
		if (!initialised) return true
		val key = DedupLedger.keyFor(payload.title, payload.artist, startedAtEpochSec)
		val claimed = ledger.claim(key)
		EventLog.append(
			"engine",
			if (claimed) "manual broadcast claimed [$key]" else "manual broadcast blocked [$key]",
		)
		return claimed
	}

	/** Undo a [claimManual] whose broadcast failed, so it can be retried. */
	fun releaseManualClaim(payload: HiveScrobblePayload, startedAtEpochSec: Long) {
		if (!initialised) return
		ledger.release(DedupLedger.keyFor(payload.title, payload.artist, startedAtEpochSec))
		EventLog.append("engine", "manual claim released after a failed send")
	}

	/** Retry anything waiting in the queue. Safe to call often. */
	fun flushQueue() {
		if (!initialised) return
		scope.launch {
			broadcastLock.withLock {
					val account = vault.account ?: return@withLock
					val key = vault.loadKey() ?: return@withLock
					for (entry in queue.due()) {
						if (!entry.username.equals(account.username, ignoreCase = true)) {
							EventLog.append(
								"queue",
								"waiting for @${entry.username}: current key belongs to " +
									"@${account.username}; entry left untouched",
							)
							continue
						}
						val result = runCatching {
							broadcaster.broadcastJson(account.username, key, entry.json)
						}.getOrElse { HiveRpc.BroadcastResult.NetworkFailure(it.message ?: "error") }

						when (result) {
							is HiveRpc.BroadcastResult.Success -> {
								val removed = queue.remove(entry.id)
								EventLog.append(
									"engine",
									"queued scrobble sent (${result.evidence.name.lowercase()}): " +
										"${entry.label} — tx ${result.txId}",
								)
								note(
									entry.label,
									if (removed) {
										"sent from queue"
									} else {
										"sent, but retry-queue removal failed — do not retry"
									},
									result.txId,
									entry.percentPlayed,
									videoId = entry.videoId,
								)
							}

						is HiveRpc.BroadcastResult.AcceptedUnconfirmed -> {
							// The accepting node has it. Retrying with a new
							// expiration would create a different tx id and can
							// permanently duplicate the listen.
								val removed = queue.remove(entry.id)
							EventLog.append(
								"engine",
								"queued scrobble accepted but confirmation unavailable: " +
									"${entry.label} — tx ${result.txId}",
							)
								note(
									entry.label,
									if (removed) {
										"accepted — confirmation unavailable"
									} else {
										"accepted, but retry-queue removal failed — do not retry"
									},
									result.txId,
									entry.percentPlayed,
									videoId = entry.videoId,
								)
							}

							is HiveRpc.BroadcastResult.Rejected -> {
								// The chain will keep rejecting this; don't loop.
								val removed = queue.remove(entry.id)
								EventLog.append(
									"engine",
									"queued scrobble dropped (rejected): ${result.message}",
								)
								note(
									entry.label,
									if (removed) {
										"queue failed permanently: ${result.message}"
									} else {
										"rejected; retry-queue removal failed"
									},
									null,
									entry.percentPlayed,
									videoId = entry.videoId,
								)
							}

						// Still owed. Left in the queue with its failure recorded so
						// the backoff applies — dropping it here is the bug that lost
							// two listens to a stalled node on 2026-07-30.
							is HiveRpc.BroadcastResult.Deferred -> {
								handleQueuedFailure(entry, result.message)
							}

							is HiveRpc.BroadcastResult.NetworkFailure -> {
								handleQueuedFailure(entry, result.message)
							}
					}
				}
				_queueSize.value = queue.size()
				if (account.username.isNotEmpty()) ledger.prune()
			}
		}
	}

	private fun handleQueuedFailure(entry: BroadcastQueue.Entry, message: String) {
		when (queue.recordFailure(entry.id, message)) {
			BroadcastQueue.FailureOutcome.RETAINED ->
				EventLog.append("engine", "queued scrobble still waiting: $message")

			BroadcastQueue.FailureOutcome.DROPPED -> {
				EventLog.append(
					"engine",
					"queued scrobble exhausted retry limit and was removed: ${entry.label}",
				)
				note(
					entry.label,
					"failed after 8 queued attempts: $message",
					null,
					entry.percentPlayed,
					videoId = entry.videoId,
				)
			}

			BroadcastQueue.FailureOutcome.NOT_FOUND ->
				EventLog.append("queue", "retry entry disappeared before failure could be recorded")

			BroadcastQueue.FailureOutcome.STORAGE_ERROR -> {
				EventLog.append(
					"queue",
					"STORAGE ERROR recording retry failure for ${entry.label}",
				)
				note(
					entry.label,
					"retry state could not be persisted — export the log",
					null,
					entry.percentPlayed,
					videoId = entry.videoId,
				)
			}
		}
	}

	private fun enqueueAndSend(payload: HiveScrobblePayload, videoId: String? = null) {
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
						val evidence = result.evidence.name.lowercase()
						EventLog.append(
							"engine",
							"scrobbled ($evidence): $label — tx ${result.txId}",
						)
						note(
							label,
							if (result.evidence == HiveRpc.BroadcastResult.Evidence.BLOCK) {
								"confirmed in block"
							} else {
								"seen relaying in mempool"
							},
							result.txId,
							payload.percentPlayed,
							videoId = videoId,
						)
					}

					is HiveRpc.BroadcastResult.AcceptedUnconfirmed -> {
						EventLog.append(
							"engine",
							"accepted but confirmation unavailable: $label — tx ${result.txId}",
						)
						note(
							label,
							"accepted — confirmation unavailable; not retried",
							result.txId,
							payload.percentPlayed,
							videoId = videoId,
						)
					}

					is HiveRpc.BroadcastResult.Rejected -> {
						// Bad auth, malformed op — this will fail identically
						// forever, so queuing would only loop.
						EventLog.append("engine", "rejected: ${result.message}")
						note(label, "rejected: ${result.message}", null, payload.percentPlayed, videoId = videoId)
					}

					// Refused for a reason that should pass later, or accepted and
					// never included. The listen is still owed, so it queues.
						is HiveRpc.BroadcastResult.Deferred -> {
							val persisted = queue.add(
								account.username,
								json,
								label,
								payload.percentPlayed,
								videoId,
							)
							_queueSize.value = queue.size()
							EventLog.append(
								"engine",
								if (persisted) {
									"queued for retry: ${result.message}"
								} else {
									"QUEUE STORAGE FAILURE — scrobble could not be persisted"
								},
							)
							note(
								label,
								if (persisted) {
									"waiting to retry — ${result.message}"
								} else {
									"failed to persist retry — export the log"
								},
								null,
								payload.percentPlayed,
								queued = persisted,
								videoId = videoId,
							)
						}

						is HiveRpc.BroadcastResult.NetworkFailure -> {
							val persisted = queue.add(
								account.username,
								json,
								label,
								payload.percentPlayed,
								videoId,
							)
							_queueSize.value = queue.size()
							EventLog.append(
								"engine",
								if (persisted) {
									"queued (offline): $label"
								} else {
									"QUEUE STORAGE FAILURE while offline: $label"
								},
							)
							note(
								label,
								if (persisted) "queued — offline" else "failed to persist offline retry",
								null,
								payload.percentPlayed,
								queued = persisted,
								videoId = videoId,
							)
						}
				}
			}
		}
	}

	/**
	 * Log a skip and surface it in the UI.
	 *
	 * Deliberately not called for the two *global* refusals above — monitoring
	 * stopped and auto-scrobble off. Those fire for every track while the switch
	 * is off, and a list of a hundred "auto-scrobble off" rows explains nothing
	 * that the switch itself isn't already saying.
	 *
	 * @param log false when the caller already wrote a better line (the dedup
	 * path logs the key, which is what makes a duplicate diagnosable)
	 */
	private fun skip(
		session: SessionSnapshot,
		reason: String,
		durationMs: Long? = session.durationMs,
		log: Boolean = true,
	) {
		if (log) EventLog.append("engine", "skipped: $reason")
		if (session.playedMs < MIN_NOTABLE_PLAYED_MS) return
		val title = session.title?.takeIf { it.isNotBlank() } ?: return
		val record = SkipRecord(
			title = title,
			artist = session.artist,
			reason = reason,
			atEpochSec = System.currentTimeMillis() / 1000,
			playedSeconds = session.playedMs / 1000,
			durationSeconds = durationMs?.takeIf { it > 0 }?.div(1000),
		)
		// `update` rather than a plain assignment: unlike every other list here,
		// this one is written from two threads — the prefilter rejects on the
		// media-session callback while the post-enrichment rules reject on the IO
		// scope, and a shorts feed can have both in flight.
		_skipped.update { (listOf(record) + it).take(50) }
	}

	/**
	 * Track whether identity produced a video id, and say so once when a run of
	 * misses starts. Logged at the threshold only — the individual finalizes are
	 * already visible in Not logged.
	 */
	private fun noteVideoIdOutcome(videoId: String?) {
		if (videoId != null) {
			_tracksWithoutVideoId.value = 0
			return
		}
		val misses = _tracksWithoutVideoId.updateAndGet { it + 1 }
		if (misses == QUIET_BAR_THRESHOLD) {
			EventLog.append(
				"url",
				"the address bar has named no video for $misses tracks in a row — " +
					"unresolved tracks will not be broadcast. Check Accessibility is still " +
					"granted, or tap the toolbar once to expand it.",
			)
		}
	}

	private fun note(
		label: String,
		status: String,
		txId: String?,
		percent: Int? = null,
		queued: Boolean = false,
		videoId: String? = null,
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
					videoId = videoId,
				),
			) + _recent.value
			).take(50)
	}
}
