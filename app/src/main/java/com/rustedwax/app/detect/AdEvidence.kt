package com.rustedwax.app.detect

import java.util.concurrent.ConcurrentHashMap

/**
 * A recent explicit YouTube ad label bound to a `/shorts/` video id.
 *
 * This store bridges the accessibility service and [SessionProbe]. Evidence is
 * deliberately short-lived: the probe consumes it into the current track, and
 * a later organic viewing of the same public video must not inherit an old ad
 * label merely because YouTube once promoted it.
 */
object AdEvidence {

	data class Evidence(
		val packageName: String,
		val videoId: String,
		val signal: String,
		val atMillis: Long = System.currentTimeMillis(),
	)

	private const val FRESH_MS = 30_000L
	private val byPackage = ConcurrentHashMap<String, Evidence>()

	@Volatile
	var onEvidence: ((Evidence) -> Unit)? = null

	fun put(evidence: Evidence) {
		if (!VIDEO_ID.matches(evidence.videoId) || evidence.signal.isBlank()) return
		val previous = byPackage.put(evidence.packageName, evidence)
		if (previous?.videoId == evidence.videoId && previous.signal == evidence.signal) return
		EventLog.append(
			"ad",
			"${evidence.packageName} → YouTube UI marked ${evidence.videoId} as an ad " +
				"(\"${evidence.signal}\")",
		)
		onEvidence?.invoke(evidence)
	}

	fun get(
		packageName: String,
		videoId: String,
		now: Long = System.currentTimeMillis(),
	): Evidence? = byPackage[packageName]
		?.takeIf { it.videoId == videoId && now - it.atMillis <= FRESH_MS }

	fun clearAll() = byPackage.clear()

	private val VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")
}
