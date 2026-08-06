package com.rustedwax.app.detect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The §5.1 outage detector.
 *
 * These pin the two ways instrumentation like this fails: staying quiet through
 * the event it exists to catch, and crying wolf often enough that the one line
 * that matters gets ignored.
 */
class AccessibilityEventSilenceTest {

	private val threshold = AccessibilityEventSilence.SILENCE_THRESHOLD_MS
	private val repeat = AccessibilityEventSilence.REPEAT_INTERVAL_MS

	@Test
	fun `a healthy event stream is never reported`() {
		val silence = AccessibilityEventSilence()
		silence.started(0)
		var now = 0L
		repeat(20) {
			now += 5_000
			silence.eventReceived(now)
			assertNull(
				silence.observed(now, ObservedSurface.YOUTUBE_SHORTS_PLAYER, screenInteractive = true),
			)
		}
	}

	@Test
	fun `the measured outage names itself with its duration and surface`() {
		// FIELD §5.1: 87 seconds of no YouTube accessibility events while a Short
		// was playing, visible in the log only as an absence of lines.
		val silence = AccessibilityEventSilence()
		silence.started(0)
		silence.eventReceived(1_000)

		assertNull(
			"a gap under the threshold is not an outage",
			silence.observed(
				1_000 + threshold - 1,
				ObservedSurface.YOUTUBE_SHORTS_PLAYER,
				screenInteractive = true,
			),
		)

		val line = silence.observed(
			88_000,
			ObservedSurface.YOUTUBE_SHORTS_PLAYER,
			screenInteractive = true,
		)
		assertNotNull(line)
		assertTrue(line!!.contains("87s"))
		assertTrue(line.contains("Shorts player on screen"))
		assertTrue(line.contains("unobserved"))
	}

	@Test
	fun `a service that can see no window at all is reportable`() {
		// The first cut only reported when a YouTube capture *succeeded*, which
		// would have stayed silent through the real event: the field log's two
		// 30s-apart lines are the throttled idle poll, so captures were running
		// the whole time and finding nothing. Whatever went wrong took the
		// accessibility tree with it.
		val silence = AccessibilityEventSilence()
		silence.started(0)
		silence.eventReceived(0)
		val line = silence.observed(threshold, ObservedSurface.NO_ROOT, screenInteractive = true)
		assertNotNull(line)
		assertTrue(line!!.contains("the service cannot see"))
	}

	@Test
	fun `explained silence is never reported`() {
		// Another app foreground, or a dark screen, fully accounts for the quiet.
		// Reporting either would bury the case that matters.
		val other = AccessibilityEventSilence()
		other.started(0)
		other.eventReceived(0)
		assertNull(
			other.observed(600_000, ObservedSurface.OTHER_APP, screenInteractive = true),
		)

		val dark = AccessibilityEventSilence()
		dark.started(0)
		dark.eventReceived(0)
		assertNull(
			dark.observed(600_000, ObservedSurface.NO_ROOT, screenInteractive = false),
		)
		assertNull(
			dark.observed(
				600_000,
				ObservedSurface.YOUTUBE_SHORTS_PLAYER,
				screenInteractive = false,
			),
		)
	}

	@Test
	fun `an outage that ends reports the total it lasted`() {
		// A start with no end leaves the duration to be guessed from log gaps,
		// which is exactly the position §5.1 was written from.
		val silence = AccessibilityEventSilence()
		silence.started(0)
		silence.eventReceived(1_000)
		assertNotNull(
			silence.observed(50_000, ObservedSurface.YOUTUBE_SHORTS_PLAYER, screenInteractive = true),
		)

		val recovered = silence.eventReceived(88_000)
		assertNotNull(recovered)
		assertTrue(recovered!!.contains("recovered"))
		assertTrue(recovered.contains("87s"))
	}

	@Test
	fun `recovery is silent when no outage was ever reported`() {
		val silence = AccessibilityEventSilence()
		silence.started(0)
		assertNull(silence.eventReceived(10_000))
		assertNull(silence.eventReceived(20_000))
	}

	@Test
	fun `a long outage leaves a trail rather than a flood`() {
		val silence = AccessibilityEventSilence()
		silence.started(0)
		silence.eventReceived(0)
		var reports = 0
		// Ten minutes of continuous silence, probed every second.
		for (now in threshold..600_000 step 1_000) {
			if (silence.observed(now, ObservedSurface.NO_ROOT, screenInteractive = true) != null) {
				reports++
			}
		}
		// One at the threshold, then one per repeat interval — not 555 lines.
		assertTrue("expected a handful of reports, got $reports", reports in 2..12)
	}

	@Test
	fun `the quiet watch screen is distinguishable from the Shorts outage`() {
		// Measured 2026-08-04: six minutes of ordinary watch playback produced
		// zero accessibility events, which is normal and must not read as the
		// §5.1 fault. It is still recorded, and says which it was.
		val silence = AccessibilityEventSilence()
		silence.started(0)
		silence.eventReceived(0)
		val line = silence.observed(
			threshold,
			ObservedSurface.YOUTUBE_NO_PLAYER,
			screenInteractive = true,
		)
		assertNotNull(line)
		assertTrue(line!!.contains("no complete Shorts player"))
	}

	@Test
	fun `probing is rate limited and stops once events return`() {
		val silence = AccessibilityEventSilence()
		silence.started(0)
		silence.eventReceived(0)

		assertFalse("healthy stream needs no probe", silence.probeDue(threshold - 1))
		assertTrue(silence.probeDue(threshold))
		assertFalse("asking again immediately is waste", silence.probeDue(threshold + 1))
		assertTrue(silence.probeDue(threshold + AccessibilityEventSilence.PROBE_INTERVAL_MS))

		silence.eventReceived(threshold + 30_000)
		assertFalse(
			"a live stream needs no probe",
			silence.probeDue(threshold + 30_000 + AccessibilityEventSilence.PROBE_INTERVAL_MS),
		)
	}

	@Test
	fun `a freshly connected service is not an outage`() {
		// The window before the first event is legitimate, not a fault.
		val silence = AccessibilityEventSilence()
		silence.started(0)
		assertNull(
			silence.observed(
				threshold - 1,
				ObservedSurface.YOUTUBE_SHORTS_PLAYER,
				screenInteractive = true,
			),
		)
		// Once past the threshold with still no event, it is worth saying: a
		// service that connects and never receives an event is the 💯💯💯🔥😎
		// case, where the Short never appeared in the log at all.
		assertNotNull(
			silence.observed(
				threshold + repeat,
				ObservedSurface.YOUTUBE_SHORTS_PLAYER,
				screenInteractive = true,
			),
		)
	}
}
