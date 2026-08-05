package com.rustedwax.app.detect

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean

/** Separately disclosed, OS-scoped foreground native YouTube Shorts observer. */
class NativeShortsAccessibilityService : AccessibilityService() {

	private val handler = Handler(Looper.getMainLooper())
	private val captureThread = HandlerThread("rustedwax-native-shorts-capture").apply { start() }
	private val captureHandler = Handler(captureThread.looper)
	private val captureInFlight = AtomicBoolean(false)
	@Volatile private var destroyed = false
	@Volatile private var lastCompleteProofAtMillis = 0L
	private var acquisitionRefreshUntilElapsed = 0L
	private var lastPlaylistPollElapsed = 0L
	private val refresh = object : Runnable {
		override fun run() {
			val now = System.currentTimeMillis()
			if (NativeShortsObserver.shouldRefresh() &&
				lastCompleteProofAtMillis > 0 &&
				now - lastCompleteProofAtMillis >= PROOF_FRESHNESS_MS
			) {
				// AccessibilityNodeInfo binder calls can stall while YouTube hides its
				// overlay. This watchdog stays on the service main thread, so a stalled
				// capture can never extend the no-credit proof grace.
				NativeShortsObserver.missing(
					"fresh foreground Shorts proof expired while capture was unavailable",
					now,
				)
			}
			if (NativeShortsObserver.shouldRefresh() ||
				SystemClock.elapsedRealtime() <= acquisitionRefreshUntilElapsed ||
				playlistAcquisitionDue()
			) {
				requestObservation("bounded foreground refresh")
			}
			handler.postDelayed(this, REFRESH_INTERVAL_MS)
		}
	}

	/**
	 * Poll slowly for the playlist bar while nothing is latched.
	 *
	 * The Shorts observer is event-driven, which suits Shorts: the user scrolls
	 * constantly, so callbacks never stop. Ordinary watch playback is the
	 * opposite — once a song is playing the UI is static and YouTube emits no
	 * accessibility events at all. Measured 2026-08-04: after the service
	 * reconnected, six minutes of playback produced **zero** observations, so
	 * the playlist was never captured and every track fell back to search.
	 *
	 * Once a playlist is latched this stops: the latch survives on its own and
	 * a change of playlist comes with UI activity, which fires events anyway.
	 */
	private fun playlistAcquisitionDue(): Boolean {
		if (NativePlaylistObserver.current() != null) return false
		val now = SystemClock.elapsedRealtime()
		if (now - lastPlaylistPollElapsed < PLAYLIST_POLL_INTERVAL_MS) return false
		lastPlaylistPollElapsed = now
		return true
	}

	override fun onServiceConnected() {
		MonitorSwitch.init(applicationContext)
		NativeSourceSwitches.init(applicationContext)
		NativeShortsObserver.connected()
		EventLog.append(
			"native-shorts",
			"foreground Shorts observer connected; OS package scope is ${YouTubeProbe.YOUTUBE_PACKAGE}",
		)
		handler.removeCallbacks(refresh)
		handler.postDelayed(refresh, REFRESH_INTERVAL_MS)
	}

	override fun onAccessibilityEvent(event: AccessibilityEvent?) {
		if (!eligible()) return
		if (event?.packageName?.toString() != YouTubeProbe.YOUTUBE_PACKAGE) return
		// YouTube can publish its final root/title/seekbar after its last content
		// callback. Retry only this exact foreground package for a short bounded
		// acquisition window; once proven, SessionProbe requests active refreshes.
		acquisitionRefreshUntilElapsed =
			SystemClock.elapsedRealtime() + ACQUISITION_REFRESH_MS
		if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
			NativeShortsObserver.shouldRefresh()
		) {
			NativeShortsObserver.missing(
				"native Shorts scroll/seek event reset the position baseline",
				System.currentTimeMillis(),
			)
		}
		requestObservation("accessibility callback")
	}

	override fun onInterrupt() = Unit

	override fun onDestroy() {
		destroyed = true
		handler.removeCallbacks(refresh)
		acquisitionRefreshUntilElapsed = 0
		lastCompleteProofAtMillis = 0
		captureThread.quitSafely()
		NativeShortsObserver.disconnected("native Shorts accessibility service disconnected")
		NativePlaylistObserver.clear("native accessibility service disconnected")
		NativeSourceSwitches.invalidatePackage(
			YouTubeProbe.YOUTUBE_PACKAGE,
			"native Shorts accessibility disconnected",
		)
		EventLog.append("native-shorts", "foreground Shorts observer disconnected and cleared")
		super.onDestroy()
	}

	private fun eligible(): Boolean = MonitorSwitch.isEnabled &&
		NativeSourceSwitches.acceptsPackage(YouTubeProbe.YOUTUBE_PACKAGE)

	private fun requestObservation(reason: String) {
		if (destroyed || !captureInFlight.compareAndSet(false, true)) return
		captureHandler.post {
			try {
				observeForeground(reason)
			} finally {
				captureInFlight.set(false)
			}
		}
	}

	private fun observeForeground(reason: String) {
		val captureStartedElapsed = SystemClock.elapsedRealtime()
		val now = System.currentTimeMillis()
		if (!eligible()) {
			NativeShortsObserver.missing(
				"observer idle: Monitoring and Native YouTube must both be on",
				now,
			)
			NativePlaylistObserver.clear("Monitoring or Native YouTube switched off")
			return
		}
		val root = rootInActiveWindow
		if (root == null) {
			NativeShortsObserver.missing("no active native YouTube accessibility root", now)
			// Backgrounded or screen off. The latch holds through this — playback
			// continues without any tree at all.
			NativePlaylistObserver.observe(
				NativePlaylistParser.Result.Unobservable(
					"no active native YouTube accessibility root",
				),
				now,
			)
			return
		}
		val tree: NativeShortTree?
		val playlistCapture: NativePlaylistCapture
		try {
			tree = captureTargeted(root)
			playlistCapture = capturePlaylist(root)
		} finally {
			root.recycle()
		}
		// Independent of the Shorts result: the playlist bar belongs to the
		// ordinary watch screen, which is exactly where captureTargeted finds
		// nothing.
		NativePlaylistObserver.observe(NativePlaylistParser.parse(playlistCapture), now)
		if (tree == null) {
			NativeShortsObserver.missing("foreground root was hidden or not native YouTube", now)
			return
		}
		val result = NativeShortParser.parse(tree)
		if (destroyed) return
		if (SystemClock.elapsedRealtime() - captureStartedElapsed > MAX_CAPTURE_AGE_MS) {
			NativeShortsObserver.missing(
				"$reason: accessibility capture exceeded the freshness bound",
				System.currentTimeMillis(),
			)
			return
		}
		if (result is NativeShortParser.Result.Invalid) {
			NativeShortsObserver.missing("$reason: ${result.reason}", now)
		} else {
			lastCompleteProofAtMillis = now
			NativeShortsObserver.parsed(result, now)
		}
	}

	private fun captureTargeted(root: AccessibilityNodeInfo): NativeShortTree? {
		if (!root.isVisibleToUser || root.packageName?.toString() != YouTubeProbe.YOUTUBE_PACKAGE) {
			return null
		}
		val budget = CaptureBudget()
		val children = mutableListOf<NativeShortNode>()
		TARGET_IDS.forEach { viewId ->
			val matches = runCatching { root.findAccessibilityNodeInfosByViewId(viewId) }
				.getOrDefault(emptyList())
			if (matches.size > MAX_MATCHES_PER_ID) budget.exceeded = true
			matches.take(MAX_MATCHES_PER_ID).forEach { match ->
				try {
					captureNode(match, depth = 0, budget = budget)?.let(children::add)
				} finally {
					match.recycle()
				}
			}
			// Nodes outside the bounded retained prefix still belong to this call.
			matches.drop(MAX_MATCHES_PER_ID).forEach(AccessibilityNodeInfo::recycle)
		}
		return NativeShortTree(
			root = NativeShortNode(
				packageName = YouTubeProbe.YOUTUBE_PACKAGE,
				children = children,
			),
			exceededCaptureBudget = budget.exceeded,
		)
	}

	/**
	 * The playlist bar, captured as one container per anchor.
	 *
	 * The anchor is `yt:position`, which is present in both measured layouts —
	 * the collapsed bar under the player and the expanded queue-panel header.
	 * Its *parent* is captured rather than the node itself, because the name and
	 * owner are siblings. Capturing per-container is what stops a queue row's
	 * `yt:title` from being read as the playlist name.
	 */
	private fun capturePlaylist(root: AccessibilityNodeInfo): NativePlaylistCapture {
		val empty = NativePlaylistCapture(
			packageName = null,
			watchScreenPresent = false,
			containers = emptyList(),
		)
		if (!root.isVisibleToUser || root.packageName?.toString() != YouTubeProbe.YOUTUBE_PACKAGE) {
			return empty
		}
		// Absent in the miniplayer, present on every measured watch screen.
		val watchScreenPresent = runCatching {
			root.findAccessibilityNodeInfosByViewId(WATCH_SCREEN_ID)
		}.getOrDefault(emptyList()).let { matches ->
			val present = matches.isNotEmpty()
			matches.forEach(AccessibilityNodeInfo::recycle)
			present
		}
		val budget = CaptureBudget(remaining = MAX_PLAYLIST_NODES)
		val containers = mutableListOf<NativeShortNode>()
		PLAYLIST_ANCHOR_IDS.forEach { viewId ->
			val matches = runCatching { root.findAccessibilityNodeInfosByViewId(viewId) }
				.getOrDefault(emptyList())
			matches.take(MAX_PLAYLIST_ANCHORS).forEach { match ->
				try {
					val parent = runCatching { match.parent }.getOrNull()
					try {
						val container = parent ?: match
						captureNode(container, depth = 0, budget = budget)?.let(containers::add)
					} finally {
						parent?.recycle()
					}
				} finally {
					match.recycle()
				}
			}
			matches.drop(MAX_PLAYLIST_ANCHORS).forEach(AccessibilityNodeInfo::recycle)
		}
		return NativePlaylistCapture(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			watchScreenPresent = watchScreenPresent,
			containers = containers,
			exceededCaptureBudget = budget.exceeded,
		)
	}

	private fun captureNode(
		node: AccessibilityNodeInfo,
		depth: Int,
		budget: CaptureBudget,
	): NativeShortNode? {
		if (depth > MAX_DEPTH || budget.remaining <= 0) {
			budget.exceeded = true
			return null
		}
		budget.remaining--
		val children = mutableListOf<NativeShortNode>()
		for (index in 0 until node.childCount) {
			if (budget.exceeded || budget.remaining <= 0) {
				budget.exceeded = true
				break
			}
			val child = node.getChild(index) ?: continue
			try {
				captureNode(child, depth + 1, budget)?.let(children::add)
			} finally {
				child.recycle()
			}
		}
		return NativeShortNode(
			packageName = node.packageName?.toString(),
			resourceId = node.viewIdResourceName,
			text = node.text?.toString(),
			contentDescription = node.contentDescription?.toString(),
			className = node.className?.toString(),
			visible = node.isVisibleToUser,
			clickable = node.isClickable,
			children = children,
		)
	}

	companion object {
		private const val MAX_DEPTH = 24
		private const val MAX_NODES = 500
		private const val MAX_MATCHES_PER_ID = 4
		private const val REFRESH_INTERVAL_MS = 1_000L
		private const val ACQUISITION_REFRESH_MS = 4_000L

		/** Slow enough to be free, fast enough to catch the bar between tracks. */
		private const val PLAYLIST_POLL_INTERVAL_MS = 5_000L
		private const val PROOF_FRESHNESS_MS = 2_500L
		private const val MAX_CAPTURE_AGE_MS = 2_000L
		private const val ID_PREFIX = "com.google.android.youtube:id/"
		private val TARGET_IDS = listOf(
			ID_PREFIX + "reel_watch_fragment_root",
			ID_PREFIX + "reel_time_bar",
			ID_PREFIX + "comments_panel",
			ID_PREFIX + "comment_sheet",
			ID_PREFIX + "engagement_panel",
		)

		/**
		 * Anchors for the ordinary watch screen's playlist bar. `position` is
		 * present in both measured layouts; `playlist_name` only in the
		 * collapsed one and is kept as a second anchor so the bar is still found
		 * if the position node is ever restructured.
		 */
		private val PLAYLIST_ANCHOR_IDS = listOf(
			ID_PREFIX + "position",
			ID_PREFIX + "playlist_name",
		)
		private const val MAX_PLAYLIST_ANCHORS = 4
		private const val MAX_PLAYLIST_NODES = 150

		/**
		 * Present on every measured watch screen and absent in the miniplayer.
		 * `watch_player` and `player_view` survive into the miniplayer and are
		 * therefore useless here.
		 */
		private const val WATCH_SCREEN_ID = ID_PREFIX + "watch_panel"

		private data class CaptureBudget(var remaining: Int = MAX_NODES, var exceeded: Boolean = false)

		fun isEnabled(context: Context): Boolean {
			// Both halves, and the master switch is not redundant. Replacing the
			// APK turns accessibility off wholesale but leaves this service
			// *named* in the list, so the list alone reports "Granted" for a
			// service Android is no longer sending a single event to. That exact
			// state cost several misleading test runs on 2026-08-04 and is what
			// `PHASE_NATIVE_PLAYLIST_IDENTITY.md` §11.3 warns about — the app was
			// making the same mistake `dumpsys accessibility` does.
			if (Settings.Secure.getInt(context.contentResolver, ACCESSIBILITY_ENABLED, 0) != 1) {
				return false
			}
			val enabled = Settings.Secure.getString(
				context.contentResolver,
				Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
			).orEmpty()
			val target = "${context.packageName}/${NativeShortsAccessibilityService::class.java.name}"
			return enabled.split(':').any { it.equals(target, ignoreCase = true) }
		}

		/** `Settings.Secure.ACCESSIBILITY_ENABLED`, which has no public constant. */
		private const val ACCESSIBILITY_ENABLED = "accessibility_enabled"
	}
}
