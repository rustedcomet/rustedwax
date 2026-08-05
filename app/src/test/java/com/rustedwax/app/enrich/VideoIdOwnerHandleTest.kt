package com.rustedwax.app.enrich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoIdOwnerHandleTest {

	private val resolver = VideoIdResolver()

	@Test
	fun `three measured owner-handle cases resolve despite display-author mismatch`() {
		val cases = listOf(
			Case("s8ZQSxuKPb0", "🎭 He Put on a Magic Mask! | The Mask (1994)😂#shorts #movierecap", "Sona Darus", "@SonaDarus", 158, 158),
			Case("Bf7Qtyr-2IQ", "Which is your favorite team? ⚡ Team Iron Man or 🛡️ Team Captain America? #Marvel", "Status", "@Status_svijet", 41, 42),
			Case("orsMh4bNeGE", "Hackers' Skills...", "Beredits", "@Beredist", 139, 139),
		)
		cases.forEach { item ->
			val attempt = resolver.selectOwnerHandleMatch(
				listOf(item.resolution()),
				item.title,
				item.handle,
				item.observedDuration,
			)
			assertEquals(item.id, attempt.resolution?.videoId)
			assertTrue(attempt.resolution?.uniquelyResolved == true)
		}
	}

	@Test
	fun `five-second tolerance is accepted and six seconds refuses`() {
		val candidate = resolution("abcdefghijk", "Exact title", "@exact_owner", 47)
		assertNotNull(resolver.selectOwnerHandleMatch(listOf(candidate), "Exact title", "@exact_owner", 42).resolution)
		assertNull(resolver.selectOwnerHandleMatch(listOf(candidate), "Exact title", "@exact_owner", 41).resolution)
	}

	@Test
	fun `missing contradictory and ambiguous handles refuse every id`() {
		val title = "Exact title"
		val missing = resolution("abcdefghijk", title, null, 42)
		val wrong = resolution("bcdefghijkl", title, "@wrong_owner", 42)
		assertNull(resolver.selectOwnerHandleMatch(listOf(missing), title, "@right_owner", 42).resolution)
		assertNull(resolver.selectOwnerHandleMatch(listOf(wrong), title, "@right_owner", 42).resolution)

		val first = resolution("cdefghijklm", title, "@right_owner", 42)
		val second = resolution("defghijklmn", title, "@right_owner", 42)
		val ambiguous = resolver.selectOwnerHandleMatch(listOf(first, second), title, "@right_owner", 42)
		assertNull(ambiguous.resolution)
		assertTrue(ambiguous.refusalReason.orEmpty().contains("ambiguous identity"))
	}

	@Test
	fun `title equality is exact after normalization not weak containment`() {
		val candidate = resolution("abcdefghijk", "Exact title extended", "@exact_owner", 42)
		assertNull(
			resolver.selectOwnerHandleMatch(
				listOf(candidate), "Exact title", "@exact_owner", 42,
			).resolution,
		)
	}

	private data class Case(
		val id: String,
		val title: String,
		val displayAuthor: String,
		val handle: String,
		val observedDuration: Long,
		val canonicalDuration: Long,
	) {
		fun resolution() = VideoResolution(
			videoId = id,
			source = "measured watch page",
			title = title,
			channel = displayAuthor,
			lengthSeconds = canonicalDuration,
			ownerHandle = handle,
		)
	}

	private fun resolution(id: String, title: String, handle: String?, duration: Long) =
		VideoResolution(
			videoId = id,
			source = "test",
			title = title,
			channel = "display author may differ",
			lengthSeconds = duration,
			ownerHandle = handle,
		)
}
