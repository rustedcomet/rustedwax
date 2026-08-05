package com.rustedwax.app.enrich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeStructuredMusicMatcherTest {
	private val resolver = VideoIdResolver()

	private fun candidate(
		id: String,
		title: String,
		channel: String,
		duration: Long,
	) = VideoResolution(id, "fixture", title, channel, duration)

	@Test
	fun `four measured native playlist shapes reduce to exact works and credits`() {
		val cases = listOf(
			Triple(
				candidate(
					"U8urOf52AlA", "Ozuna - Se Preparó (Video Oficial) | Odisea", "Ozuna", 188,
				),
				"Se Preparó",
				"Ozuna",
			),
			Triple(
				candidate(
					"t_jHrUE5IOk", "Maluma - Felices los 4 (Official Video)", "Maluma", 230,
				),
				"Felices los 4",
				"Maluma",
			),
			Triple(
				candidate(
					"abcdef12345",
					"J Balvin - Si Tu Novio Te Deja Sola ft. Bad Bunny",
					"J Balvin",
					244,
				),
				"Si Tu Novio Te Deja Sola",
				"Bad Bunny",
			),
			Triple(
				candidate(
					"5YXxnHVYRDk",
					"Ozuna - No Quiere Enamorarse (Official Lyric Video)",
					"Ozunapr",
					213,
				),
				"No Quiere Enamorarse",
				"Ozuna",
			),
		)

		for ((page, nativeTitle, nativeArtist) in cases) {
			assertTrue(
				"${page.title} should prove $nativeArtist — $nativeTitle",
				NativeStructuredMusicMatcher.matches(
					page, nativeTitle, nativeArtist, page.lengthSeconds!!,
				),
			)
		}
	}

	@Test
	fun `bare canonical title still requires exact page channel credit`() {
		val exact = candidate("abcdefghijk", "Bonita", "Release - Topic", 265)
		assertTrue(
			NativeStructuredMusicMatcher.matches(exact, "Bonita", "Release - Topic", 265),
		)
		assertFalse(
			NativeStructuredMusicMatcher.matches(
				exact.copy(channel = "Unrelated Repost"), "Bonita", "Release - Topic", 265,
			),
		)
	}

	@Test
	fun `credit matching is complete and never substring based`() {
		val page = candidate(
			"abcdefghijk",
			"J Balvin - Si Tu Novio Te Deja Sola ft. Bad Bunny",
			"J Balvin",
			244,
		)
		assertTrue(
			NativeStructuredMusicMatcher.matches(
				page, "Si Tu Novio Te Deja Sola", "Bad Bunny", 244,
			),
		)
		assertFalse(
			NativeStructuredMusicMatcher.matches(
				page, "Si Tu Novio Te Deja Sola", "Bunny", 244,
			),
		)
		assertFalse(
			NativeStructuredMusicMatcher.matches(
				page, "Si Tu Novio Te Deja", "Bad Bunny", 244,
			),
		)
	}

	@Test
	fun `search card consumes budget only after exact work and complete artist credit`() {
		assertTrue(
			NativeStructuredMusicMatcher.couldDescribeTrack(
				"Ozuna - Se Preparó (Video Oficial)", "Ozuna", "Se Preparó", "Ozuna",
			),
		)
		assertTrue(
			NativeStructuredMusicMatcher.couldDescribeTrack(
				"J Balvin - Si Tu Novio Te Deja Sola ft. Bad Bunny",
				"J Balvin",
				"Si Tu Novio Te Deja Sola",
				"Bad Bunny",
			),
		)
		assertFalse(
			NativeStructuredMusicMatcher.couldDescribeTrack(
				"Ozuna - Se Preparó (Video Oficial)", "Ozuna", "Se Preparó", "Ozu",
			),
		)
		assertFalse(
			NativeStructuredMusicMatcher.couldDescribeTrack(
				"Se Preparó", "Unrelated Repost", "Se Preparó", "Ozuna",
			),
		)
		assertFalse(
			NativeStructuredMusicMatcher.couldDescribeTrack(
				"Ozuna - Se Preparó for the tour", "Ozuna", "Se Preparó", "Ozuna",
			),
		)
	}

	@Test
	fun `exact canonical author survives a collaboration presentation prefix`() {
		val criminal = candidate(
			"VqEbCxg2bNI",
			"Natti Natasha ❌ Ozuna - Criminal [Official Video]",
			"NATTI NATASHA",
			273,
		)
		assertTrue(
			NativeStructuredMusicMatcher.couldDescribeTrack(
				criminal.title, criminal.channel, "Criminal", "NATTI NATASHA",
			),
		)
		assertTrue(
			NativeStructuredMusicMatcher.matches(
				criminal, "Criminal", "NATTI NATASHA", 273,
			),
		)
		assertEquals(
			"VqEbCxg2bNI",
			NativeStructuredMusicMatcher.select(
				listOf(criminal), "Criminal", "NATTI NATASHA", 273,
			).resolution?.videoId,
		)

		assertFalse(
			NativeStructuredMusicMatcher.matches(
				criminal.copy(channel = "Unrelated Repost"),
				"Criminal",
				"NATTI NATASHA",
				273,
			),
		)
		assertFalse(
			NativeStructuredMusicMatcher.matches(criminal, "Criminal", "Natti", 273),
		)
	}

	@Test
	fun `native featured work uses the same narrow suffix grammar as canonical pages`() {
		val officialAudio = candidate(
			"ohp8cXhIHXc",
			"Aventura feat. Don Omar - Ella Y Yo (Official Audio)",
			"Aventura",
			269,
		)
		val topicLive = candidate(
			"3-t7qlOfikI",
			"Ella y Yo (Live)",
			"Aventura - Topic",
			268,
		)

		assertTrue(
			NativeStructuredMusicMatcher.matches(
				officialAudio, "Ella Y Yo (Feat. Don Omar)", "Aventura", 268,
			),
		)
		val ambiguous = NativeStructuredMusicMatcher.select(
			listOf(officialAudio, topicLive),
			"Ella Y Yo (Feat. Don Omar)",
			"Aventura",
			268,
		)
		assertNull(ambiguous.resolution)
		assertTrue(ambiguous.refusalReason.orEmpty().contains("ambiguous identity — 2 uploads"))

		assertFalse(
			NativeStructuredMusicMatcher.matches(
				officialAudio.copy(title = "Aventura - Ella Y Yo"),
				"Ella Y Yo (Remix)",
				"Aventura",
				268,
			),
		)
		assertFalse(
			NativeStructuredMusicMatcher.matches(
				officialAudio.copy(title = "Aventura - Ella Y Yo"),
				"Ella Y Yo (Sold Out at Madison Square Garden)",
				"Aventura",
				268,
			),
		)
	}

	@Test
	fun `measured release topic artist and duration contradictions remain unresolved`() {
		val rightDurationWrongCredits = candidate(
			"QkngZ1P3aKw",
			"Ozuna Feat. Juanka El Problematik - Si Te Dejas Llevar",
			"Juanka El Problematik",
			228,
		)
		val rightAuthorWrongDuration = candidate(
			"B5eRr5sds-M",
			"Si Te Dejas Llevar",
			"Release - Topic",
			218,
		)

		val attempt = NativeStructuredMusicMatcher.select(
			listOf(rightDurationWrongCredits, rightAuthorWrongDuration),
			"Si Te Dejas Llevar",
			"Release - Topic",
			228,
		)
		assertNull(attempt.resolution)
		assertTrue(attempt.refusalReason.orEmpty().contains("no fully fetched candidate matched"))
	}

	@Test
	fun `artist-aware prefilter removes title noise before bounded page verification`() {
		val exact = (0 until VideoIdResolver.MAX_WATCH_PAGE_CANDIDATES).map { index ->
			SearchResultsParser.Candidate(
				videoId = "exact${index.toString().padStart(6, '0')}",
				title = "Ozuna - Se Preparó (Video Oficial)",
				channel = "Ozuna",
				lengthSeconds = 188,
			)
		}
		val titleOnlyNoise = (0 until 20).map { index ->
			SearchResultsParser.Candidate(
				videoId = "noise${index.toString().padStart(6, '0')}",
				title = "Se Preparó",
				channel = "Unrelated Repost $index",
				lengthSeconds = 188,
			)
		}
		assertEquals(
			exact.map(SearchResultsParser.Candidate::videoId),
			resolver.structuredNativeCandidates(
				exact + titleOnlyNoise, "Se Preparó", "Ozuna", 188,
			).map(SearchResultsParser.Candidate::videoId),
		)
		val overBudget = exact + SearchResultsParser.Candidate(
			videoId = "exact999999",
			title = "Ozuna - Se Preparó (Lyrics)",
			channel = "Ozuna",
			lengthSeconds = 188,
		)
		assertEquals(
			VideoIdResolver.MAX_WATCH_PAGE_CANDIDATES + 1,
			resolver.structuredNativeCandidates(
				overBudget, "Se Preparó", "Ozuna", 188,
			).size,
		)
	}

	@Test
	fun `duration conflict and non structural containment refuse`() {
		val page = candidate(
			"abcdefghijk", "Ozuna - Se Preparó (Video Oficial)", "Ozuna", 188,
		)
		assertFalse(NativeStructuredMusicMatcher.matches(page, "Se Preparó", "Ozuna", 32))
		assertFalse(
			NativeStructuredMusicMatcher.matches(
				page.copy(title = "How Ozuna Se Preparó for the tour"),
				"Se Preparó",
				"Ozuna",
				188,
			),
		)
	}

	@Test
	fun `selection requires exactly one fully corroborated upload`() {
		val page = candidate(
			"abcdefghijk", "Ozuna - Se Preparó (Video Oficial)", "Ozuna", 188,
		)
		val unique = NativeStructuredMusicMatcher.select(
			listOf(page), "Se Preparó", "Ozuna", 188,
		)
		assertEquals("abcdefghijk", unique.resolution?.videoId)
		assertTrue(unique.resolution?.structuredNativeMusic == true)

		val ambiguous = NativeStructuredMusicMatcher.select(
			listOf(page, page.copy(videoId = "zyxwvutsrqp")), "Se Preparó", "Ozuna", 188,
		)
		assertNull(ambiguous.resolution)
		assertTrue(ambiguous.refusalReason.orEmpty().contains("ambiguous identity"))
	}
}
