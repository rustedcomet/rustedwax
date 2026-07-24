package com.rustedwax.app.detect

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads the browser's address bar, so origin and video id stop being guesses.
 *
 * Optional and off by default (decision D5). With it off nothing here runs and
 * identity falls back to notification hints, exactly as in Phase 3.
 *
 * ## Why an accessibility service
 *
 * Chromium tells the media session nothing about the page (PHASE0 Q3) and the
 * media notification only carries the origin. The address bar is the one place
 * on the device where the actual URL is legible. That buys two things nothing
 * else can:
 *
 *  - **Exact exclusivity.** "This tab is youtube.com" instead of "a
 *    notification from this browser said youtube.com".
 *  - **The video id**, which is the sole route to `url` in the payload and the
 *    precondition for enrichment.
 *
 * ## Scope
 *
 * `accessibility_service_config.xml` pins `packageNames` to the browsers in
 * [YouTubeProbe.TARGET_PACKAGES]. That is enforced by the OS, not by this
 * class: events from any other app are never delivered here at all. The class
 * additionally re-checks the package, and reads exactly one node — the URL bar.
 *
 * ## Known limits
 *
 * The address bar reflects the **foreground tab**, and Chromium on Android
 * often shows only the host rather than the full path. So this may yield a host
 * with no video id, which is still an upgrade on a notification hint. What it
 * actually returns on Brave is logged verbatim on first sight, because it's a
 * measurement this project hasn't made yet — see PHASE4.md Q7.
 */
class UrlWatcherService : AccessibilityService() {

	override fun onServiceConnected() {
		UrlEvidence.setConnected(true)
		EventLog.append("url", "address-bar watcher connected")
	}

	override fun onDestroy() {
		super.onDestroy()
		UrlEvidence.setConnected(false)
		EventLog.append("url", "address-bar watcher stopped")
	}

	override fun onInterrupt() = Unit

	override fun onAccessibilityEvent(event: AccessibilityEvent?) {
		// Same discipline as the notification listener: stopped means stopped.
		if (!MonitorSwitch.isEnabled) return
		val pkg = event?.packageName?.toString() ?: return
		if (pkg !in YouTubeProbe.TARGET_PACKAGES) return

		val root = rootInActiveWindow ?: return
		val raw = try {
			readUrlBar(root, pkg)
		} finally {
			root.recycle()
		}
		if (raw.isNullOrBlank()) return

		UrlEvidence.put(
			pkg,
			UrlEvidence.Evidence(
				host = hostOf(raw),
				videoId = VIDEO_ID.find(raw)?.groupValues?.get(1),
				raw = raw,
			),
		)
	}

	/**
	 * The omnibox, by view id — stable across Chromium forks because Brave
	 * inherits Chrome's layout. Falls back to the first focusable EditText,
	 * which is what the omnibox is when a fork has renamed it.
	 */
	private fun readUrlBar(root: AccessibilityNodeInfo, pkg: String): String? {
		root.findAccessibilityNodeInfosByViewId("$pkg:id/url_bar")
			?.firstOrNull()
			?.let { node ->
				val text = node.text?.toString()
				node.recycle()
				if (!text.isNullOrBlank()) return text
			}
		return firstEditableText(root, depth = 0)
	}

	private fun firstEditableText(node: AccessibilityNodeInfo?, depth: Int): String? {
		if (node == null || depth > MAX_DEPTH) return null
		if (node.className == "android.widget.EditText") {
			node.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
		}
		for (i in 0 until node.childCount) {
			val child = node.getChild(i) ?: continue
			val found = firstEditableText(child, depth + 1)
			child.recycle()
			if (found != null) return found
		}
		return null
	}

	/**
	 * The bar may hold a full URL, a bare host, or a search query. Accept the
	 * first two shapes, reject the rest — same fail-closed rule the rest of
	 * detection follows.
	 */
	private fun hostOf(value: String): String? {
		val v = value.trim()
		if (v.isEmpty() || v.contains(' ')) return null
		return HOST.find(v)?.groupValues?.get(1)?.removePrefix("www.")?.lowercase()
	}

	companion object {
		private const val MAX_DEPTH = 12

		private val VIDEO_ID =
			Regex("""(?:[?&]v=|youtu\.be/|/shorts/)([A-Za-z0-9_-]{11})""")

		private val HOST =
			Regex("""(?:https?://)?((?:[a-z0-9-]+\.)+[a-z]{2,})""", RegexOption.IGNORE_CASE)

		/** Whether the user has enabled this service in system settings. */
		fun isEnabled(context: Context): Boolean {
			val enabled = Settings.Secure.getString(
				context.contentResolver,
				Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
			).orEmpty()
			val target = "${context.packageName}/${UrlWatcherService::class.java.name}"
			return enabled.split(':').any { it.equals(target, ignoreCase = true) }
		}
	}
}
