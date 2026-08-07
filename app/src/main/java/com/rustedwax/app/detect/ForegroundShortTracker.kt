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
		val title: String?,
		val ownerHandle: String,
		val currentSeconds: Long,
		val totalSeconds: Long,
		val observedAtMillis: Long,
		val sourceEpoch: Long,
	)

	/**
	 * A proven, named Short with no progress reading to advance from.
	 *
	 * @param playing the paired audio + visible-window evidence, which is the
	 * only thing that can move this Short's clock. False credits nothing.
	 */
	data class UnmeasuredObservation(
		val title: String?,
		val ownerHandle: String,
		val observedAtMillis: Long,
		val sourceEpoch: Long,
		val playing: Boolean,
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

		/**
		 * The current loss of proof is specifically the measured PiP signature.
		 *
		 * Strictly narrower than [frozenForMissingProof], which is true for every
		 * refusal including the ordinary ones a swipe produces. Accumulates while
		 * frozen so an unrelated refusal landing between two PiP polls cannot
		 * erase it, and is cleared the moment a real observation lands.
		 */
		abstract val progressSurfaceLost: Boolean

		/**
		 * Wall-clock credited by [PipPlaybackInference] while the seekbar was
		 * gone. Kept apart from [playedSeconds] all the way to the snapshot so a
		 * listen can always say how much of it was measured and how much
		 * inferred.
		 *
		 * Banked *here* rather than read back off the live inference, which is
		 * discarded the moment the seekbar returns. Deriving it from that object
		 * zeroed the total on the next refusal, so a Short that spent time in PiP
		 * and then came back finalized as though it never had.
		 */
		abstract val inferredMillis: Long

		data class Organic(
			val title: String?,
			val ownerHandle: String,
			override val currentSeconds: Long,
			override val totalSeconds: Long,
			override val observedAtMillis: Long,
			override val sourceEpoch: Long,
			override val startedAtEpochSec: Long,
			override val playedSeconds: Long = 0,
			override val loopDetected: Boolean = false,
			override val frozenForMissingProof: Boolean = false,
			override val progressSurfaceLost: Boolean = false,
			override val inferredMillis: Long = 0,
			/**
			 * This viewing has already been banked as a complete listen.
			 *
			 * A Short only used to end when something took it away — a swipe, a
			 * track change, the player vanishing. Left alone it loops, so it
			 * accumulated forever and banked nothing: measured 2026-08-06, a 105s
			 * Short reached `measured total 461s` across four loops and never once
			 * finalized, so it never scrobbled at all.
			 *
			 * Reaching its own length *is* the end of a listen, so that is where
			 * it finalizes. One-way, so a Short left looping banks exactly one
			 * listen — which is all `ScrobbleRules.capForKind` would allow anyway.
			 */
			val bankedFullListen: Boolean = false,
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
			override val progressSurfaceLost: Boolean = false,
			override val inferredMillis: Long = 0,
		) : Active()
	}

	private var active: Active? = null
	private var missingSinceMillis: Long? = null

	/**
	 * What the Short that just ended had earned, in case it comes straight back.
	 *
	 * Measured 2026-08-07: the owner scrolled Shorts while switching between the
	 * Home and Shorts tabs, and **nothing scrobbled for 42 minutes**. Each switch
	 * takes the player away for longer than the 3-second grace, so the Short
	 * finalized; each switch back re-acquired the *same* Short and started it
	 * again from zero. One 32-second Short was watched across three switches and
	 * finalized at `0s`, `3s` and `5s` — never once reaching the threshold it had
	 * long since earned in total.
	 *
	 * The MediaSession path solved this years earlier with a continuation window.
	 * This is the same idea: a Short that returns with the same identity within
	 * [RESUME_WINDOW_MS] resumes what it had, rather than starting over. Merging
	 * two genuinely separate viewings of one Short is harmless — the dedup ledger
	 * already caps a video to one scrobble.
	 */
	private data class Interrupted(
		val title: String?,
		val ownerHandle: String,
		val totalSeconds: Long,
		val sourceEpoch: Long,
		val playedSeconds: Long,
		val inferredMillis: Long,
		val loopDetected: Boolean,
		val bankedFullListen: Boolean,
		val atMillis: Long,
	)

	private var interrupted: Interrupted? = null

	/** Progress to resume for a Short that has just come back, or null. */
	private fun resumeFor(
		title: String?,
		ownerHandle: String,
		totalSeconds: Long,
		sourceEpoch: Long,
		nowMillis: Long,
	): Interrupted? {
		val prior = interrupted ?: return null
		if (nowMillis - prior.atMillis > RESUME_WINDOW_MS || nowMillis < prior.atMillis) return null
		if (prior.ownerHandle != ownerHandle || prior.sourceEpoch != sourceEpoch) return null
		if (prior.title != title) return null
		// A length learned since, or lost since, is still the same Short.
		if (prior.totalSeconds != 0L && totalSeconds != 0L && prior.totalSeconds != totalSeconds) {
			return null
		}
		return prior
	}

	private fun remember(state: Active, nowMillis: Long) {
		val organic = state as? Active.Organic ?: return
		interrupted = Interrupted(
			title = organic.title,
			ownerHandle = organic.ownerHandle,
			totalSeconds = organic.totalSeconds,
			sourceEpoch = organic.sourceEpoch,
			playedSeconds = organic.playedSeconds,
			inferredMillis = organic.inferredMillis,
			loopDetected = organic.loopDetected,
			bankedFullListen = organic.bankedFullListen,
			atMillis = nowMillis,
		)
	}

	/**
	 * Live only while the current Short's progress surface is gone.
	 *
	 * Per-Short by construction: it is created on the first PiP observation and
	 * discarded whenever the seekbar returns, the track changes, or the Short
	 * ends. It must never outlive one Short — its duration cap is that Short's.
	 */
	private var inference: PipPlaybackInference? = null

	val hasActive: Boolean get() = active != null
	val hasCompleteProof: Boolean get() = active != null && missingSinceMillis == null

	fun observe(observation: OrganicObservation): Update {
		val prior = active
		val same = prior as? Active.Organic
		val keyMatches = same != null &&
			same.title == observation.title &&
			same.ownerHandle == observation.ownerHandle &&
			// A Short that started without a seekbar has no length yet. The bar
			// appearing mid-viewing teaches it one; it does not make it a
			// different Short, and finalizing here would split one listen in two.
			(same.totalSeconds == observation.totalSeconds || same.totalSeconds == 0L) &&
			same.sourceEpoch == observation.sourceEpoch
		val finalized = if (prior != null && !keyMatches) listOf(snapshot(prior, finalized = true)) else emptyList()
		active = if (keyMatches) {
			advanceOrganic(same, observation)
		} else {
			val resumed = resumeFor(
				observation.title,
				observation.ownerHandle,
				observation.totalSeconds,
				observation.sourceEpoch,
				observation.observedAtMillis,
			)
			Active.Organic(
				title = observation.title,
				ownerHandle = observation.ownerHandle,
				currentSeconds = observation.currentSeconds,
				totalSeconds = observation.totalSeconds,
				observedAtMillis = observation.observedAtMillis,
				sourceEpoch = observation.sourceEpoch,
				startedAtEpochSec = observation.observedAtMillis / 1000,
				playedSeconds = resumed?.playedSeconds ?: 0,
				inferredMillis = resumed?.inferredMillis ?: 0,
				loopDetected = resumed?.loopDetected ?: false,
				bankedFullListen = resumed?.bankedFullListen ?: false,
			)
		}
		val measuredPlayed = (active as? Active.Organic)?.playedSeconds ?: 0
		val newlyEarned = if (keyMatches) {
			(measuredPlayed - same.playedSeconds).coerceAtLeast(0)
		} else {
			0
		}
		missingSinceMillis = null
		// A real seekbar reading is in hand, so there is nothing left to infer.
		inference = null

		// A Short that has played its whole length has finished a listen, even
		// though it is still on screen looping. Bank it now rather than waiting
		// for something to take it away, which may never happen.
		val organic = active as? Active.Organic
		val completed = if (organic != null && !organic.bankedFullListen &&
			organic.totalSeconds > 0 &&
			organic.playedSeconds + organic.inferredMillis / 1000 >= organic.totalSeconds
		) {
			active = organic.copy(bankedFullListen = true)
			listOf(snapshot(organic, finalized = true))
		} else {
			emptyList()
		}

		return Update(
			finalized = finalized + completed,
			active = active?.let { snapshot(it, finalized = false) },
			completeForegroundProof = true,
			diagnostic = when {
				completed.isNotEmpty() -> "foreground Short completed a full listen of " +
					"${organic?.totalSeconds}s while still on screen; banked it rather than " +
					"waiting for the loop to end"
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

	/**
	 * A Short that is proven and playing but publishes no progress at all.
	 *
	 * Measured 2026-08-06 late: YouTube stopped rendering the Shorts seekbar, so
	 * 47 of 71 Shorts in 85 minutes could never be *started* and therefore could
	 * never accrue anything. This starts them; the wall-clock inference then
	 * credits exactly as it does for picture-in-picture, on the same evidence,
	 * and every second of it is reported as inferred.
	 *
	 * The length is unknown here — it came from the seekbar — so nothing caps the
	 * accrual live. The cap is applied where the length is actually known: at
	 * finalize, against the duration the resolver read off the video's own page.
	 */
	fun observe(observation: UnmeasuredObservation): Update {
		val prior = active
		val same = prior as? Active.Organic
		val keyMatches = same != null &&
			same.title == observation.title &&
			same.ownerHandle == observation.ownerHandle &&
			same.sourceEpoch == observation.sourceEpoch
		val finalized = if (prior != null && !keyMatches) {
			listOf(snapshot(prior, finalized = true))
		} else {
			emptyList()
		}
		val current = if (keyMatches) {
			same
		} else {
			// A fresh Short — unless it is the one that just went away, in which
			// case it resumes what it had earned.
			inference = null
			val resumed = resumeFor(
				observation.title,
				observation.ownerHandle,
				totalSeconds = 0,
				sourceEpoch = observation.sourceEpoch,
				nowMillis = observation.observedAtMillis,
			)
			Active.Organic(
				title = observation.title,
				ownerHandle = observation.ownerHandle,
				currentSeconds = 0,
				totalSeconds = resumed?.totalSeconds ?: 0,
				observedAtMillis = observation.observedAtMillis,
				sourceEpoch = observation.sourceEpoch,
				startedAtEpochSec = observation.observedAtMillis / 1000,
				playedSeconds = resumed?.playedSeconds ?: 0,
				inferredMillis = resumed?.inferredMillis ?: 0,
				loopDetected = resumed?.loopDetected ?: false,
				bankedFullListen = resumed?.bankedFullListen ?: false,
			)
		}
		var credited = 0L
		if (observation.playing) {
			val running = inference ?: PipPlaybackInference(
				// A cap of zero would credit nothing, which is the bug being
				// fixed; no cap at all would let a Short left looping invent
				// hours. Until the real length is known, the format's own maximum
				// is the honest ceiling — and the moment a seekbar appears, the
				// video's own length replaces it.
				durationMs = if (current.totalSeconds > 0) {
					current.totalSeconds * 1000
				} else {
					MAX_SHORT_DURATION_MS
				},
				measuredMs = current.playedSeconds * 1000 + current.inferredMillis,
			).also { inference = it }
			credited = running.observe(observation.observedAtMillis, playing = true)
		} else {
			inference?.observe(observation.observedAtMillis, playing = false)
		}
		val inferredMillis = current.inferredMillis + credited
		active = current.copy(
			observedAtMillis = observation.observedAtMillis,
			frozenForMissingProof = false,
			progressSurfaceLost = true,
			inferredMillis = inferredMillis,
		)
		missingSinceMillis = null

		// Nothing more can be earned, so the listen is over — bank it now rather
		// than waiting for something to take it away. Measured 2026-08-07: an
		// untitled, seekbar-less Short sat active for **seven minutes**, hit its
		// ceiling at three, and only finalized when the next Short replaced it.
		// By then the account had watched enough other Shorts that this one had
		// fallen out of the recent-history window identity needs, so a full
		// listen was measured and then could not be named.
		val organic = active as? Active.Organic
		val banked = if (organic != null && !organic.bankedFullListen &&
			inference?.exhausted == true
		) {
			active = organic.copy(bankedFullListen = true)
			listOf(snapshot(organic, finalized = true))
		} else {
			emptyList()
		}

		return Update(
			finalized = finalized + banked,
			active = active?.let { snapshot(it, finalized = false) },
			completeForegroundProof = true,
			diagnostic = when {
				banked.isNotEmpty() -> "foreground Short with no seekbar reached the most that " +
					"can be inferred for one (${inferredMillis / 1000}s); banked it now rather " +
					"than holding it open until something replaces it"
				finalized.isNotEmpty() || prior == null ->
					"foreground Short proof acquired without a seekbar: " +
						"${observation.title?.let { "\"$it\"" } ?: "no readable title"} / " +
						"${observation.ownerHandle} — YouTube is not rendering the progress " +
						"bar, so time is inferred"
				credited > 0 -> "foreground Short has no seekbar; credited ${credited}ms of " +
					"inferred wall-clock (inferred total ${inferredMillis / 1000}s)"
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
		// A real seekbar reading is in hand, so there is nothing left to infer.
		inference = null
		return Update(
			finalized = finalized,
			active = active?.let { snapshot(it, finalized = false) },
			completeForegroundProof = true,
			diagnostic = "literal native Short ad signal: ${observation.signal}",
		)
	}

	/**
	 * Freeze immediately; a same-key recovery resets the position baseline.
	 *
	 * @param progressSurfaceLost this refusal is the measured PiP signature
	 * ([NativeShortParser.Result.Invalid.progressSurfaceLost]), not merely a
	 * refusal. Only this distinguishes "playing but unmeasurable" from "no longer
	 * on screen", and only it may reach [SessionSnapshot.foregroundProgressLost].
	 */
	fun proofMissing(
		nowMillis: Long,
		reason: String,
		progressSurfaceLost: Boolean = false,
		inferredPlaying: Boolean = false,
	): Update {
		val current = active ?: return Update(diagnostic = reason)
		if (missingSinceMillis == null) missingSinceMillis = nowMillis
		val surfaceLost = current.progressSurfaceLost || progressSurfaceLost
		// Inference is admissible only for the exact PiP signature. A swipe, a
		// blown capture budget or a hidden root are not "playing, unmeasurable" —
		// they are "gone" — and crediting wall-clock through them would turn
		// every scroll into watch time.
		val inferring = surfaceLost && inferredPlaying
		var credited = 0L
		if (inferring) {
			val running = inference ?: PipPlaybackInference(
				durationMs = current.totalSeconds * 1000,
				// Everything already accounted for: the frozen seekbar reading
				// plus anything banked by an earlier PiP stretch of this same
				// Short. Keeps measured + inferred <= duration across a
				// PiP → fullscreen → PiP round trip.
				measuredMs = current.playedSeconds * 1000 + current.inferredMillis,
			).also { inference = it }
			credited = running.observe(nowMillis, playing = true)
		} else if (progressSurfaceLost) {
			// This capture *is* the PiP signature and the evidence still said not
			// playing, so it is a genuine pause: drop the anchor and let the grace
			// run as usual.
			inference?.observe(nowMillis, playing = false)
		}
		// Any other refusal — the 1s freshness watchdog, a stale capture, a
		// scroll reset — carries no evidence about playback either way, so it
		// must leave the anchor alone. Treating those as pauses is what made the
		// first device run credit 0ms on every tick: the watchdog fires once a
		// second while the surface is gone, so the anchor was dropped and
		// re-taken between every pair of real observations and no interval ever
		// closed.
		val inferredMillis = current.inferredMillis + credited
		active = when (current) {
			is Active.Organic -> current.copy(
				frozenForMissingProof = true,
				progressSurfaceLost = surfaceLost,
				inferredMillis = inferredMillis,
			)
			is Active.Ad -> current.copy(
				frozenForMissingProof = true,
				progressSurfaceLost = surfaceLost,
				inferredMillis = inferredMillis,
			)
		}
		// While the inference is live the Short has not gone anywhere, so the
		// no-credit grace must not run out underneath it. Without this the 3s
		// grace finalizes a Short two polls into a PiP session and the credit is
		// never able to accumulate at all.
		// Holding the grace open is only honest while there is still something to
		// earn. Once the cap is reached the listen is complete, so the ordinary
		// grace has to run and finalize it — otherwise a Short left in
		// picture-in-picture accrues to its full length and then never ends,
		// which is a scrobble lost rather than gained.
		if (inferring && inference?.exhausted != true) {
			missingSinceMillis = nowMillis
			return Update(
				active = snapshot(active ?: current, finalized = false),
				completeForegroundProof = false,
				diagnostic = "$reason; Short still playing in picture-in-picture — " +
					"credited ${credited}ms of inferred wall-clock " +
					"(inferred total ${inferredMillis / 1000}s, measured ${current.playedSeconds}s)",
			)
		}
		return if (nowMillis - (missingSinceMillis ?: nowMillis) >= MISSING_PROOF_GRACE_MS) {
			val ended = snapshot(active ?: current, finalized = true)
			// The commonest reason a Short's player goes away is that the user
			// switched tabs, and the commonest thing they do next is switch back.
			remember(active ?: current, nowMillis)
			active = null
			missingSinceMillis = null
			inference = null
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
		inference = null
		return Update(diagnostic = "$reason; in-flight foreground Short discarded")
	}

	fun snapshot(): SessionSnapshot? = active?.let { snapshot(it, finalized = false) }

	private fun advanceOrganic(
		current: Active.Organic,
		observation: OrganicObservation,
	): Active.Organic {
		if (observation.observedAtMillis < current.observedAtMillis) return current
		// The seekbar appearing on a Short that started without one supplies the
		// length for the first time. Adopt it before anything uses it, so the
		// completion cap and the percentage are computed against a real duration.
		if (current.totalSeconds == 0L && observation.totalSeconds > 0) {
			return current.copy(
				currentSeconds = observation.currentSeconds,
				totalSeconds = observation.totalSeconds,
				observedAtMillis = observation.observedAtMillis,
				frozenForMissingProof = false,
				progressSurfaceLost = false,
			)
		}
		if (current.frozenForMissingProof) {
			// A readable seekbar is back, so whatever took it away is over. Field
			// §4.3: returning from PiP restores it within ~5s and it survives a
			// swipe to the next Short, so this really is recovery, not a blip.
			return current.copy(
				currentSeconds = observation.currentSeconds,
				observedAtMillis = observation.observedAtMillis,
				frozenForMissingProof = false,
				progressSurfaceLost = false,
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
				progressSurfaceLost = false,
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
		// Nullable: an unreadable footer title no longer sinks the listen, it just
		// leaves identity to the watch-history route at finalize.
		val title: String?
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
		val measuredMs = state.playedSeconds * 1000
		val inferredMs = state.inferredMillis
		val playedMs = measuredMs + inferredMs
		// Null, not zero, when YouTube rendered no seekbar to read a length from.
		// Absence of evidence has to stay absence all the way down: the rules
		// already know how to recover a length from the watch page, and the
		// corroborator must not read a zero as a contradicted duration.
		val durationMs = (state.totalSeconds * 1000).takeIf { it > 0 }
		val proofMissing = state.frozenForMissingProof
		// Deliberately *not* `proofMissing`. Every Short that ends is frozen for
		// missing proof first — scrolling to the next one included — so wiring
		// the marker to that put "(progress surface lost …)" on essentially every
		// finalize and made the honest PiP message meaningless (§3.1).
		val surfaceLost = state.progressSurfaceLost
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
			foregroundProgressLost = surfaceLost,
			inferredPlayedMs = inferredMs,
			// Unknown until the resolver reads a length off the video's own page.
			percentPlayed = durationMs?.let { playedMs.toDouble() / it } ?: 0.0,
			identity = YouTubeProbe.Identity.SiteOnly(
				host = YouTubeProbe.YOUTUBE_PACKAGE,
				isMusic = false,
				source = "foreground native Shorts accessibility player; exact id pending",
			),
			notificationHint = null,
			metadataLines = listOfNotNull(
				"sourceProof = NATIVE_FOREGROUND_SHORT",
				"title = ${title ?: "<unread; resolved from watch history>"}",
				"ownerHandle = ${handle ?: "<ad observation>"}",
				"seekbar = ${state.currentSeconds}s of ${state.totalSeconds}s",
				"measuredPlayed = ${state.playedSeconds}s (position deltas only)",
				"foregroundProof = ${if (proofMissing) "missing/frozen" else "complete"}",
				"progressSurface = lost (seekbar container present, no readable time)"
					.takeIf { surfaceLost },
				("inferredPlayed = ${state.inferredMillis / 1000}s (picture-in-picture wall-clock, " +
					"YouTube window visible + media audio started, assumed 1x)")
					.takeIf { state.inferredMillis > 0 },
			),
			trackStartedAtEpochSec = state.startedAtEpochSec,
			sourceEpoch = state.sourceEpoch,
			sourceProof = SourceProof.NATIVE_FOREGROUND_SHORT,
			ownerHandle = handle,
		)
	}

	companion object {
		const val MISSING_PROOF_GRACE_MS = 3_000L

		/**
		 * How long a Short that has just gone away may come back and resume.
		 *
		 * A tab switch away and back measured 7–14 seconds; thirty gives that
		 * room without letting an unrelated re-encounter half a minute later
		 * inherit someone else's seconds.
		 */
		const val RESUME_WINDOW_MS = 30_000L

		/**
		 * The longest a Short can be, and therefore the most that may be inferred
		 * for one whose length YouTube never rendered. Three minutes is the
		 * format's own published maximum.
		 */
		const val MAX_SHORT_DURATION_MS = 180_000L
		const val POSITION_JITTER_SECONDS = 2L
		private const val LOOP_END_FRACTION = 0.8
		private const val LOOP_START_FRACTION = 0.2
		private const val LOOP_MIN_RESET_FRACTION = 0.5
	}
}
