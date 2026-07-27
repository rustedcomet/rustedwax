package com.rustedwax.app.enrich

import com.rustedwax.app.detect.TitleParser
import org.json.JSONArray
import org.json.JSONObject

/**
 * Finds a video id by matching a YouTube search page against what the media
 * session reported. Pure — no network, no Android — so the matching rules are
 * unit-testable against a checked-in fixture.
 *
 * ## Why matching has to use all three signals
 *
 * Measured against the real search page for the reported case (2026-07-25,
 * "Doomed" / "Bring Me The Horizon - Topic", 274 s):
 *
 * | id | length | owner | title |
 * | --- | --- | --- | --- |
 * | `CZFTfYYql4k` | 4:35 | Bring Me The Horizon | Doomed |
 * | `DIEI2YLYg6o` | 4:36 | Maphra - Topic | Doomed |
 *
 * Both are titled exactly "Doomed" and both are within a second or two of the
 * session's duration — the second is a *cover by a different artist*. Title
 * alone or duration alone would have written the wrong link to an immutable
 * chain. The channel is what separates them, so a match requires title **and**
 * channel **and** duration, and anything short of that resolves to nothing.
 *
 * Channels are compared through [TitleParser.cleanChannel] because search
 * lists the owner as "Bring Me The Horizon" while the session and the watch
 * page both say "Bring Me The Horizon - Topic".
 */
object SearchResultsParser {

	data class Candidate(
		val videoId: String,
		val title: String,
		val channel: String?,
		val lengthSeconds: Long?,
	)

	/** Search's displayed length is rounded — the real video was 274 s, listed as 4:35. */
	private const val DURATION_TOLERANCE_SEC = 5L

	private val VIDEO_ID = Regex("""^[A-Za-z0-9_-]{11}$""")

	/**
	 * Pulls every video-like node out of `ytInitialData`.
	 *
	 * Walks the tree looking for objects carrying a `videoId` and a `title`
	 * rather than following a fixed path like
	 * `contents.twoColumnSearchResultsRenderer.…`. The renderer nesting differs
	 * between the desktop and mobile shells and is reorganised freely; the
	 * shape of a result item is the stable part.
	 */
	fun candidates(json: String): List<Candidate> {
		val out = LinkedHashMap<String, Candidate>()
		walk(JSONObject(json)) { node ->
			val id = node.optString("videoId").takeIf { VIDEO_ID.matches(it) } ?: return@walk
			val title = text(node.opt("title")) ?: return@walk
			if (out.containsKey(id)) return@walk
			out[id] = Candidate(
				videoId = id,
				title = title,
				channel = text(node.opt("ownerText"))
					?: text(node.opt("longBylineText"))
					?: text(node.opt("shortBylineText")),
				lengthSeconds = text(node.opt("lengthText"))?.let(::parseClock),
			)
		}
		return out.values.toList()
	}

	/**
	 * The one candidate that is certainly the same video, or null.
	 *
	 * @param durationSec the session's duration; when unknown the duration
	 * check cannot run and no match is returned — an unverifiable match is
	 * exactly the kind this class exists to refuse.
	 */
	fun bestMatch(
		candidates: List<Candidate>,
		title: String,
		channel: String?,
		durationSec: Long?,
	): Candidate? {
		if (durationSec == null || durationSec <= 0) return null
		val wantTitle = normalize(title)
		val wantChannel = channel?.let { normalize(TitleParser.cleanChannel(it).orEmpty()) }
		if (wantTitle.isEmpty() || wantChannel.isNullOrEmpty()) return null

		return candidates.firstOrNull { c ->
			val len = c.lengthSeconds ?: return@firstOrNull false
			normalize(c.title) == wantTitle &&
				normalize(TitleParser.cleanChannel(c.channel.orEmpty()).orEmpty()) == wantChannel &&
				kotlin.math.abs(len - durationSec) <= DURATION_TOLERANCE_SEC
		}
	}

	/** `4:35` → 275, `1:02:33` → 3753. */
	fun parseClock(value: String): Long? {
		val parts = value.trim().split(':')
		if (parts.size !in 2..3) return null
		var total = 0L
		for (p in parts) {
			val n = p.trim().toLongOrNull() ?: return null
			total = total * 60 + n
		}
		return total
	}

	/** Renderer text is either `{simpleText}` or `{runs:[{text}]}`. */
	private fun text(node: Any?): String? {
		val o = node as? JSONObject ?: return null
		o.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
		val runs = o.optJSONArray("runs") ?: return null
		val sb = StringBuilder()
		for (i in 0 until runs.length()) {
			sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
		}
		return sb.toString().takeIf { it.isNotEmpty() }
	}

	private fun walk(node: Any?, visit: (JSONObject) -> Unit) {
		when (node) {
			is JSONObject -> {
				visit(node)
				for (key in node.keys()) walk(node.opt(key), visit)
			}

			is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), visit)
		}
	}

	private fun normalize(value: String): String =
		value.lowercase()
			.replace(Regex("""["'’‘“”]"""), "")
			.replace(Regex("""\s+"""), " ")
			.trim()
}
