package com.rustedwax.app.detect

/** Pure bounded coalescing for a diagnostic that may arrive on every UI callback. */
class RepeatedDiagnosticThrottle(
	private val repeatIntervalMs: Long = DEFAULT_REPEAT_INTERVAL_MS,
) {
	private var lastMessage: String? = null
	private var lastEmittedAtMillis: Long = 0

	fun shouldEmit(message: String, nowMillis: Long): Boolean {
		val same = message == lastMessage
		val forwardTime = nowMillis >= lastEmittedAtMillis
		if (same && forwardTime && nowMillis - lastEmittedAtMillis < repeatIntervalMs) {
			return false
		}
		lastMessage = message
		lastEmittedAtMillis = nowMillis
		return true
	}

	fun reset() {
		lastMessage = null
		lastEmittedAtMillis = 0
	}

	companion object {
		const val DEFAULT_REPEAT_INTERVAL_MS = 30_000L
	}
}

/** Stable equivalence key for dynamic native-Short capture diagnostics. */
object NativeShortDiagnosticKey {
	fun of(message: String): String {
		val lifecycle = when {
			"foreground proof grace expired" in message -> "proof-expired"
			"progress frozen during bounded refresh grace" in message -> "proof-frozen"
			else -> "inactive"
		}
		val structural = when {
			"expected exactly one visible Shorts player root; found 0" in message ->
				"no-visible-player-root"
			"expected exactly one visible Shorts player; found 0" in message ->
				"no-visible-player"
			else -> message
		}
		return "$lifecycle|$structural"
	}
}
