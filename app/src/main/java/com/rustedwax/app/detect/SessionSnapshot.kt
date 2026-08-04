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
	/**
	 * True for the target browsers. Always true since Phase 4 — anything else
	 * is no longer watched at all, rather than watched and skipped.
	 */
	val isTarget: Boolean,
	val title: String?,
	val artist: String?,
	val album: String?,
	val durationMs: Long?,
	val positionMs: Long?,
	/**
	 * Content consumed in this track — time in STATE_PLAYING scaled by the
	 * playback rate. Drives the 60% rule, so it has to be in the same units as
	 * [durationMs]. See `SessionProbe.Watch.accumulate`.
	 */
	val playedMs: Long,
	/**
	 * True when playback was observed moving from the end of this media item
	 * back to its beginning during the same continuous viewing. Carried across
	 * Chromium media-session recreation.
	 */
	val loopDetected: Boolean,
	/**
	 * Exact visible YouTube UI label bound to this track instance as an
	 * advertisement, or null when no explicit label was observed.
	 */
	val explicitAdSignal: String? = null,
	/** Browser evidence access was enabled for the monitoring run that observed this track. */
	val browserEvidenceEnabled: Boolean = false,
	/** Fresh successful visible-YouTube-root scan frozen for this exact track. */
	val accessibilityCoverage: MediaSessionAccessibilityEvidence.Coverage? = null,
	val playbackState: String,
	val isPlaying: Boolean,
	/** playedMs / durationMs; null when duration is unknown. */
	val percentPlayed: Double?,
	val identity: YouTubeProbe.Identity,
	/**
	 * Resolver inputs frozen while this track was active. Asynchronous
	 * finalization must never consult the package's later foreground URL.
	 */
	val resolverContext: ResolverContext = ResolverContext(),
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

	/** Progress-only threshold check; final eligibility still belongs to ScrobbleRules. */
	fun reachedThreshold(threshold: Double): Boolean =
		(percentPlayed ?: 0.0) >= threshold
}

/** Immutable URL, playlist and cache evidence carried into finalization. */
data class ResolverContext(
	val playlistId: String? = null,
	val urlGeneration: Long? = null,
	val observedVideoId: String? = null,
	val observedUrl: String? = null,
	val knownTitle: String? = null,
	val knownChannel: String? = null,
	val knownDurationSeconds: Long? = null,
	val rejectedVideoIds: Set<String> = emptySet(),
)
