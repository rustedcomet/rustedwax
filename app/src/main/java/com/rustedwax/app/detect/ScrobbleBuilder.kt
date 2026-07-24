package com.rustedwax.app.detect

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.hive.HiveScrobblePayload

/**
 * Turns an observed media session into the payload the extension would have
 * broadcast.
 *
 * Kind selection moved to [MusicClassifier] in Phase 4. PHASE0 locked in
 * "`video` for youtube.com, `song` only for music.youtube.com", which turned
 * out to under-claim music badly — covers, live takes and lyric videos are
 * music that doesn't look like `Artist - Track`. Decision D4 inverts the
 * default; see PHASE4.md.
 */
object ScrobbleBuilder {

	/** Null when the session isn't broadcastable — the caller shows why. */
	fun from(session: SessionSnapshot, facts: VideoFacts? = null): HiveScrobblePayload? {
		if (!session.payloadViable) return null
		val sessionTitle = session.title ?: return null
		val duration = session.durationMs ?: return null

		// Enrichment's title is the video's real one; the media session's can be
		// whatever the page chose to publish.
		val rawTitle = facts?.title ?: sessionTitle
		val channel = facts?.author ?: session.artist

		val siteSaysMusic = when (val id = session.identity) {
			is YouTubeProbe.Identity.Confirmed -> id.isMusic
			is YouTubeProbe.Identity.SiteOnly -> id.isMusic
			else -> false
		}

		// Classified before parsing: cleaning strips "cover", "live" and
		// "lyrics", which are the strongest music signals there are.
		val kind = MusicClassifier.classify(
			rawTitle = rawTitle,
			channel = channel,
			durationMs = duration,
			siteSaysMusic = siteSaysMusic,
			enrichedCategory = facts?.category,
		)

		val parsed = TitleParser.parse(rawTitle, channel)
		// A description credit beats a parsed one: on a cover it names the
		// original artist, which is the whole point of looking.
		val artist = facts?.originalArtist ?: parsed.artist
		val title = facts?.originalTitle?.let { TitleParser.clean(it) } ?: parsed.track

		return HiveScrobblePayload(
			kind = kind.kind,
			title = title,
			artist = artist,
			timestamp = HiveScrobblePayload.isoTimestamp(session.trackStartedAtEpochSec),
			duration = HiveScrobblePayload.formatDuration(duration / 1000),
			percentPlayed = session.percentPlayed
				?.let { (it * 100).toInt().coerceIn(0, 100) },
			platform = "youtube",
			url = session.confirmed?.url,
		)
	}

	/** Why the kind came out the way it did, for the diagnostics card. */
	fun kindReason(session: SessionSnapshot, facts: VideoFacts? = null): String? {
		val title = facts?.title ?: session.title ?: return null
		return MusicClassifier.classify(
			rawTitle = title,
			channel = facts?.author ?: session.artist,
			durationMs = session.durationMs,
			siteSaysMusic = false,
			enrichedCategory = facts?.category,
		).reason
	}

	/**
	 * A fixed payload for exercising signing and broadcast without playback.
	 * Marked plainly so it's obvious on-chain that it came from a test.
	 */
	fun testPayload(): HiveScrobblePayload = HiveScrobblePayload(
		kind = HiveScrobblePayload.KIND_VIDEO,
		title = "RustedWax Mobile test scrobble",
		artist = "RustedWax Mobile",
		timestamp = HiveScrobblePayload.isoTimestamp(System.currentTimeMillis() / 1000),
		duration = "0:30",
		percentPlayed = 100,
		platform = "test",
	)
}
