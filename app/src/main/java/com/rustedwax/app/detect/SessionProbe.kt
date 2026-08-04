package com.rustedwax.app.detect

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches every active media session and tracks playback progress.
 *
 * This is the detection half of RustedWax: it observes, measures and reports
 * when a track ends. It deliberately decides nothing — thresholds, dedup and
 * payloads all live in `scrobble/`.
 *
 * v1 targets YouTube in a Chromium browser, but every session is watched so
 * native players can serve as control cases in diagnostics. Non-target sessions
 * are marked `isTarget = false` and are never broadcast.
 *
 * Position handling here is the same extrapolation Phase 3 needs: PlaybackState
 * reports a position sampled at `lastPositionUpdateTime`, so live position is
 * `position + (now - sampledAt) * speed`. We also accumulate *played
 * milliseconds* across play/pause, which is what the 60% rule consumes and is
 * computable from this data alone.
 *
 * "Played" means **content consumed**, not seconds elapsed: the same `speed`
 * factor scales the accumulator, because the threshold compares against
 * `duration`. Watching at 1.25× used to report 67% of a trailer that had been
 * watched to 79%, and at 2× a video watched in full read 50% and never
 * scrobbled. See [Watch.accumulate].
 */
class SessionProbe(context: Context) {

	private val appContext = context.applicationContext
	private val packageManager = appContext.packageManager
	private val sessionManager =
		appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
	private val listenerComponent =
		ComponentName(appContext, RustedWaxListenerService::class.java)
	private val handler = Handler(Looper.getMainLooper())

	private val watches = mutableMapOf<String, Watch>()

	/** Packages already logged as ignored, so the log records each one once. */
	private val ignoredPackages = mutableSetOf<String>()

	private val _sessions = MutableStateFlow<List<SessionSnapshot>>(emptyList())
	val sessions: StateFlow<List<SessionSnapshot>> = _sessions.asStateFlow()

	private val _error = MutableStateFlow<String?>(null)
	val error: StateFlow<String?> = _error.asStateFlow()

	/**
	 * Called when a track ends — the moment the scrobble decision is made.
	 *
	 * The snapshot is built *here*, not at track start, because identity
	 * depends on the notification hint that arrives ~300 ms late (PHASE0 run 2).
	 * By finalize time it has always landed.
	 */
	var onTrackFinalized: ((SessionSnapshot) -> Unit)? = null

	/**
	 * Fires when a session's video id becomes known — the earliest moment
	 * enrichment can start. Wired to the engine's prefetch, which dedupes, so
	 * being called on every identity re-check is fine.
	 */
	var onVideoConfirmed: ((videoId: String) -> Unit)? = null

	/**
	 * What a resolved page says about a video id — the two facts that can
	 * disprove a latch. Deliberately narrow rather than passing `VideoFacts`
	 * around: the probe corroborates identity, it doesn't consume metadata.
	 */
	data class KnownVideo(
		val title: String?,
		val channel: String?,
		val lengthSeconds: Long?,
	)

	/**
	 * Cached facts for a video id, or null when nothing is known yet. Used to
	 * corroborate a latched video id against what the session is actually
	 * playing. Cache-only — must never touch the network.
	 */
	var knownVideoFor: ((videoId: String) -> KnownVideo?)? = null

	private var started = false
	private var browserEvidenceEnabledForRun = false

	private val activeSessionsListener =
		MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
			syncControllers(controllers ?: emptyList())
		}

	fun start() {
		if (started) return
		browserEvidenceEnabledForRun = UrlWatcherService.isEnabled(appContext)
		// A notification hint often arrives *after* we've already judged the
		// track (measured: ~300 ms later), so re-run identity when one lands.
		NotificationHints.onHint = { pkg ->
			handler.post {
				watches.values
					.filter { it.packageName == pkg }
					.forEach { it.reidentify("notification") }
				publish()
			}
		}
		// The address bar lands late for the same reason, and until v0.5.3
		// nothing re-ran identity when it did — so a video id that arrived a
		// moment after track start was only ever picked up by the UI's tick,
		// making `url` depend on the app being open. Same wiring as hints.
		UrlEvidence.onEvidence = { pkg ->
			handler.post {
				watches.values
					.filter { it.packageName == pkg }
					.forEach { it.reidentify("address bar") }
				publish()
			}
		}
		// The accessibility callback sees YouTube's visible ad UI in the same
		// window snapshot that supplied the `/shorts/` id. Bind that literal
		// evidence to the matching active track; never infer from its channel.
		AdEvidence.onEvidence = { evidence ->
			handler.post {
				watches.values
					.filter { it.packageName == evidence.packageName }
					.forEach { it.noteAdEvidence(evidence) }
				publish()
			}
		}
		// Ordinary watch labels carry no video id: the address bar names the
		// organic content behind an in-stream ad. Resolve the observation only
		// against one active Watch from the same browser package.
		MediaSessionAdEvidence.onObservation = { observation ->
			handler.post {
				val candidates = watches.values.filter {
					it.packageName == observation.packageName
				}
				when {
					observation.signal == null ->
						MediaSessionAdEvidence.labelAbsent(observation.packageName)
					candidates.size != 1 -> {
						MediaSessionAdEvidence.conflict(observation.packageName)
						EventLog.append(
							"ad",
							"${observation.packageName} → ordinary watch ad label refused: " +
								"${candidates.size} active MediaSession tracks",
						)
					}
					else -> candidates.single().noteMediaSessionAdEvidence(observation)
				}
				publish()
			}
		}
		MediaSessionAccessibilityEvidence.onScan = { scan ->
			handler.post {
				val candidates = watches.values.filter { it.packageName == scan.packageName }
				if (candidates.size == 1) {
					candidates.single().noteAccessibilityScan(scan)
				} else {
					EventLog.append(
						"evidence",
						"${scan.packageName} → successful YouTube scan not bound: " +
							"${candidates.size} active MediaSession tracks",
					)
				}
				publish()
			}
		}
		try {
			sessionManager.addOnActiveSessionsChangedListener(
				activeSessionsListener,
				listenerComponent,
				handler,
			)
			syncControllers(sessionManager.getActiveSessions(listenerComponent))
			started = true
			_error.value = null
			EventLog.append("probe", "started")
		} catch (e: SecurityException) {
			// Notification Access not granted (or revoked while running).
			_error.value = "Notification Access not granted — enable it to read media sessions."
			EventLog.append("probe", "start failed: ${e.message}")
		}
	}

	/**
	 * Tear the probe down.
	 *
	 * @param finalizeTracks whether tracks still in flight get one last chance
	 * to score. True when the *system* ends things (session gone, listener
	 * disconnected) — the track really did end, and dropping it would lose a
	 * legitimate scrobble. **False when the user presses Stop**: a Stop button
	 * that writes to an immutable chain on its way out is a bad Stop button, and
	 * without this flag `dispose()` would do exactly that for any track already
	 * past the threshold.
	 */
	fun stop(finalizeTracks: Boolean = true) {
		if (!started) return
		NotificationHints.onHint = null
		UrlEvidence.onEvidence = null
		AdEvidence.onEvidence = null
		MediaSessionAdEvidence.onObservation = null
		MediaSessionAccessibilityEvidence.onScan = null
		runCatching { sessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener) }
		// Probe shutdown cannot wait for a replacement session: either score the
		// last aggregate now (system teardown) or discard it (user Stop).
		watches.values.forEach {
			it.dispose(finalize = finalizeTracks, allowContinuation = false)
		}
		watches.clear()
		// Nothing observed before a Stop may survive it — including play time
		// waiting to be handed to a session that no longer exists.
		if (!finalizeTracks) {
			TrackProgressCarry.clear()
			AdEvidence.clearAll()
		}
		MediaSessionAdEvidence.clearAll()
		MediaSessionAccessibilityEvidence.clearAll()
		started = false
		EventLog.append(
			"probe",
			if (finalizeTracks) "stopped" else "stopped — in-flight tracks discarded",
		)
		publish()
	}

	/** Recomputes snapshots so the UI shows live extrapolated position. */
	fun tick() = publish()

	// ── internals ──────────────────────────────────────────────────────

	private fun syncControllers(controllers: List<MediaController>) {
		val byKey = controllers.associateBy { it.sessionToken.toString() }

		// Gone.
		for (key in watches.keys - byKey.keys) {
			watches.remove(key)?.let {
				EventLog.append("session", "− ${it.packageName} (session ended)")
				it.dispose(finalize = true, allowContinuation = true)
			}
		}

		// New.
		for ((key, controller) in byKey) {
			if (watches.containsKey(key)) continue

			// Phase 4: anything that isn't a target browser is not watched at
			// all. Previously every app on the device got a Watch and had its
			// title/artist/album dumped to the log as a "control case" — useful
			// during the spike, but it meant Spotify and every podcast player
			// were being read by an app that only scrobbles YouTube.
			if (controller.packageName !in YouTubeProbe.TARGET_PACKAGES) {
				noteIgnored(controller.packageName)
				continue
			}

			val watch = Watch(controller, labelFor(controller.packageName))
			watches[key] = watch
			EventLog.append("session", "+ ${watch.packageName} (${watch.appLabel})  ← target")
			watch.logMetadata(controller.metadata, "initial")
			watch.logPlaybackState(controller.playbackState, "initial")
		}

		// Two MediaSessions from one browser package are deliberately ambiguous:
		// accessibility exposes no session token with which to choose between them.
		watches.values.groupBy { it.packageName }.forEach { (packageName, packageWatches) ->
			if (packageWatches.size > 1) MediaSessionAdEvidence.conflict(packageName)
		}

		publish()
	}

	/**
	 * One line per package, ever — enough to answer "why isn't my music
	 * showing up", without the package name reappearing on every session
	 * change. No metadata is read from it.
	 */
	private fun noteIgnored(packageName: String) {
		if (ignoredPackages.add(packageName)) {
			EventLog.append("session", "ignored: $packageName (not a target browser)")
		}
	}

	/**
	 * True when the browser has exactly one media session, which is what lets
	 * [NotificationHints.bestFor] fall back to the newest hint: with one session
	 * there is no other tab the notification could belong to.
	 */
	private val soleSession: Boolean get() = watches.size == 1

	private fun publish() {
		_sessions.value = watches.values.map { it.snapshot() }
	}

	private fun labelFor(packageName: String): String = runCatching {
		packageManager
			.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0))
			.toString()
	}.getOrDefault(packageName)

	private fun browserEvidenceEnabledForThisRun(): Boolean {
		if (!browserEvidenceEnabledForRun && UrlWatcherService.isEnabled(appContext)) {
			browserEvidenceEnabledForRun = true
		}
		return browserEvidenceEnabledForRun
	}

	/** Per-controller state + callbacks. */
	private inner class Watch(
		private val controller: MediaController,
		val appLabel: String,
	) {
		val packageName: String = controller.packageName

		/**
		 * Always true since Phase 4 — non-browser sessions are no longer
		 * watched at all. Kept because the payload and the UI still read it,
		 * and because a future per-app allowlist would put it back to work.
		 */
		val isTarget: Boolean = packageName in YouTubeProbe.TARGET_PACKAGES

		/**
		 * Set when evidence positively names a non-YouTube site, and cleared
		 * only on a track change.
		 *
		 * One-way on purpose. Identity is re-checked whenever a notification
		 * lands, so without this a track proven to be some other site could be
		 * rehabilitated by a YouTube hint arriving from a different tab
		 * moments later — and then scrobbled as YouTube.
		 */
		private var taintedReason: String? = null

		/**
		 * The Confirmed identity captured while this track was actually
		 * playing, kept for the track's lifetime.
		 *
		 * PHASE0's "resolve identity at finalize" rule is right for
		 * notification hints (they arrive late) and exactly wrong for
		 * address-bar evidence, which is right at track *start* and stale at
		 * track *end*. Resolving from live evidence at finalize produced two
		 * on-chain failures on 2026-07-24: a track that lost its video id
		 * because the user had already scrolled to the next short (payload got
		 * no url, no category, wrong kind), and two different songs broadcast
		 * with the *same* url because one finalized while the bar showed the
		 * other. So: latch on first confirmation, spend at finalize.
		 */
		private var latchedVideo: YouTubeProbe.Identity.Confirmed? = null

		/**
		 * Last identity selected while this Watch was still active.
		 *
		 * A continuation expiry may run a minute after this Watch was removed
		 * from [watches], when the live address bar already describes several
		 * later Shorts. It must spend this value, never re-read that later tab.
		 */
		private var lastStableIdentity: YouTubeProbe.Identity? = null

		/** Identity frozen when disappearance opened a continuation window. */
		private var continuationIdentity: YouTubeProbe.Identity? = null
		private var continuationAccessibilityCoverage:
			MediaSessionAccessibilityEvidence.Coverage? = null

		/** Exact visible YouTube ad label bound to this track, if one appeared. */
		private var explicitAdSignal: String? = null
		private var accessibilityCoverage: MediaSessionAccessibilityEvidence.Coverage? = null

		/**
		 * Video ids disproven for this track by the watch page's own title not
		 * matching the session's. Never re-latched; a rejected id also stops
		 * qualifying as live URL evidence for this track.
		 */
		private val rejectedVideoIds = mutableSetOf<String>()

		private var metadata: MediaMetadata? = controller.metadata
		private var state: PlaybackState? = controller.playbackState

		/**
		 * Milliseconds of *content* consumed in the current track — elapsed time
		 * in STATE_PLAYING, scaled by the playback rate. See [accumulate].
		 */
		private var playedMs: Long = 0

		/** Highest rate scored for this track, for the finalize line only. */
		private var fastestSpeedSeen: Double = 1.0
		/** End-to-start playback reset observed during this continuous viewing. */
		private var loopDetected: Boolean = false
		private var playingSince: Long = if (isPlaying(state)) SystemClock.elapsedRealtime() else 0
		private var trackIdentity: TrackIdentity = trackIdentityOf(metadata)
		private var trackInstanceToken: Long = MediaSessionAdEvidence.nextTrackToken()
		private var trackInstanceEstablishedAtMillis: Long = System.currentTimeMillis()
		private var trackStartedAtEpochSec: Long = System.currentTimeMillis() / 1000

		/** One finalize per track, however many callbacks announce the end. */
		private var finalized: Boolean = false

		/**
		 * Token for progress waiting to see whether Chrome creates a replacement
		 * MediaSession. While present, disappearance is not a track ending.
		 */
		private var continuationToken: Long? = null
		private var continuationTrackIdentity: TrackIdentity? = null

		/** Resolver inputs accumulated while this track was the active Watch. */
		private var resolverContext: ResolverContext = ResolverContext()

		private val callback = object : MediaController.Callback() {
			override fun onMetadataChanged(md: MediaMetadata?) {
				val newIdentity = trackIdentityOf(md)
				val trackChanged = !trackIdentity.sameTrackAs(newIdentity)
				if (trackChanged) {
					EventLog.append(
						"track",
						"$packageName track change after ${playedMsNow() / 1000}s played",
					)
					// A real metadata change outranks the continuation grace
					// period: the old track has now demonstrably ended.
					cancelContinuation()
					finalizeCurrent("track change")
					resetForNewTrack()
					trackIdentity = newIdentity
					trackInstanceToken = MediaSessionAdEvidence.nextTrackToken()
					trackInstanceEstablishedAtMillis = System.currentTimeMillis()
					MediaSessionAccessibilityEvidence.activate(
						mediaSessionAdInstance(), trackInstanceEstablishedAtMillis,
					)
				} else {
					trackIdentity = trackIdentity.refinedWith(newIdentity)
				}
				metadata = md
				if (trackChanged) {
					// The real title often arrives here rather than at
					// construction — Chromium publishes a placeholder first — so
					// this is where a resumed track usually gets its time back.
					// Assign metadata first so cross-session loop detection
					// compares against the replacement's real duration.
					restoreCarriedProgress()
				}
				logMetadata(md, "changed")
				publish()
			}

			override fun onPlaybackStateChanged(ps: PlaybackState?) {
				val previousPosition = extrapolatedPosition(state)
				accumulate()
				val wasPlaying = isPlaying(state)
				state = ps
				notePositionWrap(
					previousPositionMs = previousPosition,
					newPositionMs = extrapolatedPosition(ps),
					acrossSessionRestart = false,
				)
				if (isPlaying(ps) && playingSince == 0L) {
					playingSince = SystemClock.elapsedRealtime()
				}
				// STOPPED means the track is over; PAUSED does not — a paused
				// track is often resumed, and finalizing it would scrobble a
				// half-listen and then dedup-block the real one.
				if (wasPlaying && ps?.state == PlaybackState.STATE_STOPPED) {
					cancelContinuation()
					finalizeCurrent("stopped")
				}
				logPlaybackState(ps, "changed")
				publish()
			}

			override fun onSessionDestroyed() {
				EventLog.append("session", "× $packageName destroyed")
				// Chrome tears sessions down around ad breaks and playlist
				// transitions. Disappearance starts a continuation window; it is
				// not itself a track ending.
				deferForContinuation("session destroyed")
				publish()
			}
		}

		init {
			MediaSessionAccessibilityEvidence.activate(
				mediaSessionAdInstance(), trackInstanceEstablishedAtMillis,
			)
			controller.registerCallback(callback, handler)
			// A session that appears already knowing its track is usually a
			// replacement for one Chrome just tore down.
			restoreCarriedProgress()
		}

		fun dispose(finalize: Boolean = true, allowContinuation: Boolean = true) {
			when {
				!finalize -> cancelContinuation()
				allowContinuation -> deferForContinuation("session ended")
				else -> {
					// System teardown cannot observe a replacement. Score the
					// aggregate now; user Stop passes finalize=false above.
					cancelContinuation()
					finalizeCurrent("probe ended")
				}
			}
			MediaSessionAdEvidence.clearInstance(mediaSessionAdInstance())
			MediaSessionAccessibilityEvidence.deactivate(mediaSessionAdInstance())
			runCatching { controller.unregisterCallback(callback) }
		}

		/**
		 * Hold this track for a replacement MediaSession without scoring the
		 * fragment on its own.
		 *
		 * If no matching replacement claims the progress during [TrackProgressCarry.TTL_MS],
		 * the delayed callback finalizes the aggregate exactly once. A token keeps
		 * an older callback from consuming a newer continuation with the same key.
		 */
		private fun deferForContinuation(reason: String) {
			if (finalized || continuationToken != null || metadata == null) return
			accumulate()
			val now = System.currentTimeMillis()
			val identityKey = trackIdentity
			val frozenCoverage = currentAccessibilityCoverage(now)
			// This Watch leaves the active map immediately after disappearance.
			// Freeze what it knew while active; the expiry callback must not
			// acquire whatever id the foreground tab names a minute later.
			val frozenIdentity = lastStableIdentity ?: YouTubeProbe.Identity.Unconfirmed(
				"identity was not established before the session ended",
			)
			val token = TrackProgressCarry.remember(
				packageName = packageName,
				trackIdentity = identityKey,
				progress = TrackProgressCarry.Progress(
					playedMs = playedMs,
					trackStartedAtEpochSec = trackStartedAtEpochSec,
					fastestSpeedSeen = fastestSpeedSeen,
					atMillis = now,
					lastPositionMs = extrapolatedPosition(state),
					loopDetected = loopDetected,
					identity = frozenIdentity,
					explicitAdSignal = explicitAdSignal,
					accessibilityCoverage = frozenCoverage,
					trackInstanceToken = trackInstanceToken,
				),
			) ?: return
			continuationIdentity = frozenIdentity
			continuationAccessibilityCoverage = frozenCoverage
			continuationToken = token
			continuationTrackIdentity = identityKey
			EventLog.append(
				"session",
				"$packageName [$reason] waiting ${TrackProgressCarry.TTL_MS / 1000}s " +
					"for a replacement session before finalizing",
			)
			handler.postDelayed(
				{
					val expired = TrackProgressCarry.expire(packageName, identityKey, token)
					if (expired != null && continuationToken == token && !finalized) {
						continuationToken = null
						continuationTrackIdentity = null
						finalizeCurrent("session continuation expired")
					}
				},
				TrackProgressCarry.TTL_MS + CONTINUATION_TIMER_SLOP_MS,
			)
		}

		private fun cancelContinuation() {
			val token = continuationToken ?: return
			val identityKey = continuationTrackIdentity ?: trackIdentity
			TrackProgressCarry.cancel(packageName, identityKey, token)
			continuationToken = null
			continuationTrackIdentity = null
			continuationAccessibilityCoverage = null
		}

		/**
		 * Take back play time from a session that vanished mid-track.
		 *
		 * `trackStartedAtEpochSec` is restored along with the clock, so the
		 * on-chain `timestamp` names when the listen actually began rather than
		 * when Chrome happened to rebuild its session — and so the dedup key stays
		 * stable across the restart.
		 */
		private fun restoreCarriedProgress() {
			val carried = TrackProgressCarry.claim(packageName, trackIdentity) ?: return
			MediaSessionAccessibilityEvidence.deactivate(mediaSessionAdInstance())
			trackInstanceToken = carried.trackInstanceToken ?: trackInstanceToken
			trackInstanceEstablishedAtMillis = System.currentTimeMillis()
			MediaSessionAccessibilityEvidence.activate(
				mediaSessionAdInstance(), trackInstanceEstablishedAtMillis,
			)
			playedMs = carried.playedMs
			trackStartedAtEpochSec = carried.trackStartedAtEpochSec
			fastestSpeedSeen = carried.fastestSpeedSeen
			loopDetected = carried.loopDetected
			carried.identity?.let { identity ->
				lastStableIdentity = when (identity) {
					is YouTubeProbe.Identity.Confirmed -> identity.copy(
						source = "${identity.source} (carried across session restart)",
					).also {
						latchedVideo = it
						onVideoConfirmed?.invoke(it.videoId)
					}
					else -> identity
				}
			}
			explicitAdSignal = carried.explicitAdSignal
			carried.explicitAdSignal?.let { signal ->
				MediaSessionAdEvidence.restoreAccepted(
					mediaSessionAdInstance(),
					signal,
					carried.atMillis,
				)
			}
			carried.accessibilityCoverage?.let { coverage ->
				if (MediaSessionAccessibilityEvidence.restore(
						mediaSessionAdInstance(), coverage, trackInstanceEstablishedAtMillis,
					)
				) {
					accessibilityCoverage = coverage.copy(instance = mediaSessionAdInstance())
				}
			}
			notePositionWrap(
				previousPositionMs = carried.lastPositionMs,
				newPositionMs = extrapolatedPosition(state),
				acrossSessionRestart = true,
			)
			EventLog.append(
				"session",
				"$packageName resumed \"${trackIdentity.semanticKey}\" after a session restart — " +
					"carrying ${carried.playedMs / 1000}s of play time forward" +
					when {
						loopDetected && explicitAdSignal != null ->
							", a detected loop, and explicit ad evidence"
						loopDetected -> " and a detected loop"
						explicitAdSignal != null -> " and explicit ad evidence"
						else -> ""
					},
			)
		}

		/**
		 * Record a strict end-to-start position reset.
		 *
		 * MediaSession exposes no "automatic loop" bit. The position boundary
		 * is the literal evidence available, and [positionWrapped] deliberately
		 * requires both ends of the item so ordinary backward seeking does not
		 * qualify.
		 */
		private fun notePositionWrap(
			previousPositionMs: Long?,
			newPositionMs: Long?,
			acrossSessionRestart: Boolean,
		) {
			if (loopDetected || !positionWrapped(previousPositionMs, newPositionMs, durationOf(metadata))) {
				return
			}
			loopDetected = true
			EventLog.append(
				"playback",
				"$packageName playback position wrapped from end to start" +
					if (acrossSessionRestart) {
						" across a session restart — loop detected"
					} else {
						" — loop detected"
					},
			)
		}

		/**
		 * Hand the finished track to the engine. Guarded so the several paths
		 * that can end a track (metadata change, stop, session destroyed,
		 * disposal — several of which fire together) only report once.
		 */
		fun finalizeCurrent(reason: String) {
			if (finalized) return
			if (metadata == null) return
			finalized = true
			accumulate()
			val snapshot = snapshot(finalizedTrack = true)
			EventLog.append(
				"finalize",
				"$packageName [$reason] ${snapshot.title ?: "<untitled>"} — " +
					"played ${snapshot.playedMs / 1000}s of " +
					"${(snapshot.durationMs ?: 0) / 1000}s" +
					// Named only when it applies, so the ordinary line is unchanged
					// and a sped-up listen is obvious rather than looking like a
					// mis-measured one.
					if (fastestSpeedSeen > 1.0) " (up to ${fastestSpeedSeen}× speed)" else "",
			)
			onTrackFinalized?.invoke(snapshot)
			MediaSessionAdEvidence.clearInstance(mediaSessionAdInstance())
			MediaSessionAccessibilityEvidence.deactivate(mediaSessionAdInstance())
		}

		private fun resetForNewTrack() {
			playedMs = 0
			fastestSpeedSeen = 1.0
			loopDetected = false
			playingSince = if (isPlaying(state)) SystemClock.elapsedRealtime() else 0
			trackStartedAtEpochSec = System.currentTimeMillis() / 1000
			finalized = false
			taintedReason = null
			latchedVideo = null
			lastStableIdentity = null
			continuationIdentity = null
			continuationAccessibilityCoverage = null
			explicitAdSignal = null
			accessibilityCoverage = null
			rejectedVideoIds.clear()
			resolverContext = ResolverContext()
		}

		/**
		 * Identity for the given metadata, using the hint bound to *this*
		 * session rather than whatever landed last for the package, applying
		 * the taint rule, and latching the video id for the track's lifetime.
		 */
		private fun identityOf(md: MediaMetadata?): YouTubeProbe.Identity {
			// A Watch waiting outside the active map has ended. Its identity was
			// frozen at disappearance; consulting live evidence here is the exact
			// race that paired Karol G's progress with the next Short's payload.
			continuationIdentity?.let { return it }

			val sessionTitle = titleOf(md)
			val hint = NotificationHints.bestFor(
				packageName = packageName,
				title = sessionTitle,
				artist = artistOf(md),
				soleSession = soleSession,
			)
			// Evidence whose id was disproven for this track is not evidence.
			val url = UrlEvidence.get(packageName)
				?.takeUnless { it.videoId != null && it.videoId in rejectedVideoIds }
			if (url != null) {
				val firstConcreteId = resolverContext.observedVideoId == null && url.videoId != null
				resolverContext = resolverContext.copy(
					playlistId = resolverContext.playlistId ?: url.playlistId,
					urlGeneration = if (firstConcreteId) {
						url.generation.takeIf { it > 0 }
					} else {
						resolverContext.urlGeneration
					},
					observedVideoId = if (firstConcreteId) url.videoId else resolverContext.observedVideoId,
					observedUrl = if (firstConcreteId) url.raw else resolverContext.observedUrl,
				)
			}
			val live = YouTubeProbe.identify(md, hint, url, soleSession)
			var rejectedThisPass = false

			if (live is YouTubeProbe.Identity.Unconfirmed && live.provenOtherSite) {
				if (taintedReason == null) {
					EventLog.append("identity", "$packageName tainted for this track: ${live.reason}")
				}
				taintedReason = live.reason
			}

			// Latch the first confirmation. Later confirmations with a
			// different id are the address bar moving on, not this track
			// changing — a track change resets the latch.
			if (taintedReason == null && latchedVideo == null &&
				live is YouTubeProbe.Identity.Confirmed &&
				live.videoId !in rejectedVideoIds
			) {
				latchedVideo = live.copy(source = "${live.source} (latched)")
				EventLog.append("identity", "$packageName latched video ${live.videoId} for this track")
				onVideoConfirmed?.invoke(live.videoId)
			}

			// Corroborate the latch once a page is known: what it says must match
			// what the session is playing. A clear mismatch means the bar was
			// already showing some other video when we latched — drop the id
			// rather than broadcast a wrong url.
			//
			// Two independent checks, because either can be unavailable. The
			// title is the stronger signal but absent whenever the fetch failed;
			// the duration survives that, and it is what the 2026-07-29
			// wrong-url case turned on. See [durationsDisagree].
			latchedVideo?.let { l ->
				val known = knownVideoFor?.invoke(l.videoId)
				val titleEvidence = if (known?.title != null && sessionTitle != null) {
					VideoTitleMatcher.compare(known.title, sessionTitle)
				} else {
					null
				}
				val weakEvidenceAllowed = titleEvidence?.let {
					titleEvidenceMayRetainObservedId(
						evidence = it,
						candidateVideoId = l.videoId,
						candidateGeneration = l.urlGeneration,
						observedVideoId = resolverContext.observedVideoId,
						observedGeneration = resolverContext.urlGeneration,
						sessionDurationMs = durationOf(md),
						pageDurationSeconds = known?.lengthSeconds,
					)
				} == true
				val disagreement = when {
					known == null -> null
					titleEvidence == VideoTitleMatcher.Evidence.CONTRADICTION ->
						"page title \"${known.title}\" ≠ session title \"$sessionTitle\""
					titleEvidence == VideoTitleMatcher.Evidence.WEAK_SHORT_CANONICAL_CORE &&
						!weakEvidenceAllowed ->
						"page title \"${known.title}\" is only weak short-title evidence " +
							"without same-generation duration corroboration"
					durationsDisagree(durationOf(md), known.lengthSeconds) ->
						"page is ${known.lengthSeconds}s but the session is playing " +
							"${(durationOf(md) ?: 0) / 1000}s"
					else -> null
				}
				if (disagreement != null) {
					EventLog.append(
						"identity",
						"$packageName unlatched ${l.videoId}: $disagreement",
					)
					rejectedVideoIds += l.videoId
					latchedVideo = null
					rejectedThisPass = true
				}
			}

			val selected = taintedReason?.let {
				YouTubeProbe.Identity.Unconfirmed("another site was proven earlier: $it", true)
			} ?: identityAfterCorroboration(
				latched = latchedVideo,
				live = live,
				rejectedVideoIds = rejectedVideoIds,
				rejectedThisPass = rejectedThisPass,
			)
			lastStableIdentity = selected
			if (selected is YouTubeProbe.Identity.Confirmed && selected.isShort) {
				selected.urlGeneration?.let { generation ->
					AdEvidence.markSessionEstablished(packageName, selected.videoId, generation)
				}
				AdEvidence.get(
					packageName,
					selected.videoId,
					selected.urlGeneration,
				)?.let(::noteAdEvidence)
			}
			return selected
		}

		/**
		 * Persist explicit ad evidence only when it names this exact Short.
		 *
		 * The id binding is mandatory. A visible watch-page pre-roll label sits
		 * over the real video's URL; treating the package alone as sufficient
		 * would veto the content the user actually chose.
		 */
		fun noteAdEvidence(evidence: AdEvidence.Evidence) {
			val identity =
				latchedVideo ?: (lastStableIdentity as? YouTubeProbe.Identity.Confirmed)
			if (!adEvidenceMatches(identity, evidence)) return
			if (explicitAdSignal == evidence.signal) return
			explicitAdSignal = evidence.signal
			EventLog.append(
				"ad",
				"$packageName bound explicit ad evidence to ${evidence.videoId}: " +
					"\"${evidence.signal}\"",
			)
		}

		/** Bind an id-less ordinary-watch label only to this exact track token. */
		fun noteMediaSessionAdEvidence(observation: MediaSessionAdEvidence.Observation) {
			val accepted = MediaSessionAdEvidence.observe(
				observation = observation,
				instance = mediaSessionAdInstance(),
				instanceEstablishedBeforeObservation =
					trackIdentity.isUsable && isPlaying(state) &&
						trackInstanceEstablishedAtMillis <= observation.atMillis,
				unambiguous = true,
			) ?: return
			if (explicitAdSignal == accepted.signal) return
			explicitAdSignal = accepted.signal
			EventLog.append(
				"ad",
				"$packageName bound explicit ad evidence to MediaSession track " +
					"${accepted.instance.token}: \"${accepted.signal}\"",
			)
		}

		/** One successful root scan supplies coverage and, independently, ad input. */
		fun noteAccessibilityScan(scan: MediaSessionAccessibilityEvidence.Scan) {
			val coverage = MediaSessionAccessibilityEvidence.observe(
				scan = scan,
				instance = mediaSessionAdInstance(),
				unambiguous = watches.values.count { it.packageName == packageName } == 1,
			) ?: return
			accessibilityCoverage = coverage
			if (scan.isShort) {
				if (scan.adSignal == null) MediaSessionAdEvidence.labelAbsent(packageName)
				return
			}
			noteMediaSessionAdEvidence(
				MediaSessionAdEvidence.Observation(
					packageName = packageName,
					signal = scan.adSignal,
					atMillis = scan.atMillis,
				),
			)
		}

		private fun mediaSessionAdInstance() = MediaSessionAdEvidence.TrackInstance(
			packageName = packageName,
			token = trackInstanceToken,
			signature = trackIdentity,
		)

		private fun currentAccessibilityCoverage(
			now: Long = System.currentTimeMillis(),
		): MediaSessionAccessibilityEvidence.Coverage? {
			MediaSessionAccessibilityEvidence.current(
				instance = mediaSessionAdInstance(),
				expectedUrlGeneration = resolverContext.urlGeneration,
				now = now,
			)?.let {
				accessibilityCoverage = it
				return it
			}
			val local = accessibilityCoverage ?: return null
			val sameInstance = local.instance.packageName == packageName &&
				local.instance.token == trackInstanceToken &&
				local.instance.signature.sameTrackAs(trackIdentity)
			val lifecycleCurrent =
				MediaSessionAccessibilityEvidence.isLifecycleCurrent(local)
			val fresh = now >= local.atMillis &&
				now - local.atMillis <= MediaSessionAccessibilityEvidence.COVERAGE_FRESH_MS
			val sameGeneration = resolverContext.urlGeneration == null ||
				local.urlGeneration == null || local.urlGeneration == resolverContext.urlGeneration
			return local.takeIf { sameInstance && lifecycleCurrent && fresh && sameGeneration }
		}

		/** The hint the probe is currently bound to, for the diagnostics card. */
		private fun boundHint(md: MediaMetadata?): NotificationHints.Hint? =
			NotificationHints.bestFor(
				packageName = packageName,
				title = titleOf(md),
				artist = artistOf(md),
				soleSession = soleSession,
			)

		/**
		 * Folds the elapsed window into [playedMs] and restarts the clock.
		 *
		 * Scaled by the playback rate, which is the whole point: the threshold
		 * compares against `duration`, so what has to be measured is *content
		 * consumed*, not seconds elapsed. A 2026-07-29 field session watched a
		 * 76 s trailer at 1.25× to position 59.9 s — 79% of the video — and it
		 * went on-chain as 67%, because 50 s of wall-clock had passed. At 2× the
		 * same arithmetic puts a fully-watched video at 50% and it never
		 * scrobbles at all.
		 *
		 * Read from `state` deliberately *before* the caller assigns the new one:
		 * the window that just ended was played at the rate that was in effect
		 * during it, not at the rate being switched to.
		 */
		private fun accumulate() {
			if (playingSince != 0L) {
				val speed = speedOf(state)
				val elapsed = SystemClock.elapsedRealtime() - playingSince
				playedMs += (elapsed * speed).toLong()
				if (speed > fastestSpeedSeen) fastestSpeedSeen = speed
				playingSince = 0
			}
		}

		private fun playedMsNow(): Long =
			playedMs + if (playingSince != 0L) {
				((SystemClock.elapsedRealtime() - playingSince) * speedOf(state)).toLong()
			} else {
				0
			}

		private fun speedOf(ps: PlaybackState?): Double = speedFactor(ps?.playbackSpeed)

		fun logMetadata(md: MediaMetadata?, reason: String) {
			EventLog.appendBlock("metadata", "$packageName ($reason)", MetadataDump.dump(md))
			logIdentity(md, reason)
		}

		/** Re-run identity after a late-arriving notification hint. */
		fun reidentify(trigger: String) = logIdentity(metadata, "re-check after $trigger")

		private fun logIdentity(md: MediaMetadata?, reason: String) {
			when (val id = identityOf(md)) {
				is YouTubeProbe.Identity.Confirmed -> EventLog.append(
					"identity",
					"$packageName → YouTube ${id.videoId} " +
						"(${if (id.isMusic) "music" else "video"}) via ${id.source}",
				)

				is YouTubeProbe.Identity.SiteOnly -> EventLog.append(
					"identity",
					"$packageName → YouTube (site only, no video id) via ${id.source}",
				)

				is YouTubeProbe.Identity.Unconfirmed -> EventLog.append(
					"identity",
					"$packageName → not proven YouTube: ${id.reason}  ← would be SKIPPED",
				)
			}
		}

		fun logPlaybackState(ps: PlaybackState?, reason: String) {
			if (ps == null) {
				EventLog.append("playback", "$packageName ($reason) <null state>")
				return
			}
			EventLog.append(
				"playback",
				"$packageName ($reason) state=${stateName(ps.state)} " +
					"pos=${ps.position}ms speed=${ps.playbackSpeed} " +
					"updatedAt=${ps.lastPositionUpdateTime} played=${playedMsNow()}ms",
			)
		}

		private fun titleOf(md: MediaMetadata?): String? =
			MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_TITLE)
				?: MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)

		private fun artistOf(md: MediaMetadata?): String? =
			MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_ARTIST)
				?: MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)

		private fun durationOf(md: MediaMetadata?): Long? =
			MetadataDump.longOrNull(md, MediaMetadata.METADATA_KEY_DURATION)

		fun snapshot(finalizedTrack: Boolean = false): SessionSnapshot {
			val md = metadata
			val ps = state
			val duration = MetadataDump.longOrNull(md, MediaMetadata.METADATA_KEY_DURATION)
			val position = extrapolatedPosition(ps)
			val played = playedMsNow()
			// A finalized snapshot may not consult the later foreground URL. Live
			// diagnostics still re-identify so the Now card remains current.
			val identity = if (finalizedTrack) {
				continuationIdentity ?: lastStableIdentity
					?: YouTubeProbe.Identity.Unconfirmed(
						"identity was not established before the track ended",
					)
			} else {
				identityOf(md)
			}
			val known = (identity as? YouTubeProbe.Identity.Confirmed)
				?.let { knownVideoFor?.invoke(it.videoId) }
			val frozenResolver = resolverContext.copy(
				knownTitle = known?.title,
				knownChannel = known?.channel,
				knownDurationSeconds = known?.lengthSeconds,
				rejectedVideoIds = rejectedVideoIds.toSet(),
			)
			val frozenCoverage = if (finalizedTrack) {
				continuationAccessibilityCoverage ?: currentAccessibilityCoverage()
			} else {
				currentAccessibilityCoverage()
			}
			return SessionSnapshot(
				packageName = packageName,
				appLabel = appLabel,
				isTarget = isTarget,
				title = titleOf(md),
				artist = artistOf(md),
				album = MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_ALBUM),
				durationMs = duration,
				positionMs = position,
				playedMs = played,
				loopDetected = loopDetected,
				explicitAdSignal = explicitAdSignal,
				browserEvidenceEnabled = browserEvidenceEnabledForThisRun(),
				accessibilityCoverage = frozenCoverage,
				playbackState = stateName(ps?.state ?: PlaybackState.STATE_NONE),
				isPlaying = isPlaying(ps),
				// Percent-of-duration using content played — the input the
				// 60% / 160% rule in ScrobbleRules consumes. Both sides of this
				// division are content milliseconds, which is why the accumulator
				// has to be speed-scaled.
				percentPlayed = duration?.takeIf { it > 0 }?.let { played.toDouble() / it },
				identity = identity,
				resolverContext = frozenResolver,
				notificationHint = boundHint(md),
				metadataLines = MetadataDump.dump(md),
				trackStartedAtEpochSec = trackStartedAtEpochSec,
			)
		}
	}

	private fun extrapolatedPosition(ps: PlaybackState?): Long? {
		if (ps == null) return null
		if (ps.position < 0) return null
		if (ps.state != PlaybackState.STATE_PLAYING) return ps.position
		val drift = SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime
		return ps.position + (drift * ps.playbackSpeed).toLong()
	}

	private fun isPlaying(ps: PlaybackState?): Boolean =
		ps?.state == PlaybackState.STATE_PLAYING

	private fun trackIdentityOf(md: MediaMetadata?): TrackIdentity = TrackIdentity(
		title = MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_TITLE),
		artist = MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_ARTIST),
		album = MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_ALBUM),
		durationMs = MetadataDump.longOrNull(md, MediaMetadata.METADATA_KEY_DURATION),
	)

	private fun stateName(state: Int): String = when (state) {
		PlaybackState.STATE_NONE -> "NONE"
		PlaybackState.STATE_STOPPED -> "STOPPED"
		PlaybackState.STATE_PAUSED -> "PAUSED"
		PlaybackState.STATE_PLAYING -> "PLAYING"
		PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARDING"
		PlaybackState.STATE_REWINDING -> "REWINDING"
		PlaybackState.STATE_BUFFERING -> "BUFFERING"
		PlaybackState.STATE_ERROR -> "ERROR"
		PlaybackState.STATE_CONNECTING -> "CONNECTING"
		PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "SKIPPING_TO_PREVIOUS"
		PlaybackState.STATE_SKIPPING_TO_NEXT -> "SKIPPING_TO_NEXT"
		PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "SKIPPING_TO_QUEUE_ITEM"
		else -> "UNKNOWN($state)"
	}

	companion object {
		/** Handler and wall clocks can differ by a few milliseconds. */
		private const val CONTINUATION_TIMER_SLOP_MS = 250L

		/**
		 * Select an identity after latch corroboration without resurrecting a
		 * value that the same pass just disproved.
		 *
		 * v0.8.8 cleared `latchedVideo` and then returned
		 * `latchedVideo ?: live`. When `live` was the value just rejected, the
		 * clear was undone in the return expression and the wrong id reached
		 * enrichment. A later active callback may still latch a different,
		 * corroborated id; only this contradictory pass fails closed.
		 */
		fun identityAfterCorroboration(
			latched: YouTubeProbe.Identity.Confirmed?,
			live: YouTubeProbe.Identity,
			rejectedVideoIds: Set<String>,
			rejectedThisPass: Boolean,
		): YouTubeProbe.Identity {
			if (rejectedThisPass) {
				return YouTubeProbe.Identity.Unconfirmed(
					"the latched video id was disproven for this track",
				)
			}
			latched?.let { return it }
			if (live is YouTubeProbe.Identity.Confirmed &&
				live.videoId in rejectedVideoIds
			) {
				return YouTubeProbe.Identity.Unconfirmed(
					"video id ${live.videoId} was disproven for this track",
				)
			}
			return live
		}

		/**
		 * Explicit ad UI only belongs to the current Short whose id was read in
		 * the same browser window. Package-only binding would turn a watch-page
		 * pre-roll into a veto on the real content behind it.
		 */
		fun adEvidenceMatches(
			identity: YouTubeProbe.Identity.Confirmed?,
			evidence: AdEvidence.Evidence,
		): Boolean =
			identity?.isShort == true &&
				identity.videoId == evidence.videoId &&
				(identity.urlGeneration == null || identity.urlGeneration == evidence.urlGeneration)

		/** Shared presentation-aware evidence for page title versus MediaSession. */
		fun titleEvidence(a: String, b: String): VideoTitleMatcher.Evidence =
			VideoTitleMatcher.compare(a, b)

		/** True only when both durations exist and do not materially disagree. */
		fun durationsCorroborate(sessionMs: Long?, pageSeconds: Long?): Boolean =
			sessionMs != null && sessionMs > 0 && pageSeconds != null && pageSeconds > 0 &&
				!durationsDisagree(sessionMs, pageSeconds)

		/** Policy boundary for the weak rank; strong ranks do not need this escape hatch. */
		fun titleEvidenceMayRetainObservedId(
			evidence: VideoTitleMatcher.Evidence,
			candidateVideoId: String,
			candidateGeneration: Long?,
			observedVideoId: String?,
			observedGeneration: Long?,
			sessionDurationMs: Long?,
			pageDurationSeconds: Long?,
		): Boolean = when (evidence) {
			VideoTitleMatcher.Evidence.EXACT,
			VideoTitleMatcher.Evidence.STRONG_CONTAINMENT -> true
			VideoTitleMatcher.Evidence.WEAK_SHORT_CANONICAL_CORE ->
				candidateVideoId == observedVideoId &&
					candidateGeneration != null && candidateGeneration > 0 &&
					candidateGeneration == observedGeneration &&
					durationsCorroborate(sessionDurationMs, pageDurationSeconds)
			VideoTitleMatcher.Evidence.CONTRADICTION -> false
		}

		/**
		 * Second, independent corroboration of a latched id: does the page's length
		 * match what the session says it's playing?
		 *
		 * Added because the title check **fails open**. On 2026-07-29 the address
		 * bar was 7 seconds late advancing a playlist, so a Danger Man track latched
		 * the *previous* entry's id — and the page fetch for that id had already
		 * timed out, so there was no title to compare and the stale id survived onto
		 * the chain with a `url` pointing at Daddy Yankee's "Con Calma". The
		 * durations were 226 s against 193 s: the mismatch was sitting right there.
		 *
		 * Tolerance is both absolute *and* proportional, and it has to be both.
		 * `lengthSeconds` and the session's `DURATION` routinely differ by a second
		 * of rounding, so a flat threshold alone is too noisy; a percentage alone
		 * would let a 30-second disagreement pass on a two-hour video.
		 */
		fun durationsDisagree(sessionMs: Long?, pageSeconds: Long?): Boolean {
			if (sessionMs == null || sessionMs <= 0 || pageSeconds == null || pageSeconds <= 0) {
				return false
			}
			val pageMs = pageSeconds * 1000
			val diff = kotlin.math.abs(sessionMs - pageMs)
			val longer = maxOf(sessionMs, pageMs)
			return diff > DURATION_TOLERANCE_MS &&
				diff > longer * DURATION_TOLERANCE_FRACTION
		}

		/**
		 * Strict evidence that the same media item restarted at its beginning.
		 *
		 * Both boundary checks and the minimum jump are required. A person may
		 * seek backward anywhere in a long video; only a transition from the
		 * final 20% to the first 20%, covering at least half the duration,
		 * counts as the continuous loop signal used by the scrobble cap.
		 */
		fun positionWrapped(
			previousPositionMs: Long?,
			newPositionMs: Long?,
			durationMs: Long?,
		): Boolean {
			if (previousPositionMs == null || newPositionMs == null ||
				durationMs == null || durationMs <= 0 ||
				previousPositionMs < 0 || newPositionMs < 0
			) {
				return false
			}
			val nearEnd = previousPositionMs.toDouble() >= durationMs * LOOP_END_FRACTION
			val nearStart = newPositionMs.toDouble() <= durationMs * LOOP_START_FRACTION
			val largeReset =
				previousPositionMs - newPositionMs >= durationMs * LOOP_MIN_RESET_FRACTION
			return nearEnd && nearStart && largeReset
		}

		/**
		 * Ceiling on the rate [Watch.accumulate] will scale by.
		 *
		 * YouTube's own maximum is 2×; this leaves headroom for players that
		 * offer more while refusing to let one absurd `playbackSpeed` sample turn
		 * ten seconds of play into a full listen.
		 */
		const val MAX_PLAYBACK_SPEED = 4.0

		/** Rounding slack between `lengthSeconds` and the session's `DURATION`. */
		const val DURATION_TOLERANCE_MS = 5_000L

		/** Also required, so long videos aren't held to the flat 5 s. */
		const val DURATION_TOLERANCE_FRACTION = 0.05

		const val LOOP_END_FRACTION = 0.8
		const val LOOP_START_FRACTION = 0.2
		const val LOOP_MIN_RESET_FRACTION = 0.5

		/**
		 * The rate to score a play window at, given what `PlaybackState`
		 * reported.
		 *
		 * Non-positive — paused, or simply unreported — is **not** an instruction
		 * to count zero. `playingSince` already decides whether a window counts
		 * at all; a `speed=0.0` sample landing mid-window would otherwise erase
		 * time genuinely spent playing. A missing value means "assume normal
		 * speed", which is the pre-v0.8.1 behaviour.
		 *
		 * Clamped above so one absurd sample can't turn ten seconds into a full
		 * listen. Extracted here, away from the Android types, so those rules are
		 * unit-testable.
		 */
		fun speedFactor(reported: Float?): Double {
			val raw = reported?.toDouble() ?: return 1.0
			if (!raw.isFinite() || raw <= 0.0) return 1.0
			return raw.coerceAtMost(MAX_PLAYBACK_SPEED)
		}

		/** True if the app's notification listener is currently enabled. */
		fun hasNotificationAccess(context: Context): Boolean {
			val enabled = android.provider.Settings.Secure.getString(
				context.contentResolver,
				"enabled_notification_listeners",
			).orEmpty()
			val pkg = context.packageName
			return enabled.split(':').any { it.startsWith("$pkg/") }
		}
	}

}
