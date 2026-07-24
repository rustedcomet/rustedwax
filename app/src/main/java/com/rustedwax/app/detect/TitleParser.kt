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
		var out = BRACKETED.replace(value) { m ->
			if (isPromoOnly(m.groupValues[1])) "" else m.value
		}
		PIPE_SUFFIX.find(out)?.let { m ->
			if (isPromoOnly(m.groupValues[1])) out = out.removeRange(m.range)
		}

		// Trailing junk comes in layers — "…【Guitar Cover】＋Screen Tabs" only
		// exposes what's behind it once the tab suffix is gone. Loop until
		// stable.
		var changed = true
		while (changed) {
			val before = out
			TRAILING_JUNK.forEach { out = it.replace(out, "") }
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
