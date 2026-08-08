package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeYouTubeAppsTest {

	@Test
	fun `browser packages remain enabled independently of native toggles`() {
		for (packageName in YouTubeProbe.TARGET_PACKAGES) {
			assertTrue(
				YouTubeProbe.acceptsPackage(
					packageName = packageName,
					nativeYouTubeEnabled = false,
					nativeYouTubeMusicEnabled = false,
				),
			)
		}
	}

	@Test
	fun `each native package has its own opt-in boundary`() {
		assertFalse(YouTubeProbe.acceptsPackage(YouTubeProbe.YOUTUBE_PACKAGE, false, false))
		assertFalse(YouTubeProbe.acceptsPackage(YouTubeProbe.YOUTUBE_MUSIC_PACKAGE, false, false))
		assertTrue(YouTubeProbe.acceptsPackage(YouTubeProbe.YOUTUBE_PACKAGE, true, false))
		assertFalse(YouTubeProbe.acceptsPackage(YouTubeProbe.YOUTUBE_MUSIC_PACKAGE, true, false))
		assertFalse(YouTubeProbe.acceptsPackage(YouTubeProbe.YOUTUBE_PACKAGE, false, true))
		assertTrue(YouTubeProbe.acceptsPackage(YouTubeProbe.YOUTUBE_MUSIC_PACKAGE, false, true))
		assertFalse(YouTubeProbe.acceptsPackage("com.example.player", true, true))
	}

	@Test
	fun `native media id is exact and canonicalized`() {
		val identity = YouTubeProbe.identifyNative(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			fields = YouTubeProbe.NativeMetadataFields(mediaId = "dQw4w9WgXcQ"),
		) as YouTubeProbe.Identity.Confirmed

		assertEquals("dQw4w9WgXcQ", identity.videoId)
		assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", identity.url)
		assertEquals("media id", identity.exactIdRoute)
		assertFalse(identity.isMusic)
	}

	@Test
	fun `native exact id routes prefer media id then media URI then artwork`() {
		val identity = YouTubeProbe.identifyNative(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			fields = YouTubeProbe.NativeMetadataFields(
				mediaId = "dQw4w9WgXcQ",
				mediaUri = "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
				artUri = "https://i.ytimg.com/vi/9bZkp7q19f0/hqdefault.jpg",
			),
		) as YouTubeProbe.Identity.Confirmed

		assertEquals("dQw4w9WgXcQ", identity.videoId)
		assertEquals("media id", identity.exactIdRoute)
	}

	@Test
	fun `native media URI is accepted only from a canonical YouTube route`() {
		val identity = YouTubeProbe.identifyNative(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			fields = YouTubeProbe.NativeMetadataFields(
				mediaUri = "https://music.youtube.com/watch?list=RDAMVM&v=aqz-KE-bpKQ",
			),
		) as YouTubeProbe.Identity.Confirmed

		assertEquals("aqz-KE-bpKQ", identity.videoId)
		assertEquals("https://www.youtube.com/watch?v=aqz-KE-bpKQ", identity.url)
		assertEquals("media URI", identity.exactIdRoute)
	}

	@Test
	fun `a native shorts media URI retains short path evidence`() {
		val identity = YouTubeProbe.identifyNative(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			fields = YouTubeProbe.NativeMetadataFields(
				mediaUri = "https://www.youtube.com/shorts/aqz-KE-bpKQ",
			),
		) as YouTubeProbe.Identity.Confirmed

		assertTrue(identity.isShort)
		assertEquals("https://www.youtube.com/watch?v=aqz-KE-bpKQ", identity.url)
	}

	@Test
	fun `native artwork URI can prove the exact video id`() {
		val identity = YouTubeProbe.identifyNative(
			packageName = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
			fields = YouTubeProbe.NativeMetadataFields(
				albumArtUri = "https://i.ytimg.com/vi_webp/9bZkp7q19f0/maxresdefault.webp",
			),
		) as YouTubeProbe.Identity.Confirmed

		assertEquals("9bZkp7q19f0", identity.videoId)
		assertEquals("https://www.youtube.com/watch?v=9bZkp7q19f0", identity.url)
		assertEquals("artwork URI", identity.exactIdRoute)
		assertTrue(identity.isMusic)
	}

	@Test
	fun `invalid or non YouTube ids stay site-only and off-chain`() {
		val identity = YouTubeProbe.identifyNative(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			fields = YouTubeProbe.NativeMetadataFields(
				mediaId = "not-an-id",
				mediaUri = "https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ",
				artUri = "https://example.com/vi/9bZkp7q19f0/cover.jpg",
			),
		)

		assertTrue(identity is YouTubeProbe.Identity.SiteOnly)
		assertEquals(null, (identity as? YouTubeProbe.Identity.Confirmed)?.videoId)
	}

	@Test
	fun `conflicting or malformed media URI ids fail closed`() {
		val conflicting = YouTubeProbe.identifyNative(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			fields = YouTubeProbe.NativeMetadataFields(
				mediaUri = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&v=aqz-KE-bpKQ",
			),
		)
		val malformed = YouTubeProbe.identifyNative(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			fields = YouTubeProbe.NativeMetadataFields(
				mediaUri = "https://www.youtube.com/watch?v=%ZZ",
			),
		)

		assertTrue(conflicting is YouTubeProbe.Identity.SiteOnly)
		assertTrue(malformed is YouTubeProbe.Identity.SiteOnly)
	}

	@Test
	fun `package origin alone never invents a video id`() {
		val identity = YouTubeProbe.identifyNative(
			packageName = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
			fields = YouTubeProbe.NativeMetadataFields(),
		)

		assertTrue(identity is YouTubeProbe.Identity.SiteOnly)
		assertTrue((identity as YouTubeProbe.Identity.SiteOnly).isMusic)
		assertTrue(identity.source.contains(YouTubeProbe.YOUTUBE_MUSIC_PACKAGE))
	}
}
