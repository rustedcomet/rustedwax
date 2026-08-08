package com.rustedwax.app.detect

/**
 * Refuses transient cross-page accessibility snapshots.
 *
 * YouTube can briefly publish an outgoing footer beside an incoming seekbar.
 * A complete structural parse is therefore necessary but not sufficient at an
 * identity boundary: the identity-bearing fields must remain identical across
 * a short observation interval. Position is deliberately excluded from the
 * key because it is expected to advance.
 */
class NativeShortStabilizer {

	sealed interface Decision {
		data class Waiting(val reason: String) : Decision
		data class Accepted(val result: NativeShortParser.Result) : Decision
	}

	private data class Candidate(
		val key: Key,
		val firstSeenAtMillis: Long,
		val accepted: Boolean = false,
	)

	private sealed interface Key {
		data class Organic(
			val title: String?,
			val ownerHandle: String,
			val totalSeconds: Long,
		) : Key

		data class Ad(
			val signal: String,
			val title: String?,
			val totalSeconds: Long,
		) : Key
	}

	private var candidate: Candidate? = null

	@Synchronized
	fun observe(result: NativeShortParser.Result, observedAtMillis: Long): Decision {
		val key = when (result) {
			is NativeShortParser.Result.Organic -> Key.Organic(
				result.title,
				result.ownerHandle,
				result.totalSeconds,
			)
			// Stabilized on the two fields it has. Its length is not known here
			// at all, so it cannot take part in the key — which is the point:
			// the same Short must not re-key itself when the seekbar appears or
			// disappears mid-viewing.
			is NativeShortParser.Result.OrganicUnmeasured -> Key.Organic(
				result.title,
				result.ownerHandle,
				totalSeconds = 0,
			)
			is NativeShortParser.Result.Ad -> Key.Ad(
				result.signal,
				result.title,
				result.totalSeconds,
			)
			is NativeShortParser.Result.Invalid -> {
				reset()
				return Decision.Accepted(result)
			}
		}
		val prior = candidate
		if (prior == null || prior.key != key || observedAtMillis < prior.firstSeenAtMillis) {
			candidate = Candidate(key, observedAtMillis)
			return Decision.Waiting("foreground Short identity is stabilizing across accessibility frames")
		}
		if (!prior.accepted && observedAtMillis - prior.firstSeenAtMillis < STABILITY_MS) {
			return Decision.Waiting("foreground Short identity is stabilizing across accessibility frames")
		}
		candidate = prior.copy(accepted = true)
		return Decision.Accepted(result)
	}

	@Synchronized
	fun reset() {
		candidate = null
	}

	companion object {
		const val STABILITY_MS = 750L
	}
}
