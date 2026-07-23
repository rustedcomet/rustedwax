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

	/** Master switch for automatic scrobbling. Off until the user opts in. */
	var autoScrobble: Boolean
		get() = prefs.getBoolean(KEY_AUTO, false)
		set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

	/** `scrobblePercent` upstream — stored as a percentage, used as a fraction. */
	var scrobbleThreshold: Double
		get() = prefs.getInt(KEY_PERCENT, 60).coerceIn(20, 95) / 100.0
		set(value) = prefs.edit()
			.putInt(KEY_PERCENT, (value * 100).toInt().coerceIn(20, 95))
			.apply()

	val thresholdPercent: Int get() = (scrobbleThreshold * 100).toInt()

	private companion object {
		const val KEY_AUTO = "autoScrobble"

		/** Same key the extension uses. */
		const val KEY_PERCENT = "scrobblePercent"

		@Suppress("unused")
		val DEFAULT = ScrobbleRules.DEFAULT_THRESHOLD
	}
}
