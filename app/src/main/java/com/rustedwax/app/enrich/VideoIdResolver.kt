package com.rustedwax.app.enrich

import com.rustedwax.app.detect.EventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Recovers a video id by searching YouTube for what the session is playing.
 *
 * The fallback for when the address bar never names the video. Field logs of
 * 2026-07-25 showed why that is common rather than exceptional: across one
 * session the bar went silent for **15, 35 and 40-minute stretches**, because
 * a playlist advancing via the History API changes nothing on screen that
 * fires an accessibility event — the toolbar is scrolled away, or the browser
 * is backgrounded, or the screen is off. Fifteen of sixty scrobbles were
 * broadcast with no `url`, and four of six tracks in one album playlist.
 *
 * Unlike the address bar this needs no event, no foreground window and no
 * screen, so it covers exactly the case the bar cannot.
 *
 * ## It fails closed
 *
 * The id is *inferred*, so [SearchResultsParser.bestMatch] demands the title,
 * the channel and the duration all agree before anything is returned — the
 * measured search results for one track included a same-titled, same-length
 * cover by a different artist. Returning nothing keeps a payload without a
 * `url`, which is the outcome this whole path already tolerates. Returning the
 * wrong id would put a wrong link on an immutable chain.
 */
class VideoIdResolver {

	/** Playlist contents, fetched once per playlist and reused for every track in it. */
	private val playlistCache = java.util.concurrent.ConcurrentHashMap<
		String, List<SearchResultsParser.Candidate>,
		>()

	/**
	 * The exact entry from the playlist being played, when there is one.
	 *
	 * Tried before [resolve] because it is *exact* where search is merely
	 * plausible: for the reported playlist, search resolves "Doomed" to
	 * `CZFTfYYql4k` while the playlist actually holds `5Oc0ja19_GU` — same
	 * song, same artist, same length, different upload. One fetch then serves
	 * every remaining track in that playlist for free.
	 */
	suspend fun resolveFromPlaylist(
		playlistId: String,
		title: String,
		channel: String?,
		durationSec: Long?,
	): String? {
		if (title.isBlank() || durationSec == null) return null

		// Mixes and private lists have no fetchable page of entries. `RD…` is a
		// YouTube Mix/radio — auto-generated, personalised and endless — while
		// `LL`/`WL` are the private Liked and Watch Later lists. Field logs
		// showed three of these fetched in one session, each returning zero
		// entries after downloading about a megabyte, then falling through to
		// search anyway. Go straight to search.
		if (UNFETCHABLE_PREFIXES.any { playlistId.startsWith(it, ignoreCase = true) }) {
			EventLog.append("resolve", "$playlistId is a mix or private list — using search")
			return null
		}

		val entries = playlistCache[playlistId] ?: run {
			val html = fetchUrl(PLAYLIST_URL + playlistId) ?: run {
				EventLog.append("resolve", "playlist fetch failed for $playlistId")
				return null
			}
			val blob = WatchPageParser.extractJson(html, INITIAL_DATA)
			if (blob == null) {
				EventLog.append(
					"resolve",
					"EXTRACTION FAILED — $INITIAL_DATA not found in playlist $playlistId " +
						"(${html.length} bytes). YouTube markup may have changed.",
				)
				return null
			}
			val parsed = runCatching { PlaylistPageParser.entries(blob) }.getOrElse {
				EventLog.append("resolve", "EXTRACTION FAILED — playlist parse: ${it.message}")
				return null
			}
			EventLog.append("resolve", "playlist $playlistId → ${parsed.size} entries cached")
			playlistCache[playlistId] = parsed
			parsed
		}

		val hit = PlaylistPageParser.match(entries, title, channel, durationSec)
		if (hit == null) {
			EventLog.append(
				"resolve",
				"\"$title\" not found among ${entries.size} playlist entries — trying search",
			)
			return null
		}
		EventLog.append("resolve", "resolved \"$title\" → ${hit.videoId} from playlist $playlistId")
		return hit.videoId
	}

	/** Null whenever the video cannot be identified beyond doubt. */
	suspend fun resolve(title: String, channel: String?, durationSec: Long?): String? {
		if (title.isBlank() || channel.isNullOrBlank() || durationSec == null) {
			return null
		}

		val html = fetch("$title $channel") ?: run {
			EventLog.append("resolve", "search fetch failed for \"$title\"")
			return null
		}

		val blob = WatchPageParser.extractJson(html, INITIAL_DATA)
		if (blob == null) {
			EventLog.append(
				"resolve",
				"EXTRACTION FAILED — $INITIAL_DATA not found in search results " +
					"(${html.length} bytes). YouTube markup may have changed.",
			)
			return null
		}

		val candidates = runCatching { SearchResultsParser.candidates(blob) }.getOrElse {
			EventLog.append("resolve", "EXTRACTION FAILED — search parse: ${it.message}")
			return null
		}

		val match = SearchResultsParser.bestMatch(candidates, title, channel, durationSec)
		if (match == null) {
			EventLog.append(
				"resolve",
				"no confident match for \"$title\" / \"$channel\" (${durationSec}s) " +
					"among ${candidates.size} results — leaving url unset",
			)
			return null
		}
		EventLog.append("resolve", "resolved \"$title\" → ${match.videoId} by title+channel+duration")
		return match.videoId
	}

	private suspend fun fetch(query: String): String? =
		fetchUrl(SEARCH_URL + URLEncoder.encode(query, "UTF-8"))

	private suspend fun fetchUrl(target: String): String? = withContext(Dispatchers.IO) {
		runCatching {
			val url = URL(target)
			val connection = url.openConnection() as HttpURLConnection
			try {
				connection.apply {
					requestMethod = "GET"
					connectTimeout = TIMEOUT_MS
					readTimeout = TIMEOUT_MS
					instanceFollowRedirects = true
					// The desktop shell carries ytInitialData; the mobile one
					// serves a different document.
					setRequestProperty("User-Agent", USER_AGENT)
					setRequestProperty("Accept-Language", "en-US,en;q=0.9")
				}
				connection.inputStream.bufferedReader().readText()
			} finally {
				connection.disconnect()
			}
		}.getOrNull()
	}

	private companion object {
		const val SEARCH_URL = "https://www.youtube.com/results?search_query="
		const val PLAYLIST_URL = "https://www.youtube.com/playlist?list="

		/** Mixes (`RD…`) and the private Liked / Watch Later lists. */
		val UNFETCHABLE_PREFIXES = listOf("RD", "LL", "WL")
		const val INITIAL_DATA = "ytInitialData"
		const val TIMEOUT_MS = 5_000
		const val USER_AGENT =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
				"(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
	}
}
