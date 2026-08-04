package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTitleMatcherEvidenceTest {

	private data class Fixture(
		val id: String,
		val canonical: String,
		val session: String,
		val sessionDurationMs: Long,
		val pageDurationSeconds: Long,
	)

	private val log17 = listOf(
		Fixture("TapXs54Ah3E", "Ay Vamos", "J. Balvin - Ay Vamos (Official Video)", 266_921, 266),
		Fixture("at1axdFpcgI", "Esta Noche", "J Quiles - Esta Noche (Official Video)", 239_221, 239),
		Fixture(
			"NgFx3aq52Vg",
			"Me Reclama",
			"Dj Luian & Mambo Kingz - Me Reclama ft. Ozuna, Luigi 21 plus LETRA",
			194_981,
			194,
		),
		Fixture(
			"tdZsL8i5ASA",
			"Recuerdos",
			"Luar La L x Channel - Recuerdos (Official Music Video)",
			266_581,
			266,
		),
		Fixture(
			"tGLP74uofTo",
			"Voy Después",
			"Amenazzy - Voy Después (Video Oficial)",
			211_701,
			211,
		),
	)

	@Test
	fun `log 17 short canonical works are weak evidence for only their own generation`() {
		log17.forEachIndexed { index, fixture ->
			val evidence = VideoTitleMatcher.compare(fixture.canonical, fixture.session)
			assertEquals(fixture.id, VideoTitleMatcher.Evidence.WEAK_SHORT_CANONICAL_CORE, evidence)
			val generation = (index + 11).toLong()
			assertTrue(
				fixture.id,
				SessionProbe.titleEvidenceMayRetainObservedId(
					evidence,
					fixture.id,
					generation,
					fixture.id,
					generation,
					fixture.sessionDurationMs,
					fixture.pageDurationSeconds,
				),
			)
			assertFalse(
				"${fixture.id} cannot replace a successor id",
				SessionProbe.titleEvidenceMayRetainObservedId(
					evidence,
					"successor01",
					generation,
					fixture.id,
					generation,
					fixture.sessionDurationMs,
					fixture.pageDurationSeconds,
				),
			)
		}
	}

	@Test
	fun `weak evidence needs matching generation and independently compatible duration`() {
		val fixture = log17.first()
		val evidence = VideoTitleMatcher.compare(fixture.canonical, fixture.session)
		assertFalse(
			SessionProbe.titleEvidenceMayRetainObservedId(
				evidence, fixture.id, 2, fixture.id, 1,
				fixture.sessionDurationMs, fixture.pageDurationSeconds,
			),
		)
		assertFalse(
			SessionProbe.titleEvidenceMayRetainObservedId(
				evidence, fixture.id, 1, fixture.id, 1,
				fixture.sessionDurationMs, 600,
			),
		)
	}

	@Test
	fun `short artist adjacent works and reordered words contradict`() {
		assertEquals(
			VideoTitleMatcher.Evidence.CONTRADICTION,
			VideoTitleMatcher.compare("Bad Bunny", "Bad Bunny - Another Song"),
		)
		assertEquals(
			VideoTitleMatcher.Evidence.CONTRADICTION,
			VideoTitleMatcher.compare("Recuerdos", "Artist - Chambea"),
		)
		assertEquals(
			VideoTitleMatcher.Evidence.CONTRADICTION,
			VideoTitleMatcher.compare("Esta Noche", "Artist - Otra Noche"),
		)
		assertEquals(
			VideoTitleMatcher.Evidence.CONTRADICTION,
			VideoTitleMatcher.compare("Voy Después", "Artist - Después Voy"),
		)
	}

	@Test
	fun `exact and three-token containment remain strong ranks`() {
		assertEquals(
			VideoTitleMatcher.Evidence.EXACT,
			VideoTitleMatcher.compare("KORN - TRASH", "korn - trash"),
		)
		assertEquals(
			VideoTitleMatcher.Evidence.STRONG_CONTAINMENT,
			VideoTitleMatcher.compare(
				"Fall 2: Deadpoint (2026) Official Trailer 2 - Harriet Slater",
				"Fall 2: Deadpoint (2026) Official Trailer 2",
			),
		)
	}
}
