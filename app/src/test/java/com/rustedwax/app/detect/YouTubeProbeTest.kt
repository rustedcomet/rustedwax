package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Address-bar corroboration — the rule that decides whether a payload gets a
 * `url`.
 *
 * These pass `md = null` so no Android `MediaMetadata` is needed: route 0 (the
 * address bar) is evaluated before the metadata routes and is exactly what
 * these cases exercise.
 *
 * Field data 2026-07-25: 20 of 133 scrobbles reached the chain with no `url`.
 * Part of that was a hint with an unparseable host vetoing a perfectly good
 * video id — the case pinned below.
 */
class YouTubeProbeTest {

	private fun url(host: String?, videoId: String?, isShort: Boolean = false) =
		UrlEvidence.Evidence(host = host, videoId = videoId, isShort = isShort, raw = "test")

	private fun hint(host: String?) =
		NotificationHints.Hint(host = host, subText = host, title = null, text = null)

	@Test
	fun `bar plus agreeing notification confirms the video`() {
		val id = YouTubeProbe.identify(
			md = null,
			hint = hint("youtube.com"),
			url = url("youtube.com", "abcdefghijk"),
			soleSession = false,
		)
		assertTrue(id is YouTubeProbe.Identity.Confirmed)
		assertEquals("abcdefghijk", (id as YouTubeProbe.Identity.Confirmed).videoId)
	}

	/**
	 * The regression. Chromium's sub-text isn't always a parseable host; the
	 * hint then carries `host = null`. That is absence of information, not
	 * disagreement, and it used to block confirmation — costing the payload its
	 * `url` even though the address bar named the video outright.
	 */
	@Test
	fun `a hint with no host does not veto the address bar`() {
		val id = YouTubeProbe.identify(
			md = null,
			hint = hint(null),
			url = url("youtube.com", "abcdefghijk"),
			soleSession = true,
		)
		assertTrue(
			"a hostless hint must not suppress a good video id, got $id",
			id is YouTubeProbe.Identity.Confirmed,
		)
	}

	/** A hint naming a different site is real disagreement and must still win. */
	@Test
	fun `a hint naming another site vetoes the address bar`() {
		val id = YouTubeProbe.identify(
			md = null,
			hint = hint("soundcloud.com"),
			url = url("youtube.com", "abcdefghijk"),
			soleSession = true,
		)
		assertTrue(id !is YouTubeProbe.Identity.Confirmed)
	}

	/**
	 * With several sessions live and nothing corroborating, the bar describes
	 * the foreground tab — which need not be the tab that's playing.
	 */
	@Test
	fun `an uncorroborated bar is not trusted when other sessions exist`() {
		val id = YouTubeProbe.identify(
			md = null,
			hint = null,
			url = url("youtube.com", "abcdefghijk"),
			soleSession = false,
		)
		assertTrue(id !is YouTubeProbe.Identity.Confirmed)
	}

	@Test
	fun `a bar with a host but no video id yields site-only`() {
		val id = YouTubeProbe.identify(
			md = null,
			hint = hint("youtube.com"),
			url = url("youtube.com", null),
			soleSession = true,
		)
		assertTrue(id is YouTubeProbe.Identity.SiteOnly)
	}

	@Test
	fun `shorts flag survives into the confirmed identity`() {
		val id = YouTubeProbe.identify(
			md = null,
			hint = hint("youtube.com"),
			url = url("youtube.com", "abcdefghijk", isShort = true),
			soleSession = true,
		)
		assertTrue((id as YouTubeProbe.Identity.Confirmed).isShort)
	}

	@Test
	fun `host allowlist is exact`() {
		assertTrue(YouTubeProbe.isYouTubeHost("youtube.com"))
		assertTrue(YouTubeProbe.isYouTubeHost("m.youtube.com"))
		assertTrue(YouTubeProbe.isYouTubeHost("music.youtube.com"))
		assertTrue(YouTubeProbe.isYouTubeHost("youtu.be"))
		assertEquals(false, YouTubeProbe.isYouTubeHost("notyoutube.com"))
		assertEquals(false, YouTubeProbe.isYouTubeHost("youtube.com.evil.net"))
		assertEquals(false, YouTubeProbe.isYouTubeHost(null))
	}
}
