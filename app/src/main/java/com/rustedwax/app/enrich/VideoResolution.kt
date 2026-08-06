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
	/** Exact canonical owner handle from the candidate watch-page microformat. */
	val ownerHandle: String? = null,
	/** Exact parsed work/credit/duration proof for simplified native music metadata. */
	val structuredNativeMusic: Boolean = false,
	/**
	 * The id came from the bounded entry list of the playlist being played, and
	 * that entry's own title, channel and duration already matched the session.
	 */
	val playlistVerified: Boolean = false,
	/**
	 * The id came from the signed-in account's own watch history, where the
	 * entry's title, channel and duration already matched the session and no
	 * other recent entry did.
	 */
	val historyVerified: Boolean = false,
	/**
	 * The title this video's own page *displays*, when YouTube auto-translated
	 * it and that differs from [title]. Same page, same fetch — one video that
	 * YouTube publishes under two names, not a second candidate.
	 */
	val localizedTitle: String? = null,
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
		// One video, two titles. YouTube auto-translates for the viewer, so a
		// native observer reads the *displayed* title off the screen while
		// `videoDetails` keeps the uploaded one — measured 2026-08-05, the
		// resolver found `QnRnooyKeZk` correctly and this guard then threw it
		// away because "El Día que Karol G Vivió…" is not "The Day Karol G
		// Experienced…". They are the same video, and both names come from its
		// own page.
		//
		// Substituted only when the displayed title *is* the frozen one, and
		// only for native sessions. Duration, channel/handle and the unique-id
		// rule all still bind independently, so this admits nothing new.
		val displayedTitle = resolution.localizedTitle?.takeIf { displayed ->
			session.isNative && session.title?.let {
				SearchResultsParser.titleKey(displayed) == SearchResultsParser.titleKey(it)
			} == true
		}

		session.ownerHandle?.let { wantedHandle ->
			if (!resolution.uniquelyResolved) {
				return "foreground Short id was not the only corroborated candidate"
			}
			// A missing title is no longer a refusal. YouTube's footer lost its
			// resource ids, and a Short sent straight to picture-in-picture never
			// exposes a readable title at all — measured 2026-08-06, a Short
			// counted to 100% and was rejected here for having none. The title
			// was only ever one of three agreeing fields; duration, owner handle
			// and the watch page's own handle all still bind below, and the id
			// itself was resolved from the account's watch history against those
			// same two fields. When a title *is* present it must still agree.
			val frozenTitle = session.title
			val resolvedTitle = displayedTitle ?: resolution.title
				?: return "resolved candidate omitted the canonical title"
			if (frozenTitle != null &&
				SearchResultsParser.titleKey(frozenTitle) !=
				SearchResultsParser.titleKey(resolvedTitle)
			) {
				return "resolved candidate title \"$resolvedTitle\" contradicts foreground title \"$frozenTitle\""
			}
			if (!SessionProbe.durationsCorroborate(session.durationMs, resolution.lengthSeconds)) {
				return "resolved candidate duration did not corroborate the foreground seekbar"
			}
			if (!OwnerHandle.matches(wantedHandle, resolution.ownerHandle)) {
				return "resolved candidate owner handle ${resolution.ownerHandle ?: "<missing>"} " +
					"contradicts foreground handle $wantedHandle"
			}
			val finalFacts = facts ?: return "final watch-page handle evidence was unavailable"
			if (!OwnerHandle.matches(wantedHandle, finalFacts.ownerHandle)) {
				return "final watch-page owner handle ${finalFacts.ownerHandle ?: "<missing>"} " +
					"contradicts foreground handle $wantedHandle"
			}
		}
		val structuredNativeCompatible = if (resolution.structuredNativeMusic) {
			val frozenTitle = session.title
			val frozenArtist = session.artist
			val frozenDuration = session.durationMs?.div(1000)
			if (!session.isNative || !resolution.uniquelyResolved ||
				frozenTitle.isNullOrBlank() || frozenArtist.isNullOrBlank() ||
				frozenDuration == null || facts == null
			) {
				return "structured native music proof was incomplete at finalization"
			}
			val resolutionMatches = NativeStructuredMusicMatcher.matches(
				resolution, frozenTitle, frozenArtist, frozenDuration,
			)
			val factsMatch = NativeStructuredMusicMatcher.matches(
				VideoResolution(
					videoId = facts.videoId,
					source = "final watch facts",
					title = facts.title,
					channel = facts.author,
					lengthSeconds = facts.lengthSeconds,
				),
				frozenTitle,
				frozenArtist,
				frozenDuration,
			)
			if (!resolutionMatches || !factsMatch) {
				return "structured native music candidate did not re-corroborate against " +
					"the finalized title, artist and duration"
			}
			true
		} else {
			false
		}
		val exactNativeMediaId = session.isNative &&
			session.confirmed?.videoId == resolution.videoId &&
			session.confirmed?.exactIdRoute != null
		val sameObservedGeneration = exactNativeMediaId || resolution.videoId ==
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
			title = displayedTitle ?: resolution.title,
			channel = resolution.channel,
			lengthSeconds = resolution.lengthSeconds,
			label = "${resolution.source} candidate",
			sameObservedGeneration = sameObservedGeneration,
			allowCollaborativeByline = collaborativeCompatibility,
			allowStructuredNativeMusic = structuredNativeCompatible,
		)?.let { return it }

		// YouTube spells one Topic channel two ways: measured 2026-08-04 for
		// `7J6xA1_f8as`, the playlist page and the MediaSession both said
		// "Cosculluela El Principe" while the watch page said
		// "Cosculluela - Topic". Stripping " - Topic" leaves "Cosculluela",
		// which still is not the artist's full stage name, so the watch page
		// vetoed a correct id that the playlist had already corroborated.
		//
		// The playlist is the list actually being played, so where its own entry
		// agreed with the session artist, the watch page's alternate spelling of
		// that same channel is not new evidence. Title and duration still have to
		// agree on both passes, and this never applies to a browser session.
		val playlistChannelAlias = session.isNative &&
			resolution.playlistVerified &&
			SearchResultsParser.channelKey(resolution.channel) != null &&
			SearchResultsParser.channelKey(resolution.channel) ==
			SearchResultsParser.channelKey(session.artist)

		contradictionFor(
			session = session,
			videoId = resolution.videoId,
			title = displayedTitle ?: facts?.title,
			channel = facts?.author,
			lengthSeconds = facts?.lengthSeconds,
			label = "enriched watch facts",
			sameObservedGeneration = sameObservedGeneration,
			allowCollaborativeByline = enrichedBylineMatchesCandidate,
			allowStructuredNativeMusic = structuredNativeCompatible,
			allowPlaylistChannelAlias = playlistChannelAlias,
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
		allowStructuredNativeMusic: Boolean,
		/** Relaxes the *channel* comparison only; title and duration still bind. */
		allowPlaylistChannelAlias: Boolean = false,
	): String? {
		val frozenTitle = session.title
		val titleEvidence = if (!title.isNullOrBlank() && !frozenTitle.isNullOrBlank()) {
			VideoTitleMatcher.compare(frozenTitle, title)
		} else {
			null
		}
		if (!allowStructuredNativeMusic &&
			titleEvidence == VideoTitleMatcher.Evidence.CONTRADICTION
		) {
			return "$label title \"$title\" contradicts ended title \"$frozenTitle\""
		}
		if (!allowStructuredNativeMusic &&
			titleEvidence == VideoTitleMatcher.Evidence.WEAK_SHORT_CANONICAL_CORE &&
			!(session.isNative && session.confirmed?.exactIdRoute != null &&
				session.confirmed?.videoId == videoId &&
				SessionProbe.durationsCorroborate(session.durationMs, lengthSeconds)) &&
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
		if (session.ownerHandle == null && !channel.isNullOrBlank() && !frozenChannel.isNullOrBlank()) {
			val wanted = SearchResultsParser.channelKey(frozenChannel)
			val actual = SearchResultsParser.channelKey(channel)
			val titleAndDurationCorroborate = titleEvidence != null &&
				titleEvidence != VideoTitleMatcher.Evidence.CONTRADICTION &&
				SessionProbe.durationsCorroborate(session.durationMs, lengthSeconds)
			// A watch page credits collaborators where the media session names
			// only the uploader: "La Melma Music and 2 more" against
			// "La Melma Music", "Eladio Carrion and CAZZU" against
			// "Eladio Carrion". Same channel, longer byline — measured
			// 2026-08-05, 12 rejections in one session. The *leader* of the
			// byline is the uploader, so matching it is not a relaxation: title
			// and duration still have to agree, and a byline whose leader is a
			// different channel still contradicts.
			// Requires the title and the duration to agree as well, so this is
			// three matching fields, not a relaxation to one. A byline whose
			// leader is a different channel still contradicts, and a name with no
			// separator ("Owner Collaborator") is not a byline at all.
			val bylineLeaderMatches = wanted != null && titleAndDurationCorroborate &&
				SearchResultsParser.collaboratorLeader(channel)
					?.let { SearchResultsParser.channelKey(it) } == wanted
			if (wanted != null && actual != null && wanted != actual &&
				!bylineLeaderMatches &&
				!(sameObservedGeneration && titleAndDurationCorroborate) &&
				!allowCollaborativeByline && !allowStructuredNativeMusic &&
				!(allowPlaylistChannelAlias && titleAndDurationCorroborate)
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
		// The run-local cache is keyed for raw title/channel corroboration. A
		// structured native recovery must be re-searched and fully re-fetched.
		if (resolution.structuredNativeMusic) return false
		session.ownerHandle?.let { wantedHandle ->
			val title = facts?.title ?: resolution.title ?: return false
			val length = facts?.lengthSeconds ?: resolution.lengthSeconds ?: return false
			return resolution.uniquelyResolved &&
				SearchResultsParser.titleKey(session.title ?: return false) ==
					SearchResultsParser.titleKey(title) &&
				SessionProbe.durationsCorroborate(session.durationMs, length) &&
				OwnerHandle.matches(wantedHandle, resolution.ownerHandle) &&
				OwnerHandle.matches(wantedHandle, facts?.ownerHandle)
		}
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
