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
	val children: List<NativeShortNode> = emptyList(),
)

data class NativeShortTree(
	val root: NativeShortNode,
	val exceededCaptureBudget: Boolean = false,
)

/** Pure, structural foreground-Short parser. Unsupported shapes fail closed. */
object NativeShortParser {

	sealed interface Result {
		data class Organic(
			val title: String,
			val ownerHandle: String,
			val currentSeconds: Long,
			val totalSeconds: Long,
		) : Result

		data class Ad(
			val signal: String,
			val title: String?,
			val currentSeconds: Long,
			val totalSeconds: Long,
		) : Result

		data class Invalid(val reason: String) : Result
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
		if (times.size != 1) {
			return Result.Invalid(
				"expected exactly one readable Shorts seekbar time; found ${times.size}",
			)
		}
		val (current, total) = times.single()

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
			return Result.Ad(adSignals.first(), title, current, total)
		}

		val handles = playerNodes.flatMap(::handleCandidates)
			.mapNotNull(OwnerHandle::canonical)
			.distinct()
		if (handles.size != 1) {
			return Result.Invalid("expected exactly one exact visible owner handle")
		}
		if (title == null) return Result.Invalid("expected exactly one non-control title")
		return Result.Organic(title, handles.single(), current, total)
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

	private fun titleCandidate(nodes: List<NativeShortNode>): String? {
		fun candidates(positiveIdOnly: Boolean): List<String> = nodes.flatMap { node ->
			if (positiveIdOnly && node.resourceId?.contains("title", ignoreCase = true) != true) {
				return@flatMap emptyList()
			}
			listOf(node.text, node.contentDescription).mapNotNull { value ->
				val literal = value?.trim()?.replace(Regex("""\s+"""), " ")
					?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
				literal.takeIf { isTitleLike(node, it) }
			}
		}.distinct()

		val resourceBound = candidates(positiveIdOnly = true)
		if (resourceBound.isNotEmpty()) return resourceBound.singleOrNull()
		return candidates(positiveIdOnly = false).singleOrNull()
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

	private val DIRECT_HANDLE = Regex("""^@[A-Za-z0-9._-]{3,30}$""")
	private val SINGLE_HASHTAG = Regex("""^#[\p{L}\p{M}\p{N}_-]+$""")
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
		Regex("""^view [\d,.]+ comments?$"""),
		Regex("""^share this video$"""),
		Regex("""^remix this short\b"""),
	)
}
