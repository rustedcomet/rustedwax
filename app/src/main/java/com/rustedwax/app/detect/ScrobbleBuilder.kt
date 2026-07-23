package com.rustedwax.app.detect

import com.rustedwax.app.hive.HiveScrobblePayload

/**
 * Turns an observed media session into the payload the extension would have
 * broadcast.
 *
 * Kind selection follows the decision recorded in PHASE0.md: plain YouTube is
 * `video`; only `music.youtube.com`, when the evidence proves it, is `song`.
 * The extension's connectors made a finer distinction that isn't reproducible
 * from a media session, and overclaiming `song` would poison music-only indexes.
 */
object ScrobbleBuilder {

	/** Null when the session isn't broadcastable — the caller shows why. */
	fun from(session: SessionSnapshot): HiveScrobblePayload? {
		if (!session.payloadViable) return null
		val title = session.title ?: return null
		val duration = session.durationMs ?: return null

		val parsed = TitleParser.parse(title, session.artist)
		// music.youtube.com is proof; otherwise fall back to the title/channel
		// heuristic, because desktop scrobbles of these same videos land as
		// `song` and a mismatched kind splits one track across two indexes.
		val isMusic = when (val id = session.identity) {
			is YouTubeProbe.Identity.Confirmed -> id.isMusic
			is YouTubeProbe.Identity.SiteOnly -> id.isMusic
			else -> false
		} || TitleParser.looksLikeMusic(title, session.artist)

		return HiveScrobblePayload(
			kind = if (isMusic) HiveScrobblePayload.KIND_SONG else HiveScrobblePayload.KIND_VIDEO,
			title = parsed.track,
			artist = parsed.artist,
			timestamp = HiveScrobblePayload.isoTimestamp(session.trackStartedAtEpochSec),
			duration = HiveScrobblePayload.formatDuration(duration / 1000),
			percentPlayed = session.percentPlayed
				?.let { (it * 100).toInt().coerceIn(0, 100) },
			platform = "youtube",
			url = session.confirmed?.url,
		)
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
