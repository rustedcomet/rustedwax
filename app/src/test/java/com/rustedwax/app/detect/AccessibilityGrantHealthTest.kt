package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityGrantHealthTest {

	@Test
	fun `a live grant is live whether or not it was seen before`() {
		assertEquals(GrantHealth.LIVE, AccessibilityGrantHealth.classify(live = true, everGranted = false))
		assertEquals(GrantHealth.LIVE, AccessibilityGrantHealth.classify(live = true, everGranted = true))
	}

	@Test
	fun `never granted is not reported as a failure`() {
		// A fresh install has nothing wrong with it, and saying "stopped on its
		// own" there would train the owner to ignore the message that matters.
		assertEquals(
			GrantHealth.NEVER_GRANTED,
			AccessibilityGrantHealth.classify(live = false, everGranted = false),
		)
	}

	@Test
	fun `a grant that was once live and is now off is a drop, not a choice`() {
		// The 2026-08-05 case: Android disables a crashed accessibility service,
		// which is byte-identical to the user revoking it. The app must not
		// claim the user did it, and must not stay silent either.
		assertEquals(
			GrantHealth.DROPPED,
			AccessibilityGrantHealth.classify(live = false, everGranted = true),
		)
	}
}
