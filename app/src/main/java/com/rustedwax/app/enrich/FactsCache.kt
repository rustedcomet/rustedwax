package com.rustedwax.app.enrich

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * On-disk cache of resolved video facts, keyed by video id.
 *
 * Two reasons it isn't optional. The 160% double-listen broadcasts two
 * transactions for one track and would otherwise fetch the page twice, and the
 * Now-tab preview wants facts without being allowed to touch the network. A
 * video's category and credits don't change, so entries never expire — they're
 * only evicted to keep the directory small.
 */
class FactsCache(context: Context, private val maxEntries: Int = 200) {

	private val dir = File(context.filesDir, "enrich").apply { mkdirs() }

	/**
	 * Memory front for the disk store. Exists so the Now-tab preview — which
	 * re-renders every second — can ask for facts without touching disk after
	 * the first hit.
	 */
	private val memory = java.util.concurrent.ConcurrentHashMap<String, VideoFacts>()

	fun get(videoId: String): VideoFacts? {
		memory[videoId]?.let { return it }
		val file = fileFor(videoId) ?: return null
		if (!file.exists()) return null
		return runCatching {
			val o = JSONObject(file.readText())
			VideoFacts(
				videoId = videoId,
				title = o.optStringOrNull("title"),
				author = o.optStringOrNull("author"),
				category = o.optStringOrNull("category"),
				originalArtist = o.optStringOrNull("originalArtist"),
				originalTitle = o.optStringOrNull("originalTitle"),
			)
		}.getOrNull()?.also { memory[it.videoId] = it }
	}

	fun put(facts: VideoFacts) {
		memory[facts.videoId] = facts
		val file = fileFor(facts.videoId) ?: return
		runCatching {
			val o = JSONObject()
				.putOpt("title", facts.title)
				.putOpt("author", facts.author)
				.putOpt("category", facts.category)
				.putOpt("originalArtist", facts.originalArtist)
				.putOpt("originalTitle", facts.originalTitle)
			file.writeText(o.toString())
			evictIfNeeded()
		}
	}

	/**
	 * Video ids are `[A-Za-z0-9_-]{11}`, but this builds a file path, so it is
	 * validated rather than trusted — an id arriving from a scraped address bar
	 * has no business containing a path separator.
	 */
	private fun fileFor(videoId: String): File? =
		if (VIDEO_ID.matches(videoId)) File(dir, "$videoId.json") else null

	private fun evictIfNeeded() {
		val files = dir.listFiles() ?: return
		if (files.size <= maxEntries) return
		files.sortedBy { it.lastModified() }
			.take(files.size - maxEntries)
			.forEach { it.delete() }
	}

	private fun JSONObject.optStringOrNull(key: String): String? =
		if (isNull(key)) null else optString(key).ifBlank { null }

	private companion object {
		val VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")
	}
}
