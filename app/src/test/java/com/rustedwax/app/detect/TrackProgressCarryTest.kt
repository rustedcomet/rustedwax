package com.rustedwax.app.detect

import com.rustedwax.app.scrobble.ScrobbleRules
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Play time surviving a media session that vanished mid-track.
 *
 * Chrome destroys and recreates its `MediaSession` around ad breaks and playlist
 * transitions, which used to reset `playedMs` to zero and score each fragment
 * separately. The 2026-07-30 session lost a 196-second video that had been
 * watched to 80% because it arrived in three pieces, none of which reached 60%.
 */
class TrackProgressCarryTest {

	private val pkg = "com.android.chrome"
	private val track = "LUNA|Feid|196000"

	@Before fun setUp() = TrackProgressCarry.clear()
	@After fun tearDown() = TrackProgressCarry.clear()

	private fun progress(
		playedMs: Long,
		at: Long = 1_000_000L,
		lastPositionMs: Long? = null,
		loopDetected: Boolean = false,
		identity: YouTubeProbe.Identity? = null,
		explicitAdSignal: String? = null,
		accessibilityCoverage: MediaSessionAccessibilityEvidence.Coverage? = null,
		trackInstanceToken: Long? = null,
	) = TrackProgressCarry.Progress(
		playedMs = playedMs,
		trackStartedAtEpochSec = 1_785_000_000L,
		fastestSpeedSeen = 1.0,
		atMillis = at,
		lastPositionMs = lastPositionMs,
		loopDetected = loopDetected,
		identity = identity,
		explicitAdSignal = explicitAdSignal,
		accessibilityCoverage = accessibilityCoverage,
		trackInstanceToken = trackInstanceToken,
	)

	/**
	 * The field case, to the second: 47 + 24 + 85 = 156 s of 196 = 80%, where
	 * each fragment alone was 24%, 12% and 43%.
	 */
	@Test
	fun `three fragments of one video add up to a full listen`() {
		var carriedIn = 0L

		// Fragment 1 — session torn down after 47s.
		TrackProgressCarry.remember(pkg, track, progress(carriedIn + 47_000, at = 1_000_000))

		// Fragment 2 — new session claims it, plays 24s more.
		carriedIn = TrackProgressCarry.claim(pkg, track, now = 1_021_000)!!.playedMs
		assertEquals(47_000L, carriedIn)
		TrackProgressCarry.remember(pkg, track, progress(carriedIn + 24_000, at = 1_045_000))

		// Fragment 3 — claims again, plays 85s more.
		carriedIn = TrackProgressCarry.claim(pkg, track, now = 1_063_000)!!.playedMs
		assertEquals(71_000L, carriedIn)

		val total = carriedIn + 85_000
		assertEquals(156_000L, total)
		// The number that matters: this now clears the 60% threshold.
		assertEquals(0.79, total.toDouble() / 196_000, 0.01)
	}

	/** The listen's real start time travels with it, so the timestamp stays true. */
	@Test
	fun `the track start time is carried, not restarted`() {
		TrackProgressCarry.remember(pkg, track, progress(47_000))
		assertEquals(1_785_000_000L, TrackProgressCarry.claim(pkg, track, now = 1_010_000)!!.trackStartedAtEpochSec)
	}

	/** A track watched at 2× before the teardown is still reported as such. */
	@Test
	fun `the observed playback rate is carried`() {
		TrackProgressCarry.remember(
			pkg,
			track,
			TrackProgressCarry.Progress(90_000, 1_785_000_000L, 2.0, 1_000_000L),
		)
		assertEquals(2.0, TrackProgressCarry.claim(pkg, track, now = 1_010_000)!!.fastestSpeedSeen, 0.0)
	}

	@Test
	fun `the last position and detected loop are carried`() {
		TrackProgressCarry.remember(
			pkg,
			track,
			progress(
				playedMs = 254_000,
				lastPositionMs = 125_021,
				loopDetected = true,
			),
		)
		val carried = TrackProgressCarry.claim(pkg, track, now = 1_010_000)!!
		assertEquals(125_021L, carried.lastPositionMs)
		assertTrue(carried.loopDetected)
	}

	@Test
	fun `confirmed identity and explicit ad evidence are carried together`() {
		val identity = YouTubeProbe.Identity.Confirmed(
			videoId = "abcdefghijk",
			url = "https://www.youtube.com/watch?v=abcdefghijk",
			isMusic = false,
			isShort = true,
			source = "test",
		)
		TrackProgressCarry.remember(
			pkg,
			track,
			progress(
				playedMs = 47_000,
				identity = identity,
				explicitAdSignal = "Sponsored",
				trackInstanceToken = 73,
			),
		)
		val carried = TrackProgressCarry.claim(pkg, track, now = 1_010_000)!!
		assertEquals(identity, carried.identity)
		assertEquals("Sponsored", carried.explicitAdSignal)
		assertEquals(73L, carried.trackInstanceToken)
	}

	@Test
	fun `watch url organic A ad B organic A keeps evidence and progress separated`() {
		val organic = TrackIdentity("Organic A", "Artist", null, 180_000)
		val advert = TrackIdentity("Advert B", "Advertiser", null, 34_000)
		val organicCoverage = MediaSessionAccessibilityEvidence.Coverage(
			instance = MediaSessionAdEvidence.TrackInstance(pkg, 101, organic),
			atMillis = 1_000_000,
			urlGeneration = 9,
			videoId = "abcdefghijk",
		)
		val advertCoverage = MediaSessionAccessibilityEvidence.Coverage(
			instance = MediaSessionAdEvidence.TrackInstance(pkg, 202, advert),
			atMillis = 1_000_001,
			urlGeneration = 9,
			videoId = null,
		)

		TrackProgressCarry.remember(
			pkg,
			organic,
			progress(
				playedMs = 40_000,
				accessibilityCoverage = organicCoverage,
				trackInstanceToken = 101,
			),
		)
		TrackProgressCarry.remember(
			pkg,
			advert,
			progress(
				playedMs = 34_000,
				explicitAdSignal = "Sponsored",
				accessibilityCoverage = advertCoverage,
				trackInstanceToken = 202,
			),
		)

		val resumedAdvert = TrackProgressCarry.claim(pkg, advert, now = 1_010_000)!!
		assertEquals("Sponsored", resumedAdvert.explicitAdSignal)
		assertEquals(202L, resumedAdvert.trackInstanceToken)
		assertEquals(advertCoverage, resumedAdvert.accessibilityCoverage)
		assertTrue(
			!ScrobbleRules.decide(
				playedMs = resumedAdvert.playedMs,
				durationMs = advert.durationMs,
				explicitAdSignal = resumedAdvert.explicitAdSignal,
			).shouldScrobble,
		)

		val resumedOrganic = TrackProgressCarry.claim(pkg, organic, now = 1_010_000)!!
		assertEquals(40_000L, resumedOrganic.playedMs)
		assertNull(resumedOrganic.explicitAdSignal)
		assertEquals(101L, resumedOrganic.trackInstanceToken)
		assertEquals(organicCoverage, resumedOrganic.accessibilityCoverage)
		assertTrue(
			ScrobbleRules.decide(
				playedMs = resumedOrganic.playedMs + 80_000,
				durationMs = organic.durationMs,
				explicitAdSignal = resumedOrganic.explicitAdSignal,
			).shouldScrobble,
		)
	}

	// region what must not be carried

	@Test
	fun `exact-id-less native YouTube sessions never store or claim continuation progress`() {
		val native = YouTubeProbe.YOUTUBE_PACKAGE
		val bellakeo = TrackIdentity("BELLAKEO", "Peso Pluma & Anitta", null, 196_000)
		assertNull(TrackProgressCarry.remember(native, bellakeo, progress(47_000)))
		assertEquals(0, TrackProgressCarry.size())
		assertNull(TrackProgressCarry.claim(native, bellakeo, now = 1_010_000))
	}

	@Test
	fun `repeated exact-id-less native labels cannot inherit each others progress`() {
		val native = YouTubeProbe.YOUTUBE_PACKAGE
		val first = TrackIdentity("BELLAKEO", "Peso Pluma & Anitta", null, 196_000)
		val later = TrackIdentity("BELLAKEO", "Peso Pluma & Anitta", null, 196_000)
		assertNull(TrackProgressCarry.remember(native, first, progress(156_000)))
		assertNull(TrackProgressCarry.claim(native, later, now = 1_010_000))
	}

	@Test
	fun `native continuation is allowed only with exact immutable item authority`() {
		val native = YouTubeProbe.YOUTUBE_PACKAGE
		val exact = TrackIdentity(
			title = "Exact native item",
			artist = "Artist",
			album = null,
			durationMs = 180_000,
			sourceItemId = "abcdefghijk",
		)
		assertNotNull(TrackProgressCarry.remember(native, exact, progress(47_000)))
		assertEquals(47_000, TrackProgressCarry.claim(native, exact, now = 1_010_000)!!.playedMs)
	}

	@Test
	fun `native replacement must independently establish the same immutable id`() {
		val native = YouTubeProbe.YOUTUBE_PACKAGE
		val old = TrackIdentity(
			"La Rompe Corazones", "Daddy Yankee", null, 205_000, "abcdefghijk",
		)
		assertNotNull(TrackProgressCarry.remember(native, old, progress(106_000)))

		val unresolved = old.copy(sourceItemId = null)
		assertNull(TrackProgressCarry.claim(native, unresolved, now = 1_010_000))
		val different = old.copy(sourceItemId = "lmnopqrstuv")
		assertNull(TrackProgressCarry.claim(native, different, now = 1_010_000))
		assertEquals(
			106_000,
			TrackProgressCarry.claim(native, old.copy(), now = 1_010_000)!!.playedMs,
		)
		assertNull(TrackProgressCarry.claim(native, old.copy(), now = 1_010_000))
	}

	/**
	 * Consumed on read. Two sessions inheriting the same play time would double
	 * count it straight onto a chain that can't be edited.
	 */
	@Test
	fun `progress can only be claimed once`() {
		TrackProgressCarry.remember(pkg, track, progress(47_000))
		assertNotNull(TrackProgressCarry.claim(pkg, track, now = 1_010_000))
		assertNull(TrackProgressCarry.claim(pkg, track, now = 1_010_000))
	}

	/** Bounded so it can't attach itself to a genuine separate viewing later. */
	@Test
	fun `progress expires`() {
		TrackProgressCarry.remember(pkg, track, progress(47_000, at = 1_000_000))
		val tooLate = 1_000_000 + TrackProgressCarry.TTL_MS + 1
		assertNull(TrackProgressCarry.claim(pkg, track, now = tooLate))
	}

	@Test
	fun `an unclaimed continuation finalizes only after its grace period`() {
		val token = TrackProgressCarry.remember(
			pkg,
			track,
			progress(47_000, at = 1_000_000),
		)!!
		assertNull(
			TrackProgressCarry.expire(
				pkg,
				track,
				token,
				now = 1_000_000 + TrackProgressCarry.TTL_MS - 1,
			),
		)
		assertEquals(
			47_000L,
			TrackProgressCarry.expire(
				pkg,
				track,
				token,
				now = 1_000_000 + TrackProgressCarry.TTL_MS,
			)!!.playedMs,
		)
	}

	@Test
	fun `an old expiry token cannot consume a newer continuation`() {
		val old = TrackProgressCarry.remember(pkg, track, progress(10_000, at = 1_000_000))!!
		val newer = TrackProgressCarry.remember(pkg, track, progress(20_000, at = 1_010_000))!!
		assertNull(
			TrackProgressCarry.expire(
				pkg,
				track,
				old,
				now = 1_010_000 + TrackProgressCarry.TTL_MS,
			),
		)
		assertEquals(
			20_000L,
			TrackProgressCarry.expire(
				pkg,
				track,
				newer,
				now = 1_010_000 + TrackProgressCarry.TTL_MS,
			)!!.playedMs,
		)
	}

	@Test
	fun `cancel removes only the matching continuation`() {
		val old = TrackProgressCarry.remember(pkg, track, progress(10_000))!!
		val newer = TrackProgressCarry.remember(pkg, track, progress(20_000))!!
		TrackProgressCarry.cancel(pkg, track, old)
		assertEquals(20_000L, TrackProgressCarry.claim(pkg, track, now = 1_010_000)!!.playedMs)
		assertNull(TrackProgressCarry.claim(pkg, track, now = 1_010_000))
		assertTrue(old != newer)
	}

	@Test
	fun `a different track does not inherit the time`() {
		TrackProgressCarry.remember(pkg, track, progress(47_000))
		assertNull(TrackProgressCarry.claim(pkg, "TQG|KAROL G|197000", now = 1_010_000))
	}

	@Test
	fun `a different browser does not inherit the time`() {
		TrackProgressCarry.remember(pkg, track, progress(47_000))
		assertNull(TrackProgressCarry.claim("com.brave.browser", track, now = 1_010_000))
	}

	@Test
	fun `package teardown clears only that native packages continuation`() {
		val youtube = YouTubeProbe.YOUTUBE_PACKAGE
		val music = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE
		val youtubeTrack = TrackIdentity("YT exact", "Artist", null, 180_000, "abcdefghijk")
		val musicTrack = TrackIdentity("YTM exact", "Artist", null, 180_000, "lmnopqrstuv")
		TrackProgressCarry.remember(youtube, youtubeTrack, progress(47_000))
		TrackProgressCarry.remember(music, musicTrack, progress(29_000))

		TrackProgressCarry.clearPackage(youtube)

		assertNull(TrackProgressCarry.claim(youtube, youtubeTrack, now = 1_010_000))
		assertEquals(29_000L, TrackProgressCarry.claim(music, musicTrack, now = 1_010_000)!!.playedMs)
	}

	@Test
	fun `native recreation retains only the same exact source item`() {
		val pkg = YouTubeProbe.YOUTUBE_PACKAGE
		val first = TrackIdentity("Same title", "Same channel", null, 180_000, "abcdefghijk")
		val other = first.copy(sourceItemId = "lmnopqrstuv")
		TrackProgressCarry.remember(pkg, first, progress(73_000))

		assertNull(TrackProgressCarry.claim(pkg, other, now = 1_010_000))
		assertEquals(73_000L, TrackProgressCarry.claim(pkg, first, now = 1_010_000)!!.playedMs)
	}

	@Test
	fun `browser YouTube and YouTube Music packages cannot share continuation state`() {
		TrackProgressCarry.remember(pkg, track, progress(47_000))
		assertNull(
			TrackProgressCarry.claim(YouTubeProbe.YOUTUBE_PACKAGE, track, now = 1_010_000),
		)
		assertNull(
			TrackProgressCarry.claim(YouTubeProbe.YOUTUBE_MUSIC_PACKAGE, track, now = 1_010_000),
		)
		assertEquals(47_000L, TrackProgressCarry.claim(pkg, track, now = 1_010_000)!!.playedMs)
	}

	/** A session with no metadata yet must not claim someone else's progress. */
	@Test
	fun `a blank track key neither stores nor claims`() {
		TrackProgressCarry.remember(pkg, "", progress(47_000))
		assertEquals(0, TrackProgressCarry.size())
		assertNull(TrackProgressCarry.claim(pkg, "", now = 1_010_000))
	}

	/** Stop discards the in-flight track (D2); its time must not outlive it. */
	@Test
	fun `clear drops everything`() {
		TrackProgressCarry.remember(pkg, track, progress(47_000))
		TrackProgressCarry.clear()
		assertNull(TrackProgressCarry.claim(pkg, track, now = 1_010_000))
	}

	/** Expired entries don't accumulate for videos that never come back. */
	@Test
	fun `stale entries are pruned on write`() {
		TrackProgressCarry.remember(pkg, "gone-a", progress(1_000, at = 1_000_000))
		TrackProgressCarry.remember(pkg, "gone-b", progress(1_000, at = 1_000_000))
		assertEquals(2, TrackProgressCarry.size())
		TrackProgressCarry.remember(
			pkg,
			track,
			progress(1_000, at = 1_000_000 + TrackProgressCarry.TTL_MS + 1),
		)
		assertEquals(1, TrackProgressCarry.size())
	}
	// endregion
}
