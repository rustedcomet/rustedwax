package com.rustedwax.app.detect

import java.util.concurrent.ConcurrentHashMap

/**
 * What the browser's address bar last said, per browser package.
 *
 * The strongest evidence available: it names the origin exactly instead of
 * inferring it, and when the full URL is exposed it carries the video id —
 * which is the only route to `url` in the payload and to enrichment.
 *
 * Three limits are baked into how this is consumed, not bolted on:
 *
 *  1. It describes the **foreground tab**, which is not necessarily the tab
 *     that's playing. Background-tab audio is normal on YouTube.
 *  2. Screen off means no accessibility events, so it goes stale silently.
 *     Hence [FRESH_MS] — an old reading is discarded, never trusted.
 *  3. It's optional. With the service off this stays empty and identity falls
 *     back to notification hints (decision D6).
 */
object UrlEvidence {

	data class Evidence(
		val host: String?,
		val videoId: String?,
		/** True when the bar showed a `/shorts/` path — shorts are classified more strictly. */
		val isShort: Boolean = false,
		/** Exactly what the address bar contained, for the log. */
		val raw: String,
		val atMillis: Long = System.currentTimeMillis(),
	)

	/**
	 * How long a reading stays usable. Generous enough to survive a track
	 * starting and the screen going off a moment later; short enough that
	 * yesterday's tab can never explain today's playback.
	 */
	private const val FRESH_MS = 5L * 60 * 1000

	private val byPackage = ConcurrentHashMap<String, Evidence>()

	/** Set when the watcher service is connected, purely so the UI can say so. */
	@Volatile
	var watcherConnected: Boolean = false
		private set

	fun setConnected(value: Boolean) {
		watcherConnected = value
		if (!value) byPackage.clear()
	}

	fun put(packageName: String, evidence: Evidence) {
		val previous = byPackage.put(packageName, evidence)
		if (previous == null ||
			previous.host != evidence.host ||
			previous.videoId != evidence.videoId
		) {
			EventLog.append(
				"url",
				"$packageName → host=${evidence.host ?: "?"} " +
					"video=${evidence.videoId ?: "—"}  (\"${evidence.raw}\")",
			)
		}
	}

	fun get(packageName: String, now: Long = System.currentTimeMillis()): Evidence? =
		byPackage[packageName]?.takeIf { now - it.atMillis <= FRESH_MS }

	fun clearAll() = byPackage.clear()
}
