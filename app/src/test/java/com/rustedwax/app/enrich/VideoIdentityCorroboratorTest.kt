package com.rustedwax.app.enrich

import com.rustedwax.app.detect.ResolverContext
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.SourceProof
import com.rustedwax.app.detect.YouTubeProbe
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoIdentityCorroboratorTest {

	@Test
	fun `foreground Short final corroboration requires exact handle from candidate and facts`() {
		val foreground = ended("Hackers' Skills...", "@Beredist", 139_000).copy(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			appLabel = "YouTube Shorts (foreground)",
			sourceProof = SourceProof.NATIVE_FOREGROUND_SHORT,
			ownerHandle = "@Beredist",
		)
		val resolution = VideoResolution(
			videoId = "orsMh4bNeGE",
			source = "owner handle",
			title = "Hackers' Skills...",
			channel = "Beredits",
			lengthSeconds = 139,
			uniquelyResolved = true,
			ownerHandle = "@beredist",
		)
		val facts = VideoFacts(
			videoId = resolution.videoId,
			title = resolution.title,
			author = resolution.channel,
			ownerHandle = "@Beredist",
			lengthSeconds = 139,
			watchPageResolved = true,
		)
		assertNull(VideoIdentityCorroborator.contradiction(foreground, resolution, facts))
		assertTrue(VideoIdentityCorroborator.cacheable(foreground, resolution, facts))

		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				foreground,
				resolution.copy(ownerHandle = "@other_owner"),
				facts,
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				foreground,
				resolution,
				facts.copy(ownerHandle = null),
			),
		)
		assertFalse(VideoIdentityCorroborator.cacheable(foreground, resolution, facts.copy(ownerHandle = null)))
	}

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

	/**
	 * Measured 2026-08-04, native YouTube, playlist `Reggaeton 2016,17,18`.
	 *
	 * `7J6xA1_f8as` is entry #23 and is genuinely the track that played — "Te
	 * Busco", 234 s, 233 s of it watched. But YouTube spells its channel two
	 * ways: the playlist page and the MediaSession both say
	 * "Cosculluela El Principe" while the watch page says "Cosculluela - Topic".
	 * Stripping " - Topic" leaves "Cosculluela", still not the full stage name,
	 * so the enriched-watch-facts pass vetoed a correct id the playlist had
	 * already corroborated and the scrobble was silently lost.
	 */
	@Test
	fun `a Topic channel alias does not veto a playlist-verified native id`() {
		val session = ended("Te Busco", "Cosculluela El Principe", 234_000).copy(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			appLabel = "YouTube",
		)
		val resolution = VideoResolution(
			videoId = "7J6xA1_f8as",
			source = "playlist PL7NMzffnWK8RMWFO3rZABAgN-Rk9pCkEm",
			title = "Te Busco",
			channel = "Cosculluela El Principe",
			lengthSeconds = 234,
			uniquelyResolved = true,
			playlistVerified = true,
		)
		val facts = VideoFacts(
			videoId = resolution.videoId,
			title = "Te Busco",
			author = "Cosculluela - Topic",
			lengthSeconds = 234,
			watchPageResolved = true,
		)
		assertNull(VideoIdentityCorroborator.contradiction(session, resolution, facts))
	}

	/** The relaxation is for the channel alias only — a real mismatch still refuses. */
	@Test
	fun `a playlist-verified id is still refused when title or duration disagree`() {
		val session = ended("Te Busco", "Cosculluela El Principe", 234_000).copy(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			appLabel = "YouTube",
		)
		val resolution = VideoResolution(
			videoId = "7J6xA1_f8as",
			source = "playlist PL7NMzffnWK8RMWFO3rZABAgN-Rk9pCkEm",
			title = "Te Busco",
			channel = "Cosculluela El Principe",
			lengthSeconds = 234,
			uniquelyResolved = true,
			playlistVerified = true,
		)
		val base = VideoFacts(
			videoId = resolution.videoId,
			title = "Te Busco",
			author = "Cosculluela - Topic",
			lengthSeconds = 234,
			watchPageResolved = true,
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution, base.copy(title = "Un Verano Sin Ti"),
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution, base.copy(lengthSeconds = 95),
			),
		)
	}

	/** Browser sessions must be untouched by the native-only relaxation. */
	@Test
	fun `a browser playlist resolution keeps the strict channel rule`() {
		val session = ended("Te Busco", "Cosculluela El Principe", 234_000)
		val resolution = VideoResolution(
			videoId = "7J6xA1_f8as",
			source = "playlist PL7NMzffnWK8RMWFO3rZABAgN-Rk9pCkEm",
			title = "Te Busco",
			channel = "Cosculluela El Principe",
			lengthSeconds = 234,
			uniquelyResolved = true,
			playlistVerified = true,
		)
		val facts = VideoFacts(
			videoId = resolution.videoId,
			title = "Te Busco",
			author = "Cosculluela - Topic",
			lengthSeconds = 234,
			watchPageResolved = true,
		)
		assertNotNull(VideoIdentityCorroborator.contradiction(session, resolution, facts))
	}

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
	fun `exact native media id accepts clean short metadata with duration corroboration`() {
		val id = "oG-4Uvhm4lI"
		val native = ended("Poker Face", "Lady Gaga", 237_000).copy(
			packageName = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
			appLabel = "YouTube Music",
			identity = YouTubeProbe.Identity.Confirmed(
				videoId = id,
				url = "https://www.youtube.com/watch?v=$id",
				isMusic = true,
				exactIdRoute = "media id",
				source = "native media id",
			),
		)
		val resolution = VideoResolution(
			videoId = id,
			source = "native media id",
			title = "Lady Gaga - Poker Face (Official Music Video)",
			channel = "LadyGagaVEVO",
			lengthSeconds = 237,
		)

		assertNull(VideoIdentityCorroborator.contradiction(native, resolution, null))
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				native,
				resolution.copy(title = "A Different Song"),
				null,
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				native,
				resolution.copy(lengthSeconds = 600),
				null,
			),
		)
	}

	@Test
	fun `structured native music proof rechecks parsed facts and never applies to browser`() {
		val native = ended("No Quiere Enamorarse", "Ozuna", 213_000).copy(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			appLabel = "YouTube",
		)
		val resolution = VideoResolution(
			videoId = "5YXxnHVYRDk",
			source = "structured native music title+artist+duration",
			title = "Ozuna - No Quiere Enamorarse (Official Lyric Video)",
			channel = "Ozunapr",
			lengthSeconds = 213,
			uniquelyResolved = true,
			structuredNativeMusic = true,
		)
		val facts = VideoFacts(
			videoId = resolution.videoId,
			title = resolution.title,
			author = resolution.channel,
			lengthSeconds = resolution.lengthSeconds,
			watchPageResolved = true,
		)

		assertNull(VideoIdentityCorroborator.contradiction(native, resolution, facts))
		assertFalse(VideoIdentityCorroborator.cacheable(native, resolution, facts))
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				native, resolution, facts.copy(title = "A different song"),
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				ended("No Quiere Enamorarse", "Ozuna", 213_000), resolution, facts,
			),
		)
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

		// A byline whose *leader* is the ended channel, with the title and the
		// duration also agreeing, is the same upload described at greater length
		// — not a contradiction. Measured 2026-08-05: 12 rejections in one
		// session on "La Melma Music and 2 more" against "La Melma Music" and
		// "Eladio Carrion and CAZZU" against "Eladio Carrion", every one a real
		// listen thrown away. The earlier rule additionally demanded a
		// YouTube-Music-recognised video and a uniquely-resolved candidate, which
		// the field showed is not how these arrive — history resolves them, so
		// those flags are false.
		assertNull(VideoIdentityCorroborator.contradiction(session, base, facts(type = null)))
		assertNull(
			VideoIdentityCorroborator.contradiction(
				session, base.copy(collaborativeChannel = false), facts(),
			),
		)
		assertNull(
			VideoIdentityCorroborator.contradiction(
				session, base.copy(uniquelyResolved = false), facts(),
			),
		)
		// The three fields still all have to agree: a byline leader match cannot
		// rescue a contradicting duration.
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, base.copy(lengthSeconds = 45), facts(length = 45),
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

	/**
	 * Measured 2026-08-05. The resolver found `QnRnooyKeZk` correctly from the
	 * account's own watch history, and this guard then discarded it because
	 * YouTube's uploaded title is Spanish while the phone — and the foreground
	 * observer reading its screen — shows the auto-translated English one. One
	 * video, two names, both from its own page.
	 */
	@Test
	fun `an auto-translated displayed title is not a contradiction`() {
		val onScreen = "The Day Karol G Experienced an Unexpected Moment During a Concert"
		val session = ended(onScreen, "@enefectoescine17", 60_000).copy(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			appLabel = "YouTube Shorts (foreground)",
			sourceProof = SourceProof.NATIVE_FOREGROUND_SHORT,
			ownerHandle = "@enefectoescine17",
		)
		val resolution = VideoResolution(
			videoId = "QnRnooyKeZk",
			source = "watch history",
			title = "El D\u00eda que Karol G Vivi\u00f3 un Momento Inesperado en Pleno Concierto",
			channel = "EN EFECTO ES CINE",
			lengthSeconds = 60,
			uniquelyResolved = true,
			ownerHandle = "@enefectoescine17",
			localizedTitle = onScreen,
		)
		val facts = VideoFacts(
			videoId = "QnRnooyKeZk",
			title = "El D\u00eda que Karol G Vivi\u00f3 un Momento Inesperado en Pleno Concierto",
			author = "EN EFECTO ES CINE",
			ownerHandle = "@enefectoescine17",
			lengthSeconds = 60,
		)
		assertNull(VideoIdentityCorroborator.contradiction(session, resolution, facts))
	}

	/** A displayed title that is not the frozen one stays a contradiction. */
	@Test
	fun `an unrelated displayed title does not rescue a wrong candidate`() {
		val session = ended(
			"The Day Karol G Experienced an Unexpected Moment During a Concert",
			"@enefectoescine17",
			60_000,
		).copy(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			appLabel = "YouTube Shorts (foreground)",
			sourceProof = SourceProof.NATIVE_FOREGROUND_SHORT,
			ownerHandle = "@enefectoescine17",
		)
		val resolution = VideoResolution(
			videoId = "QnRnooyKeZk",
			source = "watch history",
			title = "Something entirely different",
			channel = "EN EFECTO ES CINE",
			lengthSeconds = 60,
			uniquelyResolved = true,
			ownerHandle = "@enefectoescine17",
			localizedTitle = "Also entirely different",
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session,
				resolution,
				VideoFacts(
					videoId = "QnRnooyKeZk",
					title = "Something entirely different",
					author = "EN EFECTO ES CINE",
					ownerHandle = "@enefectoescine17",
					lengthSeconds = 60,
				),
			),
		)
	}

	/**
	 * Measured 2026-08-06 on `RTQFqbCPUGg`, and the mirror image of the Karol G
	 * case above: there the *uploaded* title was Spanish and the screen showed
	 * English, here the upload is Spanish and the screen shows it while the
	 * resolver's own `en-US` fetch of the same page renders the auto-translated
	 * English one.
	 *
	 * Verified by fetching the page twice on 2026-08-06:
	 *
	 * | `Accept-Language` | `videoDetails.title` | `videoPrimaryInfoRenderer` |
	 * | --- | --- | --- |
	 * | `en-US` | `#musica … #noticias` | `#music … #news` |
	 * | `es-419` | `#musica … #noticias` | `#musica … #noticias` |
	 *
	 * The old guard substituted the displayed title whenever its title *key*
	 * equalled the frozen one — and an all-hashtag title has an empty key, so
	 * every such title matched vacuously and the English rendering replaced the
	 * Spanish one the screen had actually shown. A page publishes two names for
	 * one id; agreement with either is agreement.
	 */
	@Test
	fun `either of a page's two titles may corroborate an all-hashtag Short`() {
		val onScreen = "#xbox​ #trendingnow​ #rap​ #musica​ #hiphop​ " +
			"#hiphopindiadancerealtyshowand​ #noticias​ #pubg​ #hindisong​"
		val uploaded = "#xbox #trendingnow #rap #musica #hiphop " +
			"#hiphopindiadancerealtyshowand #noticias #pubg #hindisong"
		val translated = "#xbox #trendingnow #rap #music #hiphop " +
			"#hiphopindiadancerealtyshowand #news #pubg #hindisong"
		val session = ended(onScreen, "@shortsvideo", 21_000).copy(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			appLabel = "YouTube Shorts (foreground)",
			sourceProof = SourceProof.NATIVE_FOREGROUND_SHORT,
			ownerHandle = "@shortsvideo",
		)
		val resolution = VideoResolution(
			videoId = "RTQFqbCPUGg",
			source = "run-local verified candidate",
			title = uploaded,
			channel = "Shorts Video",
			lengthSeconds = 21,
			uniquelyResolved = true,
			ownerHandle = "@shortsvideo",
			localizedTitle = translated,
		)
		val facts = VideoFacts(
			videoId = "RTQFqbCPUGg",
			title = uploaded,
			author = "Shorts Video",
			ownerHandle = "@shortsvideo",
			lengthSeconds = 21,
		)
		assertNull(VideoIdentityCorroborator.contradiction(session, resolution, facts))

		// The translated rendering alone still corroborates: it is the name the
		// screen shows when the phone itself is the one being translated for.
		assertNull(
			VideoIdentityCorroborator.contradiction(
				session.copy(title = translated),
				resolution,
				facts,
			),
		)
	}

	/** Neither name agreeing is still a refusal, and the message names both. */
	@Test
	fun `a candidate whose two titles both disagree is still refused`() {
		val session = ended("Nicki Minaj - Barbie Tingz", "@NickiMinaj", 60_000).copy(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			appLabel = "YouTube Shorts (foreground)",
			sourceProof = SourceProof.NATIVE_FOREGROUND_SHORT,
			ownerHandle = "@NickiMinaj",
		)
		val resolution = VideoResolution(
			videoId = "IcrbM1l_BoI",
			source = "run-local verified candidate",
			title = "Some other upload",
			channel = "Nicki Minaj",
			lengthSeconds = 60,
			uniquelyResolved = true,
			ownerHandle = "@NickiMinaj",
			localizedTitle = "Otra subida distinta",
		)
		val refusal = VideoIdentityCorroborator.contradiction(
			session,
			resolution,
			VideoFacts(
				videoId = "IcrbM1l_BoI",
				title = "Some other upload",
				author = "Nicki Minaj",
				ownerHandle = "@NickiMinaj",
				lengthSeconds = 60,
			),
		)
		assertNotNull(refusal)
		assertTrue(refusal!!.contains("Some other upload"))
		assertTrue(refusal.contains("Otra subida distinta"))
	}
}
