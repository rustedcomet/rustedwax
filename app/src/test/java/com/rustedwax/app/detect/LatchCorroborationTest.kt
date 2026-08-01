package com.rustedwax.app.detect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two checks that can disprove a latched video id.
 *
 * The address bar is read asynchronously, so at track start it can still be
 * naming the *previous* video. The latch is corroborated against the resolved
 * page to catch that — and until v0.8.2 the only corroboration was the title,
 * which is absent whenever the page fetch failed. So it failed open:
 *
 * ```
 * 14:22:41  [enrich] fetch failed or timed out for GQwj_FRntp8
 * 14:27:39  TITLE = "Para Mis Soldados - Danger Man"
 * 14:27:39  [identity] latched video GQwj_FRntp8 for this track    ← previous entry
 * 14:27:46  [url] → video=RW7Hn24Agyc                             ← bar catches up
 * 14:28:06  broadcasting … url=…GQwj_FRntp8
 * ```
 *
 * `GQwj_FRntp8` is Daddy Yankee's "Con Calma" (193 s); the track being scrobbled
 * was 226 s. That disagreement is what the duration check now catches.
 */
class LatchCorroborationTest {

	// region duration

	/** The field case, to the second. */
	@Test
	fun `the wrong-url case is caught by duration alone`() {
		assertTrue(SessionProbe.durationsDisagree(sessionMs = 226_000, pageSeconds = 193))
	}

	/**
	 * `lengthSeconds` and the session's `DURATION` routinely differ by a second
	 * of rounding — a live page reported 39 where the session said 39141 ms.
	 */
	@Test
	fun `rounding slack is not a disagreement`() {
		assertFalse(SessionProbe.durationsDisagree(sessionMs = 39_141, pageSeconds = 39))
		assertFalse(SessionProbe.durationsDisagree(sessionMs = 39_141, pageSeconds = 40))
		assertFalse(SessionProbe.durationsDisagree(sessionMs = 240_000, pageSeconds = 240))
	}

	/**
	 * The proportional half of the tolerance: a flat 5 s threshold alone would
	 * reject a two-hour video over six seconds of rounding.
	 */
	@Test
	fun `a small absolute gap on a long video is tolerated`() {
		assertFalse(SessionProbe.durationsDisagree(sessionMs = 7_206_000, pageSeconds = 7_200))
	}

	/**
	 * And the absolute half: a percentage alone would let a 30-second
	 * disagreement pass on that same video.
	 */
	@Test
	fun `a large absolute gap on a long video is still caught`() {
		assertTrue(SessionProbe.durationsDisagree(sessionMs = 7_200_000, pageSeconds = 3_600))
	}

	/** A short clip has little room, so a few seconds is a real disagreement. */
	@Test
	fun `a few seconds is a disagreement on a short clip`() {
		assertTrue(SessionProbe.durationsDisagree(sessionMs = 18_000, pageSeconds = 40))
	}

	/** Nothing to compare is not a disagreement — the check must not fail closed. */
	@Test
	fun `missing values are never a disagreement`() {
		assertFalse(SessionProbe.durationsDisagree(sessionMs = null, pageSeconds = 193))
		assertFalse(SessionProbe.durationsDisagree(sessionMs = 226_000, pageSeconds = null))
		assertFalse(SessionProbe.durationsDisagree(sessionMs = null, pageSeconds = null))
		assertFalse(SessionProbe.durationsDisagree(sessionMs = 0, pageSeconds = 193))
		assertFalse(SessionProbe.durationsDisagree(sessionMs = 226_000, pageSeconds = 0))
	}
	// endregion

	// region title

	@Test
	fun `identical titles match`() {
		assertTrue(SessionProbe.titlesMatch("Korn - Trash", "Korn - Trash"))
		assertTrue(SessionProbe.titlesMatch("KORN - TRASH", "korn - trash"))
		assertTrue(SessionProbe.titlesMatch("Korn  -  Trash", "Korn - Trash"))
	}

	/** Chromium truncates the media-session title on long ones. */
	@Test
	fun `a truncated title still matches`() {
		assertTrue(
			SessionProbe.titlesMatch(
				"Fall 2: Deadpoint (2026) Official Trailer 2 - Harriet Slater",
				"Fall 2: Deadpoint (2026) Official Trailer 2",
			),
		)
	}

	@Test
	fun `two different videos do not match`() {
		assertFalse(SessionProbe.titlesMatch("Con Calma", "Para Mis Soldados - Danger Man"))
	}

	@Test
	fun `an empty title is never a match`() {
		assertFalse(SessionProbe.titlesMatch("", "Con Calma"))
		assertFalse(SessionProbe.titlesMatch("Con Calma", "   "))
	}
	// endregion

	/**
	 * v0.8.8's delayed-finalization failure: the live value and the latch were
	 * the same next-video id. Clearing the latch and then returning
	 * `latchedVideo ?: live` resurrected it immediately.
	 */
	@Test
	fun `a live identity rejected on this pass cannot enter the snapshot`() {
		val nextVideo = YouTubeProbe.Identity.Confirmed(
			videoId = "ysY13cbxJR4",
			url = "https://www.youtube.com/watch?v=ysY13cbxJR4",
			isMusic = false,
			isShort = true,
			source = "address bar",
		)
		val selected = SessionProbe.identityAfterCorroboration(
			latched = null,
			live = nextVideo,
			rejectedVideoIds = setOf(nextVideo.videoId),
			rejectedThisPass = true,
		)
		assertTrue(selected is YouTubeProbe.Identity.Unconfirmed)
	}

	@Test
	fun `a corroborated latch remains preferred to later live evidence`() {
		val track = YouTubeProbe.Identity.Confirmed(
			videoId = "grNk0DpiaEE",
			url = "https://www.youtube.com/watch?v=grNk0DpiaEE",
			isMusic = false,
			isShort = true,
			source = "latched",
		)
		val later = track.copy(videoId = "ysY13cbxJR4")
		assertEquals(
			track,
			SessionProbe.identityAfterCorroboration(
				latched = track,
				live = later,
				rejectedVideoIds = emptySet(),
				rejectedThisPass = false,
			),
		)
	}
}
