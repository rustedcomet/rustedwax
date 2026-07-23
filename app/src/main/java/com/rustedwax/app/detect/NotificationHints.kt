package com.rustedwax.app.detect

import java.util.concurrent.ConcurrentHashMap

/**
 * Site hints harvested from browser media notifications.
 *
 * Phase 0 measured that Chromium browsers publish artwork as an embedded
 * *bitmap* and populate no URI keys at all — so the media session alone cannot
 * tell us which site is playing (see PHASE0.md, Q3).
 *
 * The media *notification* is the second source. Chromium renders a
 * media-style notification whose sub-text is the page origin ("youtube.com"),
 * which is exactly the missing piece. This holder is written by
 * [RustedWaxListenerService] and read by [SessionProbe].
 *
 * Scope discipline: only notifications from the browser packages we target are
 * ever inspected, and only the fields describing the playing media. Nothing
 * else is read, stored, or logged.
 */
object NotificationHints {

	data class Hint(
		val host: String?,
		val subText: String?,
		val title: String?,
		val text: String?,
		val atMillis: Long = System.currentTimeMillis(),
	) {
		/** Everything we saw, for the Phase 0 report. */
		fun describe(): String =
			"host=${host ?: "<none>"} subText=${quote(subText)} " +
				"title=${quote(title)} text=${quote(text)}"

		private fun quote(s: String?) = if (s == null) "<none>" else "\"$s\""
	}

	private val byPackage = ConcurrentHashMap<String, Hint>()

	/**
	 * Notified when a hint lands, so the probe can re-evaluate an identity it
	 * already decided. Ordering is not guaranteed: Phase 0 measured the media
	 * session's metadata callback beating the notification by ~300 ms, so the
	 * first verdict for a track is routinely made blind.
	 */
	@Volatile
	var onHint: ((packageName: String) -> Unit)? = null

	fun put(packageName: String, hint: Hint) {
		val previous = byPackage.put(packageName, hint)
		if (previous?.host != hint.host) {
			onHint?.invoke(packageName)
		}
	}

	fun get(packageName: String): Hint? = byPackage[packageName]

	fun clear(packageName: String) {
		byPackage.remove(packageName)
	}
}
