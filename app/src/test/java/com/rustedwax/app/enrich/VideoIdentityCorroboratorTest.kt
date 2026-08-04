package com.rustedwax.app.enrich

import com.rustedwax.app.detect.ResolverContext
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.YouTubeProbe
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoIdentityCorroboratorTest {

	private fun ended(
		title: String,
		channel: String,
		durationMs: Long,
		playlistId: String = "frozen-list",
	) = SessionSnapshot(
		packageName = "com.android.chrome",
		appLabel = "Chrome",
		isTarget = true,
		title = title,
		artist = channel,
		album = null,
		durationMs = durationMs,
		positionMs = durationMs,
		playedMs = durationMs,
		loopDetected = false,
		playbackState = "STOPPED",
		isPlaying = false,
		percentPlayed = 1.0,
		identity = YouTubeProbe.Identity.SiteOnly(
			host = "m.youtube.com",
			isMusic = false,
			source = "frozen notification",
		),
		resolverContext = ResolverContext(playlistId = playlistId),
		notificationHint = null,
		metadataLines = emptyList(),
		trackStartedAtEpochSec = 1_785_000_000,
	)

	@Test
	fun `saGYMhApaH8 can never be completed by La Bebe 3mchJ-EW9rM`() {
		val session = ended("Me Porto Bonito", "Bad Bunny", 191_000)
		val next = VideoResolution(
			videoId = "3mchJ-EW9rM",
			source = "later foreground",
			title = "La Bebe (Remix)",
			channel = "Yng Lvcas",
			lengthSeconds = 191,
		)
		val mismatch = VideoIdentityCorroborator.contradiction(
			session,
			next,
			VideoFacts(
				videoId = "3mchJ-EW9rM",
				title = "La Bebe (Remix)",
				author = "Yng Lvcas",
				lengthSeconds = 191,
			),
		)
		assertNotNull(mismatch)
		assertTrue(mismatch!!.contains("contradicts ended"))
	}

	@Test
	fun `aZaxQG3ggng can never be completed by Coming Home 2QqyPy2itXw`() {
		val session = ended(
			"YCB Frenzy - Crazy ( Official Video ) Shot and edited by: @Maggie Rudisill",
			"YCB Frenzy",
			192_000,
		)
		val next = VideoResolution(
			videoId = "2QqyPy2itXw",
			source = "later foreground",
			title = "Coming Home SHOT BY: @SHONMAC071",
			channel = "E.K THE NKABK",
			lengthSeconds = 192,
		)
		assertNotNull(VideoIdentityCorroborator.contradiction(session, next, null))
	}

	@Test
	fun `a later foreground id without facts cannot replace observed ended identity`() {
		val session = ended("Me Porto Bonito", "Bad Bunny", 191_000).copy(
			identity = YouTubeProbe.Identity.Confirmed(
				videoId = "3mchJ-EW9rM",
				url = "https://www.youtube.com/watch?v=3mchJ-EW9rM",
				isMusic = false,
				source = "transition frame",
			),
			resolverContext = ResolverContext(observedVideoId = "saGYMhApaH8"),
		)
		val nextWithoutFacts = VideoResolution(
			videoId = "3mchJ-EW9rM",
			source = "frozen transition frame",
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(session, nextWithoutFacts, facts = null),
		)
	}

	@Test
	fun `corroborated frozen candidate remains eligible`() {
		val session = ended("Me Porto Bonito", "Bad Bunny", 191_000)
		val own = VideoResolution(
			videoId = "saGYMhApaH8",
			source = "frozen playlist",
			title = "BAD BUNNY x CHENCHO CORLEONE - ME PORTO BONITO",
			channel = "Bad Bunny",
			lengthSeconds = 192,
		)
		assertNull(VideoIdentityCorroborator.contradiction(session, own, null))
	}

	@Test
	fun `CJjvg7PbE4w observed Nunca Me Amo survives uploader versus credit roles`() {
		val id = "CJjvg7PbE4w"
		val session = ended("Nunca Me Amó", "Boy Wonder Chosen Few", 204_000).copy(
			resolverContext = ResolverContext(
				observedVideoId = id,
				urlGeneration = 41,
			),
		)
		val resolution = VideoResolution(
			videoId = id,
			source = "frozen current-generation URL",
			title = "Nunca Me Amó",
			channel = "Jon Z, Baby Rasta, & Boy Wonder CF",
			lengthSeconds = 204,
		)
		assertNull(VideoIdentityCorroborator.contradiction(session, resolution, null))
	}

	@Test
	fun `channel agreement alone cannot rescue title or duration contradiction`() {
		val id = "CJjvg7PbE4w"
		val session = ended("Nunca Me Amó", "Boy Wonder Chosen Few", 204_000).copy(
			resolverContext = ResolverContext(observedVideoId = id, urlGeneration = 41),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session,
				VideoResolution(
					id, "same channel", "Adjacent Track", "Boy Wonder Chosen Few", 204,
				),
				null,
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session,
				VideoResolution(
					id, "same channel", "Nunca Me Amó", "Boy Wonder Chosen Few", 600,
				),
				null,
			),
		)
	}

	@Test
	fun `same title different unobserved upload retains conservative channel guard`() {
		val session = ended("Nunca Me Amó", "Boy Wonder Chosen Few", 204_000)
		val otherUpload = VideoResolution(
			videoId = "otherUpload1",
			source = "search-only",
			title = "Nunca Me Amó",
			channel = "Different Uploader",
			lengthSeconds = 204,
		)
		assertNotNull(VideoIdentityCorroborator.contradiction(session, otherUpload, null))
	}

	@Test
	fun `unique exact OMV collaborative byline accepts complete leading owner`() {
		val session = ended("CENTRAL CEE - BOOGA (MUSIC VIDEO)", "Central Cee", 110_000)
		val resolution = VideoResolution(
			videoId = "JmeUtPih4U8",
			source = "title+channel+duration search",
			title = "CENTRAL CEE - BOOGA (MUSIC VIDEO)",
			channel = "Central Cee and LIVE YOURS",
			lengthSeconds = 110,
			uniquelyResolved = true,
			collaborativeChannel = true,
		)
		val facts = VideoFacts(
			videoId = resolution.videoId,
			title = resolution.title,
			author = resolution.channel,
			category = "Music",
			lengthSeconds = 110,
			musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
		)
		assertNull(VideoIdentityCorroborator.contradiction(session, resolution, facts))
		assertNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution, facts.copy(author = "Central Cee"),
			),
		)
	}

	@Test
	fun `collaborative byline needs complete owner separator title duration and hard music`() {
		val session = ended("Owner - Work (Music Video)", "Owner", 110_000)
		val base = VideoResolution(
			videoId = "abcdefghijk",
			source = "unique search",
			title = "Owner - Work (Music Video)",
			channel = "Owner and Collaborator",
			lengthSeconds = 110,
			uniquelyResolved = true,
			collaborativeChannel = true,
		)
		fun facts(
			title: String? = base.title,
			author: String? = base.channel,
			length: Long? = 110,
			type: String? = "MUSIC_VIDEO_TYPE_OMV",
		) = VideoFacts(
			videoId = base.videoId,
			title = title,
			author = author,
			category = "Music",
			lengthSeconds = length,
			musicVideoType = type,
		)

		assertNotNull(VideoIdentityCorroborator.contradiction(session, base, facts(type = null)))
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, base.copy(collaborativeChannel = false), facts(),
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, base.copy(uniquelyResolved = false), facts(),
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, base.copy(channel = "Owner Collaborator"),
				facts(author = "Owner Collaborator"),
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session.copy(artist = "Own"), base, facts(),
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, base.copy(channel = "Owner Fan and Collaborator"),
				facts(author = "Owner Fan and Collaborator"),
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, base.copy(channel = "Another Owner and Collaborator"),
				facts(author = "Another Owner and Collaborator"),
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, base.copy(title = "Wrong Work"), facts(),
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, base.copy(lengthSeconds = 140), facts(length = 140),
			),
		)
	}

	@Test
	fun `weak title cannot let a successor complete an ended track`() {
		val session = ended("J. Balvin - Ay Vamos (Official Video)", "jbalvinVEVO", 266_921).copy(
			resolverContext = ResolverContext(
				observedVideoId = "TapXs54Ah3E",
				urlGeneration = 52,
			),
		)
		val successor = VideoResolution(
			videoId = "at1axdFpcgI",
			source = "successor",
			title = "Ay Vamos",
			channel = "jbalvinVEVO",
			lengthSeconds = 266,
		)
		assertNotNull(VideoIdentityCorroborator.contradiction(session, successor, null))
	}

	@Test
	fun `finalized guard shares the log 16 presentation matcher`() {
		assertTrue(
			VideoIdentityCorroborator.titleEvidence(
				"BAD BUNNY - SOY PEOR (Video Oficial)",
				"BAD BUNNY - SOY PEOR (Official Video)",
			) != com.rustedwax.app.detect.VideoTitleMatcher.Evidence.CONTRADICTION,
		)
		assertTrue(
			VideoIdentityCorroborator.titleEvidence(
				"BAD BUNNY x JHAY CORTEZ - DÁKITI (Video Oficial)",
				"BAD BUNNY x JHAY CORTEZ - DÁKITI | EL ÚLTIMO TOUR DEL MUNDO " +
					"(Official Video)",
			) != com.rustedwax.app.detect.VideoTitleMatcher.Evidence.CONTRADICTION,
		)
		assertFalse(
			VideoIdentityCorroborator.titleEvidence(
				"BAD BUNNY x JHAY CORTEZ - DÁKITI (Video Oficial)",
				"FloyyMenor, Cris MJ - Gata Only (Video Oficial)",
			) != com.rustedwax.app.detect.VideoTitleMatcher.Evidence.CONTRADICTION,
		)
	}
}
