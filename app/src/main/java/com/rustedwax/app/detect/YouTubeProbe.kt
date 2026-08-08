package com.rustedwax.app.detect

import android.media.MediaMetadata
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Decides whether a browser media session is YouTube — and if possible, which
 * video.
 *
 * v1 scrobbles YouTube-in-Brave only. That makes this class the gate on the
 * whole pipeline, because a media session names the *package*
 * (com.brave.browser), never the site. If we can't prove the session is
 * YouTube, we don't scrobble it: guessing would attribute Spotify-web or
 * SoundCloud listens to YouTube, and wrong data on an immutable chain is worse
 * than no data.
 *
 * Evidence, in descending order of quality:
 *
 *   1. A watch URL in `METADATA_KEY_MEDIA_URI` → site *and* video id.
 *   2. An artwork URI matching `i.ytimg.com/vi/<id>` → site *and* video id.
 *   3. The page origin from the media notification's sub-text → site only.
 *
 * **Phase 0 measured that Chromium provides neither 1 nor 2** — it publishes
 * artwork as an embedded bitmap and leaves every URI key unset (PHASE0.md, Q3).
 * So route 3 is expected to be the one that actually fires. It proves the site
 * but cannot build a payload until the exact id is supplied by browser evidence
 * or recovered by the playlist/search/watch-page resolver.
 */
object YouTubeProbe {
	const val YOUTUBE_PACKAGE = "com.google.android.youtube"
	const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"

	/** Brave channels — the real target. */
	val BRAVE_PACKAGES = setOf(
		"com.brave.browser",
		"com.brave.browser_beta",
		"com.brave.browser_nightly",
	)

	/**
	 * Chrome. Included because Brave is a Chromium fork with the same media
	 * plumbing, and because emulators (BlueStacks) can't install Brave — so
	 * Chrome is the only way to exercise this path there. Findings transfer;
	 * confirm on Brave before shipping.
	 */
	val CHROME_PACKAGES = setOf(
		"com.android.chrome",
		"com.chrome.beta",
		"com.chrome.dev",
	)

	/** Browsers we inspect notifications for and would scrobble from. */
	val TARGET_PACKAGES = BRAVE_PACKAGES + CHROME_PACKAGES

	/**
	 * Native YouTube apps. Not scrobbled in v1 — kept as control cases for diagnostics:
	 * they publish rich metadata, so they show what "good" looks like next to
	 * whatever the browser gives us.
	 */
	val YOUTUBE_APP_PACKAGES = setOf(
		YOUTUBE_PACKAGE,
		YOUTUBE_MUSIC_PACKAGE,
	)

	enum class Origin(val displayName: String) {
		BROWSER("YouTube in browser"),
		NATIVE_YOUTUBE("native YouTube"),
		NATIVE_YOUTUBE_MUSIC("native YouTube Music"),
		UNSUPPORTED("unsupported package"),
	}

	fun originForPackage(packageName: String): Origin = when (packageName) {
		in TARGET_PACKAGES -> Origin.BROWSER
		YOUTUBE_PACKAGE -> Origin.NATIVE_YOUTUBE
		YOUTUBE_MUSIC_PACKAGE -> Origin.NATIVE_YOUTUBE_MUSIC
		else -> Origin.UNSUPPORTED
	}

	fun isNativePackage(packageName: String): Boolean = packageName in YOUTUBE_APP_PACKAGES

	/** Browser acceptance is invariant; each native package has a separate opt-in. */
	fun acceptsPackage(
		packageName: String,
		nativeYouTubeEnabled: Boolean,
		nativeYouTubeMusicEnabled: Boolean,
	): Boolean = when (packageName) {
		in TARGET_PACKAGES -> true
		YOUTUBE_PACKAGE -> nativeYouTubeEnabled
		YOUTUBE_MUSIC_PACKAGE -> nativeYouTubeMusicEnabled
		else -> false
	}

	private val THUMBNAIL = Regex("""i\d?\.ytimg\.com/vi/([A-Za-z0-9_-]{11})""")
	private val WATCH_URL =
		Regex("""(?:youtube\.com/watch\?(?:.*&)?v=|youtu\.be/)([A-Za-z0-9_-]{11})""")

	private const val YOUTUBE_MUSIC_HOST = "music.youtube.com"

	/**
	 * Exactly the hosts that count as YouTube.
	 *
	 * An explicit set, not a `.youtube.com` suffix test. The suffix version
	 * accepted anything ending in the domain, and "prove it or skip it" should
	 * not be resolved by a wildcard. `www.` is stripped upstream in
	 * [RustedWaxListenerService.hostOf] but listed anyway — this set is the
	 * contract, and it shouldn't depend on a caller's normalisation.
	 */
	private val YOUTUBE_HOSTS = setOf(
		"youtube.com",
		"www.youtube.com",
		"m.youtube.com",
		"music.youtube.com",
		"youtu.be",
	)

	fun isYouTubeHost(host: String?): Boolean = host?.lowercase() in YOUTUBE_HOSTS

	private val URI_KEYS = listOf(
		MediaMetadata.METADATA_KEY_ART_URI,
		MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
		MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
		MediaMetadata.METADATA_KEY_MEDIA_URI,
	)

	sealed interface Identity {
		/** Which metadata/notification field produced this verdict. */
		val source: String

		/** Proven YouTube, with the video identified. Full payload possible. */
		data class Confirmed(
			val videoId: String,
			val url: String,
			val isMusic: Boolean,
			/** True when the address bar showed a `/shorts/` path. */
			val isShort: Boolean = false,
			/** URL generation for address-bar identities; null for metadata URIs. */
			val urlGeneration: Long? = null,
			/** Native exact-id route; null for the established browser path. */
			val exactIdRoute: String? = null,
			override val source: String,
		) : Identity

		/**
		 * Proven to be YouTube, but the specific video is unknown — the expected
		 * intermediate outcome for Chromium browsers. Finalization must recover
		 * an id or refuse the scrobble; SiteOnly is not broadcastable by itself.
		 */
		data class SiteOnly(
			val host: String,
			val isMusic: Boolean,
			override val source: String,
		) : Identity

		/**
		 * Can't prove the site. Never scrobbled.
		 *
		 * @param provenOtherSite true when the evidence didn't merely fail to
		 * prove YouTube but positively named a *different* site. That's a
		 * stronger statement than "unknown", and the probe uses it to poison the
		 * track for good — see `SessionProbe.Watch.taintedReason`. Without the
		 * distinction, a track that was demonstrably SoundCloud could be
		 * rehabilitated by a YouTube notification arriving from another tab.
		 */
		data class Unconfirmed(
			val reason: String,
			val provenOtherSite: Boolean = false,
		) : Identity {
			override val source: String get() = reason
		}
	}

	/** Pure representation of the standard URI/id fields used by native apps. */
	data class NativeMetadataFields(
		val mediaId: String? = null,
		val mediaUri: String? = null,
		val artUri: String? = null,
		val albumArtUri: String? = null,
		val displayIconUri: String? = null,
	)

	fun nativeMetadataFields(md: MediaMetadata?): NativeMetadataFields = NativeMetadataFields(
		mediaId = MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_MEDIA_ID),
		mediaUri = MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_MEDIA_URI),
		artUri = MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_ART_URI),
		albumArtUri = MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
		displayIconUri = MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
	)

	/**
	 * Native package origin proves YouTube, while these fields prove a specific
	 * video. No title, channel, duration, popularity or package-only inference is
	 * allowed to manufacture an id here.
	 */
	fun identifyNative(packageName: String, fields: NativeMetadataFields): Identity {
		if (!isNativePackage(packageName)) {
			return Identity.Unconfirmed("$packageName is not a supported native YouTube package")
		}
		val isMusic = packageName == YOUTUBE_MUSIC_PACKAGE
		fun confirmed(
			videoId: String,
			route: String,
			detail: String,
			isShort: Boolean = false,
		) = Identity.Confirmed(
			videoId = videoId,
			url = watchUrl(videoId),
			isMusic = isMusic,
			isShort = isShort,
			exactIdRoute = route,
			source = "native package $packageName + $detail",
		)

		exactMediaId(fields.mediaId)?.let {
			return confirmed(it.videoId, "media id", "media id", it.isShort)
		}
		videoIdFromYouTubeUri(fields.mediaUri)?.let {
			return confirmed(it.videoId, "media URI", "media URI", it.isShort)
		}
		listOf(fields.artUri, fields.albumArtUri, fields.displayIconUri).forEach { artworkUri ->
			videoIdFromArtworkUri(artworkUri)?.let {
				return confirmed(it, "artwork URI", "artwork URI")
			}
		}

		return Identity.SiteOnly(
			host = packageName,
			isMusic = isMusic,
			source = "native package origin → $packageName (no exact video id in MediaSession)",
		)
	}

	fun identifyNative(packageName: String, md: MediaMetadata?): Identity =
		identifyNative(packageName, nativeMetadataFields(md))

	/**
	 * @param md the session's metadata
	 * @param hint the notification bound to *this* session, if any
	 * @param url what the address bar last said, if the watcher is enabled
	 * @param soleSession whether this is the browser's only media session
	 */
	fun identify(
		md: MediaMetadata?,
		hint: NotificationHints.Hint? = null,
		url: UrlEvidence.Evidence? = null,
		soleSession: Boolean = false,
	): Identity {
		// 0 — the address bar, when it corroborates or stands alone.
		//
		// Deliberately not treated as proof on its own: it describes the
		// *foreground tab*, and YouTube playing in a background tab while
		// another site is on screen is ordinary behaviour. So a YouTube URL
		// only decides identity when the notification agrees, or when there is
		// exactly one session and therefore nothing to confuse it with. By the
		// same reasoning a non-YouTube URL proves nothing here and must never
		// taint — it may simply be a different tab from the one playing.
		if (url != null && isYouTubeHost(url.host)) {
			val hintHost = hint?.host
			val hintAgrees = isYouTubeHost(hintHost)
			// A hint that names a *different* site contradicts the bar and must
			// veto it. A hint with no recognisable host is absence of
			// information, not disagreement — treating it as a veto discarded
			// perfectly good video ids whenever Chromium's sub-text wasn't
			// parseable, costing the payload its `url` for no reason.
			val hintContradicts = hintHost != null && !hintAgrees
			if (hintAgrees || (!hintContradicts && soleSession)) {
				val corroboration =
					if (hintAgrees) "address bar + notification" else "address bar, sole session"
				url.videoId?.let { id ->
					return Identity.Confirmed(
						videoId = id,
						url = watchUrl(id),
						isMusic = url.host == YOUTUBE_MUSIC_HOST,
						isShort = url.isShort,
						urlGeneration = url.generation.takeIf { it > 0 },
						source = "$corroboration → $id",
					)
				}
				return Identity.SiteOnly(
					host = url.host!!,
					isMusic = url.host == YOUTUBE_MUSIC_HOST,
					source = "$corroboration → ${url.host} (no video id in the bar)",
				)
			}
		}

		// 1 & 2 — URI evidence, which also pins the video id.
		if (md != null) {
			for (key in URI_KEYS) {
				val uri = MetadataDump.textOrNull(md, key) ?: continue
				val where = key.substringAfterLast('.')

				WATCH_URL.find(uri)?.let { m ->
					return Identity.Confirmed(
						videoId = m.groupValues[1],
						url = watchUrl(m.groupValues[1]),
						isMusic = uri.contains(YOUTUBE_MUSIC_HOST, ignoreCase = true),
						source = "$where → watch URL",
					)
				}
				THUMBNAIL.find(uri)?.let { m ->
					return Identity.Confirmed(
						videoId = m.groupValues[1],
						url = watchUrl(m.groupValues[1]),
						// A thumbnail can't separate youtube.com from music.youtube.com.
						isMusic = false,
						source = "$where → ytimg thumbnail",
					)
				}
			}
		}

		// 3 — the notification origin. Site only, no video id.
		val host = hint?.host
		if (isYouTubeHost(host)) {
			return Identity.SiteOnly(
				host = host!!,
				isMusic = host == YOUTUBE_MUSIC_HOST,
				source = "notification sub-text → $host",
			)
		}

		// A named non-YouTube host is the one case that taints the track rather
		// than merely leaving it unproven.
		if (host != null) {
			return Identity.Unconfirmed(
				"notification says $host, not YouTube",
				provenOtherSite = true,
			)
		}

		return Identity.Unconfirmed(
			when {
				md == null -> "no metadata"
				hint != null -> "notification had no recognisable host"
				URI_KEYS.any { MetadataDump.textOrNull(md, it) != null } ->
					"URIs present but none are YouTube"

				else -> "no URI keys populated and no notification hint yet"
			},
		)
	}

	private fun watchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

	private val VIDEO_ID = Regex("""^[A-Za-z0-9_-]{11}$""")

	private data class UriVideoId(val videoId: String, val isShort: Boolean)

	private fun exactMediaId(value: String?): UriVideoId? {
		val candidate = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
		return candidate.takeIf(VIDEO_ID::matches)?.let { UriVideoId(it, false) }
			?: videoIdFromYouTubeUri(candidate)
	}

	private fun videoIdFromYouTubeUri(value: String?): UriVideoId? {
		val uri = parseHttpsUri(value) ?: return null
		val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
		val path = uri.path.orEmpty()
		val candidate = when {
			host == "youtu.be" -> path.trim('/').substringBefore('/')
			host in setOf("youtube.com", "m.youtube.com", "music.youtube.com") &&
				path == "/watch" -> uniqueQueryParameter(uri.rawQuery, "v")
			host in setOf("youtube.com", "m.youtube.com", "music.youtube.com") &&
				(path.startsWith("/shorts/") || path.startsWith("/embed/") ||
					path.startsWith("/live/")) -> path.split('/').getOrNull(2)
			else -> null
		}
		val videoId = candidate?.takeIf(VIDEO_ID::matches) ?: return null
		return UriVideoId(videoId, path.startsWith("/shorts/"))
	}

	private fun videoIdFromArtworkUri(value: String?): String? {
		val uri = parseHttpsUri(value) ?: return null
		val host = uri.host?.lowercase() ?: return null
		val canonicalHost = host == "i.ytimg.com" || host == "img.youtube.com" ||
			Regex("""i\d+\.ytimg\.com""").matches(host)
		if (!canonicalHost) return null
		val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
		val marker = segments.indexOfFirst { it == "vi" || it == "vi_webp" }
		return segments.getOrNull(marker + 1)?.takeIf { marker >= 0 && VIDEO_ID.matches(it) }
	}

	private fun parseHttpsUri(value: String?): URI? {
		val uri = runCatching { URI(value?.trim().orEmpty()) }.getOrNull() ?: return null
		return uri.takeIf { it.scheme.equals("https", ignoreCase = true) && it.host != null }
	}

	/** Duplicate conflicting ids are ambiguous and malformed escapes fail closed. */
	private fun uniqueQueryParameter(rawQuery: String?, wanted: String): String? {
		val query = rawQuery ?: return null
		val values = mutableSetOf<String>()
		for (part in query.split('&')) {
			if (part.substringBefore('=') != wanted) continue
			val encoded = part.substringAfter('=', "")
			val decoded = runCatching {
				URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
			}.getOrNull() ?: return null
			values += decoded
		}
		return values.singleOrNull()
	}
}
