package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeShortStabilizerTest {

	@Test
	fun `identity must remain complete and unchanged across the stability interval`() {
		val stabilizer = NativeShortStabilizer()
		val first = organic("A", "@owner_a", total = 60, position = 0)
		assertTrue(stabilizer.observe(first, 0) is NativeShortStabilizer.Decision.Waiting)
		assertTrue(stabilizer.observe(first.copy(currentSeconds = 1), 500) is NativeShortStabilizer.Decision.Waiting)
		val accepted = stabilizer.observe(first.copy(currentSeconds = 2), 750)
		assertTrue(accepted is NativeShortStabilizer.Decision.Accepted)
	}

	@Test
	fun `outgoing title with incoming duration never stabilizes into a session`() {
		val stabilizer = NativeShortStabilizer()
		val outgoing = organic("Outgoing", "@old_owner", total = 59, position = 8)
		stabilizer.observe(outgoing, 0)
		stabilizer.observe(outgoing.copy(currentSeconds = 9), 800)

		val torn = outgoing.copy(totalSeconds = 101, currentSeconds = 0)
		assertTrue(stabilizer.observe(torn, 1_000) is NativeShortStabilizer.Decision.Waiting)
		val incoming = organic("Incoming", "@new_owner", total = 101, position = 1)
		assertTrue(stabilizer.observe(incoming, 1_200) is NativeShortStabilizer.Decision.Waiting)
		assertTrue(stabilizer.observe(incoming.copy(currentSeconds = 2), 1_949) is NativeShortStabilizer.Decision.Waiting)
		val accepted = stabilizer.observe(incoming.copy(currentSeconds = 2), 1_950)
		assertEquals(incoming.copy(currentSeconds = 2), (accepted as NativeShortStabilizer.Decision.Accepted).result)
	}

	@Test
	fun `organic ad organic each require independent stability`() {
		val stabilizer = NativeShortStabilizer()
		val organic = organic("Organic", "@creator", total = 30, position = 0)
		stabilizer.observe(organic, 0)
		stabilizer.observe(organic.copy(currentSeconds = 1), 800)

		val ad = NativeShortParser.Result.Ad("Ad", "Advert", 0, 20)
		assertTrue(stabilizer.observe(ad, 1_000) is NativeShortStabilizer.Decision.Waiting)
		assertTrue(stabilizer.observe(ad.copy(currentSeconds = 1), 1_800) is NativeShortStabilizer.Decision.Accepted)

		val successor = organic("Successor", "@next_owner", total = 45, position = 0)
		assertTrue(stabilizer.observe(successor, 2_000) is NativeShortStabilizer.Decision.Waiting)
		assertTrue(stabilizer.observe(successor.copy(currentSeconds = 1), 2_800) is NativeShortStabilizer.Decision.Accepted)
	}

	@Test
	fun `missing proof resets accumulated stability`() {
		val stabilizer = NativeShortStabilizer()
		val result = organic("A", "@owner_a", total = 60, position = 0)
		stabilizer.observe(result, 0)
		stabilizer.reset()
		assertTrue(stabilizer.observe(result.copy(currentSeconds = 1), 800) is NativeShortStabilizer.Decision.Waiting)
		assertTrue(stabilizer.observe(result.copy(currentSeconds = 2), 1_550) is NativeShortStabilizer.Decision.Accepted)
	}

	private fun organic(
		title: String,
		handle: String,
		total: Long,
		position: Long,
	) = NativeShortParser.Result.Organic(title, handle, position, total)
}
