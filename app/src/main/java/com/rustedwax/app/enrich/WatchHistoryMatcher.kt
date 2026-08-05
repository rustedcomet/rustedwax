package com.rustedwax.app.enrich

/**
 * Picks the one watch-history entry that is certainly the track that just
 * played, or refuses. Pure — no network, no Android, no credentials.
 *
 * ## History supplies a candidate, never a verdict
 *
 * The feed says what this account watched, not what *this phone* is playing
 * right now. Another device signed into the same account writes into the same
 * list; so does the user skipping forward three videos while a track finalizes.
 * So an entry is accepted only when it passes the identical three-field gate
 * the search route uses — [SearchResultsParser.identityMatches] — against the
 * frozen MediaSession tuple, and only when it is the *only* entry in the recent
 * window that does. Nothing here relaxes a rule that applies elsewhere.
 *
 * ## Why a small window rather than only the newest entry
 *
 * The plan (§11.2 item 0d) says to take the most recent entry. Position turns
 * out to be the wrong thing to lean on, in both directions: the id is asked for
 * twice — once mid-track to establish the carry authority, once at finalization
 * — and by the second of those the user may already be two videos further on,
 * so the track that just ended is no longer at the top. Meanwhile "newest"
 * confers no safety of its own; the corroboration does all of that work.
 *
 * A bounded window with a mandatory unique match is therefore strictly stronger
 * than trusting position 0: it refuses when two recent entries are
 * indistinguishable, which "take the newest" would silently resolve. The
 * position that matched is reported so the field log can answer whether the
 * newest entry really is the current track (§11.2 item 0g) rather than assuming
 * it.
 */
object WatchHistoryMatcher {

	/**
	 * How far back a track that just finished may be. Four videos of slack
	 * covers a user skipping ahead during a finalize; beyond that the feed is
	 * describing a different listening session and must not answer for this one.
	 */
	const val RECENT_WINDOW = 5

	sealed interface Verdict {
		data class Candidate(
			val entry: WatchHistoryParser.Entry,
			/** 0 is the newest entry in the feed. Diagnostic only. */
			val positionFromNewest: Int,
		) : Verdict

		data class Refused(val reason: String) : Verdict
	}

	fun candidate(
		entries: List<WatchHistoryParser.Entry>,
		title: String,
		channel: String?,
		durationSec: Long?,
	): Verdict {
		if (durationSec == null || durationSec <= 0) {
			return Verdict.Refused("the finalized duration was unknown, so no entry could be checked")
		}
		if (entries.isEmpty()) return Verdict.Refused("watch history carried no entries")

		val window = entries.take(RECENT_WINDOW)
		val matches = SearchResultsParser.identityMatches(
			window.map(::asCandidate), title, channel, durationSec,
		).distinctBy { it.videoId }

		return when (matches.size) {
			1 -> {
				val hit = matches.single()
				Verdict.Candidate(
					entry = window.first { it.videoId == hit.videoId },
					positionFromNewest = window.indexOfFirst { it.videoId == hit.videoId },
				)
			}

			0 -> Verdict.Refused(
				ABSENT_PREFIX + "${window.size} most recent watch-history entries match " +
					"\"$title\" / \"${channel.orEmpty()}\" (${durationSec}s)",
			)

			else -> Verdict.Refused(
				"ambiguous identity — ${matches.size} recent watch-history entries match " +
					"title+channel+duration (${matches.joinToString { it.videoId }}); refusing every id",
			)
		}
	}

	/**
	 * Whether an id resolved earlier in this track is still the one the feed
	 * corroborates.
	 *
	 * The carry authority is re-derived at finalization rather than trusted from
	 * memory (`PHASE_NATIVE_PLAYLIST_IDENTITY.md` §9.4), and for this route that
	 * means the same feed has to still name the same video. A different answer
	 * is a refusal, not a correction: two ids for one listen means neither was
	 * proven.
	 */
	fun revalidate(
		entries: List<WatchHistoryParser.Entry>,
		videoId: String,
		title: String,
		channel: String?,
		durationSec: Long?,
	): Verdict = when (val verdict = candidate(entries, title, channel, durationSec)) {
		is Verdict.Candidate ->
			if (verdict.entry.videoId == videoId) {
				verdict
			} else {
				Verdict.Refused(
					"watch history now names ${verdict.entry.videoId} for \"$title\", " +
						"not the carried $videoId",
				)
			}

		is Verdict.Refused -> verdict
	}

	/**
	 * "The track is simply not in the feed", as opposed to an ambiguous or
	 * unusable one. The distinction decides two things — whether to re-read
	 * before believing it, and whether it counts toward the "your YouTube app is
	 * on another account" diagnosis — so it is one predicate, not two string
	 * checks at the call sites.
	 */
	fun isAbsence(verdict: Verdict): Boolean =
		verdict is Verdict.Refused && verdict.reason.startsWith(ABSENT_PREFIX)

	private const val ABSENT_PREFIX = "none of the "

	private fun asCandidate(entry: WatchHistoryParser.Entry) = SearchResultsParser.Candidate(
		videoId = entry.videoId,
		title = entry.title,
		channel = entry.channel,
		lengthSeconds = entry.lengthSeconds,
	)
}
