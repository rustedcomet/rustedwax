package com.rustedwax.app.enrich

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule the owner asked for: the history route must refuse outright when the
 * YouTube app is signed out, on a different account, or in incognito — not keep
 * fetching a page that can never describe this phone's playback.
 *
 * None of those three is separable from the feed, so the evidence is the miss
 * itself: a feed that reads fine and repeatedly does not contain what just
 * played. The two conditions that *are* separable — a dead session and a paused
 * history — are YouTube's own answer and refuse on the first sight of them.
 */
class WatchHistoryHealthTest {

	private val minute = 60_000L

	@Test
	fun `a working route runs`() {
		val health = WatchHistoryHealth()
		assertTrue(health.mayRun(0))
		assertNull(health.refusedBecause)
	}

	@Test
	fun `one miss is a video that has not landed yet, not a diagnosis`() {
		val health = WatchHistoryHealth()
		health.recordMiss(0)
		health.recordMiss(minute)
		assertNull(health.refusedBecause)
		assertTrue(health.mayRun(2 * minute))
	}

	@Test
	fun `three consecutive misses stop the route and name every possible cause`() {
		val health = WatchHistoryHealth()
		repeat(WatchHistoryHealth.MISSES_BEFORE_REFUSING) { health.recordMiss(it * minute) }
		val reason = health.refusedBecause
		assertNotNull(reason)
		assertTrue(reason!!.contains("signed out"))
		assertTrue(reason.contains("different account"))
		assertTrue(reason.contains("incognito"))
		assertFalse(health.mayRun(3 * minute))
	}

	@Test
	fun `a track landing in the feed clears the refusal`() {
		val health = WatchHistoryHealth()
		repeat(3) { health.recordMiss(it * minute) }
		health.recordHit()
		assertNull(health.refusedBecause)
		assertTrue(health.mayRun(3 * minute))
	}

	/** A verdict that could never be revisited would need an app restart to clear. */
	@Test
	fun `a refused route re-probes once per retry interval`() {
		val health = WatchHistoryHealth()
		repeat(3) { health.recordMiss(0) }
		assertFalse(health.mayRun(WatchHistoryHealth.RETRY_INTERVAL_MS - 1))
		assertTrue(health.mayRun(WatchHistoryHealth.RETRY_INTERVAL_MS))
		// One probe, not a reopened gate.
		assertFalse(health.mayRun(WatchHistoryHealth.RETRY_INTERVAL_MS + 1))
	}

	@Test
	fun `a dead session refuses immediately and says to sign in again`() {
		val health = WatchHistoryHealth()
		health.recordUnavailable(
			WatchHistoryParser.Reason.SIGNED_OUT,
			"responseContext.loggedOut",
			0,
		)
		assertTrue(health.refusedBecause!!.contains("no longer signed in"))
	}

	@Test
	fun `a paused history refuses immediately and says where to fix it`() {
		val health = WatchHistoryHealth()
		health.recordUnavailable(WatchHistoryParser.Reason.HISTORY_PAUSED, "paused", 0)
		assertTrue(health.refusedBecause!!.contains("paused"))
		assertTrue(health.refusedBecause!!.contains("YouTube settings"))
	}

	/** An empty feed on a live session is the same evidence a miss is. */
	@Test
	fun `an empty feed is counted rather than declared`() {
		val health = WatchHistoryHealth()
		health.recordUnavailable(WatchHistoryParser.Reason.EMPTY, "no entries", 0)
		assertNull(health.refusedBecause)
		health.recordUnavailable(WatchHistoryParser.Reason.EMPTY, "no entries", minute)
		health.recordUnavailable(WatchHistoryParser.Reason.EMPTY, "no entries", 2 * minute)
		assertNotNull(health.refusedBecause)
	}

	@Test
	fun `a network failure says nothing about the account`() {
		val health = WatchHistoryHealth()
		repeat(10) { health.recordFetchFailure() }
		assertNull(health.refusedBecause)
	}

	@Test
	fun `a monitoring boundary starts the diagnosis over`() {
		val health = WatchHistoryHealth()
		repeat(3) { health.recordMiss(0) }
		health.reset()
		assertNull(health.refusedBecause)
		assertTrue(health.mayRun(0))
	}
}
