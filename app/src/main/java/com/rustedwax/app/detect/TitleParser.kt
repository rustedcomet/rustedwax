package com.rustedwax.app.detect

/**
 * Splits `"Korn - Trash (Official Audio)"` into artist and track.
 *
 * Phase 0 run 2 established this is mandatory, not cosmetic: a browser media
 * session reports `ARTIST` as the *channel* ("KornVEVO") and packs the real
 * artist into the video title. Broadcasting raw would write
 * `artist: "KornVEVO"` to the chain, which matches no desktop scrobble of the
 * same song and poisons per-artist indexes.
 *
 * A minimal port of the YouTube rules from `@web-scrobbler/metadata-filter`
 * plus the extension's `pipeline/normalize`. Upstream's tables are far larger
 * and encode years of edge cases; this covers the common shapes.
 *
 * ## Phase 4: normalize to the original recording (decision D7)
 *
 * Cover, live, instrumental and karaoke markers are now **stripped**, so a
 * guitar cover lands on the same entry as the studio track and play counts
 * accumulate in one place instead of scattering. This is a deliberate
 * divergence from the desktop extension, which keeps them — see PHASE4.md.
 *
 * Two limits worth knowing:
 *
 *  - `(Live)` is stripped; `(Live at Wembley)` is kept, because a group is only
 *    dropped when *every* word in it is a known qualifier and no table can
 *    enumerate venue names. A named live recording surviving is the better
 *    failure anyway.
 *  - `(Remix)` is kept. A remix is a distinct work, usually by a different
 *    artist, so folding it into the original would be wrong rather than tidy.
 *
 * ## Trailing hashtags are stripped too
 *
 * Short-form titles are largely tag runs, and leaving them in the title makes
 * the same clip dedup as a different listen every time its tags change. See
 * [TRAILING_HASHTAGS], and [isHashtagOnly] for the case where the tags are all
 * there is.
 */
object TitleParser {

	data class Parsed(val artist: String?, val track: String)

	/** Separators between artist and track, longest/most specific first. */
	private val SEPARATORS = listOf(
		" -- ", " ~ ", " — ", " – ", " - ", " | ", "「", "『", "｜", "／", "：",
	)

	/**
	 * Words that, on their own or in combination, mark a bracketed group as
	 * noise rather than part of the title.
	 *
	 * A group is stripped only when **every** word in it is on this list, which
	 * is why `(Radio Edit)`, `(Sol Invicto Remix)` and `(MAPHRA Vocal Cover)`
	 * survive — an unrecognised word means we don't understand the group well
	 * enough to throw it away.
	 *
	 * Enumerating words beats enumerating phrases: an earlier version listed
	 * `(Official Video)` and `(HD)` separately and let
	 * `"Thoughtless (Official HD Video)"` straight through to the chain.
	 */
	private val PROMO_WORDS = setOf(
		// Promotional / format noise.
		"official", "oficial", "video", "vídeo", "audio", "música", "music",
		"lyric", "lyrics", "visualizer", "visualiser", "hd", "hq", "uhd",
		"4k", "8k", "1080p", "720p", "full", "mv", "m/v", "clip", "stream",
		"version", "explicit", "new", "hi-res", "with", "and", "the",
		"remaster", "remastered", "restored",
		// Performance qualifiers — stripped under D7 so covers and live takes
		// fold into the original recording.
		"cover", "covers", "covered", "instrumental", "karaoke", "backing",
		"playthrough", "live", "acoustic", "performance", "session", "sessions",
		// Instrument names, which only ever appear here as part of a qualifier
		// like "(Guitar Cover)" — the rule needs every word to be known.
		"guitar", "bass", "drum", "drums", "piano", "keyboard", "vocal", "vocals",
		// Tab/gear mentions common on cover channels.
		"tab", "tabs", "screen",
	)

	/**
	 * A bracketed group and its contents, ASCII and CJK.
	 *
	 * CJK brackets were added in Phase 4: `【Guitar Cover】` is the same
	 * construct as `(Guitar Cover)` and was previously invisible to the parser.
	 */
	private val BRACKETED =
		Regex("""\s*[(\[【「『]([^()\[\]【】「」『』]*)[)\]】」』]""")

	/** Trailing `| Official Video`-style suffixes, which use no brackets. */
	private val PIPE_SUFFIX = Regex("""\s*\|\s*([^|]+)\s*$""")

	/**
	 * A leading CJK bracket naming the artist — `【Bring Me The Horizon】Song`.
	 *
	 * A common shape on Japanese-style cover channels, and the reason the
	 * reported guitar-cover scrobble landed with the channel as its artist:
	 * there is no separator anywhere in the title, so the parser fell through
	 * to the channel name while the real artist sat in the brackets.
	 *
	 * Restricted to CJK brackets on purpose. A leading `[…]` in ASCII is far
	 * more often a tag (`[FREE]`, `[4K]`) than an artist.
	 */
	private val LEADING_BRACKET_ARTIST =
		Regex("""^[【「『]([^】」』]{1,60})[】」』]\s*(.+)$""")

	/** Gear/tab mentions cover channels append to the title. */
	private val TRAILING_JUNK = listOf(
		Regex("""[＋+]\s*(?:screen\s+)?tabs?\s*$""", RegexOption.IGNORE_CASE),
		Regex("""\bw/\s*(?:screen\s+)?tabs?\s*$""", RegexOption.IGNORE_CASE),
		Regex("""[|｜/／]\s*(?:screen\s+)?tabs?\s*$""", RegexOption.IGNORE_CASE),
	)

	/** A bare year, as cover uploads tend to append (`… Medusa 2023`). */
	private val TRAILING_YEAR = Regex("""\s+\(?\[?(?:19|20)\d{2}\)?\]?\s*$""")

	/**
	 * A trailing run of hashtags, plus whatever emoji or punctuation trails it.
	 *
	 * Short-form titles are mostly tags. From the 2026-07-29 session, on-chain
	 * verbatim: `katter — #guitar #dubstep #djdubstep #fnaf #fivenightsatfreddy`
	 * and `Rony González — #plena#panama 🇵🇦`.
	 *
	 * Two costs, and the second is the one that matters. The entries read as
	 * spam — and because the tag run is part of the title, the same clip
	 * reposted with different tags dedups as a different listen and lands twice
	 * on a chain that can't be edited.
	 *
	 * Only a *trailing* run is stripped. A hashtag mid-sentence is doing work
	 * ("Song Title #2 of the series"), and no table can tell those apart.
	 * `[\p{L}\p{N}_]` rather than `\w` so non-Latin tags are recognised — the
	 * session was full of Turkish, Korean and Chinese ones. The trailing
	 * `[^\p{L}\p{N}]*` is what catches `#plena#panama 🇵🇦`, where a flag emoji
	 * sits after the last tag.
	 */
	private val TRAILING_HASHTAGS =
		Regex("""(?:\s*#[\p{L}\p{N}_]+)+[^\p{L}\p{N}]*$""")

	/**
	 * A leading `[tag]` that is *noise* rather than an artist — `[FREE]`,
	 * `[Future Bass]`, `[4K]`.
	 *
	 * Adapted from the desktop extension's `processYtVideoTitle`, which strips a
	 * leading bracket before anything else. **ASCII only**, deliberately: this
	 * project already decided the opposite for CJK brackets, because
	 * `【Bring Me The Horizon】…` names the artist (see [LEADING_BRACKET_ARTIST]).
	 * An earlier version of this rule stripped both and broke exactly that case —
	 * `【Artist】Song - Live` became artist `Song`.
	 */
	private val LEADING_TAG = Regex("""^\s*\[[^\]]{1,30}\]\s*-*\s*""")

	/**
	 * A track-number prefix, as album and vinyl rips carry: `03. `, `A1. `,
	 * `12) `. From the extension's `removeNumericPrefix` and its CD/vinyl rule.
	 *
	 * Bounded to two leading characters so a real title starting with a number
	 * (`1999`, `7 Rings`) is untouched — those have no separator after the digits.
	 */
	private val TRACK_NUMBER_PREFIX =
		Regex("""^\s*(?:[A-Za-z]{1,2}\d{0,2}|\d{1,2})\s*[.)]\s+""")

	/**
	 * `Artist "Track"` / `Artist - "Track"`. The quotes name the track outright,
	 * which is stronger than any separator guess. From the extension's
	 * `ytTitleRegExps`.
	 */
	private val QUOTED_TRACK = Regex("""^(.{1,80}?)\s*[-–—:]?\s*"([^"]{1,120})"\s*$""")

	/**
	 * `Track (by Artist)` / `Track (performed by Artist)` — the one common shape
	 * where the artist comes *second*. Also from `ytTitleRegExps`.
	 */
	private val TRACK_BY_ARTIST =
		Regex("""^(.{1,80}?)\s*\((?:[^)]*\s)?by\s+([^)]{1,60})\)\s*$""", RegexOption.IGNORE_CASE)

	private val TRAILING_PUNCT = Regex("""[\s\-–—_|｜/／+＋~]+$""")

	private val WORD_SPLIT = Regex("""[\s\-_/]+""")

	/** True when every word is a known qualifier — so the group carries no meaning. */
	private fun isPromoOnly(inner: String): Boolean {
		val words = inner.trim().lowercase()
			.split(WORD_SPLIT)
			.filter { it.isNotBlank() }
		if (words.isEmpty()) return false
		// Require at least one strong marker so a bare "(the)" isn't stripped.
		if (words.none { it in STRONG_MARKERS }) return false
		return words.all { it in PROMO_WORDS }
	}

	private val STRONG_MARKERS = setOf(
		"official", "oficial", "video", "vídeo", "audio", "lyric", "lyrics",
		"visualizer", "visualiser", "hd", "hq", "uhd", "4k", "8k", "1080p",
		"720p", "mv", "m/v",
		"cover", "covers", "covered", "instrumental", "karaoke", "backing",
		"playthrough", "live", "acoustic", "remaster", "remastered",
		"guitar", "bass", "drum", "drums", "piano", "vocal", "vocals",
		"tab", "tabs",
	)

	/** Channel suffixes that mark an artist-owned channel. */
	private val CHANNEL_SUFFIXES =
		listOf("VEVO", " - Topic", "Official", "Music", "TV", "Records")

	fun parse(rawTitle: String, channel: String?): Parsed {
		// Note this is [cleanCore], not [clean]: the trailing year must not be
		// stripped until we know which part of the title is the *track*.
		// Otherwise "The Smashing Pumpkins - 1979" loses its track before the
		// separator is ever considered, and the song becomes the band.
		val title = cleanCore(rawTitle)

		// Checked before separators: in `【Artist】Song - Live` the separator
		// would otherwise split the artist bracket into the artist field.
		LEADING_BRACKET_ARTIST.find(title)?.let { m ->
			val artist = tidy(m.groupValues[1])
			val track = finishTrack(m.groupValues[2])
			if (artist.isNotEmpty() && track.isNotEmpty()) {
				return Parsed(artist, track)
			}
		}

		// Explicit shapes beat separator guessing, because they name which side
		// is which instead of assuming artist-first. Both adapted from the
		// desktop extension's `ytTitleRegExps`.
		QUOTED_TRACK.find(title)?.let { m ->
			val artist = tidy(m.groupValues[1])
			val track = finishTrack(m.groupValues[2])
			if (artist.isNotEmpty() && track.isNotEmpty()) {
				return Parsed(artist, track)
			}
		}
		TRACK_BY_ARTIST.find(title)?.let { m ->
			val track = finishTrack(m.groupValues[1])
			val artist = tidy(m.groupValues[2])
			if (artist.isNotEmpty() && track.isNotEmpty()) {
				return Parsed(artist, track)
			}
		}

		for (sep in SEPARATORS) {
			val idx = title.indexOf(sep)
			if (idx <= 0) continue
			val artist = tidy(title.substring(0, idx))
			val track = finishTrack(title.substring(idx + sep.length))
			if (artist.isNotEmpty() && track.isNotEmpty()) {
				return Parsed(artist, track)
			}
		}

		// No separator: the channel is the best artist guess we have.
		return Parsed(cleanChannel(channel), finishTrack(title))
	}

	/** Strip noise and collapse whitespace. */
	fun clean(value: String): String = finishTrack(cleanCore(value))

	/**
	 * Everything except the trailing year — the part that's safe to do before
	 * the artist/track split.
	 */
	private fun cleanCore(value: String): String {
		// Prefixes first, and never down to nothing — `[Remix]` alone is a title
		// of sorts, and an empty one is worse.
		var out = TRACK_NUMBER_PREFIX.replace(value, "").ifBlank { value }
		LEADING_TAG.find(out)?.let { m ->
			out = out.removeRange(m.range).ifBlank { out }
		}
		out = BRACKETED.replace(out) { m ->
			if (isPromoOnly(m.groupValues[1])) "" else m.value
		}
		PIPE_SUFFIX.find(out)?.let { m ->
			if (isPromoOnly(m.groupValues[1])) out = out.removeRange(m.range)
		}

		// Trailing junk comes in layers — "…【Guitar Cover】＋Screen Tabs" only
		// exposes what's behind it once the tab suffix is gone, and a tag run
		// hides behind a promo bracket in "…#shorts (Official Video)". Loop
		// until stable so the order the uploader chose doesn't matter.
		var changed = true
		while (changed) {
			val before = out
			TRAILING_JUNK.forEach { out = it.replace(out, "") }
			out = stripTrailingHashtags(out)
			out = TRAILING_PUNCT.replace(out, "")
			changed = out != before
		}
		return tidy(out)
	}

	/** Final pass over whatever ended up being the track. */
	private fun finishTrack(value: String): String =
		tidy(stripTrailingYear(tidy(value).trimEnd('】', '」', '』')))

	private fun tidy(value: String): String =
		TRAILING_PUNCT.replace(value.replace(Regex("""\s{2,}"""), " ").trim(), "").trim()

	/**
	 * Drops a trailing hashtag run, but never everything.
	 *
	 * When the tags *are* the title (`"#guitar"`, `"#plena#panama 🇵🇦"`) the
	 * original is returned unchanged — a payload needs some title, and an empty
	 * string is worse than an ugly one. [isHashtagOnly] is how a caller detects
	 * that case and declines to call it a song.
	 */
	fun stripTrailingHashtags(value: String): String {
		val m = TRAILING_HASHTAGS.find(value) ?: return value
		val remainder = value.removeRange(m.range).trim()
		return remainder.ifEmpty { value }
	}

	/**
	 * True when nothing but hashtags, emoji and punctuation is left.
	 *
	 * A title like `"#guitar #dubstep #fnaf"` names no work. YouTube's own
	 * `Music` category was enough to make one of those a `song` on-chain, and a
	 * song with no title isn't a listen — so [MusicClassifier] uses this to
	 * refuse the `song` kind outright, keeping the entry as the video it
	 * usefully is.
	 */
	fun isHashtagOnly(rawTitle: String): Boolean {
		val m = TRAILING_HASHTAGS.find(rawTitle) ?: return false
		return rawTitle.removeRange(m.range).trim().isEmpty()
	}

	/**
	 * Drops a trailing year, but only when something is left to say. Without
	 * the word guard this erases `"1979"` and `"1999"` — real song titles that
	 * are nothing but a year.
	 */
	private fun stripTrailingYear(value: String): String {
		val m = TRAILING_YEAR.find(value) ?: return value
		val remainder = value.removeRange(m.range).trim()
		val words = remainder.split(WORD_SPLIT).filter { it.isNotBlank() }
		return if (words.size >= 2) remainder else value
	}

	/**
	 * Whether the *title shape* suggests music — an artist-owned channel, or a
	 * title that yields an artist through a separator or a leading bracket.
	 *
	 * One input among several now; [MusicClassifier] makes the actual call.
	 */
	fun looksLikeMusic(rawTitle: String, channel: String?): Boolean {
		val ch = channel?.trim().orEmpty()
		if (ch.endsWith("VEVO", ignoreCase = true) ||
			ch.endsWith("- Topic", ignoreCase = true)
		) {
			return true
		}
		return parse(rawTitle, channel).artist != null && yieldsArtist(rawTitle)
	}

	/** True when the title itself names an artist, rather than the channel doing it. */
	private fun yieldsArtist(rawTitle: String): Boolean {
		val title = clean(rawTitle)
		if (LEADING_BRACKET_ARTIST.containsMatchIn(title)) return true
		return SEPARATORS.any { title.indexOf(it) > 0 }
	}

	/**
	 * `"KornVEVO"` → `"Korn"`, `"Korn - Topic"` → `"Korn"`. Returns null for a
	 * blank channel so the payload omits `artist` rather than inventing one.
	 */
	fun cleanChannel(channel: String?): String? {
		var out = channel?.trim().orEmpty()
		if (out.isEmpty()) return null
		for (suffix in CHANNEL_SUFFIXES) {
			if (out.length > suffix.length && out.endsWith(suffix, ignoreCase = true)) {
				out = out.dropLast(suffix.length).trim().trimEnd('-', '–', '—').trim()
				break
			}
		}
		return out.ifEmpty { null }
	}
}
