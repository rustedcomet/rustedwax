package com.rustedwax.app.scrobble

import com.rustedwax.app.hive.HiveScrobblePayload
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
	 * Tracks shorter than this are ignored. Not in upstream — it was added
	 * because YouTube pre-roll ads publish their own media session with the
	 * *video's* title and a ~6 s duration (observed in PHASE0 run 1), which
	 * would otherwise scrobble the song on every ad.
	 *
	 * It now does double duty. A 2026-07-28 shorts-heavy session tripped it 21
	 * times, and every title was genuine content rather than an ad — including
	 * a 10 s guitar clip watched through twice (12 s played of 10 s). Those are
	 * skipped deliberately: a ten-second snippet isn't a listen of the song,
	 * and admitting them would fill a music logbook with fragments.
	 *
	 * Kept as a plain floor rather than exempting `/shorts/`, which the app can
	 * now detect. The floor is about *length being too short to count*, and
	 * blocking ads is a side benefit — so the message says that instead of
	 * calling a real short an ad.
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
				"track is ${durationMs / 1000}s, under the ${MIN_DURATION_SECONDS}s minimum " +
					"— too short to count as a listen (also blocks pre-roll ads)",
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

	/**
	 * The 160% double-listen only applies to songs.
	 *
	 * A deliberate deviation from upstream, which doubles every kind. The rule
	 * exists to record a genuine second listen — but YouTube shorts auto-loop,
	 * so any short watched to 1.6× its 40 seconds produced two `video`
	 * transactions for one sitting (observed on-chain 2026-07-24: the same
	 * clip broadcast twice in one block at 100% and 76%). A video is watched,
	 * not re-listened; one transaction is the honest record.
	 */
	fun capForKind(percentages: List<Int>, kind: String): List<Int> =
		if (kind == HiveScrobblePayload.KIND_SONG) percentages else percentages.take(1)
}
