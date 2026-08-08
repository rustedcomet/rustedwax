package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
		// The control is excluded, leaving no title at all — which is a Short
		// with an unread title, not a refusal.
		assertNoTitle(
			player(Case("Use this sound", "@owner", 1, 20), titleId = "sound_button"),
		)
	}

	@Test
	fun `missing and conflicting structural evidence refuses`() {
		assertInvalid(player(Case("Title", "@owner", 1, 20), includeHandle = false))
		assertInvalid(player(Case("Title", "@owner", 1, 20), includeSeekbar = false))
		assertInvalid(player(Case("Title", "@owner", 1, 20), secondHandle = "@different"))
		assertNoTitle(player(Case("Title", "@owner", 1, 20), secondTitle = "Other title"))
		assertInvalid(player(Case("Title", "@owner", 1, 20), secondSeekbar = 2L to 30L))
	}

	/**
	 * The owner handle is the one mandatory field, so losing it loses the whole
	 * listen — and the 2026-08-07 log holds 352 refusals that all read the same,
	 * with no way to tell a footer YouTube never drew from one holding two
	 * handles at once. The two have unrelated fixes, so they say so.
	 */
	@Test
	fun `an absent handle and an ambiguous one are told apart`() {
		val absent = NativeShortParser.parse(
			player(Case("Title", "@owner", 1, 20), includeHandle = false),
		) as NativeShortParser.Result.Invalid
		assertTrue(absent.reason, "found none" in absent.reason)
		assertTrue(absent.reason, "no visible label in the player carried an @" in absent.reason)

		val ambiguous = NativeShortParser.parse(
			player(Case("Title", "@owner", 1, 20), secondHandle = "@different"),
		) as NativeShortParser.Result.Invalid
		assertTrue(ambiguous.reason, "found 2" in ambiguous.reason)
		assertTrue(ambiguous.reason, "@owner" in ambiguous.reason)
		assertTrue(ambiguous.reason, "@different" in ambiguous.reason)
	}

	@Test
	fun `a handle the parser would not accept is quoted rather than lost`() {
		// A footer that *does* carry an @ but not one this parser recognises —
		// a locale we have no "Go to channel" phrase for, a handle outside the
		// accepted character set — is the case worth seeing in the log, because
		// it is the one a parser change could fix.
		val refusal = NativeShortParser.parse(
			player(
				Case("Title", "@owner", 1, 20),
				includeHandle = false,
				extraPlayer = listOf(node(text = "Kanalinaan @Ünïcödé-Öwnér")),
			),
		) as NativeShortParser.Result.Invalid
		assertTrue(refusal.reason, "found none" in refusal.reason)
		assertTrue(refusal.reason, "Kanalinaan @Ünïcödé-Öwnér" in refusal.reason)
	}

	@Test
	fun `the handle refusal detail does not defeat the diagnostic throttle`() {
		// Keyed on the shape, not the payload: a footer that changes with every
		// Short must still coalesce to one line per window (FIELD §17.1).
		val first = NativeShortParser.parse(
			player(Case("A", "@a", 1, 20), includeHandle = false),
		) as NativeShortParser.Result.Invalid
		val second = NativeShortParser.parse(
			player(
				Case("B", "@b", 1, 20),
				includeHandle = false,
				extraPlayer = listOf(node(text = "Go to canal @somethingelse")),
			),
		) as NativeShortParser.Result.Invalid
		assertEquals(
			NativeShortDiagnosticKey.of(first.reason),
			NativeShortDiagnosticKey.of(second.reason),
		)
		assertTrue(first.reason != second.reason)
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
		// Two real candidates must never resolve to one of them. The Short is
		// still tracked and measured — identity comes from watch history — but
		// the on-screen title is dropped rather than guessed.
		assertNoTitle(
			player(
				Case("Title", "@owner", 1, 20),
				secondTitle = "Other title",
				extraPlayer = listOf(node(description = "Auto-dubbed")),
			),
		)
	}

	/**
	 * Measured 2026-08-06 23:00–01:20: YouTube stopped rendering the Shorts
	 * progress bar altogether — no `SeekBar` node anywhere in the tree and no bar
	 * on screen — until the viewer taps the video once, which restores it for the
	 * rest of the session. 47 of 71 Shorts in 85 minutes were lost, because a
	 * Short that is never *started* can never accrue anything at all.
	 *
	 * The player, its container and the exact handle are all still proven, so
	 * this is a proof without a reading, not a refusal.
	 */
	@Test
	fun `a named player with no readable time is proven, not refused`() {
		val result = NativeShortParser.parse(
			player(Case("Title", "@owner", 1, 20), seekbarLabel = "8 of 67 seconds"),
		)
		assertTrue("$result", result is NativeShortParser.Result.OrganicUnmeasured)
		val unmeasured = result as NativeShortParser.Result.OrganicUnmeasured
		assertEquals("Title", unmeasured.title)
		assertEquals("@owner", unmeasured.ownerHandle)
	}

	@Test
	fun `a missing seekbar container is still a structural refusal`() {
		// A container that is not there at all is a swipe or a torn frame, not a
		// live player whose bar YouTube declined to draw. It keeps its own
		// message, which a field log once could not tell apart from the other.
		val missingContainer = NativeShortParser.parse(
			player(Case("Title", "@owner", 1, 20), includeSeekbar = false),
		)
		assertTrue(missingContainer is NativeShortParser.Result.Invalid)
		assertFalse((missingContainer as NativeShortParser.Result.Invalid).progressSurfaceLost)
		assertTrue(missingContainer.reason.contains("container"))
	}

	@Test
	fun `no handle and no readable time is the picture-in-picture signature`() {
		// FIELD_2026-08-05.md §4.2: a Short live in picture-in-picture keeps
		// reel_watch_fragment_root, reel_watch_player and reel_time_bar, but the
		// time bar loses its SeekBar child and the window has no footer to read
		// an owner from. Nothing there can start a Short — it only ever credits
		// one that was already proven.
		val pip = NativeShortParser.parse(
			player(
				Case("Title", "@owner", 1, 20),
				seekbarLabel = "8 of 67 seconds",
				includeHandle = false,
			),
		)
		assertTrue((pip as NativeShortParser.Result.Invalid).progressSurfaceLost)

		// A readable time with no handle is an ordinary refusal: the surface is
		// plainly still there.
		val noHandle = NativeShortParser.parse(
			player(Case("Title", "@owner", 1, 20), includeHandle = false),
		)
		assertFalse((noHandle as NativeShortParser.Result.Invalid).progressSurfaceLost)

		// Two readable times is an ambiguous read of a surface that is still
		// there — refuse, and never treat it as unmeasured.
		val ambiguous = NativeShortParser.parse(
			player(Case("Title", "@owner", 1, 20), secondSeekbar = 2L to 30L),
		)
		assertTrue(ambiguous is NativeShortParser.Result.Invalid)
		assertFalse((ambiguous as NativeShortParser.Result.Invalid).progressSurfaceLost)
	}

	@Test
	fun `ordinary refusals never claim the progress surface was lost`() {
		// The regression this guards: wiring the marker to generic proof loss put
		// it on essentially every Short finalize (FIELD §3.1).
		listOf(
			player(Case("Title", "@owner", 1, 20), includeHandle = false),
			player(Case("Title", "@owner", 1, 20), secondHandle = "@different"),
			player(Case("Title", "@owner", 1, 20)).copy(exceededCaptureBudget = true),
			NativeShortTree(node(pkg = "com.other.app")),
		).forEach { tree ->
			val result = NativeShortParser.parse(tree)
			assertFalse(
				"$result should not claim a lost progress surface",
				(result as NativeShortParser.Result.Invalid).progressSurfaceLost,
			)
		}
	}

	@Test
	fun `the measured id-less footer resolves despite its like and comment counters`() {
		// Captured from the device on 2026-08-05 (@MontRecaps). YouTube had
		// dropped `reel_title` from the Shorts footer entirely, so the id-bound
		// pass finds nothing and the fallback sees every text node. The like and
		// comment counters are bare ViewGroups carrying only their number, with
		// no id, no button class and no control vocabulary — so before the count
		// filter there were three survivors and every organic Short refused.
		val footer = listOf(
			node(description = "Go to channel @MontRecaps"),
			node(text = "@MontRecaps", description = "@MontRecaps"),
			node(text = "@MontRecaps", className = "android.widget.Button"),
			node(description = "Subscribe to @MontRecaps.", className = "android.widget.Button"),
			node(text = "Subscribe", description = "Subscribe"),
			node(text = "He Chose Hell for Love ❤️", description = "He Chose Hell for Love ❤️"),
			node(
				description = "like this video along with 2 thousand other people",
				className = "android.widget.RadioButton",
			),
			node(text = "2K", description = "2K"),
			node(description = "View 14 comments", className = "android.widget.Button"),
			node(text = "14", description = "14"),
			node(description = "Share this video", className = "android.widget.Button"),
			node(text = "Share", description = "Share"),
			node(description = "Remix", className = "android.widget.Button"),
			node(text = "Remix", description = "Remix"),
			node(
				description = "See more videos using this sound",
				className = "android.widget.Button",
			),
		)
		val structural = node(
			id = "reel_watch_fragment_root",
			children = listOf(node(id = "reel_watch_player", children = footer)),
		)
		val timeBar = node(
			id = "reel_time_bar",
			children = listOf(
				node(className = "android.widget.SeekBar", description = "1 minute 21 seconds of 2 minutes 59 seconds"),
			),
		)
		assertEquals(
			NativeShortParser.Result.Organic("He Chose Hell for Love ❤️", "@montrecaps", 81, 179),
			NativeShortParser.parse(
				NativeShortTree(node(pkg = YT, children = listOf(structural, timeBar))),
			),
		)
	}

	@Test
	fun `counters are excluded by shape but real titles are not`() {
		// Only a literal that is *entirely* a count is dropped. A title that
		// merely contains or starts with a number keeps its text.
		listOf("14", "2K", "1.2M", "999", "2,5 mil", "12 500", "3B").forEach { counter ->
			assertEquals(
				"$counter should not be a title",
				NativeShortParser.Result.Organic("Real title", "@owner", 1, 20),
				NativeShortParser.parse(
					player(
						Case("Real title", "@owner", 1, 20),
						titleId = null,
						extraPlayer = listOf(node(text = counter, description = counter)),
					),
				),
			)
		}
		// A genuinely conflicting prose title is still never guessed at.
		assertNoTitle(
			player(
				Case("Real title", "@owner", 1, 20),
				titleId = null,
				extraPlayer = listOf(node(text = "2000 reasons to leave", description = "2000 reasons to leave")),
			),
		)
	}

	@Test
	fun `an uncounted View comments control is not a title`() {
		// Measured 2026-08-05. A Short with no comments renders a bare "View
		// comments"; the counted-only phrase missed it, so it stood as a second
		// title candidate and refused identity on every such Short.
		listOf("View comments", "View 14 comments", "View 1 comment", "View 2,417 comments")
			.forEach { control ->
				assertEquals(
					"$control should not be a title",
					NativeShortParser.Result.Organic("Real title", "@owner", 1, 20),
					NativeShortParser.parse(
						player(
							Case("Real title", "@owner", 1, 20),
							titleId = null,
							extraPlayer = listOf(node(description = control)),
						),
					),
				)
			}
	}

	@Test
	fun `position resolves an id-less footer the blocklist cannot`() {
		// The real shape measured on the device: no resource ids anywhere in the
		// footer, and an unknown control the blocklist has never seen. Before
		// geometry this refused; the title runs along the bottom-left while every
		// control that keeps being mistaken for it is pinned to the right column.
		val footer = listOf(
			bounded(30, 1360, 640, 1390, text = "He Chose Hell for Love ❤️"),
			bounded(630, 880, 700, 950, text = "2K"),
			bounded(630, 980, 700, 1050, text = "14"),
			bounded(628, 1100, 700, 1170, description = "Some brand new control"),
			bounded(30, 1300, 300, 1340, description = "Go to channel @MontRecaps"),
		)
		val structural = node(
			id = "reel_watch_fragment_root",
			children = listOf(node(id = "reel_watch_player", children = footer)),
		)
		val timeBar = node(
			id = "reel_time_bar",
			children = listOf(node(description = "1 minute 21 seconds of 2 minutes 59 seconds")),
		)
		assertEquals(
			NativeShortParser.Result.Organic("He Chose Hell for Love ❤️", "@montrecaps", 81, 179),
			NativeShortParser.parse(
				NativeShortTree(node(pkg = YT, children = listOf(structural, timeBar))),
			),
		)
	}

	@Test
	fun `two left-aligned candidates still refuse rather than guess`() {
		// Geometry narrows; it never picks. A wrong title is a permanent wrong
		// scrobble, so genuine ambiguity must still fail closed.
		val footer = listOf(
			bounded(30, 1360, 640, 1390, text = "He Chose Hell for Love ❤️"),
			bounded(30, 1240, 620, 1280, text = "A completely different sentence"),
			bounded(630, 880, 700, 950, text = "2K"),
			bounded(30, 1300, 300, 1340, description = "Go to channel @MontRecaps"),
		)
		val structural = node(
			id = "reel_watch_fragment_root",
			children = listOf(node(id = "reel_watch_player", children = footer)),
		)
		val timeBar = node(
			id = "reel_time_bar",
			children = listOf(node(description = "0 minutes 8 seconds of 0 minutes 48 seconds")),
		)
		assertNoTitle(NativeShortTree(node(pkg = YT, children = listOf(structural, timeBar))))
	}

	private fun bounded(
		left: Int,
		top: Int,
		right: Int,
		bottom: Int,
		text: String? = null,
		description: String? = null,
	) = NativeShortNode(
		text = text,
		contentDescription = description,
		left = left,
		top = top,
		right = right,
		bottom = bottom,
	)

	@Test
	fun `a bare upload date is not a title`() {
		// Measured 2026-08-05: a Short finalized with the title "August 5, 2026".
		// It failed closed, but letting the date chip through cost the real title.
		listOf(
			"August 5, 2026", "5 August 2026", "2026-08-05", "5/8/2026",
			"Aug 5, 2026", "5 de agosto de 2026",
		).forEach { date ->
			assertEquals(
				"$date should not be a title",
				NativeShortParser.Result.Organic("Real title", "@owner", 1, 20),
				NativeShortParser.parse(
					player(
						Case("Real title", "@owner", 1, 20),
						titleId = null,
						extraPlayer = listOf(node(text = date, description = date)),
					),
				),
			)
		}
		// A title that merely mentions a date keeps its text.
		assertEquals(
			NativeShortParser.Result.Organic("August 5, 2026 was the day it all changed", "@owner", 1, 20),
			NativeShortParser.parse(
				player(Case("August 5, 2026 was the day it all changed", "@owner", 1, 20)),
			),
		)
	}

	private data class Case(val title: String, val handle: String, val current: Long, val total: Long)

	private fun player(
		case: Case,
		titleId: String? = "reel_title",
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

	/**
	 * A proven, measurable Short whose on-screen title could not be read.
	 *
	 * The listen survives — identity is resolved from watch history on owner
	 * handle + duration — but the title is never guessed from two candidates.
	 */
	private fun assertNoTitle(tree: NativeShortTree) {
		val result = NativeShortParser.parse(tree)
		assertTrue("expected Organic, got $result", result is NativeShortParser.Result.Organic)
		assertNull((result as NativeShortParser.Result.Organic).title)
	}

	private fun assertInvalid(tree: NativeShortTree) {
		assertTrue(NativeShortParser.parse(tree) is NativeShortParser.Result.Invalid)
	}

	private companion object {
		const val YT = YouTubeProbe.YOUTUBE_PACKAGE
	}
}
