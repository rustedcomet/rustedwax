package com.rustedwax.app.detect

/**
 * What the accessibility service could see while it was receiving nothing.
 *
 * The distinction is the whole point: silence only means something once paired
 * with what was on screen at the time.
 */
enum class ObservedSurface {
	/** A complete Shorts player parsed on this capture. */
	YOUTUBE_SHORTS_PLAYER,

	/** A visible native-YouTube root, but no complete Shorts player in it. */
	YOUTUBE_NO_PLAYER,

	/**
	 * `rootInActiveWindow` returned null: the service has no active window at
	 * all. With the screen on this is itself anomalous — it is the service being
	 * unable to see, not the user being elsewhere.
	 */
	NO_ROOT,

	/** Some other package is foreground. Silence is fully explained. */
	OTHER_APP,
}

/**
 * Names the outage that `FIELD_2026-08-05.md` §5.1 could only infer from gaps.
 *
 * ## The bug this instruments
 *
 * Between 17:19:40 and 17:21:07 on 2026-08-05 the log held two lines, 30s apart
 * — the idle poll and nothing else — while a Short was playing. The Shorts
 * observer had received no YouTube accessibility event for 87 seconds. In the
 * same session a Short titled `💯💯💯🔥😎` never appears in the log at all:
 * never observed, never finalized. It is the same shape as the earlier 13h
 * outage, it explains the regular-mode Shorts that did not scrobble, and it is
 * very likely why roughly half that day's watch history never reached the app.
 *
 * At hand-off the service looked healthy — `crashed services: {}` empty, both
 * grants on, standby bucket 10 — so it is intermittent and cannot be reproduced
 * on demand. Hunting a cause that only appears by surprise, from evidence that
 * only exists as an absence of lines, is how the 13h outage went unexplained.
 * So this measures it first: the next occurrence writes its own name, its own
 * duration and what was on screen at the time.
 *
 * ## Why the surface has to come from *every* capture, not just a good one
 *
 * The first cut of this class reported only when a YouTube capture succeeded,
 * which would have stayed silent through the very event it exists to catch.
 * Those two 30s-apart lines in the field log are the *throttled* idle poll: the
 * captures were running the whole time and finding nothing. Whatever went wrong
 * took the accessibility tree with it, so requiring a successful YouTube capture
 * as the precondition for reporting is requiring the outage not to be happening.
 *
 * [ObservedSurface.NO_ROOT] with the screen on is therefore a first-class
 * reportable state, and the most likely shape of the real fault.
 *
 * ## Why silence alone is still not enough
 *
 * Event silence is *normal* for ordinary watch playback. Once a song is playing
 * the watch UI is static and YouTube emits no accessibility events at all —
 * measured 2026-08-04, six minutes of playback produced zero observations, which
 * is why [NativeShortsAccessibilityService] polls for the playlist bar rather
 * than waiting for callbacks. It is equally normal with the screen off, or while
 * the user is in another app.
 *
 * So a report requires the screen to be on and the surface not to be
 * [ObservedSurface.OTHER_APP], and every line says which surface it saw. That
 * keeps the cases apart in the log without pretending the quiet watch screen is
 * a fault.
 *
 * A paused Short left on screen reports as [ObservedSurface.YOUTUBE_SHORTS_PLAYER].
 * That is a known and accepted false positive: it is indistinguishable from the
 * real thing without crediting time this app deliberately never credits.
 *
 * ## Reporting-only
 *
 * Nothing here changes a scrobble decision, a capture, or any gate. It observes
 * and it writes lines.
 */
class AccessibilityEventSilence(
	private val silenceThresholdMillis: Long = SILENCE_THRESHOLD_MS,
	private val repeatIntervalMillis: Long = REPEAT_INTERVAL_MS,
	private val probeIntervalMillis: Long = PROBE_INTERVAL_MS,
) {

	private var lastSignalAtElapsed: Long? = null
	private var lastReportAtElapsed: Long? = null
	private var lastProbeAtElapsed: Long? = null
	private var reported = false

	/**
	 * The service connected. Starts the clock without claiming anything: a
	 * freshly connected service has legitimately seen no events yet, and the
	 * window before the first one must not read as an outage.
	 */
	@Synchronized
	fun started(atElapsed: Long) {
		lastSignalAtElapsed = atElapsed
		lastReportAtElapsed = null
		lastProbeAtElapsed = null
		reported = false
	}

	/**
	 * A YouTube accessibility event arrived: the stream is alive.
	 *
	 * @return a line to log when this ends an outage that was reported, naming
	 * the measured duration, or null. The recovery line is the half that makes
	 * the next occurrence self-describing — a start with no end is how long an
	 * outage lasted becomes guesswork from log gaps again.
	 */
	@Synchronized
	fun eventReceived(atElapsed: Long): String? {
		val since = lastSignalAtElapsed
		val recovered = if (reported && since != null) {
			"accessibility event stream recovered after ${seconds(atElapsed - since)}s of silence"
		} else {
			null
		}
		lastSignalAtElapsed = atElapsed
		lastReportAtElapsed = null
		lastProbeAtElapsed = null
		reported = false
		return recovered
	}

	/**
	 * The outcome of one capture attempt, whatever it found.
	 *
	 * @param screenInteractive the display is on. Silence with the screen off is
	 * correct behaviour and must never be reported.
	 * @return a line to log, or null when the stream is healthy, the silence is
	 * explained, or a report is not yet due.
	 */
	@Synchronized
	fun observed(
		atElapsed: Long,
		surface: ObservedSurface,
		screenInteractive: Boolean,
	): String? {
		val since = lastSignalAtElapsed
		if (since == null) {
			lastSignalAtElapsed = atElapsed
			return null
		}
		// Another app being foreground, or a dark screen, fully explains the
		// quiet. Reporting either would bury the case that matters.
		if (!screenInteractive || surface == ObservedSurface.OTHER_APP) return null
		val silentFor = atElapsed - since
		if (silentFor < silenceThresholdMillis) return null
		lastReportAtElapsed?.let { if (atElapsed - it < repeatIntervalMillis) return null }
		lastReportAtElapsed = atElapsed
		val first = !reported
		reported = true
		return if (first) {
			"accessibility event stream silent for ${seconds(silentFor)}s with the screen on " +
				"(${describe(surface)}) — anything played in this window is unobserved, not idle"
		} else {
			"accessibility event stream still silent after ${seconds(silentFor)}s " +
				"(${describe(surface)})"
		}
	}

	/**
	 * Whether a foreground capture is worth making purely to check for silence.
	 *
	 * The outage that matters happens when *nothing* is latched — no active
	 * Short means no refresh, and a latched playlist stops the acquisition poll
	 * too, so the observer can go completely quiet and leave no trace. Without
	 * this probe the detector would only ever see the outages it least needs to.
	 *
	 * Mutating, like the playlist poll beside it: asking rate-limits the asking.
	 */
	@Synchronized
	fun probeDue(nowElapsed: Long): Boolean {
		val since = lastSignalAtElapsed ?: return false
		if (nowElapsed - since < silenceThresholdMillis) return false
		lastProbeAtElapsed?.let { if (nowElapsed - it < probeIntervalMillis) return false }
		lastProbeAtElapsed = nowElapsed
		return true
	}

	private fun describe(surface: ObservedSurface): String = when (surface) {
		ObservedSurface.YOUTUBE_SHORTS_PLAYER -> "Shorts player on screen"
		ObservedSurface.YOUTUBE_NO_PLAYER -> "YouTube on screen, no complete Shorts player"
		ObservedSurface.NO_ROOT ->
			"no active window visible to the service at all — the service cannot see"
		ObservedSurface.OTHER_APP -> "another app is foreground"
	}

	private fun seconds(millis: Long): Long = millis / 1000

	companion object {
		/**
		 * Comfortably longer than any healthy gap.
		 *
		 * A Shorts feed emits constantly while scrolling, and the measured
		 * outage ran 87 seconds against a 30-second idle poll. 45s is past
		 * anything the seekbar's own updates leave behind, and still short
		 * enough that a report and its recovery both land inside one outage.
		 */
		const val SILENCE_THRESHOLD_MS = 45_000L

		/** A long outage should leave a trail, not a flood. */
		const val REPEAT_INTERVAL_MS = 60_000L

		/** Cheap enough to run while silent, rare enough to cost nothing. */
		const val PROBE_INTERVAL_MS = 15_000L
	}
}
