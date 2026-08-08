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
import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.detect.NativeSourceSwitches
import com.rustedwax.app.detect.NativePreResolvedRoute
import com.rustedwax.app.detect.YouTubeProbe
import com.rustedwax.app.enrich.FactsCache
import com.rustedwax.app.enrich.MetadataResolver
import com.rustedwax.app.enrich.MusicBrainzVerifier
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.enrich.VideoIdResolver
import com.rustedwax.app.enrich.VideoIdentityCorroborator
import com.rustedwax.app.enrich.VideoResolution
import com.rustedwax.app.enrich.VideoResolutionAttempt
import com.rustedwax.app.enrich.VerifiedIdentityCandidateCache
import com.rustedwax.app.enrich.WatchHistoryResolver
import com.rustedwax.app.enrich.YouTubePageResolver
import com.rustedwax.app.storage.KeyVault
import com.rustedwax.app.storage.MutedVideos
import com.rustedwax.app.storage.Settings
import com.rustedwax.app.storage.YouTubeSessionVault

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
	private lateinit var youtubeSession: YouTubeSessionVault
	private lateinit var history: WatchHistoryResolver
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
		youtubeSession = YouTubeSessionVault(appContext)
		history = WatchHistoryResolver(youtubeSession)
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
		if (!NativeSourceSwitches.isSnapshotCurrent(session.packageName, session.sourceEpoch)) {
			EventLog.append(
				"native",
				"${session.packageName} finalized after its opt-in/lifecycle boundary — discarded",
			)
			return
		}

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
			progressSurfaceLost = session.foregroundProgressLost,
			inferredMs = session.inferredPlayedMs,
		)?.let { reason ->
			skip(session, reason)
			return
		}

		// Enrichment is a network call, so the payload can no longer be built on
		// this thread. Everything downstream of it moves into the coroutine —
		// including the dedup claim, which must be computed from the *corrected*
		// title and artist or a fixed parse would look like a new track.
		scope.launch {
			if (!NativeSourceSwitches.isSnapshotCurrent(session.packageName, session.sourceEpoch)) {
				EventLog.append("native", "${session.packageName} stale finalization refused")
				return@launch
			}
			// The address bar is the exact source when it spoke; searching is
			// the fallback for when it never did (a playlist advancing behind a
			// hidden toolbar fires no accessibility event at all).
			val attempt = session.confirmed?.let { confirmed ->
				VideoResolutionAttempt(
					resolution = VideoResolution(
						videoId = confirmed.videoId,
						source = "frozen ${confirmed.source}",
						title = session.resolverContext.knownTitle,
						channel = session.resolverContext.knownChannel,
						ownerHandle = session.ownerHandle,
						lengthSeconds = session.resolverContext.knownDurationSeconds,
					),
				)
			} ?: resolveVideoId(session)
			val resolution = attempt.resolution
			if (resolution == null) {
				noteVideoIdOutcome(session, null)
				skip(
					session,
					"video id could not be verified — " +
						(attempt.refusalReason ?: "no unique candidate corroborated") +
						"; no scrobble was broadcast because every YouTube entry requires a hyperlink",
				)
				return@launch
			}
			val videoId = resolution.videoId
			val facts = enrich(videoId)
			VideoIdentityCorroborator.contradiction(session, resolution, facts)?.let { mismatch ->
				noteVideoIdOutcome(session, null)
				skip(
					session,
					"video id could not be verified against the finalized snapshot — $mismatch",
				)
				return@launch
			}
			if (VideoIdentityCorroborator.cacheable(session, resolution, facts)) {
				VerifiedIdentityCandidateCache.remember(
					packageName = session.packageName,
					videoId = videoId,
					title = session.title,
					channel = session.artist,
					durationMs = session.durationMs,
					ownerHandle = session.ownerHandle,
				)
				EventLog.append(
					"resolve",
					"remembered $videoId as a bounded run-local verified candidate",
				)
			}
			noteVideoIdOutcome(session, videoId)
			if (session.isNative) {
				val route = session.confirmed?.exactIdRoute
				EventLog.append(
					"native-id",
					if (route != null) {
						"${session.packageName} verified $videoId via $route"
					} else {
						"${session.packageName} verified $videoId via corroborated resolver " +
							"(${resolution.source})"
					},
				)
			}

			// Once the frozen snapshot has verified the id, honor the user's mute
			// before MusicBrainz, rules, payload construction or signing.
			if (muted.isMuted(videoId)) {
				skip(session, "muted video — you asked never to scrobble this one again")
				return@launch
			}

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
				isShort = session.hasShortSourceProof,
				videoResolved = facts?.resolvedOnWatchPage == true,
				videoUnlisted = facts?.isUnlisted,
				shortClipsEnabled = settings.shortClips,
				explicitAdSignal = session.explicitAdSignal,
				browserEvidenceEnabled = session.browserEvidenceEnabled,
				resolvedWithoutExactUrl = session.confirmed == null,
				accessibilityCovered = session.accessibilityCoverage != null,
				progressSurfaceLost = session.foregroundProgressLost,
				inferredMs = session.inferredPlayedMs,
			)
			if (!decision.shouldScrobble) {
				skip(session, decision.skippedBecause ?: "no reason given", durationMs)
				return@launch
			}

			val basePayload = ScrobbleBuilder.from(
				session, facts, mb, videoId, durationMs,
				resolvedTitle = resolution.title,
			)
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
			if (!NativeSourceSwitches.isSnapshotCurrent(session.packageName, session.sourceEpoch)) {
				EventLog.append(
					"native",
					"${session.packageName} opt-in/lifecycle changed before dedup or signing — discarded",
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
					isShort = session.hasShortSourceProof,
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
				enqueueAndSend(
					payload = basePayload.copy(percentPlayed = percent),
					videoId = videoId,
					sourcePackage = session.packageName,
					sourceEpoch = session.sourceEpoch,
				)
			}
		}
	}

	/**
	 * Resolve a stable native track early enough to authorize a controller
	 * handoff. This never builds, deduplicates, signs or queues a payload.
	 */
	fun resolveNativeCarryIdentity(
		session: SessionSnapshot,
		callback: (SessionProbe.NativeResolvedIdentity?) -> Unit,
	) {
		if (!initialised || !settings.monitoringEnabled || !settings.enrichment ||
			!session.isNative || session.isForegroundShort ||
			!NativeSourceSwitches.isSnapshotCurrent(session.packageName, session.sourceEpoch)
		) {
			callback(null)
			return
		}
		scope.launch {
			val attempt = resolveVideoId(session)
			val resolution = attempt.resolution
			if (resolution == null) {
				EventLog.append(
					"native-carry",
					"${session.packageName} pre-resolution refused \"${session.title}\": " +
						(attempt.refusalReason ?: "no unique candidate corroborated"),
				)
				callback(null)
				return@launch
			}
			val facts = enrich(resolution.videoId)
			val contradiction = VideoIdentityCorroborator.contradiction(session, resolution, facts)
			if (contradiction != null ||
				!NativeSourceSwitches.isSnapshotCurrent(session.packageName, session.sourceEpoch)
			) {
				EventLog.append(
					"native-carry",
					"${session.packageName} pre-resolution refused \"${session.title}\": " +
						(contradiction ?: "native source generation changed"),
				)
				callback(null)
				return@launch
			}
			callback(
				SessionProbe.NativeResolvedIdentity(
					videoId = resolution.videoId,
					route = when {
						resolution.playlistVerified -> NativePreResolvedRoute.PLAYLIST
						resolution.historyVerified -> NativePreResolvedRoute.HISTORY
						resolution.structuredNativeMusic ->
							NativePreResolvedRoute.STRUCTURED_MUSIC

						else -> NativePreResolvedRoute.RAW_TITLE_CHANNEL
					},
				),
			)
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
			SessionProbe.KnownVideo(
				title = it.title,
				channel = it.author,
				lengthSeconds = it.lengthSeconds,
			)
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
	/**
	 * The playlist entry this session is playing, or null.
	 *
	 * Browsers supply the playlist id from the address bar. Native YouTube
	 * supplies only the playlist *name*, read off the watch screen, so it is
	 * resolved to an id here — once per playlist, cached including the misses.
	 * After the first fetch every remaining track in that playlist is free.
	 */
	private suspend fun nativePlaylistResolution(
		session: SessionSnapshot,
		title: String,
		durationSec: Long?,
	): VideoResolution? {
		val list = session.resolverContext.playlistId
			?: session.resolverContext.nativePlaylistName?.let { name ->
				idResolver.resolveNativePlaylistId(
					playlistName = name,
					ownerName = session.resolverContext.nativePlaylistOwner,
					total = session.resolverContext.nativePlaylistTotal,
				)
			}
			?: return null
		return idResolver.resolveEvidenceFromPlaylist(list, title, session.artist, durationSec)
	}

	/**
	 * The exact id from the signed-in account's watch history, or null when the
	 * route does not apply at all.
	 *
	 * Null and a refusal are different answers: null means "this session is not
	 * eligible for the route", which must fall through to search silently, while
	 * a [VideoResolutionAttempt] with a reason means the route ran and declined,
	 * which is worth reading in the log.
	 *
	 * Native `com.google.android.youtube` only. Browsers have the address bar
	 * and their behaviour is not allowed to change (§11.1); YouTube Music keeps
	 * a separate history and is out of scope (§7.1).
	 */
	private suspend fun watchHistoryResolution(
		session: SessionSnapshot,
		title: String?,
		durationSec: Long?,
	): VideoResolutionAttempt? {
		if (!settings.watchHistory || !session.isNative ||
			session.packageName != YouTubeProbe.YOUTUBE_PACKAGE || !history.hasSession
		) return null
		// A foreground Short is a different question. Its history row is a
		// `shortsLockupViewModel`, which carries an id and a title and
		// deliberately no channel and no duration, so it can never satisfy the
		// ordinary three-field gate and is kept out of it entirely. What the
		// feed does supply is the exact id — which is precisely what the search
		// route cannot find for these: measured 2026-08-04, six of eleven Shorts
		// failed at "no candidate matched exact title+duration+owner handle",
		// their titles being mostly hashtags and emoji.
		//
		// So history names the candidates and the *existing* owner-handle
		// verification decides: each id's own watch page is re-fetched and must
		// agree on title, duration and @handle, uniquely. No rule is relaxed;
		// the proven gate is simply handed the right ids.
		val handle = session.ownerHandle
		if (handle != null) {
			val candidates = history.recentShortIds(title)
			if (candidates.isEmpty()) return null
			val attempt = idResolver.resolveVerifiedCandidates(
				videoIds = candidates,
				title = title,
				channel = session.artist,
				durationSec = durationSec,
				ownerHandle = handle,
			)
			EventLog.append(
				"history",
				attempt.resolution?.let {
					"resolved Short ${title?.let { t -> "\"$t\"" } ?: "with no readable title"} " +
						"→ ${it.videoId} from watch history, corroborated on its own watch page"
				} ?: "watch-history Short candidates did not corroborate " +
					"${title?.let { t -> "\"$t\"" } ?: "this untitled Short"}: " +
					attempt.refusalReason,
			)
			return attempt
		}

		// Every non-Short route still needs a title; only the handle path above
		// can stand without one.
		if (title == null) return null
		return history.resolveEvidence(
			title = title,
			channel = session.artist,
			durationSec = durationSec,
			ownerHandle = null,
		)
	}

	private suspend fun resolveVideoId(session: SessionSnapshot): VideoResolutionAttempt {
		if (!settings.enrichment) {
			return VideoResolutionAttempt(refusalReason = "video lookup is disabled")
		}
		val durationSec = session.durationMs?.div(1000)
		// A foreground Short may legitimately arrive with no title: the footer
		// lost its resource ids and YouTube keeps restyling it, so the title is
		// the least reliable thing on screen. Its owner handle and its seekbar
		// duration are not, and the watch-history route resolves on exactly those.
		// Every other source still requires a title, because nothing else has a
		// second discriminator to fall back on.
		// A foreground Short now reaches here in three shapes: with a title and a
		// length, with a length but no title (the footer restyle), and — since
		// YouTube stopped rendering the Shorts seekbar — with a title but no
		// length at all. Watch history is the only route that can answer any of
		// them, because it is the only one that knows what this account played.
		if (session.isForegroundShort &&
			session.ownerHandle != null &&
			(session.title == null || durationSec == null)
		) {
			// Straight to watch history: the search and playlist routes below all
			// need a title *and* a length to query with, and the pre-resolved
			// carry can only exist for a Short that already had both. History
			// needs neither — it names what this account played, and whichever of
			// the two fields survived still has to match, uniquely, on the
			// candidate's own watch page.
			val missing = if (session.title == null) "title" else "length"
			return watchHistoryResolution(session, session.title, durationSec)
				?: VideoResolutionAttempt(
					refusalReason = "this Short's $missing could not be read and watch history " +
						"could not identify it from what was left; " +
						// Naming the actual state, when there is one, rather than
						// always pointing at sign-in: measured 2026-08-06, two of
						// these were refused with this wording while the route was
						// standing itself down for fifteen minutes.
						(
							history.refusedBecause?.let { "the route is not running: $it" }
								?: "sign-in and the watch-history switch are what make these resolvable"
							),
				)
		}
		val title = session.title
			?: return VideoResolutionAttempt(refusalReason = "the finalized title is missing")
		val preResolvedNativeId = session.resolverContext.preResolvedNativeVideoId
		if (preResolvedNativeId != null) {
			val artist = session.artist ?: return VideoResolutionAttempt(
				refusalReason = "the finalized native artist is missing",
			)
			val duration = durationSec ?: return VideoResolutionAttempt(
				refusalReason = "the finalized native duration is missing",
			)
			val preResolvedRoute = session.resolverContext.preResolvedNativeRoute
				?: return VideoResolutionAttempt(
					refusalReason = "pre-resolved native authority omitted its resolver route",
				)
			EventLog.append(
				"resolve",
				"re-fetching pre-resolved native carry authority $preResolvedNativeId " +
					"($preResolvedRoute) for \"$title\"",
			)
			val carryAttempt = runCatching {
				when (preResolvedRoute) {
					NativePreResolvedRoute.STRUCTURED_MUSIC ->
						idResolver.revalidatePreResolvedNativeMusic(
							preResolvedNativeId, title, artist, duration,
						)
					NativePreResolvedRoute.RAW_TITLE_CHANNEL ->
						idResolver.resolveVerifiedCandidates(
							listOf(preResolvedNativeId), title, artist, duration,
						)

					// Re-ask the feed that produced it. History is a live list,
					// so a listen it has since re-described must refuse rather
					// than carry a stale answer onto a chain nothing can edit.
					NativePreResolvedRoute.HISTORY -> {
						val revalidated = if (settings.watchHistory && history.hasSession) {
							history.revalidate(preResolvedNativeId, title, artist, duration)
						} else {
							VideoResolutionAttempt(
								refusalReason = "watch history was disconnected while " +
									"\"$title\" was playing, so the id it supplied " +
									"cannot be re-verified",
							)
						}
						revalidated
					}

					// Re-verify against the authority that produced it. The
					// playlist entry list is already cached, so this is a lookup,
					// and requiring the same id back preserves the immutability
					// the carry depends on.
					NativePreResolvedRoute.PLAYLIST -> {
						val revalidated = nativePlaylistResolution(session, title, duration)
						when {
							revalidated == null -> VideoResolutionAttempt(
								refusalReason = "pre-resolved playlist id $preResolvedNativeId " +
									"no longer matches any entry of the playlist being played",
							)

							revalidated.videoId != preResolvedNativeId -> VideoResolutionAttempt(
								refusalReason = "playlist now resolves \"$title\" to " +
									"${revalidated.videoId}, not the carried $preResolvedNativeId",
							)

							else -> VideoResolutionAttempt(resolution = revalidated)
						}
					}
				}
			}.getOrElse {
				VideoResolutionAttempt(
					refusalReason = "pre-resolved native revalidation failed: ${it.message}",
				)
			}
			if (carryAttempt.resolution != null) return carryAttempt

			// A carry that no longer revalidates is not proof that the track is
			// unidentifiable — only that *this* route can no longer vouch for it.
			// Measured 2026-08-06: "Nicki Minaj - Barbie Tingz" had its id named
			// by watch history during playback, and at finalize the carry route
			// refused and returned, so the history route was never asked again
			// and a listen that history could still identify was thrown away.
			//
			// Falling through costs nothing in rigour: every remaining route has
			// its own uniqueness proof, and VideoIdentityCorroborator still runs
			// on whatever any of them returns.
			EventLog.append(
				"resolve",
				"carried $preResolvedNativeId ($preResolvedRoute) no longer revalidates " +
					"for \"$title\" — ${carryAttempt.refusalReason}; asking the ordinary " +
					"routes rather than refusing the listen",
			)
		}
		return runCatching {
			val cachedIds = VerifiedIdentityCandidateCache.candidates(
				packageName = session.packageName,
				title = session.title,
				channel = session.artist,
				durationMs = session.durationMs,
				ownerHandle = session.ownerHandle,
			)
			if (cachedIds.isNotEmpty()) {
				EventLog.append(
					"resolve",
					"${cachedIds.size} run-local candidate(s) for \"$title\" — re-fetching",
				)
				val cachedAttempt = idResolver.resolveVerifiedCandidates(
					cachedIds, title, session.artist, durationSec, session.ownerHandle,
				)
				if (cachedAttempt.resolution != null ||
					cachedAttempt.refusalReason?.startsWith("ambiguous identity") == true
				) {
					return@runCatching cachedAttempt
				}
				EventLog.append(
					"resolve",
					"run-local recovery did not corroborate — continuing to playlist/search",
				)
			}
			// The playlist is exact where search is only plausible, and after
			// the first fetch it costs nothing for the rest of the playlist.
			//
			val playlistResolution = nativePlaylistResolution(session, title, durationSec)
			if (playlistResolution != null) {
				return@runCatching VideoResolutionAttempt(resolution = playlistResolution)
			}

			// Then the account's own watch history, which is the only route that
			// names an exact id for native playback with no playlist around it —
			// a single video, or anything played with the screen off. Ahead of
			// search because search has been measured choosing a
			// duration-identical wrong upload (§10.1); behind the playlist
			// because the playlist needs no credentials and is already proven.
			val historyAttempt = watchHistoryResolution(session, title, durationSec)
			if (historyAttempt?.resolution != null) {
				return@runCatching historyAttempt
			}

			idResolver.resolveEvidenceAttempt(
				title,
				session.artist,
				durationSec,
				session.ownerHandle,
				allowStructuredNativeMusic = session.isNative &&
					!session.isForegroundShort,
			)
		}.getOrElse {
			EventLog.append("resolve", "resolver threw: ${it.message}")
			VideoResolutionAttempt(refusalReason = "resolver failed: ${it.message ?: "unknown error"}")
		}
	}

	/** Monitoring/package reset boundary for the memory-only identity index. */
	fun clearVerifiedIdentityCandidates(packageName: String? = null) {
		if (packageName == null) VerifiedIdentityCandidateCache.clearAll()
		else VerifiedIdentityCandidateCache.clear(packageName)
		// The history route's "your app is on another account" diagnosis is a
		// run of consecutive misses; a monitoring or package boundary makes the
		// old run meaningless, so it starts over rather than carrying a verdict
		// across a state change the user may have made to fix it.
		if (initialised && packageName == null) history.reset()
	}

	// ---- watch history, the user-facing surface --------------------------------

	/** Whether an account is connected for watch-history lookups. */
	fun youTubeSession(): YouTubeSessionVault.Session? =
		if (initialised) youtubeSession.session else null

	fun watchHistoryEnabled(): Boolean = initialised && settings.watchHistory

	fun setWatchHistoryEnabled(enabled: Boolean) {
		if (!initialised) return
		settings.watchHistory = enabled
		history.reset()
		EventLog.append(
			"history",
			"watch-history lookups ${if (enabled) "on" else "off"}",
		)
	}

	/** The exact reason the route is standing down, or null when it is running. */
	fun watchHistoryRefusal(): String? = if (initialised) history.refusedBecause else null

	/**
	 * Store a session the user signed in for. The cookie never passes through a
	 * log, a state flow or the UI — only this call, and only into the vault.
	 */
	fun connectYouTubeSession(cookieHeader: String, accountLabel: String?) {
		if (!initialised) return
		youtubeSession.save(cookieHeader, accountLabel)
		settings.watchHistory = true
		history.reset()
		EventLog.append(
			"history",
			"connected a YouTube account for watch-history lookups" +
				(accountLabel?.let { " (@$it)" } ?: "") + "; the session is stored encrypted " +
				"and is never logged or sent anywhere but youtube.com",
		)
	}

	/** Prove a freshly connected session can actually read history. */
	suspend fun probeWatchHistory(): WatchHistoryResolver.Probe =
		if (initialised) {
			history.probe()
		} else {
			WatchHistoryResolver.Probe.Faulted(null, "the engine is not initialised")
		}

	fun disconnectYouTubeSession() {
		if (!initialised) return
		youtubeSession.forget()
		settings.watchHistory = false
		history.reset()
		EventLog.append("history", "YouTube account disconnected and its session wiped")
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

	private fun enqueueAndSend(
		payload: HiveScrobblePayload,
		videoId: String? = null,
		sourcePackage: String? = null,
		sourceEpoch: Long? = null,
	) {
		val account = vault.account
		if (account == null) {
			EventLog.append("engine", "no key saved — scrobble dropped")
			return
		}
		val label = "${payload.artist?.plus(" — ") ?: ""}${payload.title}"
		val json = payload.toJson()

		scope.launch {
			broadcastLock.withLock {
				if (sourcePackage != null &&
					!NativeSourceSwitches.isSnapshotCurrent(sourcePackage, sourceEpoch)
				) {
					EventLog.append(
						"native",
						"$sourcePackage opt-in/lifecycle changed before signing — broadcast cancelled",
					)
					return@withLock
				}
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
	private fun noteVideoIdOutcome(session: SessionSnapshot, videoId: String?) {
		if (session.origin != YouTubeProbe.Origin.BROWSER) return
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
