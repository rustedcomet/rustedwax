package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeShortParserTest {

	@Test
	fun `measured organic Shorts require player title handle and seekbar`() {
		val cases = listOf(
			Case("Dura - Becky G 🔥", "@fansclubbeckyg394", 7, 20),
			Case("Spider - Man ...", "@Etzy-Am", 31, 92),
			Case("🎭 He Put on a Magic Mask! | The Mask (1994)😂#shorts #movierecap", "@SonaDarus", 61, 158),
			Case("Which is your favorite team? ⚡ Team Iron Man or 🛡️ Team Captain America? #Marvel", "@Status_svijet", 12, 41),
			Case("Hackers' Skills...", "@Beredist", 48, 139),
		)
		cases.forEach { expected ->
			assertEquals(
				NativeShortParser.Result.Organic(
					expected.title,
					expected.handle.lowercase(),
					expected.current,
					expected.total,
				),
				NativeShortParser.parse(player(expected)),
			)
		}
	}

	@Test
	fun `Samsung measured sibling overlay and time bar shape parses`() {
		val title = "🎭 He Put on a Magic Mask! | The Mask (1994)😂#shorts #movierecap"
		val overlay = node(
			id = "reel_player_overlay_container",
			children = listOf(
				node(description = "Go to channel @SonaDarus", clickable = true, className = "android.widget.Button"),
				node(description = "@SonaDarus"),
				node(description = "Subscribe to @SonaDarus.", clickable = true, className = "android.widget.Button"),
				node(description = title, clickable = true),
				// Measured on the live A12 write gate: YouTube duplicated one
				// title hashtag as a standalone semantic navigation chip.
				node(description = "#movierecap", clickable = true),
				node(description = "Original Sound (Contains music from: Retro · Wayne Jones)", clickable = true, className = "android.widget.Button"),
				node(description = "like this video along with 595 thousand other people", clickable = true, className = "android.widget.RadioButton"),
				node(description = "View 2,417 comments", clickable = true, className = "android.widget.Button"),
				node(description = "Share this video", clickable = true, className = "android.widget.Button"),
				node(description = "Play video", clickable = true, className = "android.widget.ImageView"),
				node(description = "Next Video", clickable = true, className = "android.widget.ImageView"),
			),
		)
		val structural = node(
			id = "reel_watch_fragment_root",
			children = listOf(overlay, node(id = "reel_watch_player")),
		)
		val timeBar = node(
			id = "reel_time_bar",
			children = listOf(node(description = "0 minutes 20 seconds of 2 minutes 38 seconds")),
		)
		assertEquals(
			NativeShortParser.Result.Organic(title, "@sonadarus", 20, 158),
			NativeShortParser.parse(NativeShortTree(node(pkg = YT, children = listOf(structural, timeBar)))),
		)
	}

	@Test
	fun `supported measured time phrases parse and unsupported shapes refuse`() {
		assertEquals(7L to 20L, NativeShortParser.parseTime("0 minutes 7 seconds of 0 minutes 20 seconds"))
		assertEquals(61L to 158L, NativeShortParser.parseTime("1 minute 1 second of 2 minutes 38 seconds"))
		assertEquals(7L to 20L, NativeShortParser.parseTime("0 minutos 7 segundos de 0 minutos 20 segundos"))
		assertEquals(67L to 140L, NativeShortParser.parseTime("1 minuto e 7 segundos de 2 minutos e 20 segundos"))
		assertEquals(null, NativeShortParser.parseTime("7 of 20 seconds"))
		assertEquals(null, NativeShortParser.parseTime("0 Minuten 7 Sekunden von 0 Minuten 20 Sekunden"))
		assertEquals(null, NativeShortParser.parseTime("0 minutes 20 seconds of 0 minutes 0 seconds"))
		assertEquals(null, NativeShortParser.parseTime("0 minutes 21 seconds of 0 minutes 20 seconds"))
	}

	@Test
	fun `home cards ordinary pages and comments are not playback proof`() {
		val card = node(
			id = "rich_item_content",
			children = listOf(node(text = "Dura - Becky G 🔥"), node(text = "@fansclubbeckyg394")),
		)
		assertInvalid(NativeShortTree(node(pkg = YT, children = listOf(card))))
		assertInvalid(
			NativeShortTree(
				node(
					pkg = YT,
					id = "watch_player",
					children = listOf(node(text = "x title"), node(text = "@owner")),
				),
			),
		)
		assertInvalid(
			player(Case("x title", "@owner", 1, 20), extraRoot = listOf(node(id = "comments_panel"))),
		)
	}

	@Test
	fun `controls and sound labels cannot become the title`() {
		val tree = player(
			Case("Use this sound", "@owner", 1, 20),
			titleId = "sound_button",
		)
		assertInvalid(tree)
	}

	@Test
	fun `missing and conflicting structural evidence refuses`() {
		assertInvalid(player(Case("Title", "@owner", 1, 20), includeHandle = false))
		assertInvalid(player(Case("Title", "@owner", 1, 20), includeSeekbar = false))
		assertInvalid(player(Case("Title", "@owner", 1, 20), secondHandle = "@different"))
		assertInvalid(player(Case("Title", "@owner", 1, 20), secondTitle = "Other title"))
		assertInvalid(player(Case("Title", "@owner", 1, 20), secondSeekbar = 2L to 30L))
	}

	@Test
	fun `hidden evidence and scan budgets fail closed`() {
		assertInvalid(player(Case("Title", "@owner", 1, 20), handleVisible = false))
		assertInvalid(player(Case("Title", "@owner", 1, 20)).copy(exceededCaptureBudget = true))
		var deep = node(text = "leaf")
		repeat(26) { deep = node(children = listOf(deep)) }
		assertInvalid(NativeShortTree(node(pkg = YT, children = listOf(deep))))
	}

	@Test
	fun `literal ad label binds only inside the proven player`() {
		val inside = NativeShortParser.parse(
			player(Case("Advert title", "@advertiser", 3, 18), extraPlayer = listOf(node(text = "Sponsored"))),
		)
		assertTrue(inside is NativeShortParser.Result.Ad)
		assertEquals("Sponsored", (inside as NativeShortParser.Result.Ad).signal)

		val organicTree = player(Case("Organic title", "@creator", 3, 18))
		val outside = NativeShortParser.parse(
			organicTree.copy(
				root = organicTree.root.copy(
					children = organicTree.root.children + node(text = "Sponsored"),
				),
			),
		)
		assertTrue(outside is NativeShortParser.Result.Organic)
	}

	@Test
	fun `measured auto-dub badge does not compete with the title`() {
		// Captured from the live A12 tree on 2026-08-05, @enefectoescine17. The
		// auto-dubbing badge is a bare semantic View: no resource id, no button
		// class, no control vocabulary, so every other filter missed it. It stood
		// as a second title candidate, `singleOrNull` returned null, and the Short
		// refused identity silently and finalized at `measured 0s`.
		val title = "Shakira Fue a Ver el Partido de Messi y Todos Pensaron lo Mismo 😱 #artista"
		val overlay = node(
			id = "reel_player_overlay_container",
			children = listOf(
				node(description = "Auto-dubbed"),
				node(description = "Go to channel @enefectoescine17", clickable = true),
				node(description = "@enefectoescine17"),
				node(description = "Subscribe to @enefectoescine17.", clickable = true),
				node(description = title, clickable = true),
				node(description = "like this video along with 99 thousand other people", clickable = true),
				node(description = "View 1,109 comments", clickable = true),
				node(description = "Share this video", clickable = true),
				node(description = "Remix this Short along with 11 other remixes", clickable = true),
				node(description = "See more videos using this sound", clickable = true),
			),
		)
		val structural = node(
			id = "reel_watch_fragment_root",
			children = listOf(overlay, node(id = "reel_watch_player")),
		)
		// Measured shape: reel_time_bar is a sibling of the Shorts root under
		// android:id/content, carrying exactly one SeekBar child.
		val timeBar = node(
			id = "reel_time_bar",
			children = listOf(
				node(
					description = "0 minutes 8 seconds of 1 minute 7 seconds",
					className = "android.widget.SeekBar",
				),
			),
		)
		assertEquals(
			NativeShortParser.Result.Organic(title, "@enefectoescine17", 8, 67),
			NativeShortParser.parse(NativeShortTree(node(pkg = YT, children = listOf(structural, timeBar)))),
		)
	}

	@Test
	fun `excluding the badge does not admit a genuinely conflicting title`() {
		assertInvalid(
			player(
				Case("Title", "@owner", 1, 20),
				secondTitle = "Other title",
				extraPlayer = listOf(node(description = "Auto-dubbed")),
			),
		)
	}

	@Test
	fun `a missing seekbar container and an unreadable seekbar time refuse differently`() {
		// One shared message for these two made a field log unable to say which
		// had happened, and the two have unrelated fixes.
		val missingContainer = NativeShortParser.parse(
			player(Case("Title", "@owner", 1, 20), includeSeekbar = false),
		)
		val unreadableTime = NativeShortParser.parse(
			player(Case("Title", "@owner", 1, 20), seekbarLabel = "8 of 67 seconds"),
		)
		assertTrue(missingContainer is NativeShortParser.Result.Invalid)
		assertTrue(unreadableTime is NativeShortParser.Result.Invalid)
		assertTrue(
			(missingContainer as NativeShortParser.Result.Invalid).reason !=
				(unreadableTime as NativeShortParser.Result.Invalid).reason,
		)
	}

	private data class Case(val title: String, val handle: String, val current: Long, val total: Long)

	private fun player(
		case: Case,
		titleId: String = "reel_title",
		includeHandle: Boolean = true,
		includeSeekbar: Boolean = true,
		handleVisible: Boolean = true,
		secondHandle: String? = null,
		secondTitle: String? = null,
		secondSeekbar: Pair<Long, Long>? = null,
		seekbarLabel: String? = null,
		extraPlayer: List<NativeShortNode> = emptyList(),
		extraRoot: List<NativeShortNode> = emptyList(),
	): NativeShortTree {
		val playerChildren = mutableListOf(
			node(id = titleId, text = case.title),
			node(id = "sound_button", text = "Use this sound", clickable = true),
		)
		if (includeHandle) playerChildren += node(text = "Go to channel ${case.handle}", visible = handleVisible)
		secondHandle?.let { playerChildren += node(text = it) }
		secondTitle?.let { playerChildren += node(id = "reel_title", text = it) }
		if (includeSeekbar) playerChildren += seekbar(case.current, case.total, seekbarLabel)
		secondSeekbar?.let { playerChildren += seekbar(it.first, it.second) }
		playerChildren += extraPlayer
		val structural = node(
			id = "reel_watch_fragment_root",
			children = listOf(node(id = "reel_watch_player", children = playerChildren)) + extraRoot,
		)
		return NativeShortTree(node(pkg = YT, children = listOf(structural)))
	}

	private fun seekbar(current: Long, total: Long, label: String? = null): NativeShortNode = node(
		id = "reel_time_bar",
		description = label
			?: ("${current / 60} minutes ${current % 60} seconds of " +
				"${total / 60} minutes ${total % 60} seconds"),
	)

	private fun node(
		pkg: String? = null,
		id: String? = null,
		text: String? = null,
		description: String? = null,
		visible: Boolean = true,
		clickable: Boolean = false,
		className: String? = null,
		children: List<NativeShortNode> = emptyList(),
	) = NativeShortNode(
		packageName = pkg,
		resourceId = id?.let { "com.google.android.youtube:id/$it" },
		text = text,
		contentDescription = description,
		visible = visible,
		clickable = clickable,
		className = className,
		children = children,
	)

	private fun assertInvalid(tree: NativeShortTree) {
		assertTrue(NativeShortParser.parse(tree) is NativeShortParser.Result.Invalid)
	}

	private companion object {
		const val YT = YouTubeProbe.YOUTUBE_PACKAGE
	}
}
