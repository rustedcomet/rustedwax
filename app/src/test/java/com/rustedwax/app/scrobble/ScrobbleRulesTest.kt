package com.rustedwax.app.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gate G6: threshold parity with `hive-scrobbler.ts#finalize`. */
class ScrobbleRulesTest {

	private val fourMinutes = 240_000L

	@Test
	fun `below the threshold nothing is scrobbled`() {
		val d = ScrobbleRules.decide(playedMs = 100_000, durationMs = fourMinutes)
		assertFalse(d.shouldScrobble)
		assertTrue(d.skippedBecause!!.contains("below"))
	}

	@Test
	fun `just past 60 percent scrobbles once`() {
		val d = ScrobbleRules.decide(playedMs = 145_000, durationMs = fourMinutes)
		assertEquals(listOf(60), d.percentages)
	}

	@Test
	fun `a full listen scrobbles once at 100`() {
		val d = ScrobbleRules.decide(playedMs = fourMinutes, durationMs = fourMinutes)
		assertEquals(listOf(100), d.percentages)
	}

	@Test
	fun `a double listen scrobbles twice, capped at 100 each`() {
		// 170% played → upstream emits 2 tx: min(100,170)=100 and round(0.7*100)=70
		val d = ScrobbleRules.decide(playedMs = 408_000, durationMs = fourMinutes)
		assertEquals(listOf(100, 70), d.percentages)
	}

	@Test
	fun `never more than two transactions`() {
		val d = ScrobbleRules.decide(playedMs = fourMinutes * 5, durationMs = fourMinutes)
		assertEquals(2, d.percentages.size)
	}

	@Test
	fun `exactly 160 percent is the second transaction boundary`() {
		assertEquals(1, ScrobbleRules.decide(383_000, fourMinutes).percentages.size)
		assertEquals(2, ScrobbleRules.decide(384_000, fourMinutes).percentages.size)
	}

	@Test
	fun `ads and other short items are ignored`() {
		// The 6-second "track" observed in PHASE0 run 1.
		val d = ScrobbleRules.decide(playedMs = 6_000, durationMs = 6_061)
		assertFalse(d.shouldScrobble)
		assertTrue(d.skippedBecause!!.contains("ad"))
	}

	@Test
	fun `no duration means no scrobble`() {
		val d = ScrobbleRules.decide(playedMs = 200_000, durationMs = null)
		assertFalse(d.shouldScrobble)
	}

	@Test
	fun `threshold is configurable`() {
		val d = ScrobbleRules.decide(playedMs = 120_000, durationMs = fourMinutes, threshold = 0.5)
		assertEquals(listOf(50), d.percentages)
	}

	/**
	 * The 160% double-listen is for songs. A looping short broadcast the same
	 * clip twice in one block (observed on-chain 2026-07-24, percents 100+76);
	 * videos are watched, not re-listened, so they cap at one transaction.
	 */
	@Test
	fun `double listen applies to songs only`() {
		val double = listOf(100, 76)
		assertEquals(listOf(100, 76), ScrobbleRules.capForKind(double, "song"))
		assertEquals(listOf(100), ScrobbleRules.capForKind(double, "video"))
		assertEquals(listOf(100), ScrobbleRules.capForKind(double, "podcast"))
		// A single-tx decision is untouched either way.
		assertEquals(listOf(84), ScrobbleRules.capForKind(listOf(84), "video"))
	}
}
