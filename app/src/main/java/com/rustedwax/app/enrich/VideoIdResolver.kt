package com.rustedwax.app.enrich

import com.rustedwax.app.detect.EventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
 * cover by a different artist. Modern Shorts cards omit channel and duration,
 * so those candidates are completed from their individual watch pages and the
 * same three-field rule is applied there. Ambiguous or unverified results stay
 * unresolved; the engine refuses to broadcast them.
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

		val seen = LinkedHashMap<String, SearchResultsParser.Candidate>()
		for ((queryNumber, query) in searchQueries(title, channel).withIndex()) {
			val html = fetch(query)
			if (html == null) {
				EventLog.append(
					"resolve",
					"search fetch failed for query ${queryNumber + 1} of \"$title\"",
				)
				continue
			}

			val blob = WatchPageParser.extractJson(html, INITIAL_DATA)
			if (blob == null) {
				EventLog.append(
					"resolve",
					"EXTRACTION FAILED — $INITIAL_DATA not found in search results " +
						"(${html.length} bytes). YouTube markup may have changed.",
				)
				continue
			}

			val parsed = runCatching { SearchResultsParser.candidates(blob) }
			val candidates = parsed.getOrNull()
			if (candidates == null) {
				EventLog.append(
					"resolve",
					"EXTRACTION FAILED — search parse: ${parsed.exceptionOrNull()?.message}",
				)
				continue
			}
			candidates.forEach { seen.putIfAbsent(it.videoId, it) }
			EventLog.append(
				"resolve",
				"search ${queryNumber + 1} → ${candidates.size} video/Short candidates",
			)
		}

		val allCandidates = seen.values.toList()
		val plausible = allCandidates.filter {
			SearchResultsParser.hasNoIdentityContradiction(it, title, channel, durationSec)
		}
		val needsCompletion = plausible.any {
			it.channel == null || it.lengthSeconds == null
		}

		// Do not let one complete ordinary card hide an incomplete Shorts card
		// with the same identity title. That would accept the ordinary upload
		// without ever learning whether the Short is the item actually played.
		if (needsCompletion) {
			verifyFromWatchPages(plausible, title, channel, durationSec)?.let { return it }
		} else {
			// Do not return after the first query: a stripped-title query can reveal
			// a second indistinguishable upload. The immutable URL is accepted only
			// if the complete recovery set still has exactly one match.
			SearchResultsParser.bestMatch(plausible, title, channel, durationSec)
				?.let { match ->
					EventLog.append(
						"resolve",
						"resolved \"$title\" → ${match.videoId} by title+channel+duration",
					)
					return match.videoId
				}
		}

		EventLog.append(
			"resolve",
			"no verified id for \"$title\" / \"$channel\" (${durationSec}s) " +
				"among ${seen.size} unique search candidates",
		)
		return null
	}

	private suspend fun verifyFromWatchPages(
		candidates: List<SearchResultsParser.Candidate>,
		title: String,
		channel: String,
		durationSec: Long,
	): String? {
		val plausible = candidates
			// A query already contains title + channel. Eight page checks is a
			// generous recovery budget while bounding data use and latency when a
			// heavily reposted Short has dozens of near-identical cards.
			.take(MAX_WATCH_PAGE_CANDIDATES)
		if (plausible.isEmpty()) return null

		val matches = coroutineScope {
			plausible.map { candidate ->
				async(Dispatchers.IO) {
					verifyWatchPage(candidate, title, channel, durationSec)
				}
			}.awaitAll().filterNotNull().distinct()
		}

		return when (matches.size) {
			0 -> null
			1 -> matches.single().also {
				EventLog.append(
					"resolve",
					"resolved \"$title\" → $it after Shorts watch-page corroboration",
				)
			}
			else -> {
				EventLog.append(
					"resolve",
					"ambiguous identity — ${matches.size} uploads match title+channel+duration " +
						"(${matches.joinToString()}); refusing every id",
				)
				null
			}
		}
	}

	private suspend fun verifyWatchPage(
		candidate: SearchResultsParser.Candidate,
		title: String,
		channel: String,
		durationSec: Long,
	): String? {
		val html = fetchUrl(WATCH_URL + candidate.videoId) ?: return null
		val player = WatchPageParser.extractJson(html, PLAYER_RESPONSE) ?: return null
		val reportedId = runCatching {
			JSONObject(player).optJSONObject("videoDetails")?.optString("videoId")
		}.getOrNull()
		if (reportedId != candidate.videoId) return null

		val facts = runCatching {
			WatchPageParser.parsePlayerResponse(candidate.videoId, player)
		}.getOrNull() ?: return null
		val completed = SearchResultsParser.Candidate(
			videoId = candidate.videoId,
			title = facts.title ?: return null,
			channel = facts.author ?: return null,
			lengthSeconds = facts.lengthSeconds ?: return null,
		)
		return candidate.videoId.takeIf {
			SearchResultsParser.matchesIdentity(completed, title, channel, durationSec)
		}
	}

	private fun searchQueries(title: String, channel: String): List<String> {
		val cleaned = SearchResultsParser.searchTitle(title)
		return linkedSetOf<String>().apply {
			add("$title $channel")
			if (cleaned.isNotBlank()) {
				add("$cleaned $channel")
				add(cleaned)
			}
		}.toList()
	}

	private suspend fun fetch(query: String): String? =
		fetchUrl(SEARCH_URL + URLEncoder.encode(query, "UTF-8"))

	private suspend fun fetchUrl(target: String): String? {
		repeat(NETWORK_ATTEMPTS) { attempt ->
			val body = withContext(Dispatchers.IO) {
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
			if (body != null) return body
			if (attempt + 1 < NETWORK_ATTEMPTS) delay(RETRY_DELAY_MS)
		}
		return null
	}

	private companion object {
		const val SEARCH_URL = "https://www.youtube.com/results?search_query="
		const val PLAYLIST_URL = "https://www.youtube.com/playlist?list="
		const val WATCH_URL = "https://www.youtube.com/watch?v="

		/** Mixes (`RD…`) and the private Liked / Watch Later lists. */
		val UNFETCHABLE_PREFIXES = listOf("RD", "LL", "WL")
		const val INITIAL_DATA = "ytInitialData"
		const val PLAYER_RESPONSE = "ytInitialPlayerResponse"
		const val TIMEOUT_MS = 5_000
		const val NETWORK_ATTEMPTS = 2
		const val RETRY_DELAY_MS = 500L
		const val MAX_WATCH_PAGE_CANDIDATES = 8
		const val USER_AGENT =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
				"(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
	}
}
