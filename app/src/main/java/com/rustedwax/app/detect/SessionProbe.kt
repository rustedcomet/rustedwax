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
 * `position + (now - sampledAt) * speed`. We also accumulate *real played
 * milliseconds* across play/pause, which is what the 60% rule consumes and is
 * computable from this data alone.
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

	private var started = false

	private val activeSessionsListener =
		MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
			syncControllers(controllers ?: emptyList())
		}

	fun start() {
		if (started) return
		// A notification hint often arrives *after* we've already judged the
		// track (measured: ~300 ms later), so re-run identity when one lands.
		NotificationHints.onHint = { pkg ->
			handler.post {
				watches.values
					.filter { it.packageName == pkg }
					.forEach { it.reidentify() }
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

	fun stop() {
		if (!started) return
		NotificationHints.onHint = null
		runCatching { sessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener) }
		watches.values.forEach { it.dispose() }
		watches.clear()
		started = false
		EventLog.append("probe", "stopped")
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
				it.dispose()
			}
		}

		// New.
		for ((key, controller) in byKey) {
			if (watches.containsKey(key)) continue
			val watch = Watch(controller, labelFor(controller.packageName))
			watches[key] = watch
			EventLog.append(
				"session",
				"+ ${watch.packageName} (${watch.appLabel})" +
					if (watch.isTarget) "  ← target" else "  [control, not scrobbled]",
			)
			watch.logMetadata(controller.metadata, "initial")
			watch.logPlaybackState(controller.playbackState, "initial")
		}

		publish()
	}

	private fun publish() {
		_sessions.value = watches.values.map { it.snapshot() }
	}

	private fun labelFor(packageName: String): String = runCatching {
		packageManager
			.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0))
			.toString()
	}.getOrDefault(packageName)

	/** Per-controller state + callbacks. */
	private inner class Watch(
		private val controller: MediaController,
		val appLabel: String,
	) {
		val packageName: String = controller.packageName

		/** Brave/Chrome only. Everything else is observed but never scrobbled. */
		val isTarget: Boolean = packageName in YouTubeProbe.TARGET_PACKAGES

		private var metadata: MediaMetadata? = controller.metadata
		private var state: PlaybackState? = controller.playbackState

		/** Real milliseconds spent in STATE_PLAYING for the current track. */
		private var playedMs: Long = 0
		private var playingSince: Long = if (isPlaying(state)) SystemClock.elapsedRealtime() else 0
		private var trackKey: String = trackKeyOf(metadata)
		private var trackStartedAtEpochSec: Long = System.currentTimeMillis() / 1000

		/** One finalize per track, however many callbacks announce the end. */
		private var finalized: Boolean = false

		private val callback = object : MediaController.Callback() {
			override fun onMetadataChanged(md: MediaMetadata?) {
				val newKey = trackKeyOf(md)
				if (newKey != trackKey) {
					EventLog.append(
						"track",
						"$packageName track change after ${playedMsNow() / 1000}s played",
					)
					finalizeCurrent("track change")
					resetForNewTrack()
					trackKey = newKey
				}
				metadata = md
				logMetadata(md, "changed")
				publish()
			}

			override fun onPlaybackStateChanged(ps: PlaybackState?) {
				accumulate()
				val wasPlaying = isPlaying(state)
				state = ps
				if (isPlaying(ps) && playingSince == 0L) {
					playingSince = SystemClock.elapsedRealtime()
				}
				// STOPPED means the track is over; PAUSED does not — a paused
				// track is often resumed, and finalizing it would scrobble a
				// half-listen and then dedup-block the real one.
				if (wasPlaying && ps?.state == PlaybackState.STATE_STOPPED) {
					finalizeCurrent("stopped")
				}
				logPlaybackState(ps, "changed")
				publish()
			}

			override fun onSessionDestroyed() {
				EventLog.append("session", "× $packageName destroyed")
				finalizeCurrent("session destroyed")
				publish()
			}

		}

		init {
			controller.registerCallback(callback, handler)
		}

		fun dispose() {
			// A session disappearing is the last chance to score the track —
			// closing the tab never produces a STOPPED state.
			finalizeCurrent("session ended")
			runCatching { controller.unregisterCallback(callback) }
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
			val snapshot = snapshot()
			EventLog.append(
				"finalize",
				"$packageName [$reason] ${snapshot.title ?: "<untitled>"} — " +
					"played ${snapshot.playedMs / 1000}s of " +
					"${(snapshot.durationMs ?: 0) / 1000}s",
			)
			onTrackFinalized?.invoke(snapshot)
		}

		private fun resetForNewTrack() {
			playedMs = 0
			playingSince = if (isPlaying(state)) SystemClock.elapsedRealtime() else 0
			trackStartedAtEpochSec = System.currentTimeMillis() / 1000
			finalized = false
		}

		/** Folds elapsed playing time into [playedMs] and restarts the clock. */
		private fun accumulate() {
			if (playingSince != 0L) {
				playedMs += SystemClock.elapsedRealtime() - playingSince
				playingSince = 0
			}
		}

		private fun playedMsNow(): Long =
			playedMs + if (playingSince != 0L) SystemClock.elapsedRealtime() - playingSince else 0

		fun logMetadata(md: MediaMetadata?, reason: String) {
			EventLog.appendBlock("metadata", "$packageName ($reason)", MetadataDump.dump(md))
			logIdentity(md, reason)
		}

		/** Re-run identity after a late-arriving notification hint. */
		fun reidentify() = logIdentity(metadata, "re-check after notification")

		private fun logIdentity(md: MediaMetadata?, reason: String) {
			when (val id = YouTubeProbe.identify(md, NotificationHints.get(packageName))) {
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
					"$packageName → not proven YouTube: ${id.reason}" +
						if (isTarget) "  ← would be SKIPPED" else "",
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

		fun snapshot(): SessionSnapshot {
			val md = metadata
			val ps = state
			val duration = MetadataDump.longOrNull(md, MediaMetadata.METADATA_KEY_DURATION)
			val position = extrapolatedPosition(ps)
			val played = playedMsNow()
			return SessionSnapshot(
				packageName = packageName,
				appLabel = appLabel,
				isTarget = isTarget,
				title = MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_TITLE)
					?: MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
				artist = MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_ARTIST)
					?: MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
				album = MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_ALBUM),
				durationMs = duration,
				positionMs = position,
				playedMs = played,
				playbackState = stateName(ps?.state ?: PlaybackState.STATE_NONE),
				isPlaying = isPlaying(ps),
				// Percent-of-duration using real played time — the input the
				// 60% / 160% rule in ScrobbleRules will consume in Phase 3.
				percentPlayed = duration?.takeIf { it > 0 }?.let { played.toDouble() / it },
				identity = YouTubeProbe.identify(md, NotificationHints.get(packageName)),
				notificationHint = NotificationHints.get(packageName),
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

	private fun trackKeyOf(md: MediaMetadata?): String = listOf(
		MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
		MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
		MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
		MetadataDump.longOrNull(md, MediaMetadata.METADATA_KEY_DURATION)?.toString().orEmpty(),
	).joinToString("|")

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
