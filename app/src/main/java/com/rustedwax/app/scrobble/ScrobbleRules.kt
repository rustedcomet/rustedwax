package com.rustedwax.app.scrobble

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * When a finished track becomes one or more scrobbles.
 *
 * Ported from `hive-scrobbler.ts#finalize` in the extension, with one
 * deliberate difference: upstream's music branch has no threshold check of its
 * own, because web-scrobbler's controller only calls `finalize()` for tracks it
 * already decided were scrobbleable. We have no controller, so the
 * `scrobblePercent` gate lives here.
 *
 * Upstream behaviour that is preserved exactly:
 *   - 1 tx at ≥60%, a 2nd at ≥160% (a genuine double-listen), capped at 2.
 *   - `percent_played` for tx *i* is `min(100, round((progress - i) * 100))`.
 */
object ScrobbleRules {

	/** The extension's `scrobblePercent` default. */
	const val DEFAULT_THRESHOLD = 0.6

	/**
	 * Tracks shorter than this are ignored. Not in upstream — it's here because
	 * YouTube pre-roll ads publish their own media session with the *video's*
	 * title and a ~6 s duration (observed in PHASE0 run 1), which would
	 * otherwise scrobble the song on every ad.
	 */
	const val MIN_DURATION_SECONDS = 30L

	data class Decision(
		/** One entry per transaction to broadcast, each with its own percent. */
		val percentages: List<Int>,
		/** Why nothing is being broadcast, for the log. Null when scrobbling. */
		val skippedBecause: String? = null,
	) {
		val shouldScrobble: Boolean get() = percentages.isNotEmpty()
	}

	fun decide(
		playedMs: Long,
		durationMs: Long?,
		threshold: Double = DEFAULT_THRESHOLD,
	): Decision {
		if (durationMs == null || durationMs <= 0) {
			return Decision(emptyList(), "no duration — can't measure progress")
		}
		if (durationMs < MIN_DURATION_SECONDS * 1000) {
			return Decision(
				emptyList(),
				"track shorter than ${MIN_DURATION_SECONDS}s (probably an ad)",
			)
		}

		val progress = playedMs.toDouble() / durationMs
		if (progress < threshold) {
			return Decision(
				emptyList(),
				"played ${(progress * 100).roundToInt()}%, below " +
					"${(threshold * 100).roundToInt()}% threshold",
			)
		}

		// Upstream: min(2, 1 + floor(max(0, progress - 0.6)))
		val txCount = min(2.0, 1 + floor(maxOf(0.0, progress - threshold))).toInt()
		val percentages = (0 until txCount).map { i ->
			min(100, ((progress - i) * 100).roundToInt())
		}
		return Decision(percentages)
	}
}
