package com.rustedwax.app.enrich

import com.rustedwax.app.detect.EventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads a video's own metadata off its watch page.
 *
 * Chromium publishes no video id to the media session (PHASE0 Q3), so this can
 * only run once the address-bar watcher has recovered one. Given an id, the
 * watch page carries what the media session can't: YouTube's own **category** —
 * which answers `song` vs `video` far better than any title heuristic — plus
 * the description, where cover uploads routinely credit the original artist.
 *
 * ## This is the fragile part of Phase 4 (decision D8)
 *
 * `ytInitialPlayerResponse` is an undocumented blob in a page nobody promised
 * would keep its shape. The mitigations are not optional:
 *
 *  - Every extraction failure logs a **distinct** line, so breakage reads
 *    differently from "this video had nothing to add". Silent degradation here
 *    would look exactly like enrichment being switched off.
 *  - The parsing lives in [WatchPageParser], which is pure and unit-tested
 *    against a page fixture, so drift fails a test rather than a scrobble.
 *  - Everything is behind [MetadataResolver], so a Data-API implementation
 *    replaces it without touching the pipeline.
 *  - Nothing throws, nothing blocks past [TIMEOUT_MS], and a failure always
 *    falls back to the offline parse.
 *
 * ## What this sends
 *
 * One GET to youtube.com per new video, carrying the video id, from outside the
 * browser. Google already knows you watched it; the app is not telling them
 * anything new. It is still off-device traffic, so it stays a visible setting.
 */
class YouTubePageResolver(
	private val cache: FactsCache? = null,
) : MetadataResolver {

	override suspend fun resolve(videoId: String): VideoFacts? {
		cache?.get(videoId)?.let {
			EventLog.append("enrich", "cache hit for $videoId")
			return it
		}

		val html = withTimeoutOrNull(TIMEOUT_MS) { fetch(videoId) }
		if (html == null) {
			EventLog.append("enrich", "fetch failed or timed out for $videoId")
			return null
		}

		val player = WatchPageParser.extractJson(html, PLAYER_RESPONSE)
		if (player == null) {
			// The distinctive failure: the page loaded but the blob we rely on
			// wasn't where it has always been. This is what markup drift looks
			// like, and it should be obvious in an exported log.
			EventLog.append(
				"enrich",
				"EXTRACTION FAILED — $PLAYER_RESPONSE not found for $videoId " +
					"(${html.length} bytes). YouTube markup may have changed.",
			)
			return null
		}

		val facts = runCatching { WatchPageParser.parsePlayerResponse(videoId, player) }
			.getOrElse {
				EventLog.append(
					"enrich",
					"EXTRACTION FAILED — could not parse $PLAYER_RESPONSE for " +
						"$videoId: ${it.message}",
				)
				return null
			}

		EventLog.append(
			"enrich",
			"$videoId → category=${facts.category ?: "?"} " +
				"originalArtist=${facts.originalArtist ?: "—"}",
		)
		cache?.put(facts)
		return facts
	}

	private suspend fun fetch(videoId: String): String? = withContext(Dispatchers.IO) {
		runCatching {
			val connection = URL(WATCH_URL + videoId).openConnection() as HttpURLConnection
			try {
				connection.apply {
					requestMethod = "GET"
					connectTimeout = TIMEOUT_MS.toInt()
					readTimeout = TIMEOUT_MS.toInt()
					instanceFollowRedirects = true
					// A desktop UA gets the full page; the mobile shell serves a
					// different document that doesn't carry the blob.
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
		const val WATCH_URL = "https://www.youtube.com/watch?v="
		const val PLAYER_RESPONSE = "ytInitialPlayerResponse"
		const val TIMEOUT_MS = 4_000L
		const val USER_AGENT =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
				"(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
	}
}
