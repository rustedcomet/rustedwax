package com.rustedwax.app.storage

import android.content.Context
import android.content.SharedPreferences
import com.rustedwax.app.scrobble.ScrobbleRules

internal interface SettingsStore {
	fun getBoolean(key: String, default: Boolean): Boolean
	fun putBoolean(key: String, value: Boolean)
	fun getInt(key: String, default: Int): Int
	fun putInt(key: String, value: Int)
}

private class SharedPreferencesSettingsStore(
	private val prefs: SharedPreferences,
) : SettingsStore {
	override fun getBoolean(key: String, default: Boolean): Boolean =
		prefs.getBoolean(key, default)

	override fun putBoolean(key: String, value: Boolean) {
		prefs.edit().putBoolean(key, value).apply()
	}

	override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)

	override fun putInt(key: String, value: Int) {
		prefs.edit().putInt(key, value).apply()
	}
}

/**
 * User preferences. Key names match the extension's `options.ts` where the
 * setting is the same one, so a future import/export can map across directly.
 */
class Settings internal constructor(
	private val store: SettingsStore,
) {
	constructor(context: Context) : this(
		SharedPreferencesSettingsStore(
			context.getSharedPreferences("rustedwax_settings", Context.MODE_PRIVATE),
		),
	)

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
		get() = store.getBoolean(KEY_MONITORING, true)
		set(value) = store.putBoolean(KEY_MONITORING, value)

	/**
	 * Whether to look a video up on youtube.com to improve its metadata.
	 *
	 * Given an id, this fetches richer metadata. When the address bar did not
	 * produce one, the same switch also permits playlist/search/watch-page id
	 * recovery. On by default, but it is off-device traffic from outside the
	 * browser, so it stays a visible switch.
	 */
	var enrichment: Boolean
		get() = store.getBoolean(KEY_ENRICH, true)
		set(value) = store.putBoolean(KEY_ENRICH, value)

	/** Master switch for automatic scrobbling. Off until the user opts in. */
	var autoScrobble: Boolean
		get() = store.getBoolean(KEY_AUTO, false)
		set(value) = store.putBoolean(KEY_AUTO, value)

	/** Native YouTube MediaSessions are opt-in and package-scoped. */
	var nativeYouTube: Boolean
		get() = store.getBoolean(KEY_NATIVE_YOUTUBE, false)
		set(value) = store.putBoolean(KEY_NATIVE_YOUTUBE, value)

	/** Native YouTube Music MediaSessions have an independent opt-in. */
	var nativeYouTubeMusic: Boolean
		get() = store.getBoolean(KEY_NATIVE_YOUTUBE_MUSIC, false)
		set(value) = store.putBoolean(KEY_NATIVE_YOUTUBE_MUSIC, value)

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
		get() = store.getBoolean(KEY_SHORT_CLIPS, true)
		set(value) = store.putBoolean(KEY_SHORT_CLIPS, value)

	/**
	 * Whether the signed-in account's watch history may be read to identify a
	 * native track.
	 *
	 * Off until the user signs in, and independently revocable afterwards
	 * without wiping the session — the switch answers "use it", the session
	 * answers "have it". Requires [enrichment] like every other lookup, and
	 * applies only to native YouTube sessions: browsers have the address bar,
	 * and their behaviour is not allowed to change.
	 */
	var watchHistory: Boolean
		get() = store.getBoolean(KEY_WATCH_HISTORY, false)
		set(value) = store.putBoolean(KEY_WATCH_HISTORY, value)

	/**
	 * Whether each accessibility grant has ever been observed as live.
	 *
	 * Android drops a service from `enabled_accessibility_services` when it
	 * crashes, which looks exactly like the user turning it off — and on
	 * 2026-08-05 that happened to the browser watcher and went unnoticed for a
	 * day, with roughly half the day's watch history never reaching the app.
	 * Remembering that a grant was once live is what lets the UI distinguish
	 * "you have not enabled this yet" from "this stopped on its own", which are
	 * the same boolean but very different messages.
	 *
	 * Deliberately one-way: only cleared when the user re-grants and it goes
	 * live again, so a crash cannot quietly reset the evidence of itself.
	 */
	var browserEvidenceEverGranted: Boolean
		get() = store.getBoolean(KEY_BROWSER_EVER_GRANTED, false)
		set(value) = store.putBoolean(KEY_BROWSER_EVER_GRANTED, value)

	/** As [browserEvidenceEverGranted], for the native Shorts observer. */
	var nativeShortsEverGranted: Boolean
		get() = store.getBoolean(KEY_SHORTS_EVER_GRANTED, false)
		set(value) = store.putBoolean(KEY_SHORTS_EVER_GRANTED, value)

	/** `scrobblePercent` upstream — stored as a percentage, used as a fraction. */
	var scrobbleThreshold: Double
		get() = store.getInt(KEY_PERCENT, 60).coerceIn(20, 95) / 100.0
		set(value) = store.putInt(KEY_PERCENT, (value * 100).toInt().coerceIn(20, 95))

	val thresholdPercent: Int get() = (scrobbleThreshold * 100).toInt()

	private companion object {
		const val KEY_MONITORING = "monitoringEnabled"
		const val KEY_ENRICH = "enrichment"
		const val KEY_AUTO = "autoScrobble"
		const val KEY_NATIVE_YOUTUBE = "nativeYouTube"
		const val KEY_NATIVE_YOUTUBE_MUSIC = "nativeYouTubeMusic"
		const val KEY_SHORT_CLIPS = "shortClipScrobbling"
		const val KEY_WATCH_HISTORY = "watchHistoryLookup"
		const val KEY_BROWSER_EVER_GRANTED = "browserEvidenceEverGranted"
		const val KEY_SHORTS_EVER_GRANTED = "nativeShortsEverGranted"

		/** Same key the extension uses. */
		const val KEY_PERCENT = "scrobblePercent"

		@Suppress("unused")
		val DEFAULT = ScrobbleRules.DEFAULT_THRESHOLD
	}
}
