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
	/**
	 * The Short is still playing but its progress surface has gone away, so
	 * nothing further can be measured.
	 *
	 * Measured on 2026-08-05 in picture-in-picture: the accessibility tree keeps
	 * `reel_watch_fragment_root`, `reel_watch_player` and `reel_time_bar`, but
	 * the time bar loses its `SeekBar` child and no time text exists anywhere in
	 * the window, while YouTube's MediaSession reports `STATE_NONE` with
	 * `position=0`. Neither source can say how much was played.
	 *
	 * This is not the same as "0% was played", and reporting it as such is what
	 * made a PiP session indistinguishable from a parser bug for most of a day.
	 */
	val foregroundProgressLost: Boolean = false,
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
	/** Structured native metadata that can explicitly identify podcast/episode context. */
	val genre: String? = null,
	/** Native source generation; invalidated by opt-out, Stop and listener reconnect. */
	val sourceEpoch: Long? = null,
	/** Literal observer provenance; never inferred from a resolver result. */
	val sourceProof: SourceProof = SourceProof.MEDIA_SESSION,
	/** Exact accessibility owner handle for the native foreground-Short route. */
	val ownerHandle: String? = null,
) {
	val confirmed: YouTubeProbe.Identity.Confirmed?
		get() = identity as? YouTubeProbe.Identity.Confirmed

	/** True when the site is proven YouTube, with or without a video id. */
	val isYouTube: Boolean
		get() = identity is YouTubeProbe.Identity.Confirmed ||
			identity is YouTubeProbe.Identity.SiteOnly

	val origin: YouTubeProbe.Origin get() = YouTubeProbe.originForPackage(packageName)

	val isNative: Boolean get() = YouTubeProbe.isNativePackage(packageName)

	val isNativeYouTubeMusic: Boolean
		get() = packageName == YouTubeProbe.YOUTUBE_MUSIC_PACKAGE

	val isForegroundShort: Boolean
		get() = sourceProof == SourceProof.NATIVE_FOREGROUND_SHORT

	/** Browser path proof or the separately proven native foreground player. */
	val hasShortSourceProof: Boolean
		get() = isForegroundShort || confirmed?.isShort == true

	/** Progress-only threshold check; final eligibility still belongs to ScrobbleRules. */
	fun reachedThreshold(threshold: Double): Boolean =
		(percentPlayed ?: 0.0) >= threshold
}

enum class SourceProof {
	MEDIA_SESSION,
	NATIVE_FOREGROUND_SHORT,
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
	/**
	 * Unique structured id proven while an exact-ID-less native track was still
	 * playing. Memory-only carry authority; finalization must re-fetch it.
	 */
	val preResolvedNativeVideoId: String? = null,
	/** The resolver predicate whose uniqueness proof authorized that id. */
	val preResolvedNativeRoute: NativePreResolvedRoute? = null,
	/**
	 * Playlist bar name read off the native watch screen.
	 *
	 * Kept separate from [playlistId], which stays exclusively browser
	 * address-bar evidence, so a native observation can never be mistaken for a
	 * proven URL. Resolved to an id at finalization
	 * (`PHASE_NATIVE_PLAYLIST_IDENTITY.md` §7).
	 */
	val nativePlaylistName: String? = null,
	val nativePlaylistOwner: String? = null,
	val nativePlaylistTotal: Int? = null,
)

enum class NativePreResolvedRoute {
	RAW_TITLE_CHANNEL,
	STRUCTURED_MUSIC,

	/**
	 * The id came from the bounded entry list of the playlist being played.
	 *
	 * Kept distinct so finalization re-verifies against that same playlist.
	 * Re-deriving it from the watch page instead loses the playlist's own
	 * channel evidence, which is what silently dropped `Te Busco` /
	 * `7J6xA1_f8as` on 2026-08-04.
	 */
	PLAYLIST,

	/**
	 * The id came from the signed-in account's watch history.
	 *
	 * Kept distinct for the same reason as [PLAYLIST]: finalization re-asks the
	 * feed that produced it and requires the same id back, so a listen that
	 * history has since re-described refuses instead of carrying a stale answer.
	 */
	HISTORY,
}
