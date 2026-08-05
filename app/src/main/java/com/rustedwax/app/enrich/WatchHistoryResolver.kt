package com.rustedwax.app.enrich

import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.storage.YouTubeSessionVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves an exact video id from the signed-in account's watch history.
 *
 * The route of last resort before search, and the only one that answers for
 * native playback outside a playlist — backgrounded, screen off, or a single
 * video with no queue around it (`PHASE_NATIVE_PLAYLIST_IDENTITY.md` §10.3).
 *
 * Resolution priority, unchanged elsewhere:
 * `browser address bar → playlist entry set → watch history → search`.
 * The playlist stays ahead because it is one fetch serving a hundred tracks and
 * needs no credentials; history covers what the playlist cannot.
 *
 * ## Handling of the session
 *
 * The cookie is read from [YouTubeSessionVault] at the moment of the request
 * and attached to exactly one hardcoded origin. It is never logged, never
 * placed in an event, never returned to a caller, and never attached to a
 * redirect — redirects are not followed at all, precisely so a 302 cannot walk
 * the credential to another host. Cookie rotations that YouTube sends back are
 * folded into the vault so a live session is not allowed to expire on disk.
 */
class WatchHistoryResolver(private val vault: YouTubeSessionVault) {

	private val health = WatchHistoryHealth()

	private data class CachedFeed(
		val entries: List<WatchHistoryParser.Entry>,
		val fetchedAtMillis: Long,
	)

	@Volatile
	private var cached: CachedFeed? = null

	/** The exact reason the route is not running, or null when it is. */
	val refusedBecause: String? get() = health.refusedBecause

	val hasSession: Boolean get() = vault.hasSession

	/** Sign-in, sign-out and monitoring boundaries all start the state over. */
	fun reset() {
		health.reset()
		cached = null
	}

	/**
	 * One candidate id for the track described by the frozen tuple, or the exact
	 * reason there is none. Never a verdict — the engine still runs it through
	 * [VideoIdentityCorroborator] like every other route.
	 */
	suspend fun resolveEvidence(
		title: String,
		channel: String?,
		durationSec: Long?,
	): VideoResolutionAttempt = attempt(title, channel, durationSec, carriedVideoId = null)

	/**
	 * Re-derive a history-routed carry authority at finalization. The id is
	 * never trusted from memory; the feed has to still name the same video.
	 */
	suspend fun revalidate(
		videoId: String,
		title: String,
		channel: String?,
		durationSec: Long?,
	): VideoResolutionAttempt = attempt(title, channel, durationSec, carriedVideoId = videoId)

	private suspend fun attempt(
		title: String,
		channel: String?,
		durationSec: Long?,
		carriedVideoId: String?,
	): VideoResolutionAttempt {
		if (!vault.hasSession) {
			return VideoResolutionAttempt(
				refusalReason = "no YouTube account is connected for watch-history lookups",
			)
		}
		val now = System.currentTimeMillis()
		if (!health.mayRun(now)) {
			return VideoResolutionAttempt(
				refusalReason = "watch history is not being used: ${health.refusedBecause}",
			)
		}

		fun match(entries: List<WatchHistoryParser.Entry>) = if (carriedVideoId == null) {
			WatchHistoryMatcher.candidate(entries, title, channel, durationSec)
		} else {
			WatchHistoryMatcher.revalidate(entries, carriedVideoId, title, channel, durationSec)
		}

		var feed = when (val first = recentEntries(now, allowCache = true)) {
			is Feed.Entries -> first
			is Feed.Unavailable -> return VideoResolutionAttempt(refusalReason = first.reason)
		}
		var verdict = match(feed.entries)

		// A cached feed cannot contain a track that started after it was read,
		// and the commonest lookup is exactly that: the id is asked for seconds
		// into a new track, while the copy fetched to finalize the *previous*
		// one is still warm. Measured 2026-08-04 — "Por El Momento" missed
		// against a 0.5-second-old cache and fell through to the search route,
		// which is the route that picks the wrong upload. So absence against a
		// cached feed is not an answer; it is a reason to look again.
		if (feed.fromCache && WatchHistoryMatcher.isAbsence(verdict)) {
			val fresh = recentEntries(now, allowCache = false)
			if (fresh is Feed.Entries) {
				feed = fresh
				verdict = match(fresh.entries)
			}
		}
		val entries = feed.entries

		return when (verdict) {
			is WatchHistoryMatcher.Verdict.Candidate -> {
				health.recordHit()
				val entry = verdict.entry
				EventLog.append(
					"history",
					"resolved \"$title\" → ${entry.videoId} from watch history " +
						"(entry ${verdict.positionFromNewest} of ${entries.size}, " +
						"0 = newest)",
				)
				VideoResolutionAttempt(
					resolution = VideoResolution(
						videoId = entry.videoId,
						source = "watch history",
						title = entry.title,
						channel = entry.channel,
						lengthSeconds = entry.lengthSeconds,
						uniquelyResolved = true,
						historyVerified = true,
					),
				)
			}

			is WatchHistoryMatcher.Verdict.Refused -> {
				// Only a plain absence from a *freshly read* feed is evidence about
				// the account. An ambiguous feed is evidence about the uploads, and
				// a stale cache is evidence about nothing at all — counting either
				// toward the "your app is on another account" diagnosis would
				// eventually stand the route down over a timing artefact.
				if (!feed.fromCache && WatchHistoryMatcher.isAbsence(verdict)) health.recordMiss(now)
				EventLog.append("history", "refused \"$title\": ${verdict.reason}")
				VideoResolutionAttempt(refusalReason = verdict.reason)
			}
		}
	}

	/** What a freshly stored session actually turned out to be able to read. */
	sealed interface Probe {
		data class Working(
			val entries: Int,
			val newestTitle: String?,
			val accountLabel: String?,
		) : Probe

		data class Faulted(
			val reason: WatchHistoryParser.Reason?,
			val message: String,
		) : Probe
	}

	/**
	 * One-off check run straight after sign-in, so the user is told there and
	 * then whether the session can read anything — rather than finding out days
	 * later from an empty History tab.
	 *
	 * Deliberately bypasses the cache and the health gate: this is the question
	 * "does this session work", not "what is playing".
	 */
	suspend fun probe(): Probe {
		val page = fetchHistory()
			?: return Probe.Faulted(null, "youtube.com could not be reached")
		if (page.signedOutRedirect) {
			return Probe.Faulted(
				WatchHistoryParser.Reason.SIGNED_OUT,
				"youtube.com redirected the request to a sign-in page",
			)
		}
		val blob = WatchPageParser.extractJson(page.body, INITIAL_DATA)
			?: return Probe.Faulted(
				WatchHistoryParser.Reason.MARKUP_CHANGED,
				"the history page did not contain $INITIAL_DATA",
			)
		val label = accountLabel(page.body)
		vault.rememberAccountLabel(label)
		return when (val parsed = WatchHistoryParser.parse(blob)) {
			is WatchHistoryParser.Result.Feed -> {
				cached = CachedFeed(parsed.entries, System.currentTimeMillis())
				Probe.Working(
					entries = parsed.entries.size,
					newestTitle = parsed.entries.firstOrNull()?.title,
					accountLabel = label,
				)
			}

			is WatchHistoryParser.Result.Unreadable -> {
				if (parsed.reason == WatchHistoryParser.Reason.EMPTY) {
					EventLog.append("history", shapeReport(page.body))
				}
				Probe.Faulted(parsed.reason, parsed.detail)
			}
		}
	}

	/**
	 * A display name for the connected account, best effort.
	 *
	 * Only ever used to show the user *which* account RustedWax reads, so that
	 * "the YouTube app is on a different one" is something they can see rather
	 * than deduce. Absence is fine and is not an error.
	 */
	internal fun accountLabel(html: String): String? =
		ACCOUNT_LABEL_PATTERNS.firstNotNullOfOrNull { pattern ->
			pattern.find(html)?.groupValues?.getOrNull(1)
				?.takeIf { it.isNotBlank() && it.length <= 80 }
		}

	private sealed interface Feed {
		data class Entries(
			val entries: List<WatchHistoryParser.Entry>,
			/** Absence in a cached feed proves nothing; absence in a fresh one does. */
			val fromCache: Boolean,
		) : Feed

		data class Unavailable(val reason: String) : Feed
	}

	private suspend fun recentEntries(nowMillis: Long, allowCache: Boolean): Feed {
		if (allowCache) {
			cached?.takeIf { nowMillis - it.fetchedAtMillis < CACHE_MS }?.let {
				return Feed.Entries(it.entries, fromCache = true)
			}
		}

		val page = fetchHistory() ?: run {
			health.recordFetchFailure()
			return Feed.Unavailable("the watch-history page could not be fetched")
		}
		if (page.signedOutRedirect) {
			health.recordUnavailable(
				WatchHistoryParser.Reason.SIGNED_OUT,
				"youtube.com redirected the request to a sign-in page",
				nowMillis,
			)
			return Feed.Unavailable("watch history is not being used: ${health.refusedBecause}")
		}

		val blob = WatchPageParser.extractJson(page.body, INITIAL_DATA) ?: run {
			health.recordUnavailable(
				WatchHistoryParser.Reason.MARKUP_CHANGED,
				"$INITIAL_DATA not found in ${page.body.length} bytes",
				nowMillis,
			)
			EventLog.append(
				"history",
				"EXTRACTION FAILED — $INITIAL_DATA not found in the watch-history page " +
					"(${page.body.length} bytes). YouTube markup may have changed.",
			)
			return Feed.Unavailable("watch history is not being used: ${health.refusedBecause}")
		}

		return when (val parsed = WatchHistoryParser.parse(blob)) {
			is WatchHistoryParser.Result.Feed -> {
				cached = CachedFeed(parsed.entries, System.currentTimeMillis())
				EventLog.append("history", "read ${parsed.entries.size} watch-history entries")
				Feed.Entries(parsed.entries, fromCache = false)
			}

			is WatchHistoryParser.Result.Unreadable -> {
				health.recordUnavailable(parsed.reason, parsed.detail, nowMillis)
				val reason = health.refusedBecause
					?: "watch history had no usable entries (${parsed.detail})"
				EventLog.append("history", "unusable: $reason")
				if (parsed.reason == WatchHistoryParser.Reason.EMPTY) {
					EventLog.append("history", shapeReport(page.body))
				}
				Feed.Unavailable(reason)
			}
		}
	}

	/**
	 * Counts of the renderer *names* the page contains — never its content.
	 *
	 * "Your history is empty" has two completely different causes: an account
	 * that really has watched nothing, and a page whose markup this parser no
	 * longer recognises. Playlist pages have already made exactly that move
	 * once, dropping `playlistVideoRenderer` for `lockupViewModel`, and the
	 * symptom was indistinguishable. These are key names and integers only —
	 * no title, channel, id or anything else about what was watched goes into
	 * the log.
	 */
	private fun shapeReport(html: String): String {
		fun count(needle: String) = html.split(needle).size - 1
		return "empty-feed shape check: videoRenderer=${count("\"videoRenderer\"")} " +
			"lockupViewModel=${count("\"lockupViewModel\"")} " +
			"richItemRenderer=${count("\"richItemRenderer\"")} " +
			"sectionListRenderer=${count("\"sectionListRenderer\"")} " +
			"itemSectionRenderer=${count("\"itemSectionRenderer\"")} " +
			"bytes=${html.length}. Non-zero renderer counts here mean the feed has " +
			"entries this parser did not read; all-zero means the account really " +
			"has nothing recent."
	}

	private data class Page(val body: String, val signedOutRedirect: Boolean)

	/**
	 * The single place the session cookie is used.
	 *
	 * [HISTORY_URL] is a compile-time constant on youtube.com and redirects are
	 * disabled, so there is no code path on which the credential reaches another
	 * host. The host is asserted anyway — a future edit to the constant must
	 * fail closed rather than leak.
	 */
	private suspend fun fetchHistory(): Page? {
		val cookie = vault.secretCookieHeader() ?: return null
		val url = URL(HISTORY_URL)
		if (!url.host.endsWith(".youtube.com") && url.host != "youtube.com") {
			EventLog.append("history", "refusing to attach the session to ${url.host}")
			return null
		}
		return withContext(Dispatchers.IO) {
			runCatching {
				val connection = url.openConnection() as HttpURLConnection
				try {
					connection.apply {
						requestMethod = "GET"
						connectTimeout = TIMEOUT_MS
						readTimeout = TIMEOUT_MS
						instanceFollowRedirects = false
						setRequestProperty("Cookie", cookie)
						// The desktop shell carries ytInitialData; the mobile one
						// serves a different document.
						setRequestProperty("User-Agent", VideoIdResolver.USER_AGENT)
						// Keeps YouTube's own "signed out" / "history is paused"
						// wording in the language the parser recognises.
						setRequestProperty("Accept-Language", "en-US,en;q=0.9")
					}
					val code = connection.responseCode
					if (code in 300..399) {
						return@runCatching Page("", signedOutRedirect = true)
					}
					rotatedCookies(connection)?.let(vault::mergeRotatedCookies)
					if (code != 200) return@runCatching null
					Page(
						connection.inputStream.bufferedReader().readText(),
						signedOutRedirect = false,
					)
				} finally {
					connection.disconnect()
				}
			}.getOrNull()
		}
	}

	/** `Set-Cookie` values, name → value, ignoring attributes and deletions. */
	private fun rotatedCookies(connection: HttpURLConnection): Map<String, String>? {
		val headers = connection.headerFields["Set-Cookie"] ?: return null
		val out = LinkedHashMap<String, String>()
		for (header in headers) {
			val pair = header.substringBefore(';')
			val eq = pair.indexOf('=')
			if (eq <= 0) continue
			val name = pair.substring(0, eq).trim()
			val value = pair.substring(eq + 1).trim()
			// An expiry-in-the-past deletion carries an empty value; keeping the
			// old one would be wrong, but so would writing a blank over it, so
			// the whole session is left for the next real answer to correct.
			if (value.isEmpty() || value == "EXPIRED") continue
			out[name] = value
		}
		return out.takeIf { it.isNotEmpty() }
	}

	internal companion object {
		/**
		 * The account's own name as the page happens to spell it. Tried in order
		 * of how specific each one is; every one of them is optional.
		 */
		private val ACCOUNT_LABEL_PATTERNS = listOf(
			Regex(""""accountName":\{"simpleText":"([^"]{1,80})""""),
			Regex(""""channelHandle":\{"simpleText":"(@[^"]{1,60})""""),
			Regex(""""CHANNEL_HANDLE":"(@[^"]{1,60})""""),
		)

		const val HISTORY_URL = "https://www.youtube.com/feed/history"
		const val INITIAL_DATA = "ytInitialData"
		const val TIMEOUT_MS = 6_000

		/**
		 * Long enough that the pre-resolution and the finalization of one track
		 * do not both pay for a page, short enough that a track which lands in
		 * the feed a few seconds after it starts is still seen.
		 */
		const val CACHE_MS = 15_000L
	}
}
