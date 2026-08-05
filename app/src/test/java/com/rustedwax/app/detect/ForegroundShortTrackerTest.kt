package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundShortTrackerTest {

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

	private fun ad(position: Long, at: Long) = ForegroundShortTracker.AdObservation(
		signal = "Sponsored",
		title = "Advert",
		currentSeconds = position,
		totalSeconds = 18,
		observedAtMillis = at,
		sourceEpoch = 3,
	)
}
