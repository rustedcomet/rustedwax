package com.rustedwax.app.detect

import android.media.MediaMetadata

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
 * So route 3 is expected to be the one that actually fires, and it costs us the
 * `url` field: we can say "this is YouTube" but not "this is *this video*".
 * That is a payload with `platform` and no `url`, which is a supported shape.
 */
object YouTubeProbe {

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
		"com.google.android.youtube",
		"com.google.android.apps.youtube.music",
	)

	private val THUMBNAIL = Regex("""i\d?\.ytimg\.com/vi/([A-Za-z0-9_-]{11})""")
	private val WATCH_URL =
		Regex("""(?:youtube\.com/watch\?(?:.*&)?v=|youtu\.be/)([A-Za-z0-9_-]{11})""")

	private const val YOUTUBE_HOST = "youtube.com"
	private const val YOUTUBE_MUSIC_HOST = "music.youtube.com"

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
			override val source: String,
		) : Identity

		/**
		 * Proven to be YouTube, but the specific video is unknown — the
		 * expected outcome for Chromium browsers. Payload gets `platform` but
		 * no `url`.
		 */
		data class SiteOnly(
			val host: String,
			val isMusic: Boolean,
			override val source: String,
		) : Identity

		/** Can't prove the site. Never scrobbled. */
		data class Unconfirmed(val reason: String) : Identity {
			override val source: String get() = reason
		}
	}

	/**
	 * @param md the session's metadata
	 * @param hint the most recent media notification seen for this package, if any
	 */
	fun identify(md: MediaMetadata?, hint: NotificationHints.Hint? = null): Identity {
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
		if (host != null && (host == YOUTUBE_HOST || host.endsWith(".$YOUTUBE_HOST"))) {
			return Identity.SiteOnly(
				host = host,
				isMusic = host == YOUTUBE_MUSIC_HOST,
				source = "notification sub-text → $host",
			)
		}

		return Identity.Unconfirmed(
			when {
				md == null -> "no metadata"
				host != null -> "notification says $host, not YouTube"
				hint != null -> "notification had no recognisable host"
				URI_KEYS.any { MetadataDump.textOrNull(md, it) != null } ->
					"URIs present but none are YouTube"

				else -> "no URI keys populated and no notification hint yet"
			},
		)
	}

	private fun watchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"
}
