package com.rustedwax.app.enrich

import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.detect.TitleParser
import com.rustedwax.app.detect.VideoTitleMatcher
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
import kotlin.math.abs

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
	private val playlistCache = java.util.concurrent.ConcurrentHashMap<String, CachedPlaylist>()

	/** Bounds re-reading a playlist whose entries did not contain the track. */
	private val playlistRefresh = PlaylistRefreshThrottle()

	private data class CachedPlaylist(
		val entries: List<SearchResultsParser.Candidate>,
		val fetchedAtMillis: Long,
	)

	/**
	 * Playlist name → id, for native sessions that have a name but no URL.
	 *
	 * Cached including the misses: a name that resolves to nothing must not
	 * re-search on every track of a playlist RustedWax cannot identify.
	 */
	private val nativePlaylistIdCache = java.util.concurrent.ConcurrentHashMap<String, String>()

	/**
	 * The id of the playlist the native player named, or null.
	 *
	 * Native YouTube publishes no video id and no URL, so the playlist bar's
	 * name is the only handle on the closed candidate set that makes native
	 * identity exact. One search per playlist, then every track in it is served
	 * from [resolveEvidenceFromPlaylist] for free — which is also cheaper than
	 * the per-track search it replaces (measured field logs spent 56–75
	 * candidates on a *single* track).
	 */
	suspend fun resolveNativePlaylistId(
		playlistName: String,
		ownerName: String? = null,
		total: Int? = null,
	): String? {
		val key = playlistName.trim()
		if (key.isEmpty()) return null
		nativePlaylistIdCache[key]?.let { return it.takeIf { id -> id != NO_PLAYLIST } }

		val html = fetchUrl(PLAYLIST_SEARCH_URL + URLEncoder.encode(key, "UTF-8")) ?: run {
			// Not cached as a miss: a network failure is not evidence about the name.
			EventLog.append("resolve", "native playlist search fetch failed for \"$key\"")
			return null
		}
		val blob = WatchPageParser.extractJson(html, INITIAL_DATA) ?: run {
			EventLog.append(
				"resolve",
				"EXTRACTION FAILED — $INITIAL_DATA not found in playlist search for \"$key\" " +
					"(${html.length} bytes). YouTube markup may have changed.",
			)
			return null
		}
		val candidates = runCatching { PlaylistSearchParser.candidates(blob) }.getOrElse {
			EventLog.append("resolve", "EXTRACTION FAILED — playlist search parse: ${it.message}")
			return null
		}
		val hit = PlaylistSearchParser.match(candidates, key, ownerName, total)
		if (hit == null) {
			EventLog.append(
				"resolve",
				"native playlist \"$key\" did not resolve to exactly one public playlist " +
					"among ${candidates.size} results — using search",
			)
			nativePlaylistIdCache[key] = NO_PLAYLIST
			return null
		}
		EventLog.append(
			"resolve",
			"native playlist \"$key\" → ${hit.playlistId} (${hit.videoCount ?: "?"} videos)",
		)
		nativePlaylistIdCache[key] = hit.playlistId
		return hit.playlistId
	}

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
	): String? = resolveEvidenceFromPlaylist(playlistId, title, channel, durationSec)?.videoId

	suspend fun resolveEvidenceFromPlaylist(
		playlistId: String,
		title: String,
		channel: String?,
		durationSec: Long?,
	): VideoResolution? {
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

		var cached = playlistCache[playlistId] ?: fetchPlaylist(playlistId) ?: return null
		var matches = PlaylistPageParser.matches(cached.entries, title, channel, durationSec)

		// The cached list can simply be out of date: a song added to the playlist
		// after the first fetch would otherwise never be found again for the life
		// of the process. Bounded by [PlaylistRefreshThrottle], because the
		// ordinary miss is a track that is genuinely not in this playlist —
		// autoplay past the last entry — and must not re-read the page per track.
		if (matches.isEmpty() &&
			playlistRefresh.claim(playlistId, cached.fetchedAtMillis, System.currentTimeMillis())
		) {
			EventLog.append(
				"resolve",
				"\"$title\" not among ${cached.entries.size} cached entries — " +
					"re-reading playlist $playlistId in case it changed",
			)
			fetchPlaylist(playlistId)?.let { refreshed ->
				cached = refreshed
				matches = PlaylistPageParser.matches(refreshed.entries, title, channel, durationSec)
			}
		}

		// Exactly one, never the first of several (§7.2 rule 8). A playlist that
		// holds the same song twice cannot say which upload was played, and a
		// coin flip between them would be an unfixable wrong link on-chain.
		if (matches.size > 1) {
			EventLog.append(
				"resolve",
				"ambiguous playlist identity — ${matches.size} entries of $playlistId match " +
					"\"$title\" (${durationSec}s): ${matches.joinToString { it.videoId }}; " +
					"refusing every id",
			)
			return null
		}
		val hit = matches.singleOrNull()
		if (hit == null) {
			EventLog.append(
				"resolve",
				"\"$title\" not found among ${cached.entries.size} playlist entries — trying search",
			)
			return null
		}
		EventLog.append("resolve", "resolved \"$title\" → ${hit.videoId} from playlist $playlistId")
		return VideoResolution(
			videoId = hit.videoId,
			source = "playlist $playlistId",
			title = hit.title,
			channel = hit.channel,
			lengthSeconds = hit.lengthSeconds,
			uniquelyResolved = true,
			collaborativeChannel = hit.collaborativeChannel,
			playlistVerified = true,
		)
	}

	/** One page read, parsed and cached with the time it was read. */
	private suspend fun fetchPlaylist(playlistId: String): CachedPlaylist? {
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
		return CachedPlaylist(parsed, System.currentTimeMillis())
			.also { playlistCache[playlistId] = it }
	}

	/** Null whenever the video cannot be identified beyond doubt. */
	suspend fun resolve(title: String, channel: String?, durationSec: Long?): String? =
		resolveEvidence(title, channel, durationSec)?.videoId

	suspend fun resolveEvidence(
		title: String,
		channel: String?,
		durationSec: Long?,
	): VideoResolution? = resolveEvidenceAttempt(title, channel, durationSec).resolution

	suspend fun resolveEvidenceAttempt(
		title: String,
		channel: String?,
		durationSec: Long?,
		ownerHandle: String? = null,
		allowStructuredNativeMusic: Boolean = false,
	): VideoResolutionAttempt {
		val normalizedHandle = ownerHandle?.let(OwnerHandle::normalize)
		if (ownerHandle != null && normalizedHandle == null) {
			return VideoResolutionAttempt(refusalReason = "the foreground owner handle was malformed")
		}
		if (title.isBlank() || durationSec == null ||
			(normalizedHandle == null && channel.isNullOrBlank())
		) {
			return VideoResolutionAttempt(
				refusalReason = "title, owner/channel and duration were not all available for lookup",
			)
		}

		val seen = LinkedHashMap<String, SearchResultsParser.Candidate>()
		val queryOwner = ownerHandle ?: channel.orEmpty()
		for ((queryNumber, query) in searchQueries(title, queryOwner).withIndex()) {
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
		if (normalizedHandle != null) {
			return resolveByOwnerHandle(
				candidates = allCandidates,
				title = title,
				ownerHandle = ownerHandle,
				durationSec = durationSec,
			)
		}
		val requiredChannel = channel ?: return VideoResolutionAttempt(
			refusalReason = "the finalized channel was missing",
		)
		val plausible = allCandidates.filter {
			SearchResultsParser.hasNoIdentityContradiction(it, title, requiredChannel, durationSec)
		}
		val needsCompletion = plausible.any {
			it.channel == null || it.lengthSeconds == null
		}

		// Do not let one complete ordinary card hide an incomplete Shorts card
		// with the same identity title. That would accept the ordinary upload
		// without ever learning whether the Short is the item actually played.
		if (needsCompletion) {
			val completed = verifyFromWatchPages(plausible, title, requiredChannel, durationSec)
			completed.resolution?.let { return completed }
			if (completed.refusalReason?.startsWith("ambiguous identity") == true) {
				return completed
			}
		} else {
			// Do not return after the first query: a stripped-title query can reveal
			// a second indistinguishable upload. The immutable URL is accepted only
			// if the complete recovery set still has exactly one match.
			val matches = SearchResultsParser.identityMatches(
				plausible, title, requiredChannel, durationSec,
			)
			if (matches.size > 1) {
				val reason = "ambiguous identity — ${matches.size} uploads match " +
					"title+channel+duration (${matches.joinToString { it.videoId }}); refusing every id"
				EventLog.append("resolve", reason)
				return VideoResolutionAttempt(refusalReason = reason)
			}
			matches.singleOrNull()?.let { match ->
					EventLog.append(
						"resolve",
						"resolved \"$title\" → ${match.videoId} by title+channel+duration",
					)
					return VideoResolutionAttempt(
						resolution = VideoResolution(
							videoId = match.videoId,
							source = "title+channel+duration search",
							title = match.title,
							channel = match.channel,
							lengthSeconds = match.lengthSeconds,
							uniquelyResolved = true,
							collaborativeChannel = match.collaborativeChannel,
						),
					)
				}
		}

		if (allowStructuredNativeMusic) {
			val structured = resolveStructuredNativeMusic(
				allCandidates, title, requiredChannel, durationSec,
			)
			structured.resolution?.let { resolution ->
				EventLog.append(
					"resolve",
					"resolved \"$title\" → ${resolution.videoId} by " +
						"structured native music title+artist+duration",
				)
				return structured
			}
			if (structured.refusalReason?.startsWith("ambiguous identity") == true) {
				EventLog.append("resolve", structured.refusalReason)
				return structured
			}
			EventLog.append(
				"resolve",
				"structured native music recovery refused \"$title\": " +
					structured.refusalReason,
			)
			return structured
		}

		val reason = "no verified id for \"$title\" / \"$channel\" (${durationSec}s) " +
			"among ${seen.size} unique search candidates"
		EventLog.append("resolve", reason)
		return VideoResolutionAttempt(refusalReason = reason)
	}

	private suspend fun resolveStructuredNativeMusic(
		candidates: List<SearchResultsParser.Candidate>,
		title: String,
		artist: String,
		durationSec: Long,
	): VideoResolutionAttempt {
		val plausible = structuredNativeCandidates(candidates, title, artist, durationSec)
		if (plausible.size > MAX_WATCH_PAGE_CANDIDATES) {
			return VideoResolutionAttempt(
				refusalReason = "structured native music candidate set exceeded the bounded " +
					"$MAX_WATCH_PAGE_CANDIDATES-page verification budget; refusing every id",
			)
		}
		if (plausible.isEmpty()) {
			return VideoResolutionAttempt(
				refusalReason = "no search candidate had the exact structured native work " +
					"and complete artist credit",
			)
		}
		val fetched = coroutineScope {
			plausible.map { candidate ->
				async(Dispatchers.IO) {
					fetchCanonicalResolution(candidate.videoId, "structured native music search")
				}
			}.awaitAll().filterNotNull()
		}
		return NativeStructuredMusicMatcher.select(fetched, title, artist, durationSec)
	}

	internal fun structuredNativeCandidates(
		candidates: List<SearchResultsParser.Candidate>,
		title: String,
		artist: String,
		durationSec: Long,
	): List<SearchResultsParser.Candidate> = candidates.filter { candidate ->
			candidate.lengthSeconds?.let {
				abs(it - durationSec) <= DURATION_TOLERANCE_SEC
			} != false && NativeStructuredMusicMatcher.couldDescribeTrack(
				candidate.title, candidate.channel, title, artist,
			)
		}.distinctBy(SearchResultsParser.Candidate::videoId)

	/**
	 * Re-fetch one id whose uniqueness was already established while the native
	 * track was playing, then repeat the structured predicate against the frozen
	 * final snapshot. The id is never accepted from memory alone.
	 */
	suspend fun revalidatePreResolvedNativeMusic(
		videoId: String,
		title: String,
		artist: String,
		durationSec: Long,
	): VideoResolutionAttempt {
		val fetched = fetchCanonicalResolution(videoId, "pre-resolved native music revalidation")
			?: return VideoResolutionAttempt(
				refusalReason = "pre-resolved native music page could not be re-fetched",
			)
		return NativeStructuredMusicMatcher.select(
			listOf(fetched), title, artist, durationSec,
		)
	}

	/** Re-fetch candidates from the run-local cache; the cache itself is never authority. */
	suspend fun resolveVerifiedCandidates(
		videoIds: List<String>,
		title: String,
		channel: String?,
		durationSec: Long?,
		ownerHandle: String? = null,
	): VideoResolutionAttempt {
		if (videoIds.isEmpty() || durationSec == null ||
			(ownerHandle == null && channel.isNullOrBlank())
		) {
			return VideoResolutionAttempt(refusalReason = "no run-local verified candidate")
		}
		val fetched = coroutineScope {
			videoIds.distinct().take(MAX_CACHED_CANDIDATES).map { videoId ->
				async(Dispatchers.IO) { fetchCanonicalResolution(videoId, "run-local verified candidate") }
			}.awaitAll().filterNotNull()
		}
		if (ownerHandle != null) {
			return selectOwnerHandleMatch(fetched, title, ownerHandle, durationSec)
		}
		val matches = fetched.filter { candidate ->
			val candidateTitle = candidate.title ?: return@filter false
			val evidence = VideoTitleMatcher.compare(title, candidateTitle)
			(evidence == VideoTitleMatcher.Evidence.EXACT ||
				evidence == VideoTitleMatcher.Evidence.STRONG_CONTAINMENT) &&
				SearchResultsParser.channelKey(channel) ==
					SearchResultsParser.channelKey(candidate.channel) &&
				candidate.lengthSeconds?.let { abs(it - durationSec) <= DURATION_TOLERANCE_SEC } == true
		}.distinctBy { it.videoId }
		return when (matches.size) {
			1 -> VideoResolutionAttempt(
				resolution = matches.single().copy(
					source = "re-fetched run-local verified candidate",
					uniquelyResolved = true,
				),
			)
			0 -> VideoResolutionAttempt(
				refusalReason = "run-local candidate was re-fetched but did not fully corroborate",
			)
			else -> {
				val reason = "ambiguous identity — ${matches.size} re-fetched run-local candidates " +
					"match (${matches.joinToString { it.videoId }}); refusing every id"
				EventLog.append("resolve", reason)
				VideoResolutionAttempt(refusalReason = reason)
			}
		}
	}

	private suspend fun resolveByOwnerHandle(
		candidates: List<SearchResultsParser.Candidate>,
		title: String,
		ownerHandle: String,
		durationSec: Long,
	): VideoResolutionAttempt {
		val wantedTitle = SearchResultsParser.titleKey(title)
		val plausible = candidates.filter { candidate ->
			SearchResultsParser.titleKey(candidate.title) == wantedTitle &&
				candidate.lengthSeconds?.let {
					abs(it - durationSec) <= DURATION_TOLERANCE_SEC
				} != false
		}.distinctBy { it.videoId }
		if (plausible.size > MAX_WATCH_PAGE_CANDIDATES) {
			return VideoResolutionAttempt(
				refusalReason = "owner-handle candidate set exceeded the bounded " +
					"$MAX_WATCH_PAGE_CANDIDATES-page verification budget; refusing every id",
			)
		}
		val fetched = coroutineScope {
			plausible.map { candidate ->
				async(Dispatchers.IO) {
					fetchCanonicalResolution(candidate.videoId, "foreground owner-handle search")
				}
			}.awaitAll().filterNotNull()
		}
		return selectOwnerHandleMatch(fetched, title, ownerHandle, durationSec)
	}

	private suspend fun verifyFromWatchPages(
		candidates: List<SearchResultsParser.Candidate>,
		title: String,
		channel: String,
		durationSec: Long,
	): VideoResolutionAttempt {
		val plausible = candidates
			// A query already contains title + channel. Eight page checks is a
			// generous recovery budget while bounding data use and latency when a
			// heavily reposted Short has dozens of near-identical cards.
			.take(MAX_WATCH_PAGE_CANDIDATES)
		if (plausible.isEmpty()) {
			return VideoResolutionAttempt(refusalReason = "no plausible watch-page candidate")
		}

		val matches = coroutineScope {
			plausible.map { candidate ->
				async(Dispatchers.IO) {
					verifyWatchPage(candidate, title, channel, durationSec)
				}
			}.awaitAll().filterNotNull().distinctBy { it.videoId }
		}

		return when (matches.size) {
			0 -> VideoResolutionAttempt(refusalReason = "no watch-page candidate fully corroborated")
			1 -> matches.single().let {
				EventLog.append(
					"resolve",
					"resolved \"$title\" → ${it.videoId} after Shorts watch-page corroboration",
				)
				VideoResolutionAttempt(resolution = it.copy(uniquelyResolved = true))
			}
			else -> {
				val reason = "ambiguous identity — ${matches.size} uploads match " +
					"title+channel+duration (${matches.joinToString { it.videoId }}); refusing every id"
				EventLog.append("resolve", reason)
				VideoResolutionAttempt(refusalReason = reason)
			}
		}
	}

	private suspend fun verifyWatchPage(
		candidate: SearchResultsParser.Candidate,
		title: String,
		channel: String,
		durationSec: Long,
	): VideoResolution? {
		val resolution = fetchCanonicalResolution(candidate.videoId, "Shorts watch-page completion")
			?: return null
		val completed = SearchResultsParser.Candidate(
			videoId = resolution.videoId,
			title = resolution.title ?: return null,
			channel = resolution.channel ?: return null,
			lengthSeconds = resolution.lengthSeconds ?: return null,
		)
		if (!SearchResultsParser.matchesIdentity(completed, title, channel, durationSec)) return null
		return resolution.copy(collaborativeChannel = candidate.collaborativeChannel)
	}

	private suspend fun fetchCanonicalResolution(videoId: String, source: String): VideoResolution? {
		val html = fetchUrl(WATCH_URL + videoId) ?: return null
		val player = WatchPageParser.extractJson(html, PLAYER_RESPONSE) ?: return null
		val reportedId = runCatching {
			JSONObject(player).optJSONObject("videoDetails")?.optString("videoId")
		}.getOrNull()
		if (reportedId != videoId) return null

		val facts = runCatching {
			WatchPageParser.parsePlayerResponse(videoId, player)
		}.getOrNull() ?: return null
		return VideoResolution(
			videoId = videoId,
			source = source,
			title = facts.title ?: return null,
			channel = facts.author ?: return null,
			ownerHandle = facts.ownerHandle,
			lengthSeconds = facts.lengthSeconds ?: return null,
		)
	}

	internal fun selectOwnerHandleMatch(
		candidates: List<VideoResolution>,
		title: String,
		ownerHandle: String,
		durationSec: Long,
	): VideoResolutionAttempt {
		val normalizedHandle = OwnerHandle.normalize(ownerHandle) ?: return VideoResolutionAttempt(
			refusalReason = "the foreground owner handle was malformed",
		)
		val wantedTitle = SearchResultsParser.titleKey(title)
		val matches = candidates.filter { candidate ->
			val candidateTitle = candidate.title ?: return@filter false
			val candidateDuration = candidate.lengthSeconds ?: return@filter false
			SearchResultsParser.titleKey(candidateTitle) == wantedTitle &&
				abs(candidateDuration - durationSec) <= DURATION_TOLERANCE_SEC &&
				OwnerHandle.normalize(candidate.ownerHandle) == normalizedHandle
		}.distinctBy { it.videoId }
		return when (matches.size) {
			1 -> VideoResolutionAttempt(
				resolution = matches.single().copy(uniquelyResolved = true),
			)
			0 -> VideoResolutionAttempt(
				refusalReason = "no candidate matched exact title+duration+owner handle $ownerHandle",
			)
			else -> VideoResolutionAttempt(
				refusalReason = "ambiguous identity — ${matches.size} uploads match exact " +
					"title+duration+owner handle (${matches.joinToString { it.videoId }}); " +
					"refusing every id",
			)
		}
	}

	internal fun searchQueries(title: String, channel: String): List<String> {
		val variants = linkedSetOf<String>()
		fun addVariant(value: String?) {
			value?.trim()?.takeIf(String::isNotBlank)?.let(variants::add)
		}
		addVariant(title)
		addVariant(SearchResultsParser.searchTitle(title))
		addVariant(TitleParser.presentationCore(title))
		addVariant(TitleParser.parse(title, channel).track)

		val titleWords = SearchResultsParser.titleKey(title).split(' ').filter(String::isNotBlank)
		val channelWords = SearchResultsParser.titleKey(channel).split(' ').filter(String::isNotBlank)
		if (channelWords.isNotEmpty() && titleWords.size > channelWords.size &&
			titleWords.take(channelWords.size) == channelWords
		) {
			addVariant(titleWords.drop(channelWords.size).joinToString(" "))
		}

		return linkedSetOf<String>().apply {
			variants.forEach { variant ->
				add("$variant $channel")
				add(variant)
			}
		}.take(MAX_SEARCH_QUERIES)
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

	internal companion object {
		const val SEARCH_URL = "https://www.youtube.com/results?search_query="

		/** `sp=EgIQAw%3D%3D` is YouTube's "playlists only" search filter. */
		const val PLAYLIST_SEARCH_URL =
			"https://www.youtube.com/results?sp=EgIQAw%3D%3D&search_query="

		/** Sentinel for "this name resolved to nothing", so misses cache too. */
		const val NO_PLAYLIST = " none"
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
		const val MAX_CACHED_CANDIDATES = 8
		const val MAX_SEARCH_QUERIES = 6
		const val DURATION_TOLERANCE_SEC = 5L
		const val USER_AGENT =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
				"(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
	}
}
