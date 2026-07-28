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

	/**
	 * Phase 4 decision D7 reversed this. Live and acoustic takes now fold into
	 * the original recording so play counts land on one entry — the point of
	 * the change is that a cover of a song *is* a listen to that song.
	 *
	 * `(Remix)` is the deliberate exception: a remix is a distinct work,
	 * usually credited to a different artist, so folding it in would be wrong
	 * rather than tidy.
	 */
	@Test
	fun `normalizes performance qualifiers to the original recording`() {
		assertEquals("Song", TitleParser.clean("Song (Live)"))
		assertEquals("Song", TitleParser.clean("Song (Acoustic)"))
		assertEquals("Song", TitleParser.clean("Song (Instrumental)"))
		assertEquals("Song", TitleParser.clean("Song (Guitar Cover)"))
		assertEquals("Song", TitleParser.clean("Song 【Drum Cover】"))
		assertEquals("Song (Remix)", TitleParser.clean("Song (Remix)"))
	}

	/**
	 * A group is only dropped when every word in it is a known qualifier, so a
	 * *named* live recording survives. No table can enumerate venues, and this
	 * is the better failure of the two.
	 */
	@Test
	fun `keeps named live recordings`() {
		assertEquals("Song (Live at Wembley)", TitleParser.clean("Song (Live at Wembley)"))
	}

	/**
	 * The reported misindexed scrobble: https://youtube.com/watch?v=ICSCJBc9fkY
	 *
	 * Went on-chain as artist "OLD MOON CHILD" (the channel) with the entire
	 * raw title as its title. The artist was in the title the whole time, in
	 * brackets the parser didn't recognise.
	 */
	@Test
	fun `parses the reported guitar cover correctly`() {
		val raw = "【Bring Me The Horizon】I Used to Make Out With Medusa " +
			"(Instrumental) 2023【Guitar Cover】＋Screen Tabs"
		val parsed = TitleParser.parse(raw, "OLD MOON CHILD")
		assertEquals("Bring Me The Horizon", parsed.artist)
		assertEquals("I Used to Make Out With Medusa", parsed.track)
	}

	@Test
	fun `reads an artist out of leading CJK brackets`() {
		assertEquals("Artist", TitleParser.parse("【Artist】Track", "Some Channel").artist)
		assertEquals("Track", TitleParser.parse("【Artist】Track", "Some Channel").track)
		// The bracket wins over a separator later in the title, which would
		// otherwise put "【Artist】Song" in the artist field.
		assertEquals("Artist", TitleParser.parse("【Artist】Song - Live", "Chan").artist)
	}

	@Test
	fun `strips trailing tab and gear suffixes`() {
		assertEquals("Song", TitleParser.clean("Song ＋Screen Tabs"))
		assertEquals("Song", TitleParser.clean("Song + Tabs"))
		assertEquals("Song", TitleParser.clean("Song w/ Screen Tabs"))
	}

	@Test
	fun `strips a trailing year but never a title that is one`() {
		assertEquals("Some Long Song Name", TitleParser.clean("Some Long Song Name 2023"))
		// "1979" and "1999" are real songs. A title that is nothing but a year
		// must survive, which is why the strip requires two words to remain.
		assertEquals("1979", TitleParser.clean("1979"))
		assertEquals("1979", TitleParser.parse("The Smashing Pumpkins - 1979", null).track)
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
