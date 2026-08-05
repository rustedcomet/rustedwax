package com.rustedwax.app.enrich

import java.net.URI
import java.util.Locale

/** Exact YouTube owner-handle evidence; punctuation and underscores are identity. */
object OwnerHandle {

	private val HANDLE = Regex("""^[A-Za-z0-9._-]{3,30}$""")

	/**
	 * Normalizes only the presentation allowed by the native-Short contract:
	 * trim, remove at most one leading `@`, and compare case-insensitively.
	 */
	fun normalize(value: String?): String? {
		val trimmed = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
		val withoutAt = if (trimmed.startsWith('@')) trimmed.drop(1) else trimmed
		if (withoutAt.startsWith('@') || !HANDLE.matches(withoutAt)) return null
		return withoutAt.lowercase(Locale.ROOT)
	}

	fun canonical(value: String?): String? {
		val normalized = normalize(value) ?: return null
		return "@$normalized"
	}

	fun matches(first: String?, second: String?): Boolean {
		val left = normalize(first) ?: return false
		return left == normalize(second)
	}

	/**
	 * Parses only a canonical, single-segment YouTube owner profile URL.
	 * Host suffix confusion, credentials, ports, query/fragment data, percent
	 * escapes and multi-segment paths all fail closed.
	 */
	fun fromOwnerProfileUrl(value: String?): String? {
		val uri = runCatching { URI(value?.trim().orEmpty()) }.getOrNull() ?: return null
		if (uri.scheme != "http" && uri.scheme != "https") return null
		if (uri.rawUserInfo != null || uri.port != -1 || uri.rawQuery != null || uri.rawFragment != null) {
			return null
		}
		val host = uri.host?.lowercase(Locale.ROOT) ?: return null
		if (host != "youtube.com" && host != "www.youtube.com") return null
		val rawPath = uri.rawPath ?: return null
		if ('%' in rawPath) return null
		val segments = rawPath.split('/').filter(String::isNotEmpty)
		if (segments.size != 1 || !segments.single().startsWith('@')) return null
		return canonical(segments.single())
	}
}
