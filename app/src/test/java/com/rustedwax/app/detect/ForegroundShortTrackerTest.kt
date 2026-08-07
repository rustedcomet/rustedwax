package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundShortTrackerTest {

	/**
	 * Measured 2026-08-06 late: YouTube stopped rendering the Shorts progress bar
	 * entirely, so 47 of 71 Shorts in 85 minutes credited nothing — not because
	 * the inference refused, but because a Short with no reading could never be
	 * *started*, and `proofMissing` returns immediately when nothing is active.
	 */
	@Test
	fun `a Short with no seekbar still starts and accrues inferred time`() {
		val tracker = ForegroundShortTracker()
		val first = tracker.observe(unmeasured(at = 0))
		assertTrue(tracker.hasActive)
		assertEquals(0, first.active!!.playedMs)
		// Nothing is measured, so the length is unknown and quoted as such.
		assertNull(first.active!!.durationMs)

		var update = first
		for (second in 1..10) {
			update = tracker.observe(unmeasured(at = second * 1_000L))
		}
		val played = update.active!!.playedMs
		assertTrue("expected wall-clock to accrue, got ${played}ms", played >= 9_000)
		assertEquals("every second of it is inferred", played, update.active!!.inferredPlayedMs)
	}

	@Test
	fun `a Short with no seekbar credits nothing while the evidence says paused`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(unmeasured(at = 0, playing = false))
		var update = tracker.observe(unmeasured(at = 5_000, playing = false))
		update = tracker.observe(unmeasured(at = 10_000, playing = false))
		assertEquals(0, update.active!!.playedMs)
	}

	/** The seekbar coming back mid-viewing must not restart or double-count it. */
	@Test
	fun `a returning seekbar continues the same Short`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(unmeasured(at = 0))
		val inferred = tracker.observe(unmeasured(at = 6_000)).active!!.playedMs
		assertTrue(inferred > 0)
		val measured = tracker.observe(organic(position = 6, at = 7_000, total = 60))
		assertTrue("the same Short continues", measured.finalized.isEmpty())
		assertEquals(60_000L, measured.active!!.durationMs)
	}

	/**
	 * Measured 2026-08-07: the owner scrolled Shorts while switching between the
	 * Home and Shorts tabs and **nothing scrobbled for 42 minutes**. Each switch
	 * outlasts the 3-second grace, so the Short finalized; each switch back
	 * re-acquired the same Short from zero. One 32-second Short finalized at
	 * `0s`, `3s` and `5s` across three switches, never reaching a threshold it
	 * had long since earned in total.
	 */
	@Test
	fun `a Short interrupted by a tab switch resumes what it earned`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 32, at = 0))
		tracker.observe(organic(position = 12, total = 32, at = 12_000))

		// The player goes away; the grace expires and it finalizes at 12s.
		tracker.proofMissing(13_000, "player root gone")
		val ended = tracker.proofMissing(
			13_000 + ForegroundShortTracker.MISSING_PROOF_GRACE_MS,
			"player root gone",
		)
		assertEquals(12_000, ended.finalized.single().playedMs)
		assertFalse(tracker.hasActive)

		// Back within the window, same Short: it continues rather than restarting.
		val back = tracker.observe(organic(position = 12, total = 32, at = 22_000))
		assertEquals(12_000, back.active!!.playedMs)
		assertEquals(20_000, tracker.observe(organic(position = 20, total = 32, at = 30_000)).active!!.playedMs)
	}

	@Test
	fun `a different Short does not inherit the interrupted one's seconds`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(title = "A", handle = "@a", position = 0, total = 32, at = 0))
		tracker.observe(organic(title = "A", handle = "@a", position = 20, total = 32, at = 20_000))
		tracker.proofMissing(21_000, "gone")
		tracker.proofMissing(21_000 + ForegroundShortTracker.MISSING_PROOF_GRACE_MS, "gone")

		val other = tracker.observe(
			organic(title = "B", handle = "@b", position = 0, total = 32, at = 26_000),
		)
		assertEquals(0, other.active!!.playedMs)
	}

	@Test
	fun `coming back long after the interruption starts over`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 32, at = 0))
		tracker.observe(organic(position = 20, total = 32, at = 20_000))
		tracker.proofMissing(21_000, "gone")
		tracker.proofMissing(21_000 + ForegroundShortTracker.MISSING_PROOF_GRACE_MS, "gone")

		val late = tracker.observe(
			organic(position = 0, total = 32, at = 25_000 + ForegroundShortTracker.RESUME_WINDOW_MS),
		)
		assertEquals(0, late.active!!.playedMs)
	}

	@Test
	fun `only sequential seekbar deltas earn progress`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, at = 0))
		assertEquals(4_000, tracker.observe(organic(position = 4, at = 4_000)).active!!.playedMs)
		assertEquals(4_000, tracker.observe(organic(position = 4, at = 14_000)).active!!.playedMs)
		assertEquals(4_000, tracker.observe(organic(position = 30, at = 15_000)).active!!.playedMs)
		assertEquals(4_000, tracker.observe(organic(position = 8, at = 16_000)).active!!.playedMs)
		assertEquals(6_000, tracker.observe(organic(position = 10, at = 18_000)).active!!.playedMs)
	}

	@Test
	fun `unchanged accessibility polls do not erase the sparse delta time bound`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, at = 0))
		tracker.observe(organic(position = 0, at = 2_000))
		tracker.observe(organic(position = 0, at = 4_000))
		assertEquals(5_000, tracker.observe(organic(position = 5, at = 5_000)).active!!.playedMs)
	}

	@Test
	fun `strict end to start wrap earns only traversed seconds and marks loop`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 18, total = 20, at = 0))
		val snapshot = tracker.observe(organic(position = 1, total = 20, at = 3_000)).active!!
		assertEquals(3_000, snapshot.playedMs)
		assertTrue(snapshot.loopDetected)
	}

	@Test
	fun `ordinary rewind and implausible wrap earn nothing`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 15, total = 20, at = 0))
		assertEquals(0, tracker.observe(organic(position = 5, total = 20, at = 10_000)).active!!.playedMs)
		val implausible = ForegroundShortTracker().apply {
			observe(organic(position = 19, total = 20, at = 0))
		}.observe(organic(position = 3, total = 20, at = 100)).active!!
		assertEquals(0, implausible.playedMs)
		assertFalse(implausible.loopDetected)
	}

	@Test
	fun `organic ad organic transitions isolate progress and ad evidence`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(title = "A", handle = "@a_owner", position = 0, at = 0))
		tracker.observe(organic(title = "A", handle = "@a_owner", position = 5, at = 5_000))
		val toAd = tracker.observe(ad(position = 0, at = 6_000))
		assertEquals("A", toAd.finalized.single().title)
		assertEquals(5_000, toAd.finalized.single().playedMs)
		assertEquals("Sponsored", toAd.active!!.explicitAdSignal)

		val toB = tracker.observe(organic(title = "B", handle = "@b_owner", position = 0, at = 7_000))
		assertEquals("Sponsored", toB.finalized.single().explicitAdSignal)
		assertNull(toB.active!!.explicitAdSignal)
		assertEquals("B", toB.active!!.title)
		assertEquals(0, toB.active!!.playedMs)
	}

	@Test
	fun `rapid organic swipes finalize each exact key at its own value`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(title = "A", handle = "@a_owner", position = 3, at = 0))
		val b = tracker.observe(organic(title = "B", handle = "@b_owner", position = 8, at = 100))
		val c = tracker.observe(organic(title = "C", handle = "@c_owner", position = 1, at = 200))
		assertEquals("A", b.finalized.single().title)
		assertEquals(0, b.finalized.single().playedMs)
		assertEquals("B", c.finalized.single().title)
		assertEquals(0, c.finalized.single().playedMs)
	}

	@Test
	fun `temporary proof loss freezes and recovery resets the baseline`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 2, at = 0))
		tracker.observe(organic(position = 7, at = 5_000))
		val missing = tracker.proofMissing(6_000, "tree missing")
		assertEquals(5_000, missing.active!!.playedMs)
		assertFalse(missing.completeForegroundProof)
		assertEquals(5_000, tracker.observe(organic(position = 15, at = 7_000)).active!!.playedMs)
		assertEquals(7_000, tracker.observe(organic(position = 17, at = 9_000)).active!!.playedMs)
	}

	@Test
	fun `PiP proof disappearance finalizes after grace with no gap credit`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, at = 0))
		tracker.observe(organic(position = 12, at = 12_000))
		tracker.proofMissing(13_000, "PiP removed structural fields")
		val ended = tracker.proofMissing(
			13_000 + ForegroundShortTracker.MISSING_PROOF_GRACE_MS,
			"PiP removed structural fields",
		)
		assertEquals(12_000, ended.finalized.single().playedMs)
		assertNull(ended.active)
		assertFalse(tracker.hasActive)
	}

	@Test
	fun `only the lost-surface signature marks a finalize as unmeasurable`() {
		// The shipped regression (FIELD §3.1): the marker was wired to
		// frozenForMissingProof, which is true whenever a Short ends — scrolling
		// away included — so "(progress surface lost …)" was appended to
		// essentially every Short finalize.
		val ordinary = ForegroundShortTracker()
		ordinary.observe(organic(position = 0, at = 0))
		ordinary.observe(organic(position = 12, at = 12_000))
		ordinary.proofMissing(13_000, "swiped to the next Short")
		val swipedAway = ordinary.proofMissing(
			13_000 + ForegroundShortTracker.MISSING_PROOF_GRACE_MS,
			"swiped to the next Short",
		)
		assertFalse(swipedAway.finalized.single().foregroundProgressLost)

		val pip = ForegroundShortTracker()
		pip.observe(organic(position = 0, at = 0))
		pip.observe(organic(position = 12, at = 12_000))
		pip.proofMissing(13_000, "one seekbar container, no readable time", progressSurfaceLost = true)
		val wentToPip = pip.proofMissing(
			13_000 + ForegroundShortTracker.MISSING_PROOF_GRACE_MS,
			"one seekbar container, no readable time",
			progressSurfaceLost = true,
		)
		val ended = wentToPip.finalized.single()
		assertTrue(ended.foregroundProgressLost)
		// Still a refusal to claim time, not a claim of zero: the last measured
		// value stands and the gap earns nothing.
		assertEquals(12_000, ended.playedMs)
	}

	@Test
	fun `an unrelated refusal between two PiP polls does not erase the marker`() {
		// The capture watchdog and the scroll/seek reset both raise Missing with
		// their own reasons. One landing mid-PiP must not turn an unmeasurable
		// finalize back into an apparent 0%.
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, at = 0))
		tracker.observe(organic(position = 9, at = 9_000))
		tracker.proofMissing(10_000, "no readable time", progressSurfaceLost = true)
		tracker.proofMissing(10_500, "fresh foreground Shorts proof expired")
		val ended = tracker.proofMissing(
			10_000 + ForegroundShortTracker.MISSING_PROOF_GRACE_MS,
			"fresh foreground Shorts proof expired",
		)
		assertTrue(ended.finalized.single().foregroundProgressLost)
	}

	@Test
	fun `returning from PiP clears the marker so the finalize is honest again`() {
		// FIELD §4.3, measured: the seekbar is restored within ~5s of returning
		// to fullscreen and survives a swipe to the next Short. A Short that came
		// back and was measured to the end must not be reported as unmeasurable.
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, at = 0))
		tracker.observe(organic(position = 9, at = 9_000))
		tracker.proofMissing(10_000, "no readable time", progressSurfaceLost = true)
		val recovered = tracker.observe(organic(position = 11, at = 11_000)).active!!
		assertFalse(recovered.foregroundProgressLost)
		val ended = tracker.observe(organic(title = "Next", position = 1, at = 13_000))
		assertFalse(ended.finalized.single().foregroundProgressLost)
	}

	@Test
	fun `a Short still playing in PiP accrues inferred time instead of finalizing`() {
		// Before this, the 3s no-credit grace finalized a Short two polls into a
		// PiP session, so PiP was always worth exactly nothing.
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 60, at = 0))
		tracker.observe(organic(position = 20, total = 60, at = 20_000))

		var now = 20_000L
		repeat(20) {
			now += 1_000
			val update = tracker.proofMissing(
				now,
				"one seekbar container, no readable time",
				progressSurfaceLost = true,
				inferredPlaying = true,
			)
			assertTrue("must not finalize while still playing", update.finalized.isEmpty())
		}
		val active = tracker.snapshot()!!
		// 20s measured from the seekbar + ~19s of inferred wall-clock.
		assertEquals(19_000, active.inferredPlayedMs)
		assertEquals(39_000, active.playedMs)
		assertTrue(active.foregroundProgressLost)
	}

	@Test
	fun `the freshness watchdog interleaving does not stop inferred credit`() {
		// Measured on the device: while the surface is gone the 1s freshness
		// watchdog raises its own Missing with no playback evidence, so real
		// observations arrive interleaved with generic ones. Treating a generic
		// refusal as a pause dropped the anchor between every pair of real ticks
		// and credited 0ms forever.
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 179, at = 0))
		tracker.observe(organic(position = 44, total = 179, at = 44_000))
		var now = 44_000L
		repeat(10) {
			now += 500
			tracker.proofMissing(now, "fresh foreground Shorts proof expired")
			now += 500
			tracker.proofMissing(
				now,
				"one seekbar container, no readable time",
				progressSurfaceLost = true,
				inferredPlaying = true,
			)
		}
		val active = tracker.snapshot()!!
		assertEquals(9_000, active.inferredPlayedMs)
		assertEquals(53_000, active.playedMs)
	}

	@Test
	fun `a fully credited PiP Short finalizes instead of hanging forever`() {
		// The bug this pins: holding the end-of-track grace open while inferring
		// meant a Short left playing in picture-in-picture accrued to its full
		// length and then never ended. Measured 2026-08-06 — it sat at "inferred
		// total 25s" climbing, with no finalize and no scrobble, so the inference
		// that was meant to rescue the listen was losing it instead.
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 20, at = 0))
		tracker.observe(organic(position = 1, total = 20, at = 1_000))
		var now = 1_000L
		var finalized: SessionSnapshot? = null
		repeat(120) {
			now += 1_000
			val update = tracker.proofMissing(
				now,
				"one seekbar container, no readable time",
				progressSurfaceLost = true,
				inferredPlaying = true,
			)
			update.finalized.firstOrNull()?.let { if (finalized == null) finalized = it }
		}
		val ended = requireNotNull(finalized) { "a capped PiP Short must finalize" }
		// The whole Short: 1s from the seekbar plus 19s inferred, never more.
		assertEquals(20_000, ended.playedMs)
		assertEquals(19_000, ended.inferredPlayedMs)
		assertFalse(tracker.hasActive)
	}

	@Test
	fun `inferred time stops the moment the evidence stops`() {
		// Audio paused, or YouTube's window gone: the grace resumes and the
		// Short finalizes with only what it had earned.
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 60, at = 0))
		tracker.observe(organic(position = 10, total = 60, at = 10_000))
		tracker.proofMissing(11_000, "pip", progressSurfaceLost = true, inferredPlaying = true)
		tracker.proofMissing(12_000, "pip", progressSurfaceLost = true, inferredPlaying = true)

		// Evidence stops. Grace runs from here and expires as it always did.
		tracker.proofMissing(13_000, "pip", progressSurfaceLost = true, inferredPlaying = false)
		val ended = tracker.proofMissing(
			13_000 + ForegroundShortTracker.MISSING_PROOF_GRACE_MS,
			"pip",
			progressSurfaceLost = true,
			inferredPlaying = false,
		).finalized.single()
		assertEquals(1_000, ended.inferredPlayedMs)
		assertEquals(11_000, ended.playedMs)
	}

	@Test
	fun `only the PiP signature may accrue inferred time`() {
		// A swipe, a blown budget or a hidden root mean the Short is gone, not
		// unmeasurable. Crediting through those would turn scrolling into watch
		// time — the single most dangerous way this could go wrong.
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 60, at = 0))
		tracker.observe(organic(position = 10, total = 60, at = 10_000))
		tracker.proofMissing(11_000, "swiped away", progressSurfaceLost = false, inferredPlaying = true)
		val ended = tracker.proofMissing(
			11_000 + ForegroundShortTracker.MISSING_PROOF_GRACE_MS,
			"swiped away",
			progressSurfaceLost = false,
			inferredPlaying = true,
		).finalized.single()
		assertEquals(0, ended.inferredPlayedMs)
		assertEquals(10_000, ended.playedMs)
	}

	@Test
	fun `returning from PiP keeps the inferred time and resumes measuring`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 60, at = 0))
		tracker.observe(organic(position = 10, total = 60, at = 10_000))
		tracker.proofMissing(11_000, "pip", progressSurfaceLost = true, inferredPlaying = true)
		tracker.proofMissing(12_000, "pip", progressSurfaceLost = true, inferredPlaying = true)
		tracker.proofMissing(13_000, "pip", progressSurfaceLost = true, inferredPlaying = true)

		// Back to fullscreen: the seekbar is readable again (§4.3).
		val back = tracker.observe(organic(position = 13, total = 60, at = 13_500)).active!!
		assertFalse(back.foregroundProgressLost)
		// The 2s already inferred is banked, not discarded and not double counted.
		assertEquals(2_000, back.inferredPlayedMs)
		assertEquals(12_000, back.playedMs)
		// Measuring resumes from the new baseline.
		val advanced = tracker.observe(organic(position = 16, total = 60, at = 16_500)).active!!
		assertEquals(15_000, advanced.playedMs)
	}

	@Test
	fun `a looping Short in PiP cannot be credited past its own length`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 20, at = 0))
		tracker.observe(organic(position = 5, total = 20, at = 5_000))
		var now = 5_000L
		var ended: SessionSnapshot? = null
		repeat(300) {
			now += 1_000
			val update = tracker.proofMissing(
				now, "pip", progressSurfaceLost = true, inferredPlaying = true,
			)
			update.finalized.firstOrNull()?.let { if (ended == null) ended = it }
		}
		// Capped at the Short's own length, and then *ended* — a capped Short has
		// nothing left to earn, so continuing to hold it open would lose the
		// listen entirely rather than protect it.
		val finalized = requireNotNull(ended) { "a capped PiP Short must finalize" }
		assertEquals(20_000, finalized.playedMs)
		assertEquals(15_000, finalized.inferredPlayedMs)
		assertFalse(tracker.hasActive)
	}

	@Test
	fun `a Short left looping banks its listen instead of counting forever`() {
		// Measured 2026-08-06: a 105s Short left playing reached
		// "measured total 461s" over four loops and never finalized once, because
		// a Short only ended when something took it away. Left alone it never
		// ends, so it banked nothing and scrobbled nothing.
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 20, at = 0))
		var banked: SessionSnapshot? = null
		var now = 0L
		var position = 0L
		repeat(60) {
			now += 1_000
			position = (position + 1) % 21
			val update = tracker.observe(organic(position = position, total = 20, at = now))
			update.finalized.firstOrNull()?.let { if (banked == null) banked = it }
		}
		val listen = requireNotNull(banked) { "a Short played to its full length must bank" }
		assertTrue(listen.playedMs >= 20_000)
		// Still on screen and still tracked — banking the listen does not end the
		// viewing, it just stops the count being lost.
		assertTrue(tracker.hasActive)
	}

	@Test
	fun `a full listen is banked once, not once per loop`() {
		// capForKind allows a Short exactly one transaction, so banking per loop
		// would only produce refusals — and a second bank would re-open the same
		// question the dedup ledger already answers.
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 10, at = 0))
		var banks = 0
		var now = 0L
		var position = 0L
		repeat(120) {
			now += 1_000
			position = (position + 1) % 11
            banks += tracker.observe(organic(position = position, total = 10, at = now))
				.finalized.size
		}
		assertEquals(1, banks)
	}

	@Test
	fun `Stop disconnect opt-out and source epoch transition discard`() {
		listOf("Stop", "accessibility disconnected", "opt-out").forEach { reason ->
			val tracker = ForegroundShortTracker()
			tracker.observe(organic(position = 9, at = 0))
			val update = tracker.discard(reason)
			assertTrue(update.finalized.isEmpty())
			assertFalse(tracker.hasActive)
		}
		val epoch = ForegroundShortTracker()
		epoch.observe(organic(title = "same", position = 1, at = 0, epoch = 3))
		val transitioned = epoch.observe(organic(title = "same", position = 2, at = 1_000, epoch = 4))
		assertEquals(1, transitioned.finalized.size)
		assertEquals(0, transitioned.active!!.playedMs)
	}

	private fun organic(
		title: String = "Organic",
		handle: String = "@creator",
		position: Long,
		total: Long = 60,
		at: Long,
		epoch: Long = 3,
	) = ForegroundShortTracker.OrganicObservation(title, handle, position, total, at, epoch)

	private fun unmeasured(
		title: String? = "Organic",
		handle: String = "@creator",
		at: Long,
		playing: Boolean = true,
		epoch: Long = 3,
	) = ForegroundShortTracker.UnmeasuredObservation(title, handle, at, epoch, playing)

	private fun ad(position: Long, at: Long) = ForegroundShortTracker.AdObservation(
		signal = "Sponsored",
		title = "Advert",
		currentSeconds = position,
		totalSeconds = 18,
		observedAtMillis = at,
		sourceEpoch = 3,
	)
}
