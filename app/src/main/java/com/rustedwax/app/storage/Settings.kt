package com.rustedwax.app.storage

import android.content.Context
import com.rustedwax.app.scrobble.ScrobbleRules

/**
 * User preferences. Key names match the extension's `options.ts` where the
 * setting is the same one, so a future import/export can map across directly.
 */
class Settings(context: Context) {

	private val prefs =
		context.getSharedPreferences("rustedwax_settings", Context.MODE_PRIVATE)

	/**
	 * Whether the app watches media sessions at all.
	 *
	 * The outer of the two switches, and the stronger one: with this off the
	 * probe is torn down, browser notifications are not read, and nothing is
	 * observed or logged. [autoScrobble] only gates the *last* step of the
	 * pipeline, so it can't answer "stop reading anything".
	 *
	 * Defaults on — a fresh install that has been granted Notification Access
	 * is expected to be watching.
	 */
	var monitoringEnabled: Boolean
		get() = prefs.getBoolean(KEY_MONITORING, true)
		set(value) = prefs.edit().putBoolean(KEY_MONITORING, value).apply()

	/**
	 * Whether to look a video up on youtube.com to improve its metadata.
	 *
	 * Given an id, this fetches richer metadata. When the address bar did not
	 * produce one, the same switch also permits playlist/search/watch-page id
	 * recovery. On by default, but it is off-device traffic from outside the
	 * browser, so it stays a visible switch.
	 */
	var enrichment: Boolean
		get() = prefs.getBoolean(KEY_ENRICH, true)
		set(value) = prefs.edit().putBoolean(KEY_ENRICH, value).apply()

	/** Master switch for automatic scrobbling. Off until the user opts in. */
	var autoScrobble: Boolean
		get() = prefs.getBoolean(KEY_AUTO, false)
		set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

	/**
	 * Whether verified YouTube shorts may scrobble below the 30-second minimum,
	 * down to [ScrobbleRules.SHORT_MIN_DURATION_SECONDS].
	 *
	 * Requires **both** other switches, and not incidentally: the exception is
	 * granted on proof, and each switch supplies half of it. The address-bar
	 * watcher proves the `/shorts/` path; the lookup proves the video exists on
	 * its watch page. With either off there is no proof, so the ordinary
	 * 30-second floor applies and this setting does nothing — which is why the
	 * UI says so rather than showing an unexplained no-op.
	 *
	 * On by default. The floor's own field data showed it rejecting only shorts
	 * and never a watch-path track, so leaving the old behaviour in place would
	 * keep discarding a third of the shorts feed by default.
	 */
	var shortClips: Boolean
		get() = prefs.getBoolean(KEY_SHORT_CLIPS, true)
		set(value) = prefs.edit().putBoolean(KEY_SHORT_CLIPS, value).apply()

	/** `scrobblePercent` upstream — stored as a percentage, used as a fraction. */
	var scrobbleThreshold: Double
		get() = prefs.getInt(KEY_PERCENT, 60).coerceIn(20, 95) / 100.0
		set(value) = prefs.edit()
			.putInt(KEY_PERCENT, (value * 100).toInt().coerceIn(20, 95))
			.apply()

	val thresholdPercent: Int get() = (scrobbleThreshold * 100).toInt()

	private companion object {
		const val KEY_MONITORING = "monitoringEnabled"
		const val KEY_ENRICH = "enrichment"
		const val KEY_AUTO = "autoScrobble"
		const val KEY_SHORT_CLIPS = "shortClipScrobbling"

		/** Same key the extension uses. */
		const val KEY_PERCENT = "scrobblePercent"

		@Suppress("unused")
		val DEFAULT = ScrobbleRules.DEFAULT_THRESHOLD
	}
}
