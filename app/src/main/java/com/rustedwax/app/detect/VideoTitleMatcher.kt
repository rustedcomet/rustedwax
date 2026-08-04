package com.rustedwax.app.detect

import java.text.Normalizer
import java.util.Locale

/**
 * Pure presentation-aware comparison for two observations of one video title.
 *
 * This does not resolve a title to a video and contains no media catalogue. It
 * only decides whether a page title can corroborate the MediaSession title for
 * an id already observed from the browser. [SessionProbe] still checks page
 * duration independently, and finalized resolution applies the same predicate
 * before any fetched facts may enter a payload.
 */
object VideoTitleMatcher {

	enum class Evidence {
		EXACT,
		STRONG_CONTAINMENT,
		WEAK_SHORT_CANONICAL_CORE,
		CONTRADICTION,
	}

	fun compare(first: String, second: String): Evidence {
		val left = tokens(first)
		val right = tokens(second)
		if (left.isEmpty() || right.isEmpty()) return Evidence.CONTRADICTION
		if (left == right) return Evidence.EXACT

		val shorter = if (left.size <= right.size) left else right
		val longer = if (left.size <= right.size) right else left
		// Containment is whole-token and ordered. Two shared artist/promo words
		// in otherwise different adjacent songs are not enough; the complete
		// shorter identity structure must survive in the longer presentation.
		if (!containsSequence(longer, shorter)) return Evidence.CONTRADICTION
		if (shorter.size >= MIN_STRONG_CONTAINED_TOKENS) {
			return Evidence.STRONG_CONTAINMENT
		}

		// One/two-token containment is deliberately weaker. It is evidence only
		// when the short side is the work portion of a structurally parsed longer
		// title, not its artist/uploader prefix (`Bad Bunny` must not corroborate
		// `Bad Bunny - Another Song`). Callers apply the independent URL-generation
		// and duration restrictions before this rank may retain an id.
		val longerValue = if (left.size <= right.size) second else first
		val parsedWork = TitleParser.parse(longerValue, channel = null).track
		val work = tokens(TRAILING_FEATURE_CREDITS.replace(parsedWork, ""))
		return if (shorter.size in 1..MAX_WEAK_TOKENS && shorter == work) {
			Evidence.WEAK_SHORT_CANONICAL_CORE
		} else {
			Evidence.CONTRADICTION
		}
	}

	private fun tokens(value: String): List<String> {
		val presentationCore = TitleParser.presentationCore(value)
		return Normalizer.normalize(presentationCore, Normalizer.Form.NFKD)
			.lowercase(Locale.ROOT)
			.replace(Regex("""\p{M}+"""), "")
			.replace(Regex("""[^\p{L}\p{N}]+"""), " ")
			.replace(Regex("""\s+"""), " ")
			.trim()
			.split(' ')
			.filter(String::isNotBlank)
	}

	private fun containsSequence(longer: List<String>, shorter: List<String>): Boolean {
		if (shorter.size > longer.size) return false
		return (0..(longer.size - shorter.size)).any { start ->
			longer.subList(start, start + shorter.size) == shorter
		}
	}

	private const val MIN_STRONG_CONTAINED_TOKENS = 3
	private const val MAX_WEAK_TOKENS = 2
	private val TRAILING_FEATURE_CREDITS = Regex(
		"""\s+\b(?:ft|feat(?:uring)?)\.?\s+.+$""",
		RegexOption.IGNORE_CASE,
	)
}
