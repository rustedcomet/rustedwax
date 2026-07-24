package com.rustedwax.app.detect

import com.rustedwax.app.hive.HiveScrobblePayload
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 4 decision D4 flipped the default to `song`, so these tests carry more
 * weight than most: an over-claimed kind is permanent on-chain, and the
 * blocklist is the only thing standing between "default to music" and a
 * tutorial in the music index.
 */
class MusicClassifierTest {

	private fun kindOf(
		title: String,
		channel: String? = null,
		durationMs: Long? = 4 * 60 * 1000L,
		siteSaysMusic: Boolean = false,
		category: String? = null,
	) = MusicClassifier.classify(title, channel, durationMs, siteSaysMusic, category).kind

	private val song = HiveScrobblePayload.KIND_SONG
	private val video = HiveScrobblePayload.KIND_VIDEO

	/** The bug this phase exists to fix. */
	@Test
	fun `the reported guitar cover is music`() {
		assertEquals(
			song,
			kindOf(
				"【Bring Me The Horizon】I Used to Make Out With Medusa " +
					"(Instrumental) 2023【Guitar Cover】＋Screen Tabs",
				"OLD MOON CHILD",
			),
		)
	}

	@Test
	fun `covers live takes and lyric videos are all music`() {
		assertEquals(song, kindOf("Creep (Acoustic Cover)", "Some Person"))
		assertEquals(song, kindOf("Bohemian Rhapsody - Live at Wembley", "QueenOfficial"))
		assertEquals(song, kindOf("Duality (Lyrics)", "LyricChannel"))
		assertEquals(song, kindOf("Toxicity — Drum Cover", "Drummer Dan"))
	}

	@Test
	fun `youtube's own category wins over everything`() {
		// Would otherwise be blocked by "gameplay" in the title.
		assertEquals(song, kindOf("Gameplay Theme", "Chan", category = "Music"))
		// Would otherwise pass as music on the VEVO channel.
		assertEquals(video, kindOf("Anything", "SomeVEVO", category = "Gaming"))
	}

	@Test
	fun `the blocklist keeps non-music out`() {
		assertEquals(video, kindOf("How to fix a sink", "DIY Channel"))
		assertEquals(video, kindOf("Guitar Tutorial - Beginner Chords", "GuitarLessons"))
		assertEquals(video, kindOf("Episode 42: the interview", "Some Show"))
		assertEquals(video, kindOf("Cyberpunk 2077 Review", "Reviewer"))
	}

	/**
	 * Blocklist runs before the positive signals on purpose — otherwise the
	 * word "guitar" in "Guitar Tutorial" rescues it as music.
	 */
	@Test
	fun `blocklist beats a music-sounding word in the same title`() {
		assertEquals(video, kindOf("Bass Cover Tutorial", "Teacher"))
	}

	@Test
	fun `channel blocklist only applies to the channel`() {
		// "News" in a song title must not disqualify it…
		assertEquals(song, kindOf("Bad News", "Some Band"))
		// …but a news channel is not a band.
		assertEquals(video, kindOf("Bad News", "BBC News"))
	}

	@Test
	fun `long form with no music signal is a video`() {
		assertEquals(
			video,
			kindOf("Three hours of something", "Chan", durationMs = 3 * 60 * 60 * 1000L),
		)
		// …but long *and* musical stays music: DJ sets and full albums are long.
		assertEquals(
			song,
			kindOf("Full Album - Greatest Hits", "Band", durationMs = 3 * 60 * 60 * 1000L),
		)
	}

	@Test
	fun `D4 default is song`() {
		assertEquals(song, kindOf("Some upload with no signals at all", "A Channel"))
	}

	/**
	 * The reported false positive. "Cover" as a bare substring used to force
	 * song; a magazine cover is not a music cover.
	 */
	@Test
	fun `a magazine cover is not a music cover`() {
		assertEquals(
			video,
			kindOf(
				"President Trump Says His Time Magazine Cover Photo Is 'Super Bad'",
				"Some News Outlet",
			),
		)
	}

	@Test
	fun `other non-musical cover contexts are videos`() {
		assertEquals(video, kindOf("Album Cover Art Reveal", "Design Channel"))
		assertEquals(video, kindOf("How I Shot the Cover Photo", "Photographer"))
		assertEquals(video, kindOf("Undercover Boss - Full Episode", "TV Network"))
	}

	/** The genuine covers must still read as music after the tightening. */
	@Test
	fun `real music covers still classify as song`() {
		assertEquals(song, kindOf("Creep (Acoustic Cover)", "Some Person"))
		assertEquals(song, kindOf("Toxicity — Drum Cover", "Drummer Dan"))
		assertEquals(song, kindOf("Wonderwall - Cover by Jane Doe", "Jane Doe"))
		assertEquals(song, kindOf("My cover of Zombie", "Singer"))
		assertEquals(song, kindOf("Nothing Else Matters 【Guitar Cover】", "OLD MOON CHILD"))
	}

	/**
	 * The inverse risk: a blocklist word that is also a real song title. Bare
	 * "reaction", "trailer" and "hearing" used to file these under video —
	 * permanently, on-chain.
	 */
	@Test
	fun `real songs are not caught by ambiguous blocklist words`() {
		assertEquals(song, kindOf("Chain Reaction", "Diana Ross"))
		assertEquals(song, kindOf("Trailer Trash", "Modest Mouse"))
		assertEquals(song, kindOf("Hearing Damage", "Thom Yorke"))
	}

	/** …but the actual non-music formats those words describe are still caught. */
	@Test
	fun `reaction videos trailers and hearings are still videos`() {
		assertEquals(video, kindOf("My Reaction Video to the Finale", "Reactor"))
		assertEquals(video, kindOf("Reacts to the new trailer", "Streamer"))
		assertEquals(video, kindOf("Dune Part Two - Official Trailer", "Warner Bros"))
		assertEquals(video, kindOf("Senate Hearing on AI - Full", "C-SPAN"))
	}

	/**
	 * "playthrough" is gaming as often as it is music, so it now needs an
	 * instrument qualifier — the same rule as "cover".
	 */
	@Test
	fun `playthrough needs an instrument to count as music`() {
		assertEquals(video, kindOf("Elden Ring Playthrough Part 1", "Gamer"))
		assertEquals(song, kindOf("Master of Puppets - Guitar Playthrough", "Guitarist"))
		assertEquals(song, kindOf("Enter Sandman 【Drum Playthrough】", "Drummer"))
	}

	/**
	 * Word boundaries: a music word buried inside a larger word is not a match.
	 * "discover" is not "cover"; "preview" is not "review".
	 */
	@Test
	fun `does not match music or block words inside larger words`() {
		// "discover" must not trip the cover rule → falls to default song, but
		// crucially not *because* of "cover".
		assertEquals("default for YouTube (no non-music signal)",
			MusicClassifier.classify("Discover Weekly", "A Channel", 4 * 60 * 1000L, false, null).reason)
		// "preview" must not trip the "review" blocklist.
		assertEquals(song, kindOf("World Premiere Preview", "A Band"))
	}
}
