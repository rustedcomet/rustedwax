package com.rustedwax.app.enrich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The search-based video-id fallback.
 *
 * The fixture is trimmed from the **real** results page for the reported case
 * (2026-07-25, "Doomed" / "Bring Me The Horizon - Topic", 274 s), keeping the
 * renderer shape intact. The second entry matters most: `DIEI2YLYg6o` is a
 * *different artist's cover*, titled exactly "Doomed" and within two seconds
 * of the same length. Anything less than title + channel + duration would pick
 * it and write a wrong link to an immutable chain.
 */
class SearchResultsParserTest {

	private val fixture = """
		{"contents":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
		{"videoRenderer":{"videoId":"CZFTfYYql4k","title":{"runs":[{"text":"Doomed"}]},
		"ownerText":{"runs":[{"text":"Bring Me The Horizon"}]},"lengthText":{"simpleText":"4:35"}}},
		{"videoRenderer":{"videoId":"DIEI2YLYg6o","title":{"runs":[{"text":"Doomed"}]},
		"ownerText":{"runs":[{"text":"Maphra - Topic"}]},"lengthText":{"simpleText":"4:36"}}},
		{"videoRenderer":{"videoId":"r6L-GUOAhGo",
		"title":{"runs":[{"text":"Bring Me The Horizon - Doomed (MAPHRA Vocal Cover)"}]},
		"ownerText":{"runs":[{"text":"MAPHRA"}]},"lengthText":{"simpleText":"4:27"}}},
		{"videoRenderer":{"videoId":"ZWB9_gKmzxQ",
		"title":{"runs":[{"text":"BRING ME THE HORIZON - Doomed (AUDIO)"}]},
		"ownerText":{"runs":[{"text":"Psychotic Vampire"}]},"lengthText":{"simpleText":"4:33"}}}
		]}}]}}}
	""".trimIndent()

	@Test
	fun `extracts candidates regardless of renderer nesting`() {
		val c = SearchResultsParser.candidates(fixture)
		assertEquals(4, c.size)
		assertEquals("CZFTfYYql4k", c[0].videoId)
		assertEquals("Doomed", c[0].title)
		assertEquals("Bring Me The Horizon", c[0].channel)
		assertEquals(275L, c[0].lengthSeconds)
	}

	/**
	 * The reported track. The session said 274 s while search displays 4:35
	 * (275 s) — search rounds, so the duration check needs tolerance. Verified
	 * against the watch page: CZFTfYYql4k really is 274 s, "Bring Me The
	 * Horizon - Topic".
	 */
	@Test
	fun `resolves the reported track`() {
		val match = SearchResultsParser.bestMatch(
			SearchResultsParser.candidates(fixture),
			title = "Doomed",
			channel = "Bring Me The Horizon - Topic",
			durationSec = 274,
		)
		assertEquals("CZFTfYYql4k", match?.videoId)
	}

	/**
	 * The dangerous one. Same title, nearly the same length, different artist.
	 * Asking as Maphra must return Maphra's upload, never the BMTH original.
	 */
	@Test
	fun `does not confuse a same-titled cover by another artist`() {
		val match = SearchResultsParser.bestMatch(
			SearchResultsParser.candidates(fixture),
			title = "Doomed",
			channel = "Maphra - Topic",
			durationSec = 276,
		)
		assertEquals("DIEI2YLYg6o", match?.videoId)
	}

	@Test
	fun `a wrong duration is not a match`() {
		assertNull(
			SearchResultsParser.bestMatch(
				SearchResultsParser.candidates(fixture),
				title = "Doomed",
				channel = "Bring Me The Horizon - Topic",
				durationSec = 200,
			),
		)
	}

	@Test
	fun `a channel that matches nothing is not a match`() {
		assertNull(
			SearchResultsParser.bestMatch(
				SearchResultsParser.candidates(fixture),
				title = "Doomed",
				channel = "Some Reupload Channel",
				durationSec = 274,
			),
		)
	}

	/** Without a duration the match can't be verified, so it must be refused. */
	@Test
	fun `no duration means no match`() {
		assertNull(
			SearchResultsParser.bestMatch(
				SearchResultsParser.candidates(fixture),
				title = "Doomed",
				channel = "Bring Me The Horizon - Topic",
				durationSec = null,
			),
		)
	}

	/** A blank channel is the common no-evidence case and must not match. */
	@Test
	fun `no channel means no match`() {
		assertNull(
			SearchResultsParser.bestMatch(
				SearchResultsParser.candidates(fixture),
				title = "Doomed",
				channel = null,
				durationSec = 274,
			),
		)
	}

	/**
	 * VEVO channels are written as one word. The media session reports
	 * `systemofadownVEVO`; search lists the owner as `System Of A Down`.
	 * Stripping the suffix left `systemofadown`, which never equalled
	 * `system of a down` — so every VEVO track failed to resolve. Three of the
	 * four missing `url`s in the 2026-07-28 session were this, and each time
	 * the right video was the *first* result, exact title, one second off.
	 */
	@Test
	fun `a VEVO channel matches its spaced-out search listing`() {
		val body = """{"contents":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":
			{"contents":[{"videoRenderer":{"videoId":"CSvFpBOe8eY",
			"title":{"runs":[{"text":"System Of A Down - Chop Suey! (Official HD Video)"}]},
			"ownerText":{"runs":[{"text":"System Of A Down"}]},
			"lengthText":{"simpleText":"3:29"}}}]}}]}}}""".trimIndent()
		val match = SearchResultsParser.bestMatch(
			SearchResultsParser.candidates(body),
			title = "System Of A Down - Chop Suey! (Official HD Video)",
			channel = "systemofadownVEVO",
			durationSec = 208,
		)
		assertEquals("CSvFpBOe8eY", match?.videoId)
	}

	@Test
	fun `channel keys ignore spacing and known suffixes`() {
		assertEquals(
			SearchResultsParser.channelKey("System Of A Down"),
			SearchResultsParser.channelKey("systemofadownVEVO"),
		)
		assertEquals(
			SearchResultsParser.channelKey("The Verve"),
			SearchResultsParser.channelKey("TheVerveVEVO"),
		)
		assertEquals(
			SearchResultsParser.channelKey("Linkin Park"),
			SearchResultsParser.channelKey("Linkin Park - Topic"),
		)
		// Different artists must still differ.
		assertNotEquals(
			SearchResultsParser.channelKey("Bon Jovi"),
			SearchResultsParser.channelKey("Bon Iver"),
		)
	}

	@Test
	fun `clock parsing handles both shapes`() {
		assertEquals(275L, SearchResultsParser.parseClock("4:35"))
		assertEquals(3753L, SearchResultsParser.parseClock("1:02:33"))
		assertNull(SearchResultsParser.parseClock("LIVE"))
	}

	@Test
	fun `empty results yield nothing rather than throwing`() {
		val c = SearchResultsParser.candidates("""{"contents":{}}""")
		assertEquals(0, c.size)
		assertNull(SearchResultsParser.bestMatch(c, "Doomed", "BMTH", 274))
	}

	/** The extractor the resolver reuses must find ytInitialData in a page. */
	@Test
	fun `initial data is extractable from a page wrapper`() {
		val page = "<html><script>var ytInitialData = $fixture;</script></html>"
		assertNotNull(WatchPageParser.extractJson(page, "ytInitialData"))
	}
}
