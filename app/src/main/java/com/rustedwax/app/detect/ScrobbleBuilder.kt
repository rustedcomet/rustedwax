package com.rustedwax.app.detect

import com.rustedwax.app.enrich.MusicBrainzVerifier
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

	/**
	 * The artist/track pair to *ask MusicBrainz about* — always the music
	 * reading, `Artist - Track` split and all.
	 *
	 * Public because the engine needs exactly this pair for the lookup, and the
	 * two computations must never drift apart. Note this is deliberately **not**
	 * the pair a `video` payload carries: asking MusicBrainz about the music
	 * reading is how a video-looking title is discovered to be a real recording,
	 * so the question has to be asked before the kind is known. See
	 * [creditsForKind] for what actually goes on-chain.
	 */
	fun creditsOf(session: SessionSnapshot, facts: VideoFacts? = null): Parsed? {
		val rawTitle = sourceTitle(session, facts) ?: return null
		val channel = sourceArtist(session, facts)
		if (session.isNative && !session.title.isNullOrBlank()) {
			return Parsed(
				artist = session.artist?.trim()?.takeIf(String::isNotEmpty)
					?: facts?.originalArtist ?: facts?.author,
				track = session.title.trim(),
			)
		}
		val parsed = TitleParser.parse(rawTitle, channel)
		// A description credit beats a parsed one: on a cover it names the
		// original artist, which is the whole point of looking.
		return Parsed(
			artist = facts?.originalArtist ?: parsed.artist,
			track = facts?.originalTitle?.let { TitleParser.clean(it) } ?: parsed.track,
		)
	}

	/**
	 * The credits for the payload, which depend on the kind.
	 *
	 * `Artist - Track` splitting is a **music** operation. Running it on a video
	 * asserts that the text left of a dash names a performer, and on the watch
	 * path it usually names a film:
	 *
	 * ```
	 * "Fall 2: Deadpoint (2026) Official Trailer 2 - Harriet Slater, Arsema Thomas"
	 *   → artist: "Fall 2: Deadpoint (2026) Official Trailer 2"   ← the film
	 *     title:  "Harriet Slater, Arsema Thomas"                 ← the cast
	 * ```
	 *
	 * That went on-chain on 2026-07-29 with `kind: video`, `category:
	 * Film & Animation` already resolved and the channel (`Lionsgate Movies`)
	 * sitting in the notification — the kind was computed and then never
	 * consulted. The reversed `Iran threatens to attack UK bases… - Risking
	 * wider war | BBC News` is the same failure; `Track - Artist` ordering is
	 * common in Spanish-language uploads and MusicBrainz can only arbitrate it
	 * for real recordings, never for a news clip.
	 *
	 * So for a video: the channel is the artist and the whole title is the
	 * title. Nothing is split, and the description credits are left alone too —
	 * mining a description for an "original artist" only makes sense for music.
	 */
	fun creditsForKind(
		kind: String,
		session: SessionSnapshot,
		facts: VideoFacts? = null,
		mb: MusicBrainzVerifier.Match? = null,
		/** The resolver's canonical title, for a Short the screen never titled. */
		resolvedTitle: String? = null,
	): Parsed? {
		val rawTitle = sourceTitle(session, facts, resolvedTitle) ?: return null
		val channel = sourceArtist(session, facts)

		// Native apps already publish separated title/artist fields. Preserve
		// those clean values rather than feeding them back through the browser's
		// Artist–Title grammar or replacing them with fetched page presentation.
		val nativeTitle = session.title?.takeIf(String::isNotBlank)
			?: resolvedTitle?.takeIf(String::isNotBlank)
		if (session.isNative && nativeTitle != null) {
			return Parsed(
				artist = session.artist?.trim()?.takeIf(String::isNotEmpty)
					?: TitleParser.cleanChannel(facts?.author),
				track = nativeTitle.trim(),
			)
		}

		if (kind != HiveScrobblePayload.KIND_SONG) {
			return Parsed(
				artist = TitleParser.cleanChannel(channel),
				track = TitleParser.clean(rawTitle).ifEmpty { rawTitle.trim() },
			)
		}

		val credits = creditsOf(session, facts) ?: return null
		// A MusicBrainz confirmation supplies the canonical spelling, so this
		// entry lines up with every other scrobble of the same recording — and
		// it lands the right way round when the title was `Track - Artist`.
		if (mb?.found != true) return credits
		return Parsed(
			artist = mb.artist ?: credits.artist,
			track = mb.title ?: credits.track,
		)
	}

	data class Parsed(val artist: String?, val track: String)

	/**
	 * The duration to measure against: the media session's, or the watch page's
	 * when the session published none.
	 *
	 * Chromium omits `DURATION` often enough to matter — 10 shorts in the
	 * 2026-07-29 session were skipped as "no duration" before any rule could
	 * look at them, while `videoDetails.lengthSeconds` for those same ids was
	 * already being fetched and discarded.
	 */
	fun effectiveDurationMs(session: SessionSnapshot, facts: VideoFacts? = null): Long? =
		session.durationMs ?: facts?.lengthSeconds?.times(1000)

	/**
	 * Null when the session isn't broadcastable — the caller shows why.
	 *
	 * @param videoId the id to build `url` from. Defaults to the session's own
	 * confirmed id; the engine passes a search-resolved one when the address
	 * bar never named the video. Missing or malformed ids fail construction:
	 * URL-less YouTube payloads are forbidden.
	 * @param durationMs the effective duration. Defaults to
	 * [effectiveDurationMs] so every caller gets the watch-page fallback without
	 * having to know about it; the engine passes the same value it fed the rules
	 * so the payload and the decision can't disagree.
	 */
	fun from(
		session: SessionSnapshot,
		facts: VideoFacts? = null,
		mb: MusicBrainzVerifier.Match? = null,
		videoId: String? = session.confirmed?.videoId,
		durationMs: Long? = effectiveDurationMs(session, facts),
		/**
		 * The canonical title of the video the resolver proved, for the case
		 * where the screen never showed one.
		 *
		 * A Short sent straight to picture-in-picture exposes no readable title,
		 * and identity now comes from watch history on owner handle + duration
		 * instead. Measured 2026-08-06: such a Short counted to 100%, resolved
		 * correctly, and was then dropped with "payload not buildable" because
		 * the builder had no title to write. The resolver had one all along —
		 * it corroborated the id on that video's own watch page.
		 */
		resolvedTitle: String? = null,
	): HiveScrobblePayload? {
		// The session itself may not have published a duration; `durationMs` is
		// the shared session-or-watch-page value used by rules and payload.
		if (!session.isYouTube) return null
		val verifiedVideoId = videoId
			?.takeIf { YOUTUBE_VIDEO_ID.matches(it) }
			?: return null
		// The screen's title, when there was one. A Short that went straight to
		// picture-in-picture never showed one, and that is no longer fatal: the
		// resolver proved the video on its own watch page and its canonical title
		// is the better name anyway.
		val sessionTitle = session.title?.takeIf { it.isNotBlank() }
			?: facts?.title?.takeIf { it.isNotBlank() }
			?: resolvedTitle?.takeIf { it.isNotBlank() }
			?: return null
		val duration = durationMs?.takeIf { it > 0 } ?: return null

		// Enrichment's title is the video's real one; the media session's can be
		// whatever the page chose to publish.
		val rawTitle = sourceTitle(session, facts, resolvedTitle) ?: sessionTitle
		val channel = sourceArtist(session, facts)

		val siteSaysMusic = when (val id = session.identity) {
			is YouTubeProbe.Identity.Confirmed -> id.isMusic && !session.isNative
			is YouTubeProbe.Identity.SiteOnly -> id.isMusic && !session.isNative
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
			isShort = session.hasShortSourceProof,
			musicbrainzMatch = mb?.found == true,
			autoGenerated = facts?.autoGenerated == true,
			recognisedByYouTubeMusic = facts?.recognisedByYouTubeMusic == true,
			nativeYouTubeMusic = session.isNativeYouTubeMusic,
			youtubeMusicPodcastEpisode =
				facts?.musicVideoType == "MUSIC_VIDEO_TYPE_PODCAST_EPISODE",
			nativeGenre = session.genre,
		)

		// Credits depend on the kind — a video is not split into artist/track.
		val credits = creditsForKind(kind.kind, session, facts, mb, resolvedTitle) ?: return null

		return HiveScrobblePayload(
			kind = kind.kind,
			title = credits.track,
			artist = credits.artist,
			// Songs only. `album` is release metadata: on a video it would either
			// be empty or, worse, whatever a description scrape found next to a
			// trailer's credits.
			album = if (kind.kind == HiveScrobblePayload.KIND_SONG) {
				if (session.isNative) {
					session.album?.trim()?.takeIf(String::isNotEmpty) ?: facts?.album
				} else {
					facts?.album
				}
			} else {
				null
			},
			timestamp = HiveScrobblePayload.isoTimestamp(session.trackStartedAtEpochSec),
			duration = HiveScrobblePayload.formatDuration(duration / 1000),
			// Recomputed against `duration` rather than read off the snapshot:
			// `SessionSnapshot.percentPlayed` divides by the *session's* duration,
			// which is null in exactly the case a watch-page length rescues — so
			// reading it there would omit `percent_played` from a payload whose
			// progress is perfectly well known. The automatic path overwrites this
			// per transaction; the manual button is what would have shown blank.
			percentPlayed = ((session.playedMs.toDouble() / duration) * 100)
				.toInt().coerceIn(0, 100),
			platform = "youtube",
			url = "https://www.youtube.com/watch?v=$verifiedVideoId",
		)
	}

	/**
	 * Why the kind came out the way it did, for the diagnostics card. Uses the
	 * same inputs as [from], so the preview and the broadcast can't disagree —
	 * an earlier version dropped `siteSaysMusic` and `isShort` here, and the
	 * card showed a different verdict than the one that went on-chain.
	 */
	fun kindReason(
		session: SessionSnapshot,
		facts: VideoFacts? = null,
		mb: MusicBrainzVerifier.Match? = null,
	): String? {
		val title = sourceTitle(session, facts) ?: return null
		val siteSaysMusic = when (val id = session.identity) {
			is YouTubeProbe.Identity.Confirmed -> id.isMusic && !session.isNative
			is YouTubeProbe.Identity.SiteOnly -> id.isMusic && !session.isNative
			else -> false
		}
		return MusicClassifier.classify(
			rawTitle = title,
			channel = sourceArtist(session, facts),
			durationMs = effectiveDurationMs(session, facts),
			siteSaysMusic = siteSaysMusic,
			enrichedCategory = facts?.category,
			isShort = session.hasShortSourceProof,
			musicbrainzMatch = mb?.found == true,
			autoGenerated = facts?.autoGenerated == true,
			recognisedByYouTubeMusic = facts?.recognisedByYouTubeMusic == true,
			nativeYouTubeMusic = session.isNativeYouTubeMusic,
			youtubeMusicPodcastEpisode =
				facts?.musicVideoType == "MUSIC_VIDEO_TYPE_PODCAST_EPISODE",
			nativeGenre = session.genre,
		).reason
	}

	private fun sourceTitle(
		session: SessionSnapshot,
		facts: VideoFacts?,
		resolvedTitle: String? = null,
	): String? = if (session.isNative) {
		session.title ?: facts?.title ?: resolvedTitle
	} else {
		facts?.title ?: session.title ?: resolvedTitle
	}

	private fun sourceArtist(session: SessionSnapshot, facts: VideoFacts?): String? =
		if (session.isNative) session.artist ?: facts?.author else facts?.author ?: session.artist

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

	private val YOUTUBE_VIDEO_ID = Regex("""^[A-Za-z0-9_-]{11}$""")
}
