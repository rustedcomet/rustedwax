package com.rustedwax.app.detect

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * The semantic identity of one MediaSession track.
 *
 * Chromium republishes otherwise identical metadata while refining duration.
 * Duration is therefore evidence about a track, not an exact component of its
 * identity. Title, artist and album remain the stable identity fields; a
 * missing duration becoming known or a bounded rounding drift refines the
 * observation without ending the track.
 */
data class TrackIdentity(
	val title: String?,
	val artist: String?,
	val album: String?,
	val durationMs: Long?,
	/** Exact source item id when a native MediaSession publishes one. */
	val sourceItemId: String? = null,
) {
	private val titleKey = normalize(title)
	private val artistKey = normalize(artist)
	private val albumKey = normalize(album)
	// YouTube video ids are case-sensitive; unlike presentation metadata this
	// value must never be case-folded.
	private val sourceItemKey = sourceItemId?.trim().orEmpty()
	val hasExactSourceItemId: Boolean get() = sourceItemKey.isNotEmpty()

	/** Stable carry key; duration is validated separately by [sameTrackAs]. */
	val semanticKey: String = listOf(titleKey, artistKey, albumKey).joinToString("|")

	val isUsable: Boolean get() = titleKey.isNotEmpty()

	/**
	 * Same presentation metadata, no exact id on either side, but durations that
	 * cannot describe one continuous item. Native YouTube emitted this shape for
	 * short transition/ad phases while the organic song metadata was already
	 * installed. The caller must discard rather than finalize or carry the first
	 * fragment; this predicate itself never labels the fragment an ad.
	 */
	fun isExactIdlessMaterialDurationReplacement(other: TrackIdentity): Boolean {
		if (!isUsable || !other.isUsable ||
			sourceItemKey.isNotEmpty() || other.sourceItemKey.isNotEmpty() ||
			titleKey != other.titleKey || artistKey != other.artistKey || albumKey != other.albumKey
		) return false
		val left = durationMs.validDuration() ?: return false
		val right = other.durationMs.validDuration() ?: return false
		return abs(left - right) > DURATION_REFINEMENT_TOLERANCE_MS
	}

	fun sameTrackAs(other: TrackIdentity): Boolean {
		if (titleKey != other.titleKey || artistKey != other.artistKey || albumKey != other.albumKey) {
			return false
		}
		if (sourceItemKey.isNotEmpty() && other.sourceItemKey.isNotEmpty() &&
			sourceItemKey != other.sourceItemKey
		) {
			return false
		}
		val left = durationMs.validDuration()
		val right = other.durationMs.validDuration()
		return left == null || right == null || abs(left - right) <= DURATION_REFINEMENT_TOLERANCE_MS
	}

	/**
	 * Keep the first concrete duration as the comparison baseline. If the first
	 * observation omitted duration, the first known value establishes it.
	 */
	fun refinedWith(other: TrackIdentity): TrackIdentity = when {
		!sameTrackAs(other) -> other
		durationMs.validDuration() == null && other.durationMs.validDuration() != null ||
			sourceItemKey.isEmpty() && other.sourceItemKey.isNotEmpty() -> copy(
			durationMs = durationMs.validDuration() ?: other.durationMs,
			sourceItemId = sourceItemId?.takeIf(String::isNotBlank) ?: other.sourceItemId,
		)
		else -> this
	}

	companion object {
		const val DURATION_REFINEMENT_TOLERANCE_MS = 2_000L

		private fun normalize(value: String?): String = value
			?.let { Normalizer.normalize(it, Normalizer.Form.NFKC) }
			?.lowercase(Locale.ROOT)
			?.replace(Regex("""\s+"""), " ")
			?.trim()
			.orEmpty()

		private fun Long?.validDuration(): Long? = this?.takeIf { it > 0 }
	}
}
