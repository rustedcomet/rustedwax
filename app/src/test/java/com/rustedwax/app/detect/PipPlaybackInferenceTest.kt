package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The caps are the whole safety story here.
 *
 * Credit is being handed out on evidence that cannot see the player, so what
 * matters is not that it counts, but that it cannot count more than the Short
 * could possibly contain.
 */
class PipPlaybackInferenceTest {

	private fun inference(durationSec: Long, measuredSec: Long) =
		PipPlaybackInference(durationMs = durationSec * 1000, measuredMs = measuredSec * 1000)

	@Test
	fun `nothing is credited for the first observation`() {
		// There is no interval yet. Crediting here would invent time from the
		// moment the surface went away, which nobody measured.
		val pip = inference(durationSec = 60, measuredSec = 10)
		assertEquals(0, pip.observe(1_000, playing = true))
		assertEquals(0, pip.credited)
	}

	@Test
	fun `elapsed time between two playing observations is credited`() {
		val pip = inference(durationSec = 60, measuredSec = 10)
		pip.observe(1_000, playing = true)
		assertEquals(1_000, pip.observe(2_000, playing = true))
		assertEquals(1_000, pip.observe(3_000, playing = true))
		assertEquals(2_000, pip.credited)
	}

	@Test
	fun `a pause credits nothing and cannot be back-filled on resume`() {
		// The audio list drops paused players, so this is the ordinary "user
		// paused the PiP window" path. The paused stretch must not reappear as
		// watch time the moment playback resumes.
		val pip = inference(durationSec = 60, measuredSec = 0)
		pip.observe(0, playing = true)
		pip.observe(1_000, playing = true)
		assertEquals(1_000, pip.credited)

		assertEquals(0, pip.observe(2_000, playing = false))
		assertEquals(0, pip.observe(30_000, playing = false))
		// First observation after the pause re-anchors and credits nothing.
		assertEquals(0, pip.observe(31_000, playing = true))
		assertEquals(1_000, pip.credited)
		assertEquals(1_000, pip.observe(32_000, playing = true))
		assertEquals(2_000, pip.credited)
	}

	@Test
	fun `a long stall credits at most one step`() {
		// Doze, a frozen process or a stalled binder can put an arbitrary gap
		// between two polls. The first poll after the gap must not credit it all.
		val pip = inference(durationSec = 600, measuredSec = 0)
		pip.observe(0, playing = true)
		val credited = pip.observe(10 * 60 * 1000, playing = true)
		assertEquals(PipPlaybackInference.MAX_STEP_MS, credited)
	}

	@Test
	fun `measured plus inferred can never exceed the Short's duration`() {
		// Shorts auto-loop. Without this cap a Short left playing in PiP would
		// accumulate credit forever and clear any threshold, which is exactly the
		// over-counting the whole design exists to prevent.
		val pip = inference(durationSec = 30, measuredSec = 12)
		var now = 0L
		repeat(1_000) {
			now += 1_000
			pip.observe(now, playing = true)
		}
		assertEquals(18_000, pip.credited)
		assertTrue(12_000 + pip.credited <= 30_000)
	}

	@Test
	fun `a Short already measured to the end earns no inferred time at all`() {
		val pip = inference(durationSec = 30, measuredSec = 30)
		pip.observe(0, playing = true)
		pip.observe(1_000, playing = true)
		assertEquals(0, pip.credited)
	}

	@Test
	fun `out of order observations credit nothing`() {
		val pip = inference(durationSec = 60, measuredSec = 0)
		pip.observe(5_000, playing = true)
		assertEquals(0, pip.observe(4_000, playing = true))
	}
}
