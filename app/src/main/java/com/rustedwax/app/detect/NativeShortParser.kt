package com.rustedwax.app.detect

import com.rustedwax.app.enrich.OwnerHandle

/** Android-free accessibility tree used by the structural native-Short parser. */
data class NativeShortNode(
	val packageName: String? = null,
	val resourceId: String? = null,
	val text: String? = null,
	val contentDescription: String? = null,
	val className: String? = null,
	val visible: Boolean = true,
	val clickable: Boolean = false,
	/**
	 * On-screen bounds, when the capture supplied them.
	 *
	 * All-zero means "not captured", which is the case for every hand-built test
	 * tree, so geometry is only ever applied when it is actually present.
	 */
	val left: Int = 0,
	val top: Int = 0,
	val right: Int = 0,
	val bottom: Int = 0,
	val children: List<NativeShortNode> = emptyList(),
) {
	val hasBounds: Boolean get() = right > left && bottom > top
	val width: Int get() = right - left
}

data class NativeShortTree(
	val root: NativeShortNode,
	val exceededCaptureBudget: Boolean = false,
)

/** Pure, structural foreground-Short parser. Unsupported shapes fail closed. */
object NativeShortParser {

	sealed interface Result {
		data class Organic(
			/**
			 * Null when the on-screen title could not be read unambiguously.
			 *
			 * The footer lost its resource ids, so the title is the one piece of
			 * this that YouTube keeps breaking — five separate causes in a single
			 * day, and still only ~1 Short in 6 identified. The handle and the
			 * seekbar are far more stable, and together they already prove a Short
			 * is playing and give its exact length. Identity is then resolved at
			 * finalize from the account's own watch history, joined on owner
			 * handle + duration, which is evidence YouTube cannot restyle away.
			 */
			val title: String?,
			val ownerHandle: String,
			val currentSeconds: Long,
			val totalSeconds: Long,
		) : Result

		/**
		 * A proven, named Shorts player with no readable progress reading.
		 *
		 * The player, its time-bar container and its exact owner handle are all
		 * present; only the seekbar's time is missing. Measured 2026-08-06 late:
		 * YouTube stopped rendering the Shorts progress bar entirely — no
		 * `SeekBar` node in the tree, no bar on screen — until the viewer taps
		 * the video once, which restores it for the rest of the session.
		 *
		 * Treating that as a refusal cost 47 of 71 Shorts in 85 minutes, because
		 * a Short that is never *started* can never accrue anything, inferred or
		 * otherwise. This result starts it. Time is then credited only by the
		 * same wall-clock inference picture-in-picture uses, on the same paired
		 * evidence, and is always reported as inferred.
		 */
		data class OrganicUnmeasured(
			val title: String?,
			val ownerHandle: String,
		) : Result

		data class Ad(
			val signal: String,
			val title: String?,
			val currentSeconds: Long,
			val totalSeconds: Long,
		) : Result

		data class Invalid(
			val reason: String,
			/**
			 * The one refusal that means "playing, but unmeasurable" rather than
			 * "not a Shorts player".
			 *
			 * Set only for the exact measured picture-in-picture signature — a
			 * single visible `reel_time_bar` container with no readable time
			 * inside it (`FIELD_2026-08-05.md` §4.2: 82 of these and zero
			 * container failures during PiP). Every other refusal leaves this
			 * false, including a missing container, a second container, and an
			 * ambiguous pair of times.
			 *
			 * It exists because the honest refusal wording and the finalize
			 * marker must key off *this* outcome. Keying them off generic proof
			 * loss instead — which is true whenever any Short ends, scrolling
			 * away included — put "(progress surface lost …)" on essentially
			 * every Short finalize (§3.1).
			 */
			val progressSurfaceLost: Boolean = false,
		) : Result
	}

	fun parse(tree: NativeShortTree): Result {
		if (tree.exceededCaptureBudget) return Result.Invalid("accessibility capture budget exceeded")
		if (tree.root.packageName != YouTubeProbe.YOUTUBE_PACKAGE) {
			return Result.Invalid("foreground package was not native YouTube")
		}
		val scan = scan(tree.root)
		if (scan.exceeded) return Result.Invalid("parser depth/node budget exceeded")
		val roots = scan.nodes.filter { it.node.visible && it.node.hasId(ROOT_ID) }
		if (roots.size != 1) {
			return Result.Invalid(
				"expected exactly one visible Shorts player root; found ${roots.size}; " +
					structureSummary(scan.nodes),
			)
		}
		val structuralRoot = roots.single().node
		if (hasVisibleCommentsSurface(scan.nodes.map(DepthNode::node))) {
			return Result.Invalid("comments/non-player surface obscured the Shorts player")
		}
		val players = descendants(structuralRoot)
			.filter { it.visible && it.hasId(PLAYER_ID) }
		if (players.size != 1) {
			return Result.Invalid(
				"expected exactly one visible Shorts player; found ${players.size}; " +
					structureSummary(scan.nodes),
			)
		}
		val player = players.single()

		// Samsung's measured build places reel_time_bar beside, rather than
		// beneath, reel_watch_fragment_root. The exact root/player still proves
		// the surface; require one time-bar container in that same YouTube window
		// and one unambiguous phrase inside it. The two refusals below used to
		// share one message, which cost a field investigation real time on
		// 2026-08-05: a run of them said nothing about whether the container was
		// missing or its label unreadable, and the two have unrelated fixes.
		val timeBars = scan.nodes.map(DepthNode::node)
			.filter { it.visible && it.hasId(TIME_BAR_ID) }
		if (timeBars.size != 1) {
			return Result.Invalid(
				"expected exactly one visible Shorts seekbar container; found ${timeBars.size}",
			)
		}
		val times = descendants(timeBars.single())
			.filter(NativeShortNode::visible)
			.flatMap { node -> listOf(node.contentDescription, node.text) }
			.mapNotNull(::parseTime)
			.distinct()
		if (times.size > 1) {
			return Result.Invalid(
				"expected exactly one readable Shorts seekbar time; found ${times.size}",
			)
		}
		val reading = times.singleOrNull()

		// The semantic footer/overlay is a sibling of reel_watch_player in the
		// measured tree, but remains inside the one structural Shorts root.
		val playerNodes = descendants(structuralRoot).filter(NativeShortNode::visible)
		val adSignals = playerNodes.flatMap { node ->
			listOfNotNull(
				YouTubeAdDetector.signalFor(node.text),
				YouTubeAdDetector.signalFor(node.contentDescription),
			)
		}.distinct()
		val title = titleCandidate(playerNodes)
		if (adSignals.isNotEmpty()) {
			// An ad with no readable seekbar is still an ad. It carries no
			// duration, which costs nothing: an ad is refused, never credited.
			return Result.Ad(adSignals.first(), title, reading?.first ?: 0, reading?.second ?: 0)
		}

		val handles = playerNodes.flatMap(::handleCandidates)
			.mapNotNull(OwnerHandle::canonical)
			.distinct()
		if (handles.size != 1) {
			return Result.Invalid(
				"expected exactly one exact visible owner handle",
				// No handle *and* no readable time is the picture-in-picture
				// signature: the window keeps the player but has no footer to
				// read an owner from. It stays "playing but unmeasurable", which
				// only ever accrues for a Short that was already proven.
				progressSurfaceLost = reading == null,
			)
		}
		// Deliberately NOT refusing on a missing title. It is no longer identity
		// evidence — the watch-history route is — and refusing here threw away the
		// measurement as well, which is what made a footer restyle cost the whole
		// listen rather than just its label.
		if (reading == null) {
			// Nor on a missing seekbar, for the same reason and at far greater
			// cost. Measured 2026-08-06 23:00–01:20: YouTube stopped rendering the
			// Shorts progress bar at all — no `SeekBar` node anywhere in the tree
			// and no bar on screen — and 47 of 71 Shorts in 85 minutes were lost
			// because a proven, playing, named Short could not be *started*.
			// A tap on the video restores the bar for the rest of the session,
			// which is not something an observer may do for the user.
			//
			// The player, the container and the exact handle are all still proven
			// here. What is missing is only the progress *reading*, and that is
			// the case the wall-clock inference already exists for.
			return Result.OrganicUnmeasured(title, handles.single())
		}
		return Result.Organic(title, handles.single(), reading.first, reading.second)
	}

	/** Strict measured/localized seekbar phrases; unknown locale/shape refuses. */
	fun parseTime(value: String?): Pair<Long, Long>? {
		val literal = value?.trim()?.replace(Regex("""\s+"""), " ") ?: return null
		val match = TIME_PATTERNS.firstNotNullOfOrNull { it.matchEntire(literal) } ?: return null
		val currentMinutes = match.groupValues[1].toLongOrNull() ?: return null
		val currentSecondsPart = match.groupValues[2].toLongOrNull() ?: return null
		val totalMinutes = match.groupValues[3].toLongOrNull() ?: return null
		val totalSecondsPart = match.groupValues[4].toLongOrNull() ?: return null
		if (currentSecondsPart !in 0..59 || totalSecondsPart !in 0..59) return null
		val current = currentMinutes * 60 + currentSecondsPart
		val total = totalMinutes * 60 + totalSecondsPart
		if (total <= 0 || current < 0 || current > total) return null
		return current to total
	}

	private data class Scanned(val nodes: List<DepthNode>, val exceeded: Boolean)
	private data class DepthNode(val node: NativeShortNode, val depth: Int)

	private fun structureSummary(nodes: List<DepthNode>): String {
		val ids = nodes.asSequence()
			.mapNotNull { it.node.resourceId?.substringAfterLast('/') }
			.filter { id ->
				id.contains("reel", ignoreCase = true) ||
					id.contains("player", ignoreCase = true) ||
					id.contains("time_bar", ignoreCase = true)
			}
			.distinct()
			.take(12)
			.toList()
		return "captured ${nodes.size} nodes; structural ids=${ids.ifEmpty { listOf("<none>") }}"
	}

	private fun scan(root: NativeShortNode): Scanned {
		val out = mutableListOf<DepthNode>()
		var exceeded = false
		fun visit(node: NativeShortNode, depth: Int) {
			if (depth > MAX_DEPTH || out.size >= MAX_NODES) {
				exceeded = true
				return
			}
			out += DepthNode(node, depth)
			node.children.forEach { visit(it, depth + 1) }
		}
		visit(root, 0)
		return Scanned(out, exceeded)
	}

	private fun descendants(root: NativeShortNode): List<NativeShortNode> {
		val out = mutableListOf<NativeShortNode>()
		fun visit(node: NativeShortNode) {
			out += node
			node.children.forEach(::visit)
		}
		visit(root)
		return out
	}

	private fun handleCandidates(node: NativeShortNode): List<String> {
		val out = mutableListOf<String>()
		listOf(node.text, node.contentDescription).forEach { value ->
			val literal = value?.trim() ?: return@forEach
			if (DIRECT_HANDLE.matches(literal)) out += literal
			CHANNEL_DESCRIPTION.matchEntire(literal)?.groupValues?.get(1)?.let(out::add)
		}
		return out
	}

	/**
	 * The title, by resource id where one exists and by *position* where none
	 * does.
	 *
	 * ### Why position
	 *
	 * YouTube has removed the resource ids from the Shorts footer — measured
	 * 2026-08-05, there is no `reel_title` and no id on anything around it. That
	 * left the fallback choosing the title as "the single survivor of a
	 * blocklist", which is a losing game: every footer element YouTube adds is a
	 * new way to produce two survivors and refuse the Short outright. Three
	 * separate causes were fixed in one day that way — the auto-dub badge, the
	 * like/comment counters, and an uncounted `View comments` — and the
	 * acquisition rate was still about one Short in six.
	 *
	 * The title has a stable *place*: it runs along the bottom-left of the
	 * player, spanning most of the width, while every control that keeps being
	 * mistaken for it lives in the right-hand action column. That is positive
	 * evidence, and it does not decay each time the footer gains an element.
	 *
	 * ### Still fails closed
	 *
	 * Geometry narrows the candidates; it never picks between them. If two
	 * left-aligned candidates survive, this refuses exactly as before — a wrong
	 * title is a wrong scrobble, and those are permanent.
	 *
	 * Applied only when the capture actually supplied bounds, so hand-built trees
	 * (and any capture where geometry is unavailable) keep the old behaviour.
	 */
	private fun titleCandidate(nodes: List<NativeShortNode>): String? {
		fun candidates(positiveIdOnly: Boolean): List<Pair<NativeShortNode, String>> =
			nodes.flatMap { node ->
				if (positiveIdOnly && node.resourceId?.contains("title", ignoreCase = true) != true) {
					return@flatMap emptyList()
				}
				listOf(node.text, node.contentDescription).mapNotNull { value ->
					val literal = value?.trim()?.replace(Regex("""\s+"""), " ")
						?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
					literal.takeIf { isTitleLike(node, it) }?.let { node to it }
				}
			}

		val resourceBound = candidates(positiveIdOnly = true).map { it.second }.distinct()
		if (resourceBound.isNotEmpty()) return resourceBound.singleOrNull()

		val all = candidates(positiveIdOnly = false)
		all.map { it.second }.distinct().singleOrNull()?.let { return it }
		return leftAlignedTitle(all)
	}

	/**
	 * Narrow an ambiguous fallback to the candidates in the title's own region.
	 *
	 * The action column (like, comment count, share, remix) is pinned to the
	 * right edge; the title starts at the left. Requiring a candidate to begin in
	 * the leftmost part of the player's width removes the entire column at once,
	 * without naming any of its labels.
	 */
	private fun leftAlignedTitle(candidates: List<Pair<NativeShortNode, String>>): String? {
		val measured = candidates.filter { it.first.hasBounds }
		if (measured.isEmpty()) return null
		val playerRight = measured.maxOf { it.first.right }
		val playerLeft = measured.minOf { it.first.left }
		val span = playerRight - playerLeft
		if (span <= 0) return null
		val cutoff = playerLeft + span * TITLE_LEFT_FRACTION / 100
		return measured
			.filter { it.first.left <= cutoff }
			.map { it.second }
			.distinct()
			.singleOrNull()
	}

	private fun isTitleLike(node: NativeShortNode, literal: String): Boolean {
		if (literal.length > MAX_TITLE_LENGTH ||
			node.className?.contains("Button", ignoreCase = true) == true
		) return false
		// Some Shorts expose a clickable hashtag from the full footer title as a
		// second, bare semantic View (measured as `#hack` on the A12). It is a
		// navigation chip, not a competing title. Keep this deliberately narrower
		// than removing hashtags from a real title: only one all-hashtag token is
		// excluded, while any conflicting prose still fails closed.
		if (SINGLE_HASHTAG.matches(literal)) return false
		if (OwnerHandle.normalize(literal) != null && literal.startsWith('@')) return false
		if (CHANNEL_DESCRIPTION.matches(literal) || parseTime(literal) != null) return false
		if (YouTubeAdDetector.signalFor(literal) != null) return false
		val id = node.resourceId.orEmpty().lowercase()
		if (CONTROL_ID_TOKENS.any(id::contains)) return false
		val key = literal.lowercase()
		if (CONTROL_PHRASES.any { it.containsMatchIn(key) }) return false
		// YouTube's auto-dubbing rollout renders the badge as a bare semantic View
		// with no resource id, no control vocabulary and no button class, so every
		// other filter above misses it and it stands as a second title candidate.
		// Measured 2026-08-05 on @enefectoescine17 ("Auto-dubbed"): two survivors
		// made `singleOrNull` null, so the Short refused identity silently and
		// finalized at `measured 0s`. Excluded by exact label only — a real prose
		// title that genuinely conflicts still fails closed, as before.
		if (key in BADGE_LABELS) return false
		// The like and comment counters render as bare `ViewGroup`s carrying only
		// their number — `2K`, `14` — with no resource id, no button class and no
		// control vocabulary, so every filter above misses them exactly as the
		// auto-dub badge did. Measured 2026-08-05 on @MontRecaps: the footer had
		// lost its `reel_title` id entirely, so the id-bound pass found nothing
		// and the fallback saw three survivors — the real title plus both
		// counters — making `singleOrNull` null and refusing every organic Short
		// on the device.
		//
		// Excluded by shape, and deliberately only this shape: a count is a bare
		// number with an optional magnitude suffix. A title that merely *starts*
		// with a number keeps its own text and still fails closed on conflict.
		if (COUNT_LABEL.matches(literal)) return false
		// The upload-date chip, which sits in the same id-less footer and reads as
		// ordinary prose to every rule above. Measured 2026-08-05: a Short
		// finalized with the title "August 5, 2026". It failed closed — the
		// history gate requires title agreement — but a bare date is never a
		// title, and letting it through cost the real one.
		if (DATE_LABEL.matches(literal)) return false
		return key !in EXACT_CONTROLS
	}

	private fun hasVisibleCommentsSurface(nodes: List<NativeShortNode>): Boolean =
		nodes.any { node ->
			node.visible && COMMENT_SURFACE_TOKENS.any {
				node.resourceId.orEmpty().contains(it, ignoreCase = true)
			}
		}

	private fun NativeShortNode.hasId(suffix: String): Boolean =
		resourceId?.substringAfterLast("/") == suffix

	private const val ROOT_ID = "reel_watch_fragment_root"
	private const val PLAYER_ID = "reel_watch_player"
	private const val TIME_BAR_ID = "reel_time_bar"
	private const val MAX_DEPTH = 24
	private const val MAX_NODES = 500
	private const val MAX_TITLE_LENGTH = 500

	/**
	 * How far into the player's width a title may start, as a percentage.
	 *
	 * Measured on the 720px A12: the title begins at x≈30 while the action
	 * column sits at x≈630 of 720. A third of the width separates them by a wide
	 * margin in both directions.
	 */
	private const val TITLE_LEFT_FRACTION = 33

	private val DIRECT_HANDLE = Regex("""^@[A-Za-z0-9._-]{3,30}$""")
	private val SINGLE_HASHTAG = Regex("""^#[\p{L}\p{M}\p{N}_-]+$""")

	/**
	 * A bare engagement counter: `14`, `2K`, `1.2M`, `2,5 mil`.
	 *
	 * Both the decimal comma and the decimal point appear depending on locale,
	 * and the magnitude suffix is localized too (`K`/`M`/`B`, `mil`, `mn`, `tis`).
	 * Anchored at both ends so only a literal that is *entirely* a count is
	 * excluded.
	 */
	/**
	 * A bare upload date: `August 5, 2026`, `5 Aug 2026`, `2026-08-05`, `5/8/2026`.
	 *
	 * Anchored at both ends, so only a literal that is *entirely* a date is
	 * excluded — a title that merely mentions one keeps its text.
	 */
	private val DATE_LABEL = Regex(
		"""^(?:""" +
			// 2026-08-05, 05/08/2026, 5.8.26
			"""\d{1,4}[-/.]\d{1,2}[-/.]\d{1,4}""" +
			"""|""" +
			// August 5, 2026  /  5 August 2026  /  ago 5, 2026
			"""(?:\d{1,2}\s+)?[\p{L}]{3,12}\.?\s+\d{1,2},?(?:\s+\d{4})?""" +
			"""|""" +
			"""\d{1,2}\s+(?:de\s+)?[\p{L}]{3,12}\.?(?:\s+(?:de\s+)?\d{4})?""" +
			""")$""",
		RegexOption.IGNORE_CASE,
	)

	private val COUNT_LABEL = Regex(
		"""^\d{1,3}(?:[.,\s]\d{1,3})*\s?(?:K|M|B|G|mil|mn|mio|tis|rb|jt)?$""",
		RegexOption.IGNORE_CASE,
	)
	private val CHANNEL_DESCRIPTION = Regex(
		"""^(?:Go to channel|Ir al canal|Acessar canal)\s+(@[A-Za-z0-9._-]{3,30})$""",
		RegexOption.IGNORE_CASE,
	)
	private val TIME_PATTERNS = listOf(
		Regex("""^(\d+) minutes? (\d+) seconds? of (\d+) minutes? (\d+) seconds?$""", RegexOption.IGNORE_CASE),
		Regex("""^(\d+) minutos? (\d+) segundos? de (\d+) minutos? (\d+) segundos?$""", RegexOption.IGNORE_CASE),
		Regex("""^(\d+) minutos?(?: e)? (\d+) segundos? de (\d+) minutos?(?: e)? (\d+) segundos?$""", RegexOption.IGNORE_CASE),
	)
	private val CONTROL_ID_TOKENS = setOf(
		"like", "dislike", "comment", "share", "remix", "subscribe", "sound",
		"channel", "progress", "time_bar", "player_control", "action_button", "menu",
	)
	private val COMMENT_SURFACE_TOKENS = setOf("comments_panel", "comment_sheet", "engagement_panel")
	private val EXACT_CONTROLS = setOf(
		"like", "dislike", "comments", "share", "remix", "subscribe", "use this sound",
		"more", "play", "pause", "next", "previous", "download",
		"play video", "pause video", "next video", "previous video", "mute video", "unmute video",
		"me gusta", "no me gusta", "comentarios", "compartir",
		"gostei", "não gostei", "comentários", "compartilhar",
	)
	/**
	 * Auto-dub badge labels, which are annotations on the video rather than
	 * controls of it. English is the measured spelling; the es/pt entries mirror
	 * the locales [TIME_PATTERNS] and [EXACT_CONTROLS] already carry. An entry
	 * here can only ever remove a title candidate, never admit one, so a wrong
	 * spelling costs nothing beyond the refusal that already happens today.
	 */
	private val BADGE_LABELS = setOf(
		"auto-dubbed",
		"auto dubbed",
		"auto-dubbed audio",
		"dubbed automatically",
		"doblado automáticamente",
		"doblaje automático",
		"dublado automaticamente",
	)
	private val CONTROL_PHRASES = listOf(
		Regex("""^subscribe to @"""),
		Regex("""^(?:original|use this|see more videos using this) sound"""),
		Regex("""^like this video\b"""),
		// The count is optional. A Short with no comments yet renders a bare
		// "View comments", which the counted form missed entirely — so it
		// survived as a second title candidate and refused identity on every
		// such Short. Measured 2026-08-05 on "do you remember Maggie Lindemann".
		Regex("""^view(?:\s+[\d,.]+)?\s+comments?$"""),
		Regex("""^share this video$"""),
		Regex("""^remix this short\b"""),
		// The sound/effect attribution row, which sits directly under the title
		// and is left-aligned with it, so geometry cannot separate the two.
		// Measured 2026-08-05 on a Short using the Green screen effect, where it
		// rendered as two nodes — "Green screen with @MirajYts" and
		// "Green screen, Effect · 297M Shorts," — both surviving as title
		// candidates and refusing the Short.
		//
		// Both forms are attribution rather than prose: an effect or sound name
		// followed by an author handle, or by a Shorts usage count. Anchored
		// tightly so a title that merely mentions a creator is untouched.
		Regex("""\bwith @[a-z0-9._-]{3,30}$"""),
		Regex("""\beffect\s*[·•]\s*[\d,.]+[kmb]?\s*shorts,?$"""),
	)
}
