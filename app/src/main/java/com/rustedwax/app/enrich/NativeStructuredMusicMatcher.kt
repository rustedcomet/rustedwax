package com.rustedwax.app.enrich

import com.rustedwax.app.detect.TitleParser
import kotlin.math.abs

/**
 * Exact structured identity for native players that publish clean music fields
 * but omit the immutable YouTube video id.
 *
 * This is deliberately not a fuzzy title matcher. The canonical page title is
	 * parsed with the existing credit grammar, its work must equal the separated
	 * MediaSession work, the native artist must be one complete parsed or canonical
	 * author credit, and duration must agree. The resolver still requires exactly
	 * one fully fetched public page before this evidence can become authority.
 */
object NativeStructuredMusicMatcher {

	fun couldDescribeTrack(
		candidateTitle: String?,
		candidateChannel: String?,
		nativeTitle: String,
		nativeArtist: String,
	): Boolean {
		val title = candidateTitle?.takeIf(String::isNotBlank) ?: return false
		val evidence = parse(title, candidateChannel)
		if (titleKey(evidence.track) != titleKey(work(nativeTitle))) return false
		val wantedArtist = SearchResultsParser.channelKey(nativeArtist) ?: return false
		return evidence.credits.any { credit ->
			SearchResultsParser.channelKey(credit) == wantedArtist
		}
	}

	fun matches(
		candidate: VideoResolution,
		nativeTitle: String,
		nativeArtist: String,
		durationSec: Long,
	): Boolean {
		val candidateTitle = candidate.title?.takeIf(String::isNotBlank) ?: return false
		val candidateDuration = candidate.lengthSeconds ?: return false
		if (abs(candidateDuration - durationSec) > VideoIdResolver.DURATION_TOLERANCE_SEC) {
			return false
		}
		val evidence = parse(candidateTitle, candidate.channel)
		if (titleKey(evidence.track) != titleKey(work(nativeTitle))) return false
		val wantedArtist = SearchResultsParser.channelKey(nativeArtist) ?: return false
		return evidence.credits.any { credit ->
			SearchResultsParser.channelKey(credit) == wantedArtist
		}
	}

	fun select(
		candidates: List<VideoResolution>,
		nativeTitle: String,
		nativeArtist: String,
		durationSec: Long,
	): VideoResolutionAttempt {
		val matches = candidates.filter { candidate ->
			matches(candidate, nativeTitle, nativeArtist, durationSec)
		}.distinctBy(VideoResolution::videoId)
		return when (matches.size) {
			1 -> VideoResolutionAttempt(
				resolution = matches.single().copy(
					source = "structured native music title+artist+duration",
					uniquelyResolved = true,
					structuredNativeMusic = true,
				),
			)
			0 -> VideoResolutionAttempt(
				refusalReason = "no fully fetched candidate matched structured native " +
					"music title+artist+duration",
			)
			else -> VideoResolutionAttempt(
				refusalReason = "ambiguous identity — ${matches.size} uploads match structured " +
					"native music title+artist+duration " +
					"(${matches.joinToString { it.videoId }}); refusing every id",
			)
		}
	}

	private data class Evidence(val track: String, val credits: Set<String>)

	private fun parse(title: String, channel: String?): Evidence {
		val parsed = TitleParser.parse(title, channel)
		val feature = featureSuffix(parsed.track)
		val credits = linkedSetOf<String>()
		// The canonical author/byline is independent structural evidence. Keep it
		// even when the presentation title parses to a broader collaboration such
		// as `Natti Natasha ❌ Ozuna`: exact author agreement must not disappear
		// merely because the title also carries credits.
		channel?.trim()?.takeIf(String::isNotBlank)?.let(credits::add)
		parsed.artist?.let { artist ->
			credits += artist.trim()
			credits += splitCredits(artist)
		}
		feature.second?.let { featured ->
			credits += featured.trim()
			credits += splitCredits(featured)
		}
		return Evidence(feature.first, credits.filter(String::isNotBlank).toSet())
	}

	/** Apply the same explicit feature grammar to native and canonical works. */
	private fun work(value: String): String = featureSuffix(value).first

	/** Preserve the work while exposing only an explicit trailing feature credit. */
	private fun featureSuffix(track: String): Pair<String, String?> {
		PARENTHETICAL_FEATURE.matchEntire(track.trim())?.let { match ->
			return match.groupValues[1].trim() to match.groupValues[2].trim()
		}
		BARE_FEATURE.matchEntire(track.trim())?.let { match ->
			return match.groupValues[1].trim() to match.groupValues[2].trim()
		}
		return track.trim() to null
	}

	private fun splitCredits(value: String): List<String> = value
		.split(CREDIT_SEPARATOR)
		.map(String::trim)
		.filter(String::isNotBlank)

	private fun titleKey(value: String): String = SearchResultsParser.titleKey(value)

	private val PARENTHETICAL_FEATURE = Regex(
		"""^(.+?)\s*\(\s*(?:ft|feat|featuring)\.?\s+([^)]+)\)\s*$""",
		RegexOption.IGNORE_CASE,
	)
	private val BARE_FEATURE = Regex(
		"""^(.+?)\s+(?:ft|feat|featuring)\.?\s+(.+?)\s*$""",
		RegexOption.IGNORE_CASE,
	)
	private val CREDIT_SEPARATOR = Regex(
		"""\s*(?:,|&|\band\b|\bx\b|×|/)\s*""",
		RegexOption.IGNORE_CASE,
	)
}
