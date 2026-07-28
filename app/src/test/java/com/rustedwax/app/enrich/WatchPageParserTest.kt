package com.rustedwax.app.enrich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the fragile half of decision D8.
 *
 * The resolver scrapes an undocumented blob out of a page that can change
 * without notice. These tests are what turns that drift into a failing build
 * rather than a scrobble that quietly stops being enriched — the fixture below
 * is the shape the parser expects, trimmed to the fields actually read.
 */
class WatchPageParserTest {

	private val fixture = """
		<!DOCTYPE html><html><head><title>x</title></head><body>
		<script nonce="abc">var ytInitialPlayerResponse = {"responseContext":{},
		"videoDetails":{"videoId":"ICSCJBc9fkY",
		"title":"【Bring Me The Horizon】I Used to Make Out With Medusa",
		"lengthSeconds":"254","author":"OLD MOON CHILD",
		"shortDescription":"Original song by Bring Me The Horizon\nTabs in the video {not json}"},
		"microformat":{"playerMicroformatRenderer":{"category":"Music",
		"publishDate":"2023-01-01"}}};var meta = {"other":"thing"};</script>
		</body></html>
	""".trimIndent()

	@Test
	fun `extracts the player response from a watch page`() {
		val json = WatchPageParser.extractJson(fixture, "ytInitialPlayerResponse")
		assertNotNull(json)
		val facts = WatchPageParser.parsePlayerResponse("ICSCJBc9fkY", json!!)
		assertEquals("OLD MOON CHILD", facts.author)
		assertEquals("Music", facts.category)
		assertEquals("Bring Me The Horizon", facts.originalArtist)
	}

	/**
	 * The description contains braces and the script continues after the blob,
	 * so brace matching has to stop in the right place. A regex to the first
	 * `}` truncates; a regex to the last swallows the next statement.
	 */
	@Test
	fun `brace matching stops at the end of the object`() {
		val json = WatchPageParser.extractJson(fixture, "ytInitialPlayerResponse")!!
		assertEquals('{', json.first())
		assertEquals('}', json.last())
		assertEquals(false, json.contains("var meta"))
	}

	@Test
	fun `reports nothing when the marker is missing`() {
		assertNull(WatchPageParser.extractJson("<html>nothing here</html>", "ytInitialPlayerResponse"))
	}

	@Test
	fun `mines auto-generated topic credits`() {
		val credits = WatchPageParser.mineDescription(
			"Provided to YouTube by Universal Music Group\n\nTrash · Korn\n\nUntouchables",
		)
		assertEquals("Korn", credits?.first)
		assertEquals("Trash", credits?.second)
	}

	@Test
	fun `mines hand-written cover credits`() {
		assertEquals(
			"Bring Me The Horizon",
			WatchPageParser.mineDescription("Original song by Bring Me The Horizon")?.first,
		)
		assertEquals(
			"Slipknot",
			WatchPageParser.mineDescription("Cover I did.\nOriginally by Slipknot\nEnjoy")?.first,
		)
		assertEquals(
			"Korn",
			WatchPageParser.mineDescription("Artist: Korn\nAlbum: Untouchables")?.first,
		)
	}

	/**
	 * Free prose must yield nothing. Half-guessing an artist here writes
	 * confident nonsense to a chain that can't be edited.
	 */
	@Test
	fun `leaves prose alone`() {
		assertNull(WatchPageParser.mineDescription("Thanks for watching! Subscribe for more."))
		assertNull(WatchPageParser.mineDescription(""))
	}
}
