package com.rustedwax.app.detect

import kotlin.math.floor

/**
 * Pure foreground native-Short lifecycle.
 *
 * Progress is earned only from accepted seekbar deltas. Wall-clock time merely
 * caps a delta; it is never itself credited. Missing proof freezes the baseline
 * and finalizes after a short no-credit grace.
 */
class ForegroundShortTracker {

	data class OrganicObservation(
		val title: String,
		val ownerHandle: String,
		val currentSeconds: Long,
		val totalSeconds: Long,
		val observedAtMillis: Long,
		val sourceEpoch: Long,
	)

	data class AdObservation(
		val signal: String,
		val title: String?,
		val currentSeconds: Long,
		val totalSeconds: Long,
		val observedAtMillis: Long,
		val sourceEpoch: Long,
	)

	data class Update(
		val finalized: List<SessionSnapshot> = emptyList(),
		val active: SessionSnapshot? = null,
		val completeForegroundProof: Boolean = false,
		val diagnostic: String? = null,
	)

	private sealed class Active {
		abstract val currentSeconds: Long
		abstract val totalSeconds: Long
		abstract val observedAtMillis: Long
		abstract val sourceEpoch: Long
		abstract val startedAtEpochSec: Long
		abstract val playedSeconds: Long
		abstract val loopDetected: Boolean
		abstract val frozenForMissingProof: Boolean

		data class Organic(
			val title: String,
			val ownerHandle: String,
			override val currentSeconds: Long,
			override val totalSeconds: Long,
			override val observedAtMillis: Long,
			override val sourceEpoch: Long,
			override val startedAtEpochSec: Long,
			override val playedSeconds: Long = 0,
			override val loopDetected: Boolean = false,
			override val frozenForMissingProof: Boolean = false,
		) : Active()

		data class Ad(
			val signal: String,
			val title: String?,
			override val currentSeconds: Long,
			override val totalSeconds: Long,
			override val observedAtMillis: Long,
			override val sourceEpoch: Long,
			override val startedAtEpochSec: Long,
			override val playedSeconds: Long = 0,
			override val loopDetected: Boolean = false,
			override val frozenForMissingProof: Boolean = false,
		) : Active()
	}

	private var active: Active? = null
	private var missingSinceMillis: Long? = null

	val hasActive: Boolean get() = active != null
	val hasCompleteProof: Boolean get() = active != null && missingSinceMillis == null

	fun observe(observation: OrganicObservation): Update {
		val prior = active
		val same = prior as? Active.Organic
		val keyMatches = same != null &&
			same.title == observation.title &&
			same.ownerHandle == observation.ownerHandle &&
			same.totalSeconds == observation.totalSeconds &&
			same.sourceEpoch == observation.sourceEpoch
		val finalized = if (prior != null && !keyMatches) listOf(snapshot(prior, finalized = true)) else emptyList()
		active = if (keyMatches) {
			advanceOrganic(same, observation)
		} else {
			Active.Organic(
				title = observation.title,
				ownerHandle = observation.ownerHandle,
				currentSeconds = observation.currentSeconds,
				totalSeconds = observation.totalSeconds,
				observedAtMillis = observation.observedAtMillis,
				sourceEpoch = observation.sourceEpoch,
				startedAtEpochSec = observation.observedAtMillis / 1000,
			)
		}
		val measuredPlayed = (active as? Active.Organic)?.playedSeconds ?: 0
		val newlyEarned = if (keyMatches) {
			(measuredPlayed - same.playedSeconds).coerceAtLeast(0)
		} else {
			0
		}
		missingSinceMillis = null
		return Update(
			finalized = finalized,
			active = active?.let { snapshot(it, finalized = false) },
			completeForegroundProof = true,
			diagnostic = when {
				finalized.isNotEmpty() -> "foreground Short identity transitioned to " +
					"\"${observation.title}\" / ${observation.ownerHandle} / ${observation.totalSeconds}s"
				prior == null -> "foreground Short proof acquired: " +
					"\"${observation.title}\" / ${observation.ownerHandle} / " +
					"${observation.currentSeconds}s of ${observation.totalSeconds}s"
				newlyEarned > 0 -> "foreground Short seekbar advanced to " +
					"${observation.currentSeconds}s of ${observation.totalSeconds}s; " +
					"credited ${newlyEarned}s (measured total ${measuredPlayed}s)"
				else -> null
			},
		)
	}

	fun observe(observation: AdObservation): Update {
		val prior = active
		val same = prior as? Active.Ad
		val keyMatches = same != null && same.signal == observation.signal &&
			same.totalSeconds == observation.totalSeconds && same.sourceEpoch == observation.sourceEpoch
		val finalized = if (prior != null && !keyMatches) listOf(snapshot(prior, finalized = true)) else emptyList()
		active = if (keyMatches) {
			advanceAd(same, observation)
		} else {
			Active.Ad(
				signal = observation.signal,
				title = observation.title,
				currentSeconds = observation.currentSeconds,
				totalSeconds = observation.totalSeconds,
				observedAtMillis = observation.observedAtMillis,
				sourceEpoch = observation.sourceEpoch,
				startedAtEpochSec = observation.observedAtMillis / 1000,
			)
		}
		missingSinceMillis = null
		return Update(
			finalized = finalized,
			active = active?.let { snapshot(it, finalized = false) },
			completeForegroundProof = true,
			diagnostic = "literal native Short ad signal: ${observation.signal}",
		)
	}

	/** Freeze immediately; a same-key recovery resets the position baseline. */
	fun proofMissing(nowMillis: Long, reason: String): Update {
		val current = active ?: return Update(diagnostic = reason)
		if (missingSinceMillis == null) missingSinceMillis = nowMillis
		active = when (current) {
			is Active.Organic -> current.copy(frozenForMissingProof = true)
			is Active.Ad -> current.copy(frozenForMissingProof = true)
		}
		return if (nowMillis - (missingSinceMillis ?: nowMillis) >= MISSING_PROOF_GRACE_MS) {
			val ended = snapshot(active ?: current, finalized = true)
			active = null
			missingSinceMillis = null
			Update(
				finalized = listOf(ended),
				diagnostic = "$reason; foreground proof grace expired at the last valid seekbar value",
			)
		} else {
			Update(
				active = snapshot(active ?: current, finalized = false),
				completeForegroundProof = false,
				diagnostic = "$reason; progress frozen during bounded refresh grace",
			)
		}
	}

	/** Stop, opt-out, disconnect and source-epoch changes discard rather than score. */
	fun discard(reason: String): Update {
		active = null
		missingSinceMillis = null
		return Update(diagnostic = "$reason; in-flight foreground Short discarded")
	}

	fun snapshot(): SessionSnapshot? = active?.let { snapshot(it, finalized = false) }

	private fun advanceOrganic(
		current: Active.Organic,
		observation: OrganicObservation,
	): Active.Organic {
		if (observation.observedAtMillis < current.observedAtMillis) return current
		if (current.frozenForMissingProof) {
			return current.copy(
				currentSeconds = observation.currentSeconds,
				observedAtMillis = observation.observedAtMillis,
				frozenForMissingProof = false,
			)
		}
		// Samsung can expose the same cached integer seekbar value across many
		// successful polls, then publish several genuinely traversed seconds at
		// once. An unchanged value earns nothing and must not move the delta's
		// monotonic-time baseline. Explicit scroll/seek UI events freeze/reset the
		// baseline before their successor observation reaches this tracker.
		if (observation.currentSeconds == current.currentSeconds) return current
		val earned = acceptedDelta(
			previous = current.currentSeconds,
			next = observation.currentSeconds,
			total = current.totalSeconds,
			elapsedMillis = observation.observedAtMillis - current.observedAtMillis,
		)
		return current.copy(
			currentSeconds = observation.currentSeconds,
			observedAtMillis = observation.observedAtMillis,
			playedSeconds = current.playedSeconds + earned.seconds,
			loopDetected = current.loopDetected || earned.wrap,
		)
	}

	private fun advanceAd(current: Active.Ad, observation: AdObservation): Active.Ad {
		if (observation.observedAtMillis < current.observedAtMillis) return current
		if (current.frozenForMissingProof) {
			return current.copy(
				currentSeconds = observation.currentSeconds,
				observedAtMillis = observation.observedAtMillis,
				frozenForMissingProof = false,
			)
		}
		if (observation.currentSeconds == current.currentSeconds) return current
		val earned = acceptedDelta(
			current.currentSeconds,
			observation.currentSeconds,
			current.totalSeconds,
			observation.observedAtMillis - current.observedAtMillis,
		)
		return current.copy(
			currentSeconds = observation.currentSeconds,
			observedAtMillis = observation.observedAtMillis,
			playedSeconds = current.playedSeconds + earned.seconds,
			loopDetected = current.loopDetected || earned.wrap,
		)
	}

	private data class Delta(val seconds: Long = 0, val wrap: Boolean = false)

	private fun acceptedDelta(
		previous: Long,
		next: Long,
		total: Long,
		elapsedMillis: Long,
	): Delta {
		if (elapsedMillis < 0 || next == previous) return Delta()
		val maxDelta = floor(elapsedMillis / 1000.0).toLong() + POSITION_JITTER_SECONDS
		if (next > previous) {
			val delta = next - previous
			return if (delta <= maxDelta) Delta(delta) else Delta()
		}
		val wrapped = previous.toDouble() >= total * LOOP_END_FRACTION &&
			next.toDouble() <= total * LOOP_START_FRACTION &&
			previous - next >= total * LOOP_MIN_RESET_FRACTION
		if (!wrapped) return Delta()
		val traversed = (total - previous).coerceAtLeast(0) + next
		return if (traversed in 0..maxDelta) Delta(traversed, wrap = true) else Delta()
	}

	private fun snapshot(state: Active, finalized: Boolean): SessionSnapshot {
		val title: String
		val handle: String?
		val signal: String?
		when (state) {
			is Active.Organic -> {
				title = state.title
				handle = state.ownerHandle
				signal = null
			}
			is Active.Ad -> {
				title = state.title ?: "Native YouTube Short advertisement"
				handle = null
				signal = state.signal
			}
		}
		val playedMs = state.playedSeconds * 1000
		val durationMs = state.totalSeconds * 1000
		val proofMissing = state.frozenForMissingProof
		return SessionSnapshot(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			appLabel = "YouTube Shorts (foreground)",
			isTarget = true,
			title = title,
			artist = handle,
			album = null,
			durationMs = durationMs,
			positionMs = state.currentSeconds * 1000,
			playedMs = playedMs,
			loopDetected = state.loopDetected,
			explicitAdSignal = signal,
			playbackState = when {
				proofMissing -> "FOREGROUND_PROOF_MISSING"
				signal != null -> "FOREGROUND_SHORT_AD"
				else -> "FOREGROUND_SHORT"
			},
			isPlaying = !finalized && !proofMissing,
			percentPlayed = playedMs.toDouble() / durationMs,
			identity = YouTubeProbe.Identity.SiteOnly(
				host = YouTubeProbe.YOUTUBE_PACKAGE,
				isMusic = false,
				source = "foreground native Shorts accessibility player; exact id pending",
			),
			notificationHint = null,
			metadataLines = listOf(
				"sourceProof = NATIVE_FOREGROUND_SHORT",
				"title = $title",
				"ownerHandle = ${handle ?: "<ad observation>"}",
				"seekbar = ${state.currentSeconds}s of ${state.totalSeconds}s",
				"measuredPlayed = ${state.playedSeconds}s (position deltas only)",
				"foregroundProof = ${if (proofMissing) "missing/frozen" else "complete"}",
			),
			trackStartedAtEpochSec = state.startedAtEpochSec,
			sourceEpoch = state.sourceEpoch,
			sourceProof = SourceProof.NATIVE_FOREGROUND_SHORT,
			ownerHandle = handle,
		)
	}

	companion object {
		const val MISSING_PROOF_GRACE_MS = 3_000L
		const val POSITION_JITTER_SECONDS = 2L
		private const val LOOP_END_FRACTION = 0.8
		private const val LOOP_START_FRACTION = 0.2
		private const val LOOP_MIN_RESET_FRACTION = 0.5
	}
}
