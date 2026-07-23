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
 * and encode years of edge cases; this covers the common shapes and should be
 * replaced by a fuller port during Phase 3.
 */
object TitleParser {

	data class Parsed(val artist: String?, val track: String)

	/** Separators between artist and track, longest/most specific first. */
	private val SEPARATORS = listOf(
		" -- ", " ~ ", " — ", " – ", " - ", " | ", "「", "『",
	)

	/**
	 * Words that, on their own or in combination, mark a bracketed group as
	 * promotional noise rather than part of the title.
	 *
	 * A group is stripped only when **every** word in it is on this list, which
	 * is why `(Live)`, `(Acoustic)`, `(Remix)` and `(Radio Edit)` survive —
	 * they identify a different recording and belong in the title.
	 *
	 * Enumerating words beats enumerating phrases: an earlier version listed
	 * `(Official Video)` and `(HD)` separately and let
	 * `"Thoughtless (Official HD Video)"` straight through to the chain.
	 */
	private val PROMO_WORDS = setOf(
		"official", "oficial", "video", "vídeo", "audio", "música", "music",
		"lyric", "lyrics", "visualizer", "visualiser", "hd", "hq", "uhd",
		"4k", "8k", "1080p", "720p", "full", "mv", "m/v", "clip", "stream",
		"version", "explicit", "new", "hi-res", "with", "and", "the",
	)

	/** A bracketed group and its contents. */
	private val BRACKETED = Regex("""\s*[(\[]([^()\[\]]*)[)\]]""")

	/** Trailing `| Official Video`-style suffixes, which use no brackets. */
	private val PIPE_SUFFIX = Regex("""\s*\|\s*([^|]+)\s*$""")

	private val WORD_SPLIT = Regex("""[\s\-_/]+""")

	/** True when every word is promotional — so the group carries no meaning. */
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
	)

	/** Channel suffixes that mark an artist-owned channel. */
	private val CHANNEL_SUFFIXES =
		listOf("VEVO", " - Topic", "Official", "Music", "TV", "Records")

	fun parse(rawTitle: String, channel: String?): Parsed {
		val title = clean(rawTitle)

		for (sep in SEPARATORS) {
			val idx = title.indexOf(sep)
			if (idx <= 0) continue
			val artist = title.substring(0, idx).trim()
			val track = title.substring(idx + sep.length).trim()
			if (artist.isNotEmpty() && track.isNotEmpty()) {
				return Parsed(artist, track)
			}
		}

		// No separator: the channel is the best artist guess we have.
		return Parsed(cleanChannel(channel), title)
	}

	/** Strip promotional noise and collapse whitespace. */
	fun clean(value: String): String {
		var out = BRACKETED.replace(value) { m ->
			if (isPromoOnly(m.groupValues[1])) "" else m.value
		}
		PIPE_SUFFIX.find(out)?.let { m ->
			if (isPromoOnly(m.groupValues[1])) out = out.removeRange(m.range)
		}
		return out.replace(Regex("""\s{2,}"""), " ").trim()
	}

	/**
	 * Whether this looks like music rather than an arbitrary video.
	 *
	 * The extension's YouTube connector decided this from the page's category;
	 * we only have the title and channel. Two signals are reliable enough: an
	 * artist-owned channel (`…VEVO`, `… - Topic`), or a title in `Artist -
	 * Track` form. Desktop scrobbles of the same videos land as `song`, so
	 * defaulting to `video` here would split the same track across two kinds.
	 */
	fun looksLikeMusic(rawTitle: String, channel: String?): Boolean {
		val ch = channel?.trim().orEmpty()
		if (ch.endsWith("VEVO", ignoreCase = true) ||
			ch.endsWith("- Topic", ignoreCase = true)
		) {
			return true
		}
		return parse(rawTitle, channel).let { it.artist != null && hasSeparator(rawTitle) }
	}

	private fun hasSeparator(title: String) = SEPARATORS.any { title.indexOf(it) > 0 }

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
