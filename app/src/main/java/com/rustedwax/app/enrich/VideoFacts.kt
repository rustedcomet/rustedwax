package com.rustedwax.app.enrich

/**
 * What a resolver managed to learn about a video beyond what the media session
 * said.
 *
 * Every field is optional and every field is advisory. Enrichment runs on a
 * budget against markup nobody promised us, so the pipeline must produce a
 * correct scrobble with this object absent, empty, or only half filled in.
 */
data class VideoFacts(
	val videoId: String,
	/** The real video title, free of whatever the page passed to MediaSession. */
	val title: String? = null,
	/** The uploading channel. */
	val author: String? = null,
	/** YouTube's own category — "Music", "Gaming", "Entertainment"… */
	val category: String? = null,
	/** Artist credited in the description, for covers and live takes. */
	val originalArtist: String? = null,
	/** Song credited in the description, when it differs from the video title. */
	val originalTitle: String? = null,
) {
	val isEmpty: Boolean
		get() = title == null && author == null && category == null &&
			originalArtist == null && originalTitle == null
}

/**
 * Resolves extra facts about a video.
 *
 * An interface for one reason: the shipping implementation scrapes an
 * undocumented blob out of the watch page (decision D8) and can break whenever
 * YouTube reskins. When it does, a Data-API implementation drops in here
 * without the pipeline noticing.
 */
interface MetadataResolver {
	/** Null when nothing could be learned. Never throws. */
	suspend fun resolve(videoId: String): VideoFacts?
}
