package com.rustedwax.app.detect

import com.rustedwax.app.hive.HiveScrobblePayload

/**
 * Decides whether a YouTube session is `song` or `video`.
 *
 * ## Why this exists
 *
 * Until Phase 4 the decision was two signals wide: `music.youtube.com`, or a
 * VEVO/Topic channel, or an `Artist - Track` title. Everything else became
 * `video`. A reported guitar cover — Japanese-bracket title, ordinary channel
 * name — matched none of them and was indexed as a video despite being music,
 * which is the general shape of the bug: covers, live takes and lyric videos
 * are exactly the music that doesn't look like `Artist - Track`.
 *
 * ## Evidence beats keywords (2026-07-24 field data)
 *
 * A day of real testing produced eleven film clips, trailers and shorts
 * scrobbled as `song`. Fetching their watch pages showed why: four were
 * categorized `Film & Animation` by YouTube itself — a category this class
 * didn't know — and the rest sat in ambiguous categories (`Entertainment`,
 * `People & Blogs`) where the old code fell through to weak heuristics: a `|`
 * or `-` in the title read as "names an artist", or the bare D4 default.
 *
 * So the hierarchy is now explicit:
 *
 *  1. **Category, when known, governs.** `Music` → song. Film/gaming/news →
 *     video. An *ambiguous* category doesn't decide — but it demands
 *     **explicit** music evidence (a cover, lyrics, a VEVO channel), because an
 *     uploader who had a Music category available and didn't pick it is weak
 *     evidence against, and a dash in the title is no rebuttal.
 *  2. **Short-form is held to the same higher bar.** Shorts are rapid-fire
 *     browsing; one wrong default per scroll session ruins a playlist. And
 *     almost no real song is under 90 seconds.
 *  3. Keyword lists remain as the **offline fallback only** — for when
 *     enrichment is off or the fetch failed.
 *
 * The asymmetry is deliberate and matches how the data is used: a missed song
 * is one lost playlist entry; a false song is permanent playlist pollution the
 * user has to curate away by hand, forever, on an immutable chain.
 *
 * ## Ordering matters
 *
 * Classification must run on the **raw** title, before [TitleParser.clean].
 * "Cover", "live" and "lyrics" are the strongest music signals available, and
 * cleaning strips exactly those words under D7. Classify first, then clean.
 */
object MusicClassifier {

	data class Result(val kind: String, val reason: String) {
		val isMusic: Boolean get() = kind == HiveScrobblePayload.KIND_SONG
	}

	private fun song(reason: String) = Result(HiveScrobblePayload.KIND_SONG, reason)
	private fun video(reason: String) = Result(HiveScrobblePayload.KIND_VIDEO, reason)

	/** A term kept next to its word-boundary regex, so reasons stay readable. */
	private class Term(val text: String, val regex: Regex) {
		fun matches(haystack: String) = regex.containsMatchIn(haystack)
	}

	/** YouTube's own category, when enrichment resolved one. Decisive. */
	private const val MUSIC_CATEGORY = "music"

	/**
	 * Categories that decisively mean "not music". `Film & Animation` was the
	 * costly omission: every movie trailer and film clip in the 2026-07-24
	 * sample carried it, fetched correctly, and was then ignored.
	 *
	 * `Entertainment`, `People & Blogs` and `Comedy` are deliberately *not*
	 * here — fan-uploaded music and comedy songs live there. They are handled
	 * as ambiguous instead: known, non-music, non-decisive.
	 */
	private val DECISIVE_NON_MUSIC_CATEGORIES = setOf(
		"film & animation", "gaming", "news & politics", "sports", "education",
		"howto & style", "science & technology", "autos & vehicles",
		"travel & events", "pets & animals", "nonprofits & activism",
	)

	/**
	 * Phrases in the *title* that mean this isn't music, whatever else it looks
	 * like. Deliberately conservative — each has to be something that
	 * essentially never appears in a music upload's title.
	 *
	 * Matched on word boundaries via [wordish], not as raw substrings, so
	 * "review" no longer fires on "preview" and "cover" no longer fires on
	 * "discover". The `cover …` / `… cover` entries are the fix for the
	 * magazine-cover false positive: a photo cover, a cover story and an album
	 * cover are decisively *not* music, and blocklist beats the positive rules.
	 */
	private val NON_MUSIC_TITLE = listOf(
		"podcast", "episode", "interview", "tutorial", "how to", "how-to",
		"walkthrough", "gameplay", "let's play", "lets play", "speedrun",
		"unboxing", "review", "vlog", "documentary",
		"explained", "q&a", "press conference", "highlights", "full match",
		"teaser", "tier list", "first look", "hands on", "devlog",
		"sermon", "lecture", "keynote", "webinar", "recipe", "asmr",
		"breaking news", "town hall", "debate", "testimony",
		// Film-clip vocabulary, from the 2026-07-24 field sample.
		"movie scene", "movie scenes", "top movie", "movie clip", "movie clips",
		"full movie", "short film", "showreel", "behind the scenes",
		// Reaction: the bare word is a real song ("Chain Reaction"), so require
		// the video-format phrasing instead.
		"reaction video", "reacts to",
		// Trailer: bare "trailer" blocks "Trailer Trash", "Trailer Park"; the
		// structural rule below handles the word in film context.
		"official trailer", "movie trailer", "game trailer",
		// Hearing: bare word blocks "Hearing Damage"; keep only the venues.
		"senate hearing", "court hearing", "congressional hearing",
		// Non-musical "cover" contexts.
		"cover photo", "cover story", "cover art", "album cover",
		"magazine cover", "book cover", "cover reveal", "cover letter",
		"undercover", "cover up", "cover-up",
	).map { Term(it, wordish(it)) }

	/**
	 * "Trailer" recognized structurally instead of by enumerated phrase —
	 * "Official *Final* Trailer" slipped past the exact phrases in the field
	 * sample. A film context word within two words before "trailer", or
	 * "Trailer 2" / "Trailer (2026)". Song titles that merely *contain* the
	 * word ("Trailer Trash (Official Video)") match neither shape.
	 */
	private val TRAILER_STRUCTURAL = Regex(
		"""\b(?:official|final|teasers?|movie|film|imax|4k)\s+(?:\w+\s+){0,2}trailers?\b""" +
			"""|\btrailers?\s*(?:#?\d|\(\d{4}\))""",
		RegexOption.IGNORE_CASE,
	)

	/**
	 * Words in the *channel name* only. These are too common in song titles to
	 * match there — "Bad News" and "Good News" are songs; `BBC News` is not a
	 * band. The film row exists because clip channels advertise themselves in
	 * the name: "Movie Trailers Source", "Cinema Crunch", "Universal Pictures",
	 * "Groovy Movie Dog", "Natalizio Filmes" (all observed misclassified).
	 */
	private val NON_MUSIC_CHANNEL = listOf(
		"news", "gaming", "podcast", "esports", "sports", "tutorials",
		"movie", "movies", "movieclips", "film", "films", "filmes", "cinema",
		"trailers", "pictures",
	).map { Term(it, wordish(it)) }

	/** Instruments/styles that qualify a "cover" or "playthrough" as musical. */
	private const val INSTRUMENTS =
		"""guitar|electric|acoustic|bass|drums?|piano|keyboard|synth|""" +
			"""vocal|vocals|metal|violin|cello|sax|saxophone|ukulele|orchestral|""" +
			"""8-?bit|lo-?fi|band|male|female|duet|choir|acapella|a cappella"""

	private val COVER_MUSIC = Regex(
		"""\b(?:$INSTRUMENTS)\s+covers?\b""" +
			"""|\bcovers?\s+(?:of|songs?|versions?|by)\b""" +
			"""|[(\[【][^)\]】]*\bcovers?\b[^)\]】]*[)\]】]""",
		RegexOption.IGNORE_CASE,
	)

	/**
	 * A "playthrough" that means music, never the bare word.
	 *
	 * A guitar or drum playthrough is music; a game playthrough is not, and
	 * `"Elden Ring Playthrough"` carries no blocklist word to catch it. So this
	 * requires the same instrument qualifier a cover does.
	 */
	private val PLAYTHROUGH_MUSIC = Regex(
		"""\b(?:$INSTRUMENTS)\s+play\s?through\b""" +
			"""|[(\[【][^)\]】]*\bplay\s?through\b[^)\]】]*[)\]】]""",
		RegexOption.IGNORE_CASE,
	)

	/** Any "playthrough" — musical or not. */
	private val PLAYTHROUGH_ANY = Regex("""\bplay\s?through\b""", RegexOption.IGNORE_CASE)

	/** Positive music evidence in the title, other than a cover or playthrough. */
	private val MUSIC_TITLE = listOf(
		"instrumental", "karaoke", "backing track",
		"lyrics", "lyric video", "official audio", "official video",
		"official music video", "live at", "live performance", "live session",
		"live in concert", "acoustic", "unplugged", "remix", "remaster",
		"remastered", "full album", "feat.", "ft.", "prod.", "concert",
		"mashup", "medley", "soundtrack", "theme song", "guitar solo",
	).map { Term(it, wordish(it)) }

	/** Channel suffixes that only artists and labels use. */
	private val MUSIC_CHANNEL_SUFFIXES = listOf(
		"vevo", "- topic", "records", "music", "recordings",
	)

	/**
	 * A term as a word-boundary regex. Interior spaces match any run of
	 * whitespace so "how to" also catches "how  to"; `.` (as in "feat.") is
	 * escaped. Boundaries are only required where the term ends in a word
	 * character, so "q&a" and "feat." still match.
	 */
	private fun wordish(term: String): Regex {
		// \Q…\E quotes the term literally; interior spaces become \s+ so
		// "how to" also matches "how  to".
		val body = "\\Q${term.lowercase()}\\E".replace(" ", "\\E\\s+\\Q")
		val lead = if (term.first().isLetterOrDigit()) "\\b" else ""
		val tail = if (term.last().isLetterOrDigit()) "\\b" else ""
		return Regex("$lead$body$tail", RegexOption.IGNORE_CASE)
	}

	/**
	 * Above this, with *no* music evidence at all, we call it a video.
	 * Deliberately generous: DJ sets, full albums and concert films are long
	 * and are music, so this can only fire when nothing else spoke up.
	 */
	private const val LONG_FORM_MS = 30L * 60 * 1000

	/**
	 * Below this, weak evidence isn't enough. Almost no real song is under 90
	 * seconds, but a huge share of shorts and clips are — and their titles are
	 * full of dashes and pipes that fool the artist heuristic.
	 */
	private const val SHORT_FORM_MS = 90L * 1000

	/**
	 * @param rawTitle the media session title, **before** cleaning
	 * @param channel the session's ARTIST field — the channel, on YouTube
	 * @param siteSaysMusic proof the origin was music.youtube.com
	 * @param enrichedCategory YouTube's own category string, when known
	 * @param isShort true when the URL said `/shorts/`
	 */
	fun classify(
		rawTitle: String,
		channel: String?,
		durationMs: Long?,
		siteSaysMusic: Boolean,
		enrichedCategory: String? = null,
		isShort: Boolean = false,
	): Result {
		val title = rawTitle.lowercase()
		val ch = channel?.trim()?.lowercase().orEmpty()
		val category = enrichedCategory?.trim()?.lowercase()?.ifEmpty { null }

		// 1. YouTube's own answer beats every heuristic below it.
		if (category == MUSIC_CATEGORY) return song("YouTube category: Music")
		if (category in DECISIVE_NON_MUSIC_CATEGORIES) {
			return video("YouTube category: $enrichedCategory")
		}

		// 2. Blocklist. Runs before the positive signals so "Guitar Tutorial"
		//    doesn't get rescued by the word "guitar", and "Magazine Cover
		//    Photo" doesn't get rescued by the word "cover".
		NON_MUSIC_TITLE.firstOrNull { it.matches(title) }?.let {
			return video("title has non-music \"${it.text}\"")
		}
		if (TRAILER_STRUCTURAL.containsMatchIn(title)) {
			return video("title reads as a film trailer")
		}
		NON_MUSIC_CHANNEL.firstOrNull { it.matches(ch) }?.let {
			return video("channel has non-music \"${it.text}\"")
		}
		// A "playthrough" with no instrument is a game playthrough. The
		// instrument-qualified case is let through to become a song below;
		// unlike a cover, a bare playthrough defaults to gaming, not music.
		if (PLAYTHROUGH_ANY.containsMatchIn(title) && !PLAYTHROUGH_MUSIC.containsMatchIn(title)) {
			return video("game playthrough (no instrument)")
		}

		// 3. Explicit music evidence — enough to overrule an ambiguous
		//    category and to qualify a short.
		if (siteSaysMusic) return song("music.youtube.com")
		MUSIC_CHANNEL_SUFFIXES.firstOrNull { ch.endsWith(it) }?.let {
			return song("channel ends with \"$it\"")
		}
		if (COVER_MUSIC.containsMatchIn(title)) return song("title names a cover")
		if (PLAYTHROUGH_MUSIC.containsMatchIn(title)) return song("title names a playthrough")
		MUSIC_TITLE.firstOrNull { it.matches(title) }?.let {
			return song("title has music \"${it.text}\"")
		}

		// 4. From here down only weak evidence remains — and two situations
		//    where weak evidence is not accepted.
		//
		//    A known non-music category: the uploader had "Music" available and
		//    chose something else. The 2026-07-24 sample was full of film clips
		//    in `Entertainment` whose `|`-separated titles passed the artist
		//    heuristic below; that heuristic doesn't outrank the uploader.
		if (category != null) {
			return video("category \"$enrichedCategory\" and no explicit music signal")
		}
		//    Short-form: shorts are browsed by the dozen, titles are dash-heavy
		//    clip captions, and a real sub-90-second song is rare.
		if (isShort || (durationMs != null && durationMs < SHORT_FORM_MS)) {
			return video("short-form with no explicit music signal")
		}

		// 5. Weak evidence, acceptable only for ordinary watch-page videos of
		//    unknown category.
		if (TitleParser.looksLikeMusic(rawTitle, channel)) {
			return song("title names an artist")
		}

		// 6. Nothing said music, and it's long. Probably a talk or a stream.
		if (durationMs != null && durationMs >= LONG_FORM_MS) {
			return video("no music signal and longer than 30 min")
		}

		// 7. D4 — default to music.
		return song("default for YouTube (no non-music signal)")
	}
}
