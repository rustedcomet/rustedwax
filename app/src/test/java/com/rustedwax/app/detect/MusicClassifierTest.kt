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
		isShort: Boolean = false,
	) = MusicClassifier.classify(title, channel, durationMs, siteSaysMusic, category, isShort).kind

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
	 * The 2026-07-24 field sample: eleven film clips, trailers and shorts that
	 * went on-chain as `song`. Titles, channels and categories are the real
	 * values fetched from each video's watch page. Every one must be `video`.
	 */
	@Test
	fun `the field sample of misclassified film content is video`() {
		// Film & Animation — the category alone must decide these.
		assertEquals(video, kindOf(
			"COYOTE VS. ACME Official Final Trailer (2026) John Cena",
			"ONE Media", category = "Film & Animation"))
		assertEquals(video, kindOf(
			"THE END OF OAK STREET Official Final Trailer (2026)",
			"ONE Media", category = "Film & Animation"))
		assertEquals(video, kindOf(
			"Top Movie Scene | Giant Spider Attack | Kong: Skull Island",
			"VISIONREEL PRODUCTIONS", category = "Film & Animation"))
		assertEquals(video, kindOf(
			"You Don't Like Real Girls | Blade Runner 2049 [Open Matte]",
			"Natalizio Filmes", category = "Film & Animation"))
		// Ambiguous categories — known-but-not-music demands explicit evidence,
		// and a pipe or dash in the title is not explicit evidence.
		assertEquals(video, kindOf(
			"You and who? | 🎬 Notting Hill (1999)",
			"Universal Pictures", category = "Entertainment"))
		assertEquals(video, kindOf(
			"The weapon is meant as a gift -- It is a Crysknife  | DUNE 2021 |",
			"Groovy Movie Dog", category = "Entertainment"))
		assertEquals(video, kindOf(
			"Gsxr1000r police chase x Rolling in the deep",
			"Awahys", category = "Entertainment"))
		assertEquals(video, kindOf(
			"The surgeon (Short Film)",
			"AidAVisioN", category = "People & Blogs"))
		assertEquals(video, kindOf(
			"Connie Doherty - Showreel",
			"Connie Doherty", category = "People & Blogs"))
		assertEquals(video, kindOf(
			"Cozy Kitchen Ghibli Vibes💖",
			"Cozy Minamy", category = "People & Blogs"))
		assertEquals(video, kindOf(
			"One of the Coldest Scenes in Western Movies - \"How good are ya\"",
			"Cinema Crunch", category = "People & Blogs", isShort = true))
	}

	/** The same sample with enrichment OFF — the structural fallbacks. */
	@Test
	fun `the field sample degrades sanely without a category`() {
		// "Official Final Trailer" slipped past the exact phrase "official
		// trailer"; the structural rule reads context + trailer.
		assertEquals(video, kindOf(
			"COYOTE VS. ACME Official Final Trailer (2026) John Cena", "ONE Media"))
		// Clip channels advertise themselves in the name.
		assertEquals(video, kindOf("Some scene compilation", "Movie Trailers Source"))
		assertEquals(video, kindOf("Some scene compilation", "Cinema Crunch"))
		assertEquals(video, kindOf("Random clip", "Universal Pictures"))
		// Film vocabulary in the title.
		assertEquals(video, kindOf(
			"Top Movie Scene | Giant Spider Attack | Kong: Skull Island", "Somebody"))
		assertEquals(video, kindOf("The surgeon (Short Film)", "AidAVisioN"))
		assertEquals(video, kindOf("Connie Doherty - Showreel", "Connie Doherty"))
	}

	/**
	 * An ambiguous category requires explicit evidence — but explicit evidence
	 * does win. Fan-uploaded covers commonly sit in Entertainment or People &
	 * Blogs, and they must not be lost to the stricter rule.
	 */
	@Test
	fun `explicit music evidence overrules an ambiguous category`() {
		assertEquals(song, kindOf(
			"Creep (Acoustic Cover)", "Some Person", category = "Entertainment"))
		assertEquals(song, kindOf(
			"Duality (Lyrics)", "LyricChannel", category = "People & Blogs"))
		assertEquals(song, kindOf(
			"Anything at all", "Radiohead - Topic", category = "Entertainment"))
	}

	/**
	 * Shorts are browsed by the dozen and titled like clip captions, so weak
	 * evidence (a dash, the bare default) is not accepted for them.
	 */
	@Test
	fun `shorts need explicit music evidence`() {
		assertEquals(video, kindOf("Epic Moment - Best Scene Ever", "Clips", isShort = true))
		assertEquals(video, kindOf("Some random caption", "Uploader", isShort = true))
		// Explicit evidence still qualifies a short as music.
		assertEquals(song, kindOf("Zombie 【Guitar Cover】", "OLD MOON CHILD", isShort = true))
		// Same bar for anything under 90 seconds, shorts URL or not.
		assertEquals(video, kindOf("Funny Dog - Compilation", "Dogs", durationMs = 45_000L))
		assertEquals(song, kindOf("Riff (Official Audio)", "Band", durationMs = 45_000L))
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
