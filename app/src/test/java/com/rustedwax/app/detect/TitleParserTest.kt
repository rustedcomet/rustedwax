package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Q5 finding from PHASE0.md: a browser media session reports the *channel*
 * as ARTIST and packs the real artist into the title. These cases come from
 * real observed sessions plus the shapes upstream's YouTube rules handle.
 */
class TitleParserTest {

	@Test
	fun `splits the observed Korn session correctly`() {
		// Exactly what Chrome reported in Phase 0 run 2.
		val parsed = TitleParser.parse("Korn - Trash (Official Audio)", "KornVEVO")
		assertEquals("Korn", parsed.artist)
		assertEquals("Trash", parsed.track)
	}

	@Test
	fun `strips promotional noise`() {
		assertEquals("Song", TitleParser.clean("Song (Official Video)"))
		assertEquals("Song", TitleParser.clean("Song [Official Music Video]"))
		assertEquals("Song", TitleParser.clean("Song (Lyrics)"))
		assertEquals("Song", TitleParser.clean("Song (HD)"))
		assertEquals("Song", TitleParser.clean("Song | Official Video"))
	}

	/**
	 * Regression: v0.2.1 stripped "(Official Video)" and "(HD)" but not the
	 * combination, so this exact title reached the chain with its suffix
	 * intact. Observed 2026-07-23.
	 */
	@Test
	fun `strips combined promotional qualifiers`() {
		assertEquals("Thoughtless", TitleParser.clean("Thoughtless (Official HD Video)"))
		assertEquals("Song", TitleParser.clean("Song (Official 4K Video)"))
		assertEquals("Song", TitleParser.clean("Song [Official Lyric Video]"))
		assertEquals("Song", TitleParser.clean("Song (Official Music Video HD)"))
		assertEquals(
			"Thoughtless",
			TitleParser.parse("Korn - Thoughtless (Official HD Video)", "KornVEVO").track,
		)
	}

	@Test
	fun `keeps bracketed content that is not purely promotional`() {
		assertEquals("Song (Radio Edit)", TitleParser.clean("Song (Radio Edit)"))
		assertEquals("Song (Sol Invicto Remix)", TitleParser.clean("Song (Sol Invicto Remix)"))
		assertEquals("Song (MAPHRA Vocal Cover)", TitleParser.clean("Song (MAPHRA Vocal Cover)"))
	}

	@Test
	fun `identifies music by channel or title shape`() {
		assertTrue(TitleParser.looksLikeMusic("Korn - Thoughtless", "KornVEVO"))
		assertTrue(TitleParser.looksLikeMusic("Anything at all", "Radiohead - Topic"))
		assertTrue(TitleParser.looksLikeMusic("Slipknot - Duality", "SomeUploader"))
		assertFalse(TitleParser.looksLikeMusic("How to fix a sink", "DIY Channel"))
	}

	@Test
	fun `keeps meaningful qualifiers that identify a different recording`() {
		assertEquals("Song (Live)", TitleParser.clean("Song (Live)"))
		assertEquals("Song (Acoustic)", TitleParser.clean("Song (Acoustic)"))
		assertEquals("Song (Remix)", TitleParser.clean("Song (Remix)"))
	}

	@Test
	fun `handles the separators youtube titles actually use`() {
		assertEquals("Artist", TitleParser.parse("Artist – Track", null).artist)
		assertEquals("Artist", TitleParser.parse("Artist — Track", null).artist)
		assertEquals("Artist", TitleParser.parse("Artist ~ Track", null).artist)
		assertEquals("Track", TitleParser.parse("Artist | Track", null).track)
	}

	@Test
	fun `falls back to the channel when the title has no separator`() {
		val parsed = TitleParser.parse("Trash", "KornVEVO")
		assertEquals("Korn", parsed.artist)
		assertEquals("Trash", parsed.track)
	}

	@Test
	fun `cleans channel suffixes`() {
		assertEquals("Korn", TitleParser.cleanChannel("KornVEVO"))
		assertEquals("Korn", TitleParser.cleanChannel("Korn - Topic"))
		assertEquals("Radiohead", TitleParser.cleanChannel("Radiohead"))
		assertNull(TitleParser.cleanChannel(null))
		assertNull(TitleParser.cleanChannel("   "))
	}

	@Test
	fun `omits artist rather than inventing one`() {
		val parsed = TitleParser.parse("Some random upload", null)
		assertNull(parsed.artist)
		assertEquals("Some random upload", parsed.track)
	}

	@Test
	fun `does not split on a leading separator`() {
		val parsed = TitleParser.parse("- Track", "Channel")
		assertEquals("Channel", parsed.artist)
	}
}
