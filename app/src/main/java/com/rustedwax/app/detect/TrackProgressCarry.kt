package com.rustedwax.app.detect

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Play time rescued from a media session that vanished while its track kept
 * playing.
 *
 * ## The bug this exists for
 *
 * Chrome destroys and recreates its `MediaSession` mid-video — around ad breaks
 * and playlist transitions. Each recreation is a new `sessionToken`, so
 * [SessionProbe] built a fresh `Watch` with `playedMs` back at zero, and every
 * fragment was scored against the 60% threshold on its own. Observed 2026-07-30,
 * `LUNA`, 196 s long:
 *
 * ```
 * 11:45:05  [finalize] LUNA — played 47s of 196s   → 24%, skipped
 * 11:45:50  [finalize] LUNA — played 24s of 196s   → 12%, skipped
 * 11:47:33  [finalize] LUNA — played 85s of 196s   → 43%, skipped
 * ```
 *
 * 47 + 24 + 85 = **156 s of 196 = 80% watched**, and it produced no entry. The
 * playback positions show the video never actually stopped — `pos=47039ms` at
 * the start of the second fragment, `pos=70769ms` at the start of the third
 * (47 + 24 = 71). Only our accumulator restarted.
 *
 * ## Why a separate object
 *
 * `Watch` owns a `MediaController` and can't be unit-tested. The rules worth
 * pinning — how progress is keyed, when it expires, that it is consumed exactly
 * once — are pure, so they live here where a test can reach them.
 */
object TrackProgressCarry {

	/**
	 * @param fastestSpeedSeen carried too, so a track watched at 2× before the
	 * teardown still reports the rate it was actually watched at
	 */
	data class Progress(
		val playedMs: Long,
		val trackStartedAtEpochSec: Long,
		val fastestSpeedSeen: Double,
		val atMillis: Long,
		/** Position at teardown, used to recognise an end-to-start replacement. */
		val lastPositionMs: Long? = null,
		/** Once observed, the loop signal must survive later session churn. */
		val loopDetected: Boolean = false,
		/** Identity frozen while this fragment was still the active track. */
		val identity: YouTubeProbe.Identity? = null,
		/** Exact visible YouTube ad label, once bound to this track. */
		val explicitAdSignal: String? = null,
		/** Fresh successful root scan, bound to the same token/signature. */
		val accessibilityCoverage: MediaSessionAccessibilityEvidence.Coverage? = null,
		/** Unique ordinary-watch track token; carried only with this signature. */
		val trackInstanceToken: Long? = null,
	)

	/**
	 * How long a vanished track's progress stays claimable.
	 *
	 * The observed gaps between a session disappearing and its replacement
	 * appearing were 18 and 21 seconds, so this is generous. It is bounded at all
	 * because progress must not survive long enough to attach itself to a
	 * genuine, separate viewing of the same video later on.
	 */
	const val TTL_MS = 60_000L

	private data class Stored(
		val token: Long,
		val progress: Progress,
		val trackIdentity: TrackIdentity? = null,
	)

	private val carried = ConcurrentHashMap<String, Stored>()
	private val nextToken = AtomicLong(1)

	/**
	 * Hold this track's progress for whatever session picks it up next.
	 *
	 * Only called when a session disappears on its own. A user Stop deliberately
	 * discards the in-flight track (decision D2), and carrying it would smuggle
	 * that discarded time into the next session.
	 */
	fun remember(packageName: String, trackKey: String, progress: Progress): Long? {
		if (trackKey.isBlank()) return null
		// A free-form key carries no independently proven source item id. Keep
		// this compatibility overload browser-only so no future native caller can
		// bypass the semantic overload's exact-id invariant.
		if (YouTubeProbe.isNativePackage(packageName)) return null
		prune(progress.atMillis)
		val token = nextToken.getAndIncrement()
		carried[keyFor(packageName, trackKey)] = Stored(token, progress)
		return token
	}

	/** Semantic variant used by SessionProbe so duration refinements keep time. */
	fun remember(
		packageName: String,
		trackIdentity: TrackIdentity,
		progress: Progress,
	): Long? {
		if (!trackIdentity.isUsable) return null
		if (YouTubeProbe.isNativePackage(packageName) && !trackIdentity.hasExactSourceItemId) {
			return null
		}
		prune(progress.atMillis)
		val token = nextToken.getAndIncrement()
		carried[keyFor(packageName, trackIdentity.semanticKey)] =
			Stored(token, progress, trackIdentity)
		return token
	}

	/**
	 * Claim progress for a track that has just reappeared, or null.
	 *
	 * **Consumed on read.** Two sessions must never both inherit the same play
	 * time, and a stale entry must not be applied twice to one track.
	 */
	fun claim(
		packageName: String,
		trackKey: String,
		now: Long = System.currentTimeMillis(),
	): Progress? {
		if (trackKey.isBlank()) return null
		if (YouTubeProbe.isNativePackage(packageName)) return null
		val stored = carried.remove(keyFor(packageName, trackKey)) ?: return null
		return stored.progress.takeIf { now - it.atMillis <= TTL_MS }
	}

	/**
	 * Claim only when the replacement metadata is the same semantic track. A
	 * material duration conflict shares the stable key but fails this predicate.
	 */
	fun claim(
		packageName: String,
		trackIdentity: TrackIdentity,
		now: Long = System.currentTimeMillis(),
	): Progress? {
		if (!trackIdentity.isUsable) return null
		if (YouTubeProbe.isNativePackage(packageName) && !trackIdentity.hasExactSourceItemId) {
			return null
		}
		val key = keyFor(packageName, trackIdentity.semanticKey)
		val stored = carried[key] ?: return null
		if (now - stored.progress.atMillis > TTL_MS) {
			carried.remove(key, stored)
			return null
		}
		val remembered = stored.trackIdentity ?: return null
		if (!remembered.sameTrackAs(trackIdentity)) return null
		return if (carried.remove(key, stored)) stored.progress else null
	}

	/**
	 * Take a continuation that no replacement claimed before its grace period.
	 *
	 * The token prevents an older delayed callback from consuming a newer
	 * continuation for the same metadata key.
	 */
	fun expire(
		packageName: String,
		trackKey: String,
		token: Long,
		now: Long = System.currentTimeMillis(),
	): Progress? {
		val key = keyFor(packageName, trackKey)
		val stored = carried[key] ?: return null
		if (stored.token != token || now - stored.progress.atMillis < TTL_MS) return null
		return if (carried.remove(key, stored)) stored.progress else null
	}

	fun expire(
		packageName: String,
		trackIdentity: TrackIdentity,
		token: Long,
		now: Long = System.currentTimeMillis(),
	): Progress? = expire(packageName, trackIdentity.semanticKey, token, now)

	/** Cancel one pending continuation without touching a newer replacement. */
	fun cancel(packageName: String, trackKey: String, token: Long) {
		val key = keyFor(packageName, trackKey)
		val stored = carried[key] ?: return
		if (stored.token == token) carried.remove(key, stored)
	}

	fun cancel(packageName: String, trackIdentity: TrackIdentity, token: Long) =
		cancel(packageName, trackIdentity.semanticKey, token)

	/** Monitoring stopped — nothing observed before it should leak past it. */
	fun clear() = carried.clear()

	/** Package opt-out/teardown must not disturb another source package. */
	fun clearPackage(packageName: String) {
		val prefix = "$packageName|"
		carried.keys.removeAll { it.startsWith(prefix) }
	}

	/** Visible for tests. */
	fun size(): Int = carried.size

	private fun keyFor(packageName: String, trackKey: String) = "$packageName|$trackKey"

	private fun prune(now: Long) {
		carried.entries.removeAll { now - it.value.progress.atMillis > TTL_MS }
	}
}
