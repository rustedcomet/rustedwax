package com.rustedwax.app.hive

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The scrobble payload, ported verbatim from the extension's
 * `src/core/scrobbler/hive/hive.types.ts`.
 *
 * Field names are the on-chain contract with the indexers — do not rename,
 * reorder meaningfully, or "improve" them. Absent fields are omitted entirely
 * rather than serialized as null, matching the extension's behaviour of only
 * assigning properties it actually has.
 *
 * v1 populates the music-side fields only; the movie/episode fields exist in
 * the upstream type but are unreachable without DOM access (see PHASE0.md).
 */
data class HiveScrobblePayload(
	val kind: String = KIND_VIDEO,
	val title: String,
	val timestamp: String,
	val artist: String? = null,
	val album: String? = null,
	/** Formatted `m:ss`, as the extension writes it. */
	val duration: String? = null,
	val percentPlayed: Int? = null,
	val platform: String? = null,
	val url: String? = null,
	val app: String = APP_NAME,
) {
	/** YouTube listens are invalid without the canonical link the indexer renders. */
	val hasRequiredYouTubeUrl: Boolean
		get() = platform != PLATFORM_YOUTUBE || url?.let(YOUTUBE_WATCH_URL::matches) == true

	/**
	 * Compact JSON, key order matching the extension's assignment order so
	 * on-chain payloads from phone and desktop look identical.
	 */
	fun toJson(): String {
		// JSONObject does not preserve insertion order, so build the string
		// directly — byte-identical output is worth the small amount of manual
		// work, and it keeps the dhive golden vector meaningful.
		val sb = StringBuilder("{")
		fun field(name: String, value: String, quote: Boolean = true) {
			if (sb.length > 1) sb.append(',')
			sb.append(quoteJson(name)).append(':')
			if (quote) sb.append(quoteJson(value)) else sb.append(value)
		}
		field("app", app)
		field("kind", kind)
		field("title", title)
		field("timestamp", timestamp)
		artist?.let { field("artist", it) }
		album?.let { field("album", it) }
		duration?.let { field("duration", it) }
		percentPlayed?.let { field("percent_played", it.toString(), quote = false) }
		platform?.let { field("platform", it) }
		url?.let { field("url", it) }
		sb.append('}')
		return sb.toString()
	}

	companion object {
		/**
		 * Applies the same hyperlink invariant to serialized retry-queue entries,
		 * including entries created by an older build that allowed a missing URL.
		 */
		fun serializedHasRequiredYouTubeUrl(json: String): Boolean = runCatching {
			val payload = JSONObject(json)
			if (payload.optString("platform") != PLATFORM_YOUTUBE) return@runCatching true
			YOUTUBE_WATCH_URL.matches(payload.optString("url"))
		}.getOrDefault(false)

		/**
		 * JSON string escaping matching JavaScript's `JSON.stringify`.
		 *
		 * Hand-rolled on purpose. Android's bundled `org.json` escapes forward
		 * slashes (`hivescrobblesai\/1.0`) while `JSON.stringify` does not, so
		 * using `JSONObject.quote` produced payloads that differed byte-for-byte
		 * from the extension's — and the JVM `org.json` used in unit tests
		 * doesn't reproduce that behaviour, so the tests passed while the device
		 * wrote something else. Observed on-chain, 2026-07-23.
		 */
		fun quoteJson(value: String): String {
			val sb = StringBuilder(value.length + 2)
			sb.append('"')
			for (c in value) {
				when (c) {
					'"' -> sb.append("\\\"")
					'\\' -> sb.append("\\\\")
					'\n' -> sb.append("\\n")
					'\r' -> sb.append("\\r")
					'\t' -> sb.append("\\t")
					'\b' -> sb.append("\\b")
					'' -> sb.append("\\f")
					else -> if (c < ' ') {
						sb.append("\\u%04x".format(c.code))
					} else {
						sb.append(c)
					}
				}
			}
			sb.append('"')
			return sb.toString()
		}

		/** `custom_json` id. The indexers filter on this exact string. */
		const val CUSTOM_JSON_ID = "hive_scrobble_ai"
		const val APP_NAME = "hivescrobblesai/1.0"

		const val KIND_SONG = "song"
		const val KIND_VIDEO = "video"
		const val KIND_PODCAST = "podcast"
		const val PLATFORM_YOUTUBE = "youtube"
		private val YOUTUBE_WATCH_URL =
			Regex("""^https://www\.youtube\.com/watch\?v=[A-Za-z0-9_-]{11}$""")

		/** ISO-8601 UTC, matching `new Date(...).toISOString()` in the extension. */
		fun isoTimestamp(epochSeconds: Long): String {
			val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
			fmt.timeZone = TimeZone.getTimeZone("UTC")
			return fmt.format(Date(epochSeconds * 1000))
		}

		/** `m:ss`, matching the extension's duration formatting. */
		fun formatDuration(totalSeconds: Long): String =
			"%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
	}
}
