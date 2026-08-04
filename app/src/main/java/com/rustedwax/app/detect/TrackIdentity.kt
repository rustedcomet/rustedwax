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
) {
	private val titleKey = normalize(title)
	private val artistKey = normalize(artist)
	private val albumKey = normalize(album)

	/** Stable carry key; duration is validated separately by [sameTrackAs]. */
	val semanticKey: String = listOf(titleKey, artistKey, albumKey).joinToString("|")

	val isUsable: Boolean get() = titleKey.isNotEmpty()

	fun sameTrackAs(other: TrackIdentity): Boolean {
		if (titleKey != other.titleKey || artistKey != other.artistKey || albumKey != other.albumKey) {
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
		durationMs.validDuration() == null && other.durationMs.validDuration() != null ->
			copy(durationMs = other.durationMs)
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
