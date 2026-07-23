package com.rustedwax.app.detect

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.rustedwax.app.scrobble.ScrobbleEngine

/**
 * Notification listener — and, since Phase 3, the host for detection itself.
 *
 * Three jobs:
 *
 *  1. Hold the Notification Access grant. [android.media.session.MediaSessionManager.getActiveSessions]
 *     requires the caller to name an *enabled* listener component; that grant is
 *     Android's gate on reading other apps' media sessions.
 *
 *  2. Harvest the page origin from browser media notifications. Phase 0 found
 *     Chromium publishes no URI metadata on the media session (PHASE0.md, Q3),
 *     so the notification's sub-text — which Chromium sets to the origin, e.g.
 *     "youtube.com" — is our remaining way to know which site is playing.
 *
 *  3. **Run the probe and the scrobble engine.** The plan called for a
 *     foreground service, but that turned out to be the wrong tool: a
 *     NotificationListenerService is already bound and kept alive by the system
 *     for as long as the grant is held. Hosting detection here means no
 *     persistent notification, no `foregroundServiceType` wrangling, and none
 *     of Android 15's data-sync runtime caps — and it's how established
 *     scrobblers do it. The trade is that lifetime is the system's call, so
 *     [onListenerConnected] must be able to (re)build everything from scratch.
 *
 * Scope discipline: only notifications from [YouTubeProbe.TARGET_PACKAGES] are
 * inspected. Every other app's notifications are ignored on the first line of
 * the callback and never read, stored, or logged.
 */
class RustedWaxListenerService : NotificationListenerService() {

	private var probe: SessionProbe? = null

	override fun onListenerConnected() {
		Log.i(TAG, "Notification listener connected — media sessions readable")
		EventLog.init(applicationContext)
		EventLog.append("listener", "connected")

		ScrobbleEngine.init(applicationContext)

		// Rebuilt on every connect: the system can tear this service down and
		// bring it back at will, and a stale probe would hold dead controllers.
		probe?.stop()
		probe = SessionProbe(applicationContext).also { p ->
			p.onTrackFinalized = ScrobbleEngine::onTrackFinalized
			p.start()
			ProbeHolder.set(p)
		}

		// Anything stranded by an earlier offline spell gets another go.
		ScrobbleEngine.flushQueue()
	}

	override fun onListenerDisconnected() {
		Log.w(TAG, "Notification listener disconnected")
		EventLog.append("listener", "disconnected")
		probe?.stop()
		probe = null
		ProbeHolder.set(null)
	}

	override fun onNotificationPosted(sbn: StatusBarNotification?) {
		val pkg = sbn?.packageName ?: return
		if (pkg !in YouTubeProbe.TARGET_PACKAGES) return

		val extras = sbn.notification?.extras ?: return
		// Only a media-style notification describes playback. Anything else
		// from the browser (downloads, tab reminders) is none of our business.
		if (!extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return

		val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
		val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
		val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

		val hint = NotificationHints.Hint(
			host = hostOf(subText) ?: hostOf(text),
			subText = subText,
			title = title,
			text = text,
		)
		NotificationHints.put(pkg, hint)
		EventLog.append("notification", "$pkg → ${hint.describe()}")
	}

	override fun onNotificationRemoved(sbn: StatusBarNotification?) {
		val pkg = sbn?.packageName ?: return
		if (pkg in YouTubeProbe.TARGET_PACKAGES) {
			NotificationHints.clear(pkg)
		}
	}

	/**
	 * Pull a hostname out of whatever Chromium put in the field. It may be a
	 * bare origin ("youtube.com"), a full URL, or free text — accept the first
	 * two, reject the rest rather than guessing.
	 */
	private fun hostOf(value: String?): String? {
		val v = value?.trim().orEmpty()
		if (v.isEmpty()) return null
		HOST.find(v)?.let { return it.groupValues[1].removePrefix("www.").lowercase() }
		return null
	}

	companion object {
		private const val TAG = "RustedWaxListener"

		/** Matches a bare domain or the host part of a URL. */
		private val HOST =
			Regex("""(?:https?://)?((?:[a-z0-9-]+\.)+[a-z]{2,})""", RegexOption.IGNORE_CASE)
	}
}
