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

	// region shapes adapted from the desktop extension's ytTitleRegExps

	/** Quotes name the track outright, which beats guessing at a separator. */
	@Test
	fun `splits a quoted track`() {
		TitleParser.parse("""BABYMETAL "Gimme Chocolate!!"""", "Some Channel").let {
			assertEquals("BABYMETAL", it.artist)
			assertEquals("Gimme Chocolate!!", it.track)
		}
		TitleParser.parse("""Korn - "Trash"""", "KornVEVO").let {
			assertEquals("Korn", it.artist)
			assertEquals("Trash", it.track)
		}
	}

	/** The one common shape where the artist comes second. */
	@Test
	fun `splits Track (by Artist)`() {
		TitleParser.parse("Nevada (by Vicetone)", "Some Channel").let {
			assertEquals("Vicetone", it.artist)
			assertEquals("Nevada", it.track)
		}
		TitleParser.parse("Yesterday (performed by The Beatles)", null).let {
			assertEquals("The Beatles", it.artist)
			assertEquals("Yesterday", it.track)
		}
	}

	/** Album and vinyl rips carry a track number the chain shouldn't see. */
	@Test
	fun `strips a track-number prefix`() {
		assertEquals("Trash", TitleParser.clean("03. Trash"))
		assertEquals("Trash", TitleParser.clean("A1. Trash"))
		assertEquals("Trash", TitleParser.clean("12) Trash"))
		TitleParser.parse("07. Korn - Trash", null).let {
			assertEquals("Korn", it.artist)
			assertEquals("Trash", it.track)
		}
	}

	/** A number that *is* the title has no separator after it, so it survives. */
	@Test
	fun `does not strip numbers that are part of the title`() {
		assertEquals("1999", TitleParser.clean("1999"))
		assertEquals("7 Rings", TitleParser.clean("7 Rings"))
		assertEquals("100 Bad Days", TitleParser.clean("100 Bad Days"))
	}

	/** A leading genre tag is noise — but only when an artist survives without it. */
	@Test
	fun `strips a leading tag when a separator survives`() {
		TitleParser.parse("[Future Bass] Vicetone - Nevada", null).let {
			assertEquals("Vicetone", it.artist)
			assertEquals("Nevada", it.track)
		}
	}

	/**
	 * Regression guard for the interaction: the reported guitar cover puts the
	 * *artist* in a leading CJK bracket, so the tag strip must not eat it.
	 */
	@Test
	fun `keeps a leading bracket that names the artist`() {
		val parsed = TitleParser.parse(
			"【Bring Me The Horizon】I Used to Make Out With Medusa",
			"OLD MOON CHILD",
		)
		assertEquals("Bring Me The Horizon", parsed.artist)
		assertEquals("I Used to Make Out With Medusa", parsed.track)
	}
	// endregion

	// region trailing hashtags
	//
	// All of these went on-chain verbatim in the 2026-07-29 session. The cost
	// isn't only cosmetic: the tag run is part of the title, so the same clip
	// reposted with different tags dedups as a different listen.

	@Test
	fun `strips a trailing hashtag run`() {
		assertEquals(
			"Rüyamda seni gördüm",
			TitleParser.clean(
				"Rüyamda seni gördüm #ilkveson #dizi #blutv #hazalsubaşı #ulastunaastepe",
			),
		)
		assertEquals(
			"🏫Un reggaeton educativo",
			TitleParser.clean("🏫Un reggaeton educativo #insulini #reggaeton #perreo #poliglota"),
		)
	}

	/** Non-Latin tags: the session was full of Turkish, Korean and Chinese ones. */
	@Test
	fun `strips non-latin hashtags`() {
		assertEquals(
			"下班后的日常",
			TitleParser.clean("下班后的日常 #老人与海 #shortsvideo #dance #护士跳舞"),
		)
	}

	/** `#plena#panama 🇵🇦` — no spaces between tags, an emoji after the last one. */
	@Test
	fun `strips runs with no spaces and trailing emoji`() {
		assertTrue(TitleParser.isHashtagOnly("#plena#panama 🇵🇦"))
		assertEquals(
			"PREGUNTO.",
			TitleParser.clean("PREGUNTO. #oldveteran #plena #reggaeespañol #panama"),
		)
	}

	@Test
	fun `keeps a hashtag that is not trailing`() {
		assertEquals("Song #2 of the series", TitleParser.clean("Song #2 of the series"))
	}

	/**
	 * `#` is also a sharp. A tag needs word characters immediately after the
	 * hash, which a note name never has — the letter comes *before* it.
	 */
	@Test
	fun `does not mistake sharp notes for hashtags`() {
		assertEquals("Prelude in C#", TitleParser.clean("Prelude in C#"))
		assertEquals("Nocturne in C# Minor", TitleParser.clean("Nocturne in C# Minor"))
		assertEquals("Learn C# Programming", TitleParser.clean("Learn C# Programming"))
		assertFalse(TitleParser.isHashtagOnly("Prelude in C#"))
	}

	/**
	 * A tag run hiding behind a promo bracket. The clean loop has to reach it
	 * whichever order the uploader used.
	 */
	@Test
	fun `strips a hashtag run behind promotional noise`() {
		assertEquals("Song", TitleParser.clean("Song #shorts (Official Video)"))
		assertEquals("Song", TitleParser.clean("Song (Official Video) #shorts"))
	}

	/**
	 * When the tags are all there is, the title is returned unchanged — a
	 * payload needs some title, and empty is worse than ugly. The classifier
	 * uses [TitleParser.isHashtagOnly] to refuse the `song` kind instead.
	 */
	@Test
	fun `never strips a title down to nothing`() {
		assertEquals("#guitar", TitleParser.clean("#guitar"))
		assertEquals(
			"#guitar #dubstep #djdubstep #fnaf",
			TitleParser.clean("#guitar #dubstep #djdubstep #fnaf"),
		)
	}

	@Test
	fun `recognises a title that is only hashtags`() {
		assertTrue(TitleParser.isHashtagOnly("#guitar #dubstep #fnaf"))
		assertTrue(TitleParser.isHashtagOnly("#skrillex #electricguitar  #dubstep"))
		assertFalse(TitleParser.isHashtagOnly("That's sharp 🔥 #artist #music #guitar"))
		assertFalse(TitleParser.isHashtagOnly("Blackened"))
	}

	/** Tags must not survive into the artist half of a split, either. */
	@Test
	fun `strips tags before splitting artist and track`() {
		val parsed = TitleParser.parse("Slayer - South of Heaven #metal #thrash", null)
		assertEquals("Slayer", parsed.artist)
		assertEquals("South of Heaven", parsed.track)
	}
	// endregion
}
