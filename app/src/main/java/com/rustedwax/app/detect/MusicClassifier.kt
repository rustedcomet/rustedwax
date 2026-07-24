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
 * ## The default flipped (decision D4)
 *
 * Proven-YouTube playback is now **`song` unless something says otherwise**.
 * That inverts the risk: the old default under-claimed music, the new one can
 * over-claim it. The blocklist is what keeps that honest, and it is the part
 * worth tuning against real listening — see PHASE4.md §10.
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

	private val NON_MUSIC_CATEGORIES = setOf(
		"gaming", "news & politics", "sports", "education", "howto & style",
		"science & technology", "autos & vehicles", "travel & events",
		"pets & animals",
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
		// Reaction: the bare word is a real song ("Chain Reaction"), so require
		// the video-format phrasing instead.
		"reaction video", "reacts to",
		// Trailer: bare "trailer" blocks "Trailer Trash", "Trailer Park"; a
		// real trailer names what it is a trailer *for*.
		"official trailer", "movie trailer", "game trailer",
		// Hearing: bare word blocks "Hearing Damage"; keep only the venues.
		"senate hearing", "court hearing", "congressional hearing",
		// Non-musical "cover" contexts.
		"cover photo", "cover story", "cover art", "album cover",
		"magazine cover", "book cover", "cover reveal", "cover letter",
		"undercover", "cover up", "cover-up",
	).map { Term(it, wordish(it)) }

	/**
	 * Words in the *channel name* only. These are too common in song titles to
	 * match there — "Bad News" and "Good News" are songs; `BBC News` is not a
	 * band.
	 */
	private val NON_MUSIC_CHANNEL =
		listOf("news", "gaming", "podcast", "esports", "sports", "tutorials")
			.map { Term(it, wordish(it)) }

	/**
	 * A "cover" that actually means a music cover — never the bare word.
	 *
	 * Real covers are always qualified: an instrument or style in front
	 * ("Guitar Cover", "Acoustic Cover"), the song named after ("cover of…",
	 * "cover song"), or the whole thing bracketed ("(Guitar Cover)",
	 * "【Drum Cover】"). "Magazine Cover Photo" matches none of these — and even
	 * if it somehow did, the blocklist above runs first.
	 */
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
	 * @param rawTitle the media session title, **before** cleaning
	 * @param channel the session's ARTIST field — the channel, on YouTube
	 * @param siteSaysMusic proof the origin was music.youtube.com
	 * @param enrichedCategory YouTube's own category string, when known
	 */
	fun classify(
		rawTitle: String,
		channel: String?,
		durationMs: Long?,
		siteSaysMusic: Boolean,
		enrichedCategory: String? = null,
	): Result {
		val title = rawTitle.lowercase()
		val ch = channel?.trim()?.lowercase().orEmpty()

		// 1. YouTube's own answer beats every heuristic below it.
		enrichedCategory?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { category ->
			if (category == MUSIC_CATEGORY) return song("YouTube category: Music")
			if (category in NON_MUSIC_CATEGORIES) {
				return video("YouTube category: $enrichedCategory")
			}
		}

		// 2. Blocklist. Runs before the positive signals so "Guitar Tutorial"
		//    doesn't get rescued by the word "guitar", and "Magazine Cover
		//    Photo" doesn't get rescued by the word "cover".
		NON_MUSIC_TITLE.firstOrNull { it.matches(title) }?.let {
			return video("title has non-music \"${it.text}\"")
		}
		NON_MUSIC_CHANNEL.firstOrNull { it.matches(ch) }?.let {
			return video("channel has non-music \"${it.text}\"")
		}
		// A "playthrough" with no instrument is a game playthrough. The
		// instrument-qualified case is let through to become a song at step 3;
		// unlike a cover, a bare playthrough defaults to gaming, not music.
		if (PLAYTHROUGH_ANY.containsMatchIn(title) && !PLAYTHROUGH_MUSIC.containsMatchIn(title)) {
			return video("game playthrough (no instrument)")
		}

		// 3. Positive evidence.
		if (siteSaysMusic) return song("music.youtube.com")
		MUSIC_CHANNEL_SUFFIXES.firstOrNull { ch.endsWith(it) }?.let {
			return song("channel ends with \"$it\"")
		}
		// A cover only counts in a real musical construction, never as the bare
		// word — that's what a magazine cover, a cover story and album cover
		// all trip on, and none of them reach here anyway (blocklisted above).
		if (COVER_MUSIC.containsMatchIn(title)) return song("title names a cover")
		if (PLAYTHROUGH_MUSIC.containsMatchIn(title)) return song("title names a playthrough")
		MUSIC_TITLE.firstOrNull { it.matches(title) }?.let {
			return song("title has music \"${it.text}\"")
		}
		if (TitleParser.looksLikeMusic(rawTitle, channel)) {
			return song("title names an artist")
		}

		// 4. Nothing said music, and it's long. Probably a talk or a stream.
		if (durationMs != null && durationMs >= LONG_FORM_MS) {
			return video("no music signal and longer than 30 min")
		}

		// 5. D4 — default to music.
		return song("default for YouTube (no non-music signal)")
	}
}
