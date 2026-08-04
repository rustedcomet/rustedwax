package com.rustedwax.app.enrich

import com.rustedwax.app.detect.SessionProbe
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.TitleParser
import com.rustedwax.app.detect.VideoTitleMatcher

/** Structured resolver evidence carried into the finalized-track boundary. */
data class VideoResolution(
	val videoId: String,
	val source: String,
	val title: String? = null,
	val channel: String? = null,
	val lengthSeconds: Long? = null,
	/** Resolver's unchanged ambiguity gate left exactly one matching id. */
	val uniquelyResolved: Boolean = false,
	/** Search presentation carried YouTube's explicit collaborator affordance. */
	val collaborativeChannel: Boolean = false,
)

/** A resolver success or the exact fail-closed reason shown to the user. */
data class VideoResolutionAttempt(
	val resolution: VideoResolution? = null,
	val refusalReason: String? = null,
)

/**
 * Final defense against assembling one payload from two adjacent tracks.
 *
 * The URL/id, resolver candidate and enriched page facts are checked only
 * against the immutable ended [SessionSnapshot]. No live package evidence is
 * an input. Null fields are absence of evidence; present contradictory fields
 * refuse the id before metadata can replace the session values.
 */
object VideoIdentityCorroborator {

	fun contradiction(
		session: SessionSnapshot,
		resolution: VideoResolution,
		facts: VideoFacts?,
	): String? {
		if (resolution.videoId in session.resolverContext.rejectedVideoIds) {
			return "video id ${resolution.videoId} was rejected while the track was active"
		}
		session.confirmed?.let { frozen ->
			if (frozen.videoId != resolution.videoId) {
				return "resolved id ${resolution.videoId} replaced frozen id ${frozen.videoId}"
			}
		}
		val sameObservedGeneration = resolution.videoId ==
			session.resolverContext.observedVideoId &&
			session.resolverContext.urlGeneration != null &&
			(session.confirmed?.urlGeneration == null ||
				 session.confirmed?.urlGeneration == session.resolverContext.urlGeneration)
		val collaborativeCompatibility = collaborativeCandidateCompatible(
			session, resolution, facts,
		)
		val enrichedBylineMatchesCandidate = collaborativeCompatibility &&
			SearchResultsParser.channelKey(facts?.author) ==
			SearchResultsParser.channelKey(resolution.channel)

		contradictionFor(
			session = session,
			videoId = resolution.videoId,
			title = resolution.title,
			channel = resolution.channel,
			lengthSeconds = resolution.lengthSeconds,
			label = "${resolution.source} candidate",
			sameObservedGeneration = sameObservedGeneration,
			allowCollaborativeByline = collaborativeCompatibility,
		)?.let { return it }

		contradictionFor(
			session = session,
			videoId = resolution.videoId,
			title = facts?.title,
			channel = facts?.author,
			lengthSeconds = facts?.lengthSeconds,
			label = "enriched watch facts",
			sameObservedGeneration = sameObservedGeneration,
			allowCollaborativeByline = enrichedBylineMatchesCandidate,
		)?.let { return it }

		val firstObservedId = session.resolverContext.observedVideoId
		val identityMoved = firstObservedId != null && firstObservedId != resolution.videoId
		val hasCorroboratingMetadata = resolution.title != null ||
			resolution.channel != null || resolution.lengthSeconds != null ||
			facts?.title != null || facts?.author != null || facts?.lengthSeconds != null
		if (identityMoved && !hasCorroboratingMetadata) {
			return "resolved id ${resolution.videoId} replaced observed id $firstObservedId " +
				"without frozen candidate facts"
		}

		return null
	}

	private fun contradictionFor(
		session: SessionSnapshot,
		videoId: String,
		title: String?,
		channel: String?,
		lengthSeconds: Long?,
		label: String,
		sameObservedGeneration: Boolean,
		allowCollaborativeByline: Boolean,
	): String? {
		val frozenTitle = session.title
		val titleEvidence = if (!title.isNullOrBlank() && !frozenTitle.isNullOrBlank()) {
			VideoTitleMatcher.compare(frozenTitle, title)
		} else {
			null
		}
		if (titleEvidence == VideoTitleMatcher.Evidence.CONTRADICTION) {
			return "$label title \"$title\" contradicts ended title \"$frozenTitle\""
		}
		if (titleEvidence == VideoTitleMatcher.Evidence.WEAK_SHORT_CANONICAL_CORE &&
			!SessionProbe.titleEvidenceMayRetainObservedId(
				evidence = titleEvidence,
				candidateVideoId = videoId,
				candidateGeneration = session.confirmed?.urlGeneration
					?: session.resolverContext.urlGeneration,
				observedVideoId = session.resolverContext.observedVideoId,
				observedGeneration = session.resolverContext.urlGeneration,
				sessionDurationMs = session.durationMs,
				pageDurationSeconds = lengthSeconds,
			)
		) {
			return "$label title \"$title\" is only weak evidence and cannot establish " +
				"video id $videoId for the ended track"
		}

		val frozenChannel = session.artist
		if (!channel.isNullOrBlank() && !frozenChannel.isNullOrBlank()) {
			val wanted = SearchResultsParser.channelKey(frozenChannel)
			val actual = SearchResultsParser.channelKey(channel)
			val titleAndDurationCorroborate = titleEvidence != null &&
				titleEvidence != VideoTitleMatcher.Evidence.CONTRADICTION &&
				SessionProbe.durationsCorroborate(session.durationMs, lengthSeconds)
			if (wanted != null && actual != null && wanted != actual &&
				!(sameObservedGeneration && titleAndDurationCorroborate) &&
				!allowCollaborativeByline
			) {
				return "$label channel \"$channel\" contradicts ended channel \"$frozenChannel\""
			}
		}

		if (SessionProbe.durationsDisagree(session.durationMs, lengthSeconds)) {
			return "$label duration ${lengthSeconds}s contradicts ended duration " +
				"${session.durationMs?.div(1000)}s"
		}
		return null
	}

	/** Strong search-presentation exception; never a substring or runtime History lookup. */
	private fun collaborativeCandidateCompatible(
		session: SessionSnapshot,
		resolution: VideoResolution,
		facts: VideoFacts?,
	): Boolean {
		if (!resolution.uniquelyResolved || !resolution.collaborativeChannel ||
			facts?.recognisedByYouTubeMusic != true
		) return false
		val endedTitle = session.title ?: return false
		val candidateTitle = resolution.title ?: return false
		val titleEvidence = VideoTitleMatcher.compare(endedTitle, candidateTitle)
		if (titleEvidence != VideoTitleMatcher.Evidence.EXACT &&
			titleEvidence != VideoTitleMatcher.Evidence.STRONG_CONTAINMENT
		) return false
		if (!SessionProbe.durationsCorroborate(session.durationMs, resolution.lengthSeconds)) {
			return false
		}
		val candidateChannel = resolution.channel ?: return false
		val parts = candidateChannel.split(COLLABORATOR_SEPARATOR)
			.map(String::trim)
		if (parts.size < 2 || parts.any(String::isBlank)) return false
		val leaderKey = SearchResultsParser.channelKey(parts.first()) ?: return false
		val endedChannelKey = SearchResultsParser.channelKey(session.artist) ?: return false
		val parsedArtistKey = SearchResultsParser.channelKey(
			TitleParser.parse(endedTitle, session.artist).artist,
		) ?: return false
		return leaderKey == endedChannelKey && leaderKey == parsedArtistKey
	}

	private val COLLABORATOR_SEPARATOR = Regex(
		"""\s+(?:and|&|x|×)\s+""",
		RegexOption.IGNORE_CASE,
	)

	/** The finalized guard uses the same ranked predicate as the live latch. */
	fun titleEvidence(first: String, second: String): VideoTitleMatcher.Evidence =
		VideoTitleMatcher.compare(first, second)

	/** Only fully populated, strong canonical evidence may seed replay recovery. */
	fun cacheable(
		session: SessionSnapshot,
		resolution: VideoResolution,
		facts: VideoFacts?,
	): Boolean {
		val title = facts?.title ?: resolution.title ?: return false
		val channel = facts?.author ?: resolution.channel ?: return false
		val length = facts?.lengthSeconds ?: resolution.lengthSeconds ?: return false
		val frozenTitle = session.title ?: return false
		val frozenChannel = session.artist ?: return false
		val evidence = VideoTitleMatcher.compare(frozenTitle, title)
		if (evidence != VideoTitleMatcher.Evidence.EXACT &&
			evidence != VideoTitleMatcher.Evidence.STRONG_CONTAINMENT
		) return false
		if (SearchResultsParser.channelKey(frozenChannel) != SearchResultsParser.channelKey(channel)) {
			return false
		}
		return SessionProbe.durationsCorroborate(session.durationMs, length)
	}
}
