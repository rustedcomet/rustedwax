package com.rustedwax.app.scrobble

import android.content.Context

/**
 * Remembers which tracks have already been scrobbled, so a replay, a service
 * restart or a double finalize can't write the same listen twice.
 *
 * Ported from the `finalized_<startTimestamp>` scheme in `hive-scrobbler.ts`,
 * including the 6-hour prune. Two deliberate changes:
 *
 *  - **Durable, not session-scoped.** The extension used
 *    `browser.storage.session`, which the browser clears on exit. Android kills
 *    and restarts our process freely, so a session-scoped ledger would forget
 *    everything mid-listen and re-scrobble.
 *  - **Keyed by content, not just start time.** Upstream keys music scrobbles on
 *    `startTimestamp` alone, which is fine when one browser owns the timeline.
 *    Here a paused-and-resumed track can produce a new start timestamp for the
 *    same listen, so the key folds in the title.
 */
class DedupLedger(context: Context) {

	private val prefs =
		context.getSharedPreferences("rustedwax_dedup", Context.MODE_PRIVATE)

	/** True if this is the first time we've seen the key; marks it as used. */
	@Synchronized
	fun claim(key: String): Boolean {
		val storageKey = PREFIX + key
		if (prefs.contains(storageKey)) return false
		prefs.edit().putLong(storageKey, System.currentTimeMillis()).apply()
		return true
	}

	@Synchronized
	fun contains(key: String): Boolean = prefs.contains(PREFIX + key)

	/**
	 * Give a claim back.
	 *
	 * Only for the manual-broadcast path, which claims *before* sending so two
	 * quick taps can't both get through, and must undo that if the send fails —
	 * otherwise a network error would permanently block retrying the listen.
	 * The automatic path never releases: a failed send there goes to
	 * [BroadcastQueue], so the listen is still owed.
	 */
	@Synchronized
	fun release(key: String) {
		prefs.edit().remove(PREFIX + key).apply()
	}

	/** Drop entries older than 6 hours, matching upstream's prune window. */
	@Synchronized
	fun prune() {
		val cutoff = System.currentTimeMillis() - RETENTION_MS
		val editor = prefs.edit()
		var removed = 0
		for ((k, v) in prefs.all) {
			if (!k.startsWith(PREFIX)) continue
			val stamp = v as? Long ?: 0L
			if (stamp < cutoff) {
				editor.remove(k)
				removed++
			}
		}
		if (removed > 0) editor.apply()
	}

	companion object {
		private const val PREFIX = "finalized_"
		private const val RETENTION_MS = 6 * 60 * 60 * 1000L

		/**
		 * Key for one listen. Hour-bucketed like upstream's video path, so a
		 * genuine replay more than an hour later scrobbles again but an
		 * immediate repeat of the same finalize does not.
		 */
		fun keyFor(title: String, artist: String?, startedAtEpochSec: Long): String {
			val hourBucket = startedAtEpochSec / 3600
			return "${title.lowercase()}|${artist?.lowercase().orEmpty()}|$hourBucket"
		}
	}
}
