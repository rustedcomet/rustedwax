package com.rustedwax.app.detect

/**
 * One media session as observed right now.
 *
 * Field names deliberately echo `HiveScrobblePayload` (title / artist / album /
 * duration / percent_played / url) so the diagnostics read as "here is the
 * payload we could have built, and here is what's missing."
 */
data class SessionSnapshot(
	val packageName: String,
	val appLabel: String,
	/** True for Brave — the only source v1 scrobbles. Others are control cases. */
	val isTarget: Boolean,
	val title: String?,
	val artist: String?,
	val album: String?,
	val durationMs: Long?,
	val positionMs: Long?,
	/** Real time spent in STATE_PLAYING for this track — drives the 60% rule. */
	val playedMs: Long,
	val playbackState: String,
	val isPlaying: Boolean,
	/** playedMs / durationMs; null when duration is unknown. */
	val percentPlayed: Double?,
	val identity: YouTubeProbe.Identity,
	/** Most recent browser media notification seen for this package, if any. */
	val notificationHint: NotificationHints.Hint?,
	val metadataLines: List<String>,
	val trackStartedAtEpochSec: Long,
) {
	val confirmed: YouTubeProbe.Identity.Confirmed?
		get() = identity as? YouTubeProbe.Identity.Confirmed

	/** True when the site is proven YouTube, with or without a video id. */
	val isYouTube: Boolean
		get() = identity is YouTubeProbe.Identity.Confirmed ||
			identity is YouTubeProbe.Identity.SiteOnly

	/**
	 * Everything v1 needs to broadcast: proven YouTube, a title, and a duration
	 * to measure 60% against. Anything less is skipped rather than guessed.
	 * A missing video id costs the payload its `url`, not its validity.
	 */
	val payloadViable: Boolean
		get() = isYouTube && !title.isNullOrBlank() && durationMs != null

	/** Would the extension's music branch have broadcast by now? (≥60%.) */
	val wouldScrobble: Boolean
		get() = payloadViable && (percentPlayed ?: 0.0) >= 0.6
}
