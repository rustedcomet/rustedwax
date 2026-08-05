package com.rustedwax.app.enrich

/**
 * Decides when the watch-history route must stop running, and says why.
 *
 * ## The condition this exists for
 *
 * The route reads the history of the account **RustedWax** is signed into. If
 * the YouTube app on the phone is signed out, signed into a *different* account,
 * or playing in its incognito mode, then nothing this phone plays is ever
 * written to the feed RustedWax can read. Every lookup then costs a page fetch
 * and returns an entry belonging to some other listening session, which the
 * corroborator correctly refuses — quietly, one track at a time, forever.
 *
 * The owner's instruction is that the route must refuse outright in that state
 * rather than keep trying. So the miss itself is the evidence: a *successful*
 * feed read that does not contain the track that just played is one data point,
 * and [MISSES_BEFORE_REFUSING] consecutive ones are a diagnosis. That is
 * measurable from the feed alone — no guessing at which account the YouTube app
 * is using, and no reading of a surface Android does not offer us.
 *
 * The three causes are not separable from the feed, so the message names all of
 * them. Two of them *are* separable and are reported exactly instead:
 * `SIGNED_OUT` and `HISTORY_PAUSED` come from YouTube's own answer
 * ([WatchHistoryParser.Reason]) and refuse immediately, without waiting for a
 * pattern.
 *
 * ## Recovery
 *
 * A refusal that can never be revisited would need an app restart to clear
 * after the user fixes the account, so a refused route re-probes once every
 * [RETRY_INTERVAL_MS]. One track landing in the feed clears the state entirely.
 */
class WatchHistoryHealth(
	private val missesBeforeRefusing: Int = MISSES_BEFORE_REFUSING,
	private val retryIntervalMs: Long = RETRY_INTERVAL_MS,
) {

	private var consecutiveMisses = 0

	@Volatile
	private var refusal: String? = null

	private var refusedAtMillis = 0L
	private var lastProbeMillis = 0L

	/** The exact reason the route is not running, or null when it is. */
	val refusedBecause: String? get() = refusal

	/** Whether a lookup may be attempted now. */
	@Synchronized
	fun mayRun(nowMillis: Long): Boolean {
		if (refusal == null) return true
		if (nowMillis - maxOf(refusedAtMillis, lastProbeMillis) < retryIntervalMs) return false
		lastProbeMillis = nowMillis
		return true
	}

	/** A track was found in the feed: whatever was wrong is not wrong now. */
	@Synchronized
	fun recordHit() {
		consecutiveMisses = 0
		refusal = null
		refusedAtMillis = 0
		lastProbeMillis = 0
	}

	/** The feed read fine and simply did not contain the track that just played. */
	@Synchronized
	fun recordMiss(nowMillis: Long) {
		if (refusal != null) return
		consecutiveMisses++
		if (consecutiveMisses < missesBeforeRefusing) return
		refuse(
			"the last $consecutiveMisses native tracks were not written to the watch " +
				"history of the signed-in account. The YouTube app is signed out, signed " +
				"into a different account, or playing in incognito. History lookups are " +
				"paused until a track appears there again.",
			nowMillis,
		)
	}

	/** YouTube answered with an exact fault of its own. */
	@Synchronized
	fun recordUnavailable(
		reason: WatchHistoryParser.Reason,
		detail: String,
		nowMillis: Long,
	) {
		when (reason) {
			WatchHistoryParser.Reason.SIGNED_OUT -> refuse(
				"the stored YouTube session is no longer signed in ($detail). " +
					"Sign in again to use watch history.",
				nowMillis,
			)

			WatchHistoryParser.Reason.HISTORY_PAUSED -> refuse(
				"watch history is paused for this account ($detail), so nothing " +
					"played is recorded. Turn it back on in YouTube settings.",
				nowMillis,
			)

			// An empty feed on a live session is the signed-out-app case again:
			// the account records nothing because this phone is not playing into
			// it. Counted, not declared, for the same reason a single miss is.
			WatchHistoryParser.Reason.EMPTY -> recordMiss(nowMillis)

			WatchHistoryParser.Reason.MARKUP_CHANGED -> refuse(
				"the watch-history page no longer has the shape RustedWax reads " +
					"($detail). Nothing was guessed.",
				nowMillis,
			)
		}
	}

	/** The network failed. Not evidence about the account; nothing is recorded. */
	@Synchronized
	fun recordFetchFailure() = Unit

	/** Sign-in, sign-out, opt-out and monitoring stop all start over. */
	@Synchronized
	fun reset() {
		consecutiveMisses = 0
		refusal = null
		refusedAtMillis = 0
		lastProbeMillis = 0
	}

	private fun refuse(reason: String, nowMillis: Long) {
		refusal = reason
		refusedAtMillis = nowMillis
		lastProbeMillis = nowMillis
	}

	companion object {
		/**
		 * Three, for the same reason the quiet-address-bar warning uses three:
		 * one miss is a video that genuinely has not landed yet, three in a row
		 * is a configuration.
		 */
		const val MISSES_BEFORE_REFUSING = 3

		/** A refused route costs one page read per this interval, not one per track. */
		const val RETRY_INTERVAL_MS = 15 * 60 * 1000L
	}
}
