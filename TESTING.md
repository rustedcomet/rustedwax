# Testing RustedWax v0.8.15 (Phase 4 field-accepted with documented exceptions)

`dist/rustedwax-0.8.15.apk` — detection, key handling, automatic scrobbling
(Phase 3), plus the Phase 4 layer: the Stop switch, YouTube-only exclusivity,
evidence-based `kind` classification, the optional browser-evidence watcher,
watch-page enrichment, and MusicBrainz verification.

v0.8.7 is the contract-reconciliation build. It removes the high-progress
Short rejection, defers finalization across browser session churn, makes Stop a
complete evidence boundary, separates block/mempool/unconfirmed broadcast
states, binds queued work to its account, and makes the Now/manual paths use the
configured rules. The canonical contract and the exact v0.8.6 discrepancies are
in [BEHAVIOR_CONTRACT.md](BEHAVIOR_CONTRACT.md).

v0.8.8 is the rule-correction patch from the next physical-device run:
explicitly unlisted Shorts are rejected at every duration, YouTube Music's
podcast-episode type is not song evidence, and an observed end-to-start
playback-position reset is carried across Chrome session recreation and caps
the continuous viewing to one transaction regardless of payload kind.

v0.8.9 adds an explicit visible-ad veto for the Shorts feed and closes the
late-address-bar race exposed by log 11. Exact YouTube controls such as
`Sponsored` are bound to the current Short; brand and title text are not used as
ad guesses. A track waiting through Chrome's one-minute continuation window now
keeps its frozen identity and ad evidence instead of consulting a later Short's
live URL when it finally ends.

v0.8.10 makes a canonical YouTube hyperlink mandatory. It parses the modern
Shorts search shape that log 12 exposed, corroborates title-only Short
candidates against their watch pages, rejects ambiguous ids, and refuses
automatic, manual, or queued YouTube payloads whose id was not verified.

Log 14 remains the broad v0.8.10 field-verification record. Its chain/profile
boundary passed completely, but it exposed four pre-broadcast losses and four
song-credit parser failures. v0.8.11 implements the exact automated regressions
in §13: finalized snapshot isolation, URL-generation ad evidence, bounded
duration refinement, and structure-aware song credits. The v0.8.11 APK was
generated and passed its source gate. Its first physical-device gate was
attempted in log 16 and failed; §14 preserves that evidence and §15 records the
implemented generic correction. Its automated gate passed; log 17 then tested
the corrected artifact and again failed the device gate while proving a clean
67/67 transport/profile reconciliation. Sections 16–17 preserve that evidence
and define the implemented v0.8.12 correction. Section 18 records the broad
log-18 v0.8.12 device round; §19 defines and verifies its v0.8.13 correction.
Section 20 records the failed log-19 v0.8.13 device round and the implemented
v0.8.14 correction. Section 21 records the failed log-20 v0.8.14 device round,
the complete reconciliation and the bounded implemented v0.8.15 correction.
Section 22 records the final log-21 v0.8.15 field round, chain/profile
reconciliation, accepted limitations and Phase 4 release decision.

From the 2026-07-29 46-video run (0.8.0): verified shorts scrobble from 10
seconds (§6), a **Not logged** tab giving every skip a visible reason, durations
recovered from the watch page when the media session omits them, and
`Artist - Track` splitting restricted to songs so trailers stop going on-chain
with a cast list in the title.

From the Chrome run that evening (0.8.1), both of which are what §6 and §7 now
test: the shorts ad guard is `isUnlisted` rather than "the page resolved", after
an 18-second ad reached the chain — YouTube serves shorts ads at real `/shorts/`
URLs with real watch pages. And **played time is now scaled by playback speed**,
because it wasn't: a trailer watched to 79% at 1.25× went on-chain as 67%, and at
2× a fully-watched video read 50% and wouldn't have scrobbled at all.

From reading the desktop extension side by side with this app (0.8.2): a
**YouTube Music catalogue lookup** that answers where MusicBrainz returns `no
match`, the **`album` field** finally populated, **upper duration gates** so a
45-minute podcast can't parse as a song, four more title shapes, and a
**duration cross-check** on the latched video id — which closes a path that had
already put a wrong `url` on-chain. §8 covers all of it.

RustedWax is an independent app. It is not part of scrobble.life, Hive Scrobbler
or Web Scrobbler, and is not supported by them — see the README.

> **Prototype policy note.** Tests that enable **Look videos up** exercise automated YouTube page
> extraction and an undocumented YouTube Music endpoint. Those interfaces are retained for concept
> testing but are unsupported, fragile, and conflict with YouTube's written automated-access,
> scraping and undocumented-API restrictions. This is not a policy-cleared distribution build.
> Testing with lookups off covers the conservative Android-metadata-only path. See
> [README.md](README.md#youtube-policy-and-prototype-tradeoff).

Automatic scrobbling is **off until you enable it**, and the switch is disabled
until a key is saved. **Monitoring** (the outer switch) is on by default; Stop
tears down all observation and discards the in-flight track without
broadcasting it.

## Before you start

Broadcasts are **permanent and public**. Use a throwaway Hive account, or a
posting key you're willing to rotate. See "About your posting key" in the app.

Ideally: from desktop Keychain, add a fresh public key to your account's posting
authority and give the app that key. Then revoking it later costs you nothing.

## Install

```bash
~/Library/Android/sdk/platform-tools/adb install -r dist/rustedwax-0.8.14.apk
```

Verified debug artifact SHA-256:

```text
a9507c733b188f9cf3a481c8b1446535da22449343181cc17ccf556890f298b8
```

## 1. Key validation (no chain writes)

Account tab → enter your Hive username and posting key → **Validate & save**.

The app derives the public key locally and compares it against your account's
on-chain `posting.key_auths`. Things worth trying:

| Input | Expected |
| --- | --- |
| Correct username + posting key | "Key verified … and saved", the `STM…` key shown |
| Correct key, wrong username | Rejected, naming the key it derived vs what the account lists |
| A typo'd key | Rejected as not a valid posting key (checksum fails locally, no network call) |
| Your **public** key pasted in | Rejected — it isn't a WIF |
| Airplane mode | "Couldn't reach a Hive node" — never a silent pass |

Nothing is written to the chain by any of this. The key is stored in
`EncryptedSharedPreferences` under an Android Keystore master key.

## 2. Test broadcast (writes to the chain)

Account tab → **Broadcast a test scrobble**. Sends a fixed payload with
`title: "RustedWax Mobile test scrobble"`, `platform: "test"` so it's obviously
synthetic.

### Where the transaction id appears

In the app, in two places:

1. **Account tab**, in the status line under the buttons:
   `Confirmed in a block — tx f0569fe0…`

   Mempool and unavailable-confirmation outcomes use the distinct wording in §10.
2. **Log tab**, which also records the exact payload sent, tagged `[hive]`.
   Long-press or use **Export** to get the text off the device.

The id is computed on-device (`sha256(serialized tx)[0..20]`) because
`condenser_api.broadcast_transaction` returns an empty result on success. It's
the same id the explorers use — verified against dhive in the unit tests. Since
0.8.4 the app also looks that id up before calling anything a success. A block
status is the normal confirmed path. An independent mempool observation and an
unanswered independent confirmation are retained without retrying, but neither
is called block inclusion. See the README's confirmation section before
interpreting a History tx id as irreversible inclusion.

### Where to look it up

Paste the id into any Hive explorer:

```
https://hiveblocks.com/tx/f0569fe0...
```

Alternatives if one is down or slow to index: `https://hivehub.dev/tx/<txid>`,
or `https://hivexplorer.com/tx/<txid>`.

**Easier route — skip the id entirely.** Open your account's history and look at
the most recent operation:

```
https://hiveblocks.com/@your-username
```

A successful scrobble shows as a `custom_json` operation. Expand it and check:

- `id` → `hive_scrobble_ai`
- `required_posting_auths` → `["your-username"]`
- `json` → the payload, with `app: hivescrobblesai/1.0`

**Timing:** a transaction takes ~3 seconds to enter a block, and explorers can
lag a few seconds more. If the tx page 404s immediately after broadcasting, wait
10 seconds and reload before concluding anything went wrong — the account
history view is usually the faster of the two to update.

This is **gate G4** — the one thing the unit tests can't prove. The signing math
is verified byte-for-byte against dhive, but only a real broadcast proves the
whole chain of assumptions.

Errors worth recognising:

| Message | Meaning |
| --- | --- |
| `missing required posting authority` | The key doesn't actually hold posting authority — validation should have caught this |
| `insufficient RC` / `resource credits` | Account is out of resource credits; not a bug |
| `Couldn't reach a node` | Transport; every node failed or was too stale to use |
| `Not on-chain yet — …` | Independent healthy nodes explicitly did not find it; the automatic path queues these, the manual button asks you to retry |
| `Seen relaying in an independent node's mempool` | Independently observed but not yet in a block; not retried |
| `Accepted … confirmation was unavailable — do not retry` | The accepting node succeeded but independent services could not answer; not presented as block inclusion and not retried |

## 3. Real scrobble from YouTube

1. Play a YouTube video in Chrome (or Brave, on a real device).
2. Now tab → the session card appears.
3. Check the **payload** block — this is what would be written:
   - `artist` should be the *real* artist, not the channel. For
     `"Korn - Trash (Official Audio)"` on channel `KornVEVO`, expect
     `artist: Korn`, `title: Trash`.
   - **kind because** names the rule that decided `song` vs `video`;
     **category** shows what YouTube's own page said (needs address bar +
     lookups on); **yt music** shows the catalogue's `musicVideoType` or
     `not in the catalogue`; **musicbrainz** shows `✓ Artist — Title` on a
     confirmed match, `no match`, or `— (not checked yet)`; **listed** is what
     the short-clip floor turns on. **album** appears on Art Tracks only.
   - Titles are normalized to the original recording: `(Live)`,
     `(Instrumental)` and `【Guitar Cover】` are stripped; `(Remix)` is kept.
4. Once the configured threshold and duration rules are satisfied, tap
   **Broadcast this scrobble** → verify on hiveblocks as above. Before the
   threshold, the same button must refuse with the automatic rule's reason.

The badge tells you whether it's broadcastable:

| Badge | Meaning |
| --- | --- |
| `payload OK` | Proven YouTube *with* a video id — full payload including `url` |
| `waiting for verified video id` | Proven YouTube origin, but no payload exists until finalization recovers an id |
| `not proven YouTube` | Can't identify the site — deliberately refuses to guess |
| `no duration` | No DURATION, so percent-played can't be computed |

Non-browser apps (Spotify, podcast players) no longer appear at all — the log
records a single `ignored: <package>` line and nothing is read from them.

## 4. Automatic scrobbling (the Phase 3 feature)

Flip **Automatic scrobbling** on at the top of the screen, note the configured
threshold, then play a track in Chrome/Brave and **let it finish** — or skip to
the next track once you're past that threshold. A scrobble fires when the track
ends, not merely when it crosses the threshold.

Then check the **History** tab: each entry shows percent played, status, and the
transaction id. History holds the newest 50 results for the current app process;
it is diagnostic memory and is expected to clear after process death.

What to try:

| Case | Expected |
| --- | --- |
| Play a song past 60%, then skip to the next | One scrobble at the percent you reached |
| Play a song to the end | One scrobble at 100% |
| Skip out at 20% | Nothing — Log tab says "below 60% threshold" |
| Let the same **song** play twice through (~170%) | **Two** scrobbles: 100% and 70% — the double-listen rule |
| Let a **short** loop past 160% | **One** scrobble — the `/shorts/` source is capped at one tx even if classified as `song` |
| Replay a song immediately | Second one blocked — Log says "already scrobbled" |
| Play with the app closed | Still scrobbles; detection lives in the listener service |
| Turn on airplane mode, play a song through | Scrobble is queued, "N waiting to send" appears; turn networking back on and hit **Retry now** |
| A pre-roll ad, or any watch-page clip under 30s | Ignored — **Not logged** says "under the 30s minimum (not a verified short)" |
| A **verified short** of 10s or more, watched through | Scrobbled since v0.8.0 — see §6. A short whose watch page didn't resolve is still held to 30s, and says which |
| A clip under 10s | Ignored on every path, shorts included |
| Park on a looping 10s short for minutes | **One** scrobble. Log says `probable short loop detected — first viewing kept`; no `looped unattended` rejection |
| A short whose media session publishes no duration | Scrobbled if it clears the rules — the length comes from the watch page, and the Log says "duration recovered from the watch page: Ns" |
| Press **Stop** mid-track past 60% | Nothing broadcast, nothing in History — Stop never scrobbles on the way out |
| Press **Start** again | Monitoring resumes without touching Notification Access |
| Stop while a full video URL is visible, navigate elsewhere, then Start | Pre-Stop URL/playlist evidence is not reused; the new session must earn fresh evidence |
| While stopped, dismiss a Brave/Chrome media notification | No notification/title line is added; removal callbacks return before reading extras |
| YouTube in one tab + another audio site in another | Each session keeps its own origin; closing one tab doesn't kill the other's scrobble |
| Browse shorts with Browser evidence access on | The video id is latched at track start — the `url` on-chain matches the short you watched, not the next one |
| Tap **Broadcast this scrobble** below the configured threshold | Nothing sent; status gives the same below-threshold reason as automatic scrobbling |
| Tap **Broadcast this scrobble** after it qualifies, then let the track finish | **One** scrobble. The manual send claims the ledger, so the automatic finalize is blocked — Log says "already scrobbled" |
| Tap **Broadcast this scrobble** twice | The button reads `Sending…` and is disabled during the send; a second tap after it returns is refused with "Already scrobbled" |

Watch the **Log** tab while testing. Every decision is recorded: `[finalize]`
when a track ends, then `[engine]` with either the payload broadcast or the
reason it was skipped. **Not logged** is the same skip reasons without the
grepping — use it first, and fall back to the Log when you need the surrounding
`[url]` / `[identity]` / `[enrich]` lines.

### Does it survive the app being closed?

This is the one behaviour that needs a **physical device** — BlueStacks won't
tell you anything useful. Swipe the activity away, keep playing music, reopen it
before Android kills the listener process, and check History. Detection runs
inside the notification listener, which Android keeps bound while the grant is
held. If Android kills the whole process, in-memory History resets; verify the
chain and exported Log instead.

If the screen says "Waiting for Android to start the listener service", toggle
Notification Access off and on.

## 5. Compare against desktop

The strongest check: scrobble the same song from the desktop extension and from
the phone, then compare the two `custom_json` payloads field by field. They
should differ only in `platform`, `url`, `percent_played` and `timestamp`.
Any difference in `title` or `artist` is a metadata-filter gap worth reporting.

## 6. Short clips and shorts-feed ads

A **verified** YouTube short scrobbles from 10 seconds instead of 30. "Verified"
means three things: the address bar showed a `/shorts/` path, the video resolved
on its own watch page, and that page said the video is **publicly listed**. The
switch is **Short clips** in the controls card, and it greys out unless both
*Browser evidence access* and *Look videos up* are on, because those supply
the proof.

Everything on `/watch` still needs the 30-second floor and the configured
threshold. Nothing about the watch-path floor changed.

### 6a. The happy path

1. Open a shorts feed in the browser and scroll through 15–20 clips, letting
   each play to the end but not parking on any of them.
2. Check **History** — short clips of 10 s and up should now appear.
3. Check **Not logged** — this is the new tab, and it is the point of the
   exercise. Every clip that *didn't* scrobble should be there with its reason:

   ```
   track is 7s, under the 10s minimum (verified short)
   played 34%, below 60% threshold
   track is 12s, under the 30s minimum (a short, but its watch page didn't resolve …)
   ```

   A skipped clip with **no row here and no reason** is the bug to report.

4. If the Log says `(watch page unavailable — music client only)`, a clip under
   30 seconds must still say its watch page did not resolve. A YouTube Music
   length/listed result may recover metadata, but must not be renamed into
   watch-page proof for the lowered floor.

### 6b. Ads in the shorts feed — explicit evidence

**Use Chrome, not Brave: Brave blocks the ads, so the case can't be reached.**

There are now two independent ad vetoes:

- An explicitly unlisted `/shorts/` item is rejected at every duration.
- With **Browser evidence access** on, an exact visible YouTube control such as
  `Sponsored`, `Ad`, or `Skip ad` is bound to that `/shorts/` video id and
  rejected at every duration.

The second path is the mobile equivalent of the desktop Hive Scrobbler
connector checking YouTube's explicit `.ad-showing` page state. It does not
guess from `PONDS CAM`, another brand/channel name, the title, or generic words
about advertising.

1. On **Chrome**, not on a Premium plan, scroll a shorts feed long enough to hit
   several ads. Keep **Browser evidence access** on.
2. For an explicitly unlisted creative, **Not logged** should read:

   ```
   a short and the video is unlisted — almost certainly a feed ad
   ```

3. For a public promotion whose page visibly says `Sponsored` (or another
   supported exact label), the Log should contain both:

   ```
   [ad] com.android.chrome → YouTube UI marked <video-id> as an ad ("Sponsored")
   [ad] com.android.chrome bound explicit ad evidence to <video-id>: "Sponsored"
   ```

   Its **Not logged** reason should be:

   ```
   YouTube's visible UI marked this Short as an ad ("Sponsored")
   ```

4. Both are unconditional vetoes. Test an ad longer than 30 seconds if one
   appears, and test the Now tab's manual **Broadcast this scrobble** button. No
   `[engine] broadcasting` line may follow either explicit ad reason.
5. Check that ordinary content is not guessed to be an ad. Titles/channels such
   as `PONDS CAM`, `Bad and Boujee`, `Includes paid promotion`, `Adidas`, and
   `Add to queue` do not match by themselves.
6. If the Log's latest URL evidence is only a bare `youtube.com` host, a visible
   label must not be attached to the previously seen Short id. Exact ad evidence
   requires the concrete `/shorts/<id>` URL and label in the same accessibility
   snapshot.
7. The enrichment line remains useful for the unlisted path:

   ```
   [enrich] CYgQQqvwwsY → category=People & Blogs originalArtist=— listed=no (unlisted)
   ```

   `listed=?` still fails closed for the lowered Short floor. If a visible ad
   produces neither an `[ad]` line nor an unlisted result, export that log: the
   likely boundary is that this YouTube/browser build did not expose its label
   through Android accessibility. In that case **Never scrobble this again**
   remains the fallback.

### 6c. Leaving one looping

Park on a single short for 3–4 minutes with the screen on. It should land in
**History exactly once**, normally at 100%. When Chromium publishes the position
wrap, the Log should say:

```
playback position wrapped from end to start — loop detected
```

If it does not publish a usable position transition, the Short fallback should
still say:

```
probable short loop detected — first viewing kept; video remains capped to one scrobble
```

It must not appear in **Not logged** as `looped unattended rather than watched`.
The app only calls the wrap detected when the previous position was in the final
20%, the new position in the first 20%, and the jump covered at least half the
duration.

Then reproduce the v0.8.7 timer case on a normal `/watch` URL:

1. Play `2 Minute Timer Bomb [COOKIE] 🍪` through the end and let it restart.
2. Its YouTube Music line may say `MUSIC_VIDEO_TYPE_PODCAST_EPISODE`, but the Now
   card must remain `kind: video`.
3. If Chrome rebuilds its media session at the loop boundary, the resumed line
   must also say it carried a detected loop.
4. Exactly one transaction may be sent. The v0.8.7 run sent two identical
   `kind: song` transactions.

## 7. Playback speed

Not tested before 0.8.1, and it found a real bug: played time was counted in
wall-clock seconds and compared against the video's duration, so any sped-up
viewing under-reported itself.

1. Play a video of a minute or more and set the speed to **1.25×** or **1.5×**
   partway through. Let it finish.
2. `percent_played` should reflect **how much of the video you saw**, not how
   long you sat there. Watching a 76-second video to the end at 1.25× is 100%,
   not 67%.
3. The finalize line names the rate when it wasn't 1×:

   ```
   [finalize] … — played 76s of 76s (up to 1.25× speed)
   ```

4. The case that used to fail outright: play a video **fully at 2×**. It should
   scrobble at 100%. Before this fix it computed 50% and fell below the
   threshold, so it produced no entry at all.
5. Also try **0.5×** — it should count *less* than the wall-clock time, since you
   consumed less video than seconds elapsed.

Pausing mid-track must not be affected: a paused session reports `speed=0.0`, and
that falls back to 1× rather than erasing the time already played.

## 8. Adapted from the desktop extension (new in 0.8.2)

### 8a. The YouTube Music catalogue

Every video now also gets a small POST to `music.youtube.com`, keyed by video id.
It's the signal MusicBrainz couldn't give, because it matches on the id rather
than on a parsed artist/track string.

1. Play something MusicBrainz has never heard of — a small Latin or regional
   channel is the reliable case. The Now card should show:

   ```
   yt music     ✓ MUSIC_VIDEO_TYPE_ATV
   musicbrainz  no match
   kind because YouTube Music catalogue
   ```

2. On an **Art Track** (`…_ATV`, a `- Topic` upload) the artist and title should
   be the catalogue's, not the uploader's — `Daddy Yankee` / `Con Calma`.
3. On an **official music video** (`…_OMV`) the kind should be `song` but the
   credits should still come from the title parse. For a guitar cover expect
   `Metallica` / `Blackened`, **not** `Elena Verrier` / `Metallica - Blackened
   (guitar cover)`. The extension trusts OMV credits; we deliberately don't.
4. A non-music clip should read `not in the catalogue` and change nothing.
5. `MUSIC_VIDEO_TYPE_PODCAST_EPISODE` must read as not recognised as music. It
   must not overrule an Education category or turn the timer regression into a
   song.

The log carries it on the existing per-video line:

```
[enrich] GQwj_FRntp8 → category=Music originalArtist=Daddy Yankee listed=yes ytmusic=MUSIC_VIDEO_TYPE_ATV
```

**Worth watching for:** the lookup is positive-only by design. If you ever see a
real song *demoted* to `video` because of it, that's a bug — absence of a
catalogue entry must never count against music.

### 8b. `album`

Play a `- Topic` upload (an auto-generated Art Track). The Now card should show an
`album` row and the payload should carry it — the first time RustedWax has ever
populated that field.

A hand-written description must yield **no** album. If you see an album on a
cover upload or a trailer, that's the prose guard failing.

### 8c. Upper duration gates

Play something long with a dash in the title — a podcast episode, a 45-minute
interview, a stream VOD — with lookups **off** so there's no category to help.

Expected: `kind: video`, reason `long-form (45 min) with no explicit music
signal`. Before 0.8.2 that became a `song`, because a dash was read as naming an
artist.

Then check the reverse: a **full album upload** or a **DJ set** of the same length
should still be `song`, because `full album` / `live at` / `mix` vocabulary is
checked first.

### 8d. Title shapes

| Title | Expected |
| --- | --- |
| `BABYMETAL "Gimme Chocolate!!"` | artist `BABYMETAL`, title `Gimme Chocolate!!` |
| `Nevada (by Vicetone)` | artist `Vicetone`, title `Nevada` |
| `03. Trash` | title `Trash` |
| `[Future Bass] Vicetone - Nevada` | artist `Vicetone`, title `Nevada` |
| `【Bring Me The Horizon】I Used to Make Out With Medusa` | artist **`Bring Me The Horizon`** — CJK brackets still name the artist |
| `1999`, `7 Rings` | unchanged — a number that *is* the title |

### 8e. The latched-id duration cross-check

This is the one that fixes a wrong `url` already on-chain. The failure needs a
**playlist** to reproduce, because it depends on the address bar lagging a track
change:

1. Play a music playlist and let it advance on its own several times, ideally with
   the app in the background and the toolbar hidden.
2. For every scrobble, check that the `url` opens **the track that was scrobbled**.
   That is the whole test.
3. When the app catches a stale id, the log says so explicitly:

   ```
   [identity] com.brave.browser unlatched GQwj_FRntp8: page is 193s but the session is playing 226s
   ```

   A track whose id cannot be recovered must appear in **Not logged** as
   `video id could not be verified`. Any broadcast without `url` is a failure.

## 9. Muting a video, and the quiet-bar warning (new in 0.8.3)

### 9a. Never scrobble this again

A dedicated **unlisted** creative is caught by the rules (§6b). A public
promotion is also caught when YouTube exposes an exact visible ad label through
Android accessibility. If it does not expose that label—or Browser evidence
access is off—the promoted video remains identical to the same public video
reached organically. `PONDS CAM — Consigue tu rutina ahora.` reached the chain
through that former evidence gap.

So: **History** rows now carry a **Never scrobble this again** button.

1. Find an entry you don't want counted again and tap it. The row should change to
   `Muted — this video won't scrobble again`, and the log should say
   `muted <id> — "<label>" will not scrobble again`.
2. Play that same video again and let it finish. **Not logged** should read
   `muted video — you asked never to scrobble this one again`.
3. Open the same video on the Now tab and press **Broadcast this scrobble**. It
   must refuse — `That video is muted…`. If the manual button gets through, the
   mute is bypassable and that's the bug.

It cannot remove the entry already on-chain. Nothing can — that's the point of the
chain, and the confirmation message says so.

### 9b. Browser evidence going quiet

The 0.8.2 run lost 13 minutes to this without saying a word: the watcher reported
`connected`, read the collapsed omnibox once, and went silent. Historically that
created five entries without links and lost four more shorts. v0.8.10 instead
attempts recovery and keeps any still-unresolved viewing off-chain.

To reproduce deliberately: with monitoring and lookups on, **revoke Accessibility
from system settings mid-session**, then keep watching shorts.

After three consecutive tracks with no video id you should get a red banner —
*"The address bar has gone quiet"* — naming both costs and offering an
Accessibility button, plus one log line:

```
[url] the address bar has named no video for 3 tracks in a row — unresolved
      tracks will not be broadcast. Check Accessibility…
```

Two things to confirm:

- **Re-granting clears it.** The counter resets on the first track that gets an id.
- **A long video must not trigger it.** Play a 30-minute video on the watch path:
  the bar names the id once and then goes quiet for the whole video, and that is
  normal. If the banner appears there, the counter is measuring the wrong thing.

## 10. Broadcast confirmation and node health (new in 0.8.4)

On 2026-07-30 the app reported seven scrobbles it had not made. `api.openhive.network`
froze 77 minutes behind the chain, kept answering RPCs normally, swallowed five
transactions into a block it would never produce, and refused two more with a
per-block rate limit that only makes sense if the block never advances.

Accepted outcomes now remain distinct instead of sharing one success label.

### 10a. The happy path reads differently

Scrobble anything and watch the timing. A broadcast can take **3–15 seconds
longer** than the accepting RPC, because the app polls independent healthy
nodes for block or mempool evidence. That delay is the feature.

- `Confirmed in a block — tx …`: look it up on an explorer; it must exist.
- `Accepted by <node> and seen relaying in an independent node's mempool — tx …`:
  it is independently relaying but is not yet claimed to be in a block.
- `Accepted by <node>, but confirmation was unavailable — do not retry — tx …`:
  independent services did not answer. This deliberately keeps the manual dedup
  claim because retrying could create a second transaction.

Before v0.8.7 all three were displayed as `Confirmed on-chain`, even though the
last two are not block proof.

### 10b. Force a stale node

The check that would have prevented the whole incident. If you can point the app
at a known-stale node, do that; otherwise this is mostly a code-reading check.

Expected: the stale node is skipped silently and the next healthy one is used. If
**every** node is stale you should get `Couldn't reach a node: <host>: Ns behind
the chain` — never a false success.

### 10c. Transient refusals queue instead of vanishing

Hive allows a limited number of `custom_json` operations per account per block. To
provoke it, scrobble while something else on the same account is also posting
custom_json — your `flix` app, a Hive frontend, anything chatty.

Expected: **History** shows `waiting to retry — …` and the queue count goes up.
The listen must **not** disappear. Then hit **Retry now** and confirm it lands.

Change the saved account while an entry is queued. **Retry now** must leave the
entry untouched and log that it belongs to the original account. Restore the
matching account/key, retry, and verify that History retains the original
percent and video mute action. After eight failed queued attempts, a terminal
History row must say it was removed; the loss must not be silent.

Before 0.8.4 that path discarded the scrobble permanently, which is how KAROL G
and FloyyMenor were lost.

### 10d. The error messages have numbers in them now

Any chain rejection in the log should name values, not templates:

```
rejected: … Account skiptvads already submitted 5 custom json operation(s) …
```

If you ever see a literal `${a}` or `${n}` again, the extraction has regressed —
and that's worth reporting, because those placeholders are what hid a frozen node
for a full session.

## 11. Play time across a session restart (new in 0.8.5)

The bug that had been misread as random loss for three sessions. Chrome destroys
and recreates its `MediaSession` mid-video; each fragment used to be scored
against the 60% threshold on its own.

Play a **long video (3+ minutes) on the watch page, from a playlist, with ads**,
and let it run to the end. The point is to provoke at least one teardown — an ad
break usually does it.

1. Watch the log for `[session] −` followed by `[session] +` while the same video
   is still playing. That's the churn.
2. Each restart should log:

   ```
   [session] com.android.chrome resumed "LUNA" after a session restart —
             carrying 47s of play time forward
   ```

   If the old fragment ended near the media duration and the replacement starts
   near zero, the line must additionally report that the loop signal was
   carried. An ordinary mid-video restart must not.

3. There must be **no** `[finalize]` and no **Not logged** row for an early
   fragment. Session disappearance should instead log that it is waiting up to
   60 seconds for a replacement.
4. The final `[finalize]` should report the **whole** watch, not the last
   fragment — `played 156s of 196s`, not `played 85s of 196s`.
5. The video should scrobble once.

### 11a. Frozen identity during the one-minute delay (v0.8.9)

This regression came from the first Short in log 11. The ended track was
`grNk0DpiaEE`, but the address bar had advanced through later Shorts and named
`ysY13cbxJR4` by delayed finalization. Corroboration rejected the later id, then
the old fallback expression returned it anyway.

1. Start a Short and let it qualify, then scroll fast enough to make Chrome
   remove/recreate its media session while the first item is pending.
2. Continue through several different Shorts during the 60-second continuation
   window.
3. If the original session is resumed, it must carry the original confirmed id
   and any explicit ad flag. If it never resumes, wait for
   `session continuation expired`.
4. The original item's final snapshot must contain either its frozen original
   identity or no video id. It must never contain a later Short's id, title
   facts, or ad evidence.
5. In particular, seeing `ysY13cbxJR4` rejected by an `[identity]` line and then
   used as the original track's `url` is a failure.

Three things that must **not** happen:

- **No early score.** A fragment crossing the threshold must still wait for a
  replacement or continuation expiry; browser churn is not a track ending.
- **Stop still discards.** Press **Stop** mid-video, then **Start** and replay the
  same video. The old time must be gone — Stop discards the in-flight track by
  design.
- **A genuine rewatch starts fresh after the continuation window.** Watch
  something, leave it more than a minute, then watch it again. It should start
  from zero, not inherit. Identical metadata returning inside the one-minute
  window is inherently indistinguishable from browser churn and is documented
  as such.
- **A session that never returns still finalizes once.** Close a qualifying tab
  and wait just over 60 seconds. One `session continuation expired` finalize
  should appear.

### 11b. Mandatory hyperlink recovery (v0.8.10)

This is the regression for the six unlinked Video rows in log 12. Use Chrome's
mobile Shorts feed with **Look videos up** on. Collapse the address bar until
the log reports only `host=m.youtube.com video=—`, then play several Shorts
long enough to qualify, including titles with emoji and trailing hashtags.

For every finalized item, exactly one of these outcomes is valid:

1. The address bar supplied an id and the payload contains the matching
   canonical `https://www.youtube.com/watch?v=<11-character-id>` URL.
2. The resolver logs search candidate counts, then
   `after Shorts watch-page corroboration`, and the payload contains that id.
3. The resolver logs `no verified id` or `ambiguous identity`; **Not logged**
   says the video id could not be verified and there is no broadcast line.

Also verify:

- A modern search page should no longer say zero candidates merely because all
  its results are Shorts lockups.
- The Now-card manual button refuses while it says
  `waiting for verified video id`.
- No log line contains `broadcasting WITHOUT url`.
- Every ordinary YouTube broadcast JSON contains `"url":"https://www.youtube.com/watch?v=`.
- Every new Music or Video profile title is a hyperlink. Compare its target id
  with YouTube History where History retained the item.
- A deliberately ambiguous same-title, same-channel, same-duration result is
  not guessed; it belongs in **Not logged**.

## 12. Field-verification record: log 14 (v0.8.10)

**Run window:** 2026-07-31 22:14 through 2026-08-01 12:22 local time
(2026-08-01 03:14–17:22 UTC), with the log beginning during an already-active
session. **Evidence:** `debug/rustedwax-log (14).txt`, the signed-in YouTube
History page, and both live `skiptvads.vidz` profile views (Music and Videos).

The reconciliation used the 11-character YouTube id as the join key. It did
not infer success from the app's History count or from a locally computed
transaction id. A payload counted as delivered only when the log showed block
confirmation and the matching id was present in the expected live profile
section with a hyperlink.

### 12a. Counts and clean boundaries

| Check | Result |
| --- | --- |
| Finalized tracks | 174 |
| Engine skip decisions | 66: 34 below 60%, 15 under the 10-second hard floor, 12 explicit UI-ad vetoes, 3 unresolved-id vetoes, 2 dedup vetoes |
| Broadcast payloads | 109: 54 `kind: song`, 55 `kind: video`, 106 unique YouTube ids |
| Confirmation | 109 in block; 0 mempool-only, accepted-unconfirmed, queued or failed outcomes |
| Live profile placement | 54/54 song payloads under Music; 55/55 video payloads under Videos |
| Hyperlinks | 109/109 payloads visible with `/music?v=<same-id>` targets; no URL-less payload |
| Ads on profile | 0 ad-like payloads |
| Loop diagnostics | 33 detected/inferred loops capped; no looped Short/video produced a second transaction |

This proves the v0.8.10 mandatory-hyperlink boundary and the Hive/profile
transport path for every payload the engine actually emitted. It does **not**
prove that every qualifying viewing reached the engine, which is where the four
failures below occurred.

### 12b. Confirmed qualifying misses

All four ids below were present in YouTube History and absent from both profile
sections. They are app-side omissions, not delayed node confirmation or a
scrobble.life rendering delay.

| Id | Viewing evidence | Actual outcome |
| --- | --- | --- |
| `saGYMhApaH8` — Me Porto Bonito | 193/191 seconds | Finalization enriched and broadcast the following `3mchJ-EW9rM` La Bebe identity instead (log lines 635–637) |
| `5YrJf3CpHNk` — Cardi B - Trump | One continuous 227-second play | An exact-duration key change (`227125` to `227124` ms) split it into 48% and 53%; both fragments failed separately (lines 3128–3129 and 3186–3187) |
| `IW524Zl2Pus` — The best cosplayer avengers | 23/22 seconds | The URL changed at line 3852 and stale `Sponsored` followed two milliseconds later at line 3853; the organic Short was vetoed at lines 4009–4010 |
| `aZaxQG3ggng` — YCB Frenzy - Crazy | 194/192 seconds | Finalization enriched and broadcast the following `2QqyPy2itXw` Coming Home identity instead (lines 9474–9476) |

The two mixed-identity cases narrow the old race. v0.8.9 correctly stopped a
rejected live URL from being resurrected inside the probe and froze identity
during continuation. Log 14 shows the ended track can still be completed with
the next track's id/facts later, in asynchronous resolution, enrichment or
payload construction. The next contract therefore covers the full
finalize-to-broadcast pipeline rather than another probe-only fallback.

The ad case narrowed “same accessibility snapshot.” The snapshot literally held
the new id and old `Sponsored` label together, so same-snapshot id binding was
necessary but not sufficient. URL generation and stabilization are now part of
the implemented v0.8.11 evidence rule.

### 12c. Expected skips and non-failures

- Ads named Rexona, Lysol, Vicks/VapoRub, Zuko, Heineken, the stadium,
  Emzoom/GAC, Balboa, Bimbo and YouTube bumpers stayed off-chain. Three
  unresolved 15-second Vicks creatives also failed closed.
- Ordinary history entries below the configured threshold were correctly
  omitted. The closest final omissions were 54% and 53%; a 59% football Short
  was skipped on one attempt but qualified and appeared after a later viewing.
- The 27:13 Davoo Xeneize video finalized at 86%, appeared under Videos and had
  the correct hyperlink.
- YouTube History exposed 197 unique loaded ids but did not expose 17 early
  Shorts that the log and profile both contained. Those 17 are not counted as
  app misses; History was not a complete independent source for that interval.
- `Gata Only` produced two song payloads with one start timestamp after 149
  seconds were carried across a leave/return inside the 60-second continuation
  window and the aggregate reached 170%. No end-to-start wrap was observed, so
  the engine treated it as a cumulative double-listen rather than a loop. This
  remains the documented ambiguity for identical metadata returning inside the
  continuation window, not one of the 33 loop-cap failures.

### 12d. Section and metadata quality

All payloads reached the section their immutable `kind` requested. One result
is a product-classification edge rather than a proven implementation error:
`aiQOD-G9SR0`, a Titanium flashmob at a Spider-Man fan event, appeared under
Music because the available fallback reported YouTube category `Music`. Keep
that behavior until the product decides whether Music means any musical
performance or only a track/music video.

Four `kind: song` entries were unquestionably malformed before broadcast:

| Id | Broadcast result | Required parser fixture |
| --- | --- | --- |
| `vG4h2KkwMDA` (line 3286) | artist `Ice Spice Performs … Live On The BET Stage!`; title `BET Awards '24` | artist `Ice Spice`; title `Think You The Sh*t (Fart)` |
| `VpXRPrwezQ8` (line 7599) | artist `Sexyy Red "Get It Sexyy" (Official Video`; title `No Skits)` | artist `Sexyy Red`; title `Get It Sexyy`; never split inside the parentheses |
| `z5WrgDzNIZ0` (line 8797) | artist `6IX9INE "Gotti" (WSHH Exclusive`; title `Official Music Video)` | artist `6IX9INE`; title `Gotti`; ignore the nested promo separator |
| `oNg3M9IJJlY` (line 12123) | artist `TROLLZ`; title `6ix9ine & Nicki Minaj` | artist `6ix9ine & Nicki Minaj`; title `TROLLZ`; choose track-first orientation from channel agreement |

## 13. v0.8.11 regression matrix (automated gate implemented)

The runtime changes and exact unit fixtures in §§13a–13d are implemented. They
describe the generated v0.8.11 build. Section 13e was attempted and failed;
§14 is the first field record, §15 is its implemented correction, §16 is the
second failed field record, §17 is the implemented v0.8.12 correction/matrix,
§18 is its failed device record, and §19 is the v0.8.13 correction.

### 13a. Finalized snapshot isolation

Build pure fixtures from the exact log-14 orderings:

1. Finalize Me Porto Bonito with site-only/unresolved frozen identity, then make
   La Bebe the live foreground evidence before resolution completes.
2. Finalize YCB Frenzy - Crazy, then make Coming Home live before enrichment
   completes.
3. Assert that resolver context is part of the ended bundle, the result is
   corroborated against its title/channel/duration, and facts cannot replace
   session metadata until that check passes.
4. Valid outcomes are the ended track's own verified id or a visible
   `video id could not be verified` refusal. The next id, title, artist or facts
   are never valid.

### 13b. Transition-safe explicit ad evidence

Reproduce the exact ordering: stadium ad id and `Sponsored`, URL changes to
`IW524Zl2Pus`, stale `Sponsored` arrives two milliseconds later, then the real
cosplayer MediaSession metadata appears.

- The first new-id/old-label pair remains provisional and is discarded when it
  is not re-observed for that stable URL generation.
- A real promoted Short whose same id and explicit label remain stable across
  the required observations is still vetoed.
- Another URL change, label disappearance, Stop and service/package reset clear
  provisional evidence.
- An accepted veto is carried with the track instance across session churn and
  does not poison a later organic viewing of the same public id.

### 13c. Same-track duration refinement

- Unchanged title/artist/album with duration `227125 → 227124` ms is one track;
  progress remains cumulative and finalizes once.
- Missing duration becoming known and a drift up to 2,000 ms are refinements.
- A title or artist change is a real ending. A duration change beyond the
  tolerance is not silently merged and must follow the normal corroboration
  path.
- The Cardi B fixture reaches one eligible decision; it does not create two
  below-threshold Not logged rows.

### 13d. Structure-aware song credits

Add the four ids in §12d as literal raw-title/channel fixtures. Separator
scanning ignores balanced parentheses, brackets and quotes. Explicit quoted
track and performance shapes run before generic separators. Generic
orientation uses channel agreement; if neither orientation is strong, the
conservative output is channel plus whole cleaned title.

Video-kind behavior remains unchanged: videos never run through artist/track
splitting.

### 13e. Historical physical-device release gate (five attempts failed before log 21)

After targeted tests, run the full uncached unit suite, assemble the APK and run
lint. Then perform another mixed watch-page/playlist/Shorts session with ads,
loops, 2× playback, a long video, title-parser fixtures and deliberate rapid
Short transitions. Reconcile by id against YouTube History and both live profile
sections.

The first 2026-08-01 attempt used the original artifact, did not execute every
targeted fixture below, and failed the zero-qualifying-omission/parser
requirements; it is recorded in §14. The corrected artifact from §15 underwent
the broad log-17 round in §16. It passed transport and the exact log-16 cases
but again failed capture/metadata requirements and still did not execute every
fixture below. The v0.8.12 artifact underwent the broad log-18 round in §18;
transport and isolation remained clean, but two payload defects and incomplete
fixture coverage failed the gate. The v0.8.13 artifact underwent the log-19
round in §20; transport again reconciled exactly, but two ordinary watch-page
ads reached Hive, four metadata payloads were defective/conservative, and the
mandatory fixtures were not replayed. The implemented v0.8.14 artifact repeated
the broad field round in log 20 (§21); transport and most rule behavior again
reconciled, but a Namecheap ad escaped during an accessibility-observation
outage, one qualifying organic song was refused, two payloads reversed credits,
and the fixture matrix was still incomplete. The v0.8.15 artifact must
repeat the complete gate.

Use this exact field checklist:

1. Install the source-gate-approved
   future `dist/rustedwax-0.8.15.apk` with `adb install -r`, confirm the app
   reports v0.8.15, export/clear the prior diagnostic log, and record the test
   account plus the Monitor, Auto-scrobble, Short clips, Browser evidence, and
   Look videos up settings.
2. Play `saGYMhApaH8` past 60%, then move immediately to `3mchJ-EW9rM` before
   the continuation window closes. Repeat `aZaxQG3ggng` → `2QqyPy2itXw`.
   Each ended item must use its own id/facts or show a visible verification
   refusal; no mixed payload is allowed. For the follow-up candidate, also run
   the exact log-16 Soy Peor → Me Porto Bonito and DÁKITI → Gata Only
   transitions from §14c. Each first song must retain its own correct id despite
   localized/presentation differences; a safe omission is no longer a pass for
   those known-equivalent title pairs.
3. In the Shorts feed, capture a visible `Sponsored`/ad-labeled item and swipe
   rapidly to organic `IW524Zl2Pus` while the overlay is transitioning. The ad
   must remain off-chain and the organic successor must remain eligible. Also
   leave one stable labeled ad on screen long enough for re-observation; it
   must still be vetoed. Exercise another URL, label disappearance, Stop, and a
   monitor restart while provisional evidence exists.
   Separately run ordinary `/watch` or playlist playback until an in-stream ad
   exposes an exact accessibility label. Capture both a public 30+ second ad
   and a second ad followed by the organic content under the unchanged watch
   URL. The ad track instance must be vetoed, while the resumed organic track
   keeps/earns only its own progress. A label-free promotion remains an explicit
   unproven limitation; History may verify the field outcome but is never a
   runtime input. Also prove the §21 coverage-unavailable path: a resolver-only
   track with Browser evidence enabled but no current-track root scan must stay
   off-chain with the evidence-outage reason, while a resolver-only organic
   control with a successful clean scan remains eligible.
4. Play `5YrJf3CpHNk` continuously through the threshold and confirm any
   `227125 → 227124` metadata refinement produces one accumulated finalization,
   not two Not-logged fragments. Include a missing-to-known duration case and
   a deliberate different-title/material-duration transition as controls.
5. Qualify all four parser fixtures: `vG4h2KkwMDA`, `VpXRPrwezQ8`,
   `z5WrgDzNIZ0`, and `oNg3M9IJJlY`. Verify their exact artist/title pairs from
   §12d in both the payload log and profile. Also qualify `F1_aOX0acbY` and
   require title `LA PLENA`, artist `Beéle, Westcol, Ovy On The Drums`. Run the
   complete additional log-17/log-18/log-19/log-20 id matrix listed in §21f;
   the older five fixtures are necessary but no longer sufficient.
6. Include an ordinary watch-page item, a playlist advanced with the toolbar
   hidden, a genuine organic Short, a looping Short/video, a 2× play, and long
   video `fZGvnNVKGPs`. Exercise one manual Broadcast action only after checking
   its Now-card verdict, then let automatic finalization run to confirm shared
   deduplication. Turn the screen off during one qualifying Chrome track, allow
   at least two MediaSession recreations, and verify progress is carried once.
   Deliberately jump to another playlist after a completed song.
7. Export the complete app log and signed-in YouTube History. For every emitted
   id, record the payload kind/url, honest delivery state, block transaction,
   Music-or-Videos placement, and live hyperlink target. Separately account for
   every history-backed qualifying id that did not emit a payload using its
   visible Not-logged reason. End the final browser session and wait at least
   60 seconds for its continuation timer before pressing Stop or exporting.

The patch passes only with all of these simultaneously:

- zero qualifying history-backed known-id viewing omitted by an app-side race
  or over-strict contradiction when sufficient unique evidence exists;
- zero ad-like payloads and zero organic successors falsely vetoed as ads;
- one finalization for bounded duration refinements;
- no mixed identity/facts payloads;
- the three log-16 equivalent-title pairs retaining their own correct ids;
- every emitted payload confirmed or reported in its honest transport state;
- every emitted YouTube payload visible in the correct section with its exact
  hyperlink; and
- every parser/resolver fixture in §§13e and 21f carrying its intended,
  non-truncated credits or its explicitly required ambiguity refusal.

A viewing whose external candidates remain genuinely indistinguishable may
still fail closed, but the log must name the competing evidence and the
reconciliation must not count that as an app-side race correction.

## 14. v0.8.11 physical-device field record: log 16

**Run window:** 2026-08-01 13:59:50 through 14:42:52 local time
(18:59:50–19:42:52 UTC). **Artifact tested at that time:**
`dist/rustedwax-0.8.11.apk`, version code 31, SHA-256
`bdf81bcc560dea1c5a430d869193d702480f15f95c19f4d211abbb320ca4e296`.
**Evidence:** `debug/rustedwax-log (16).txt`. Log 16 contains the
earlier log-15 export plus the continued session and is therefore the complete
app-log record for this attempt. No independent signed-in YouTube History or
live-profile reconciliation was supplied; delivery statements below use the
app's independent-node block evidence only.

### 14a. Counts and transport outcomes

| Check | Result |
| --- | --- |
| Finalized tracks | 18 |
| Engine skip decisions | 11: 4 below threshold, 3 finalized-snapshot identity contradictions, 2 unresolved-id refusals, 1 under the 10-second floor, 1 no-duration/too-little refusal |
| Broadcast payloads | 7 |
| Confirmation | 7 in block; 0 mempool-only, accepted-unconfirmed, queued or failed outcomes |
| Address-bar evidence | Chrome advanced from generation 2 through generation 15; the watcher did not freeze |
| Duplicate/ad outcome | No duplicate transaction and no ad-like payload among the seven emitted entries |

The seven block-confirmed ids were `jZGpkLElSu8` (TQG), `xKKeqlBQ3Js`
(Me Acostumbré), `CUYrEiymUMY` (Tu No Vive Asi), `-r687V8yqKY` (Gata Only),
`AhQcNVyndSM` (Asesina), `5ospiemGG3M` (Lollipop), and `F1_aOX0acbY`
(LA PLENA). The final id had the correct canonical link but malformed credits,
so block confirmation is a transport pass rather than a metadata pass.

Two ad-like MediaSession creatives remained off-chain without title/brand
guessing. No explicit YouTube ad-label event was present, so the engine did not
classify them as ads from their names. Instead, the 15-second Mastercard and P&G
items first saw the following organic URL, then lost that id when fetched page
facts contradicted the active MediaSession. Search could not verify a different
canonical id, so both failed closed. The organic Me Acostumbré and LA PLENA
sessions later established their own identities and remained eligible.

### 14b. Address-bar and screen-off findings

The initial report of a frozen address bar was not supported by the complete
log. Chrome reported `ws00k_lIQ9U`, `saGYMhApaH8`, `jZGpkLElSu8`,
`5r5UePOgMQU`, then generations 6–13 through the original mix. After Lollipop,
the URL changed to `2u5UTPEDGAw&list=RD2u5UTPEDGAw` at 14:38:04. A later
Chrome event returned to `F1_aOX0acbY` at generation 15. A Brave observation of
`2u5UTPEDGAw` was package-scoped and did not become Chrome identity evidence.

The log has no screen-on/off event, so the exact display transition cannot be
proved. The likely interval began around 14:40:20, when Chrome repeatedly
removed and recreated its MediaSession while Fantasy Pool Party continued.
v0.8.11 carried the same semantic track at approximately 134, 172 and 177
seconds. This is a positive device result for session-restart continuity: the
screen did not need to generate address-bar events for Android MediaSession and
notification callbacks to continue, and no duplicate finalization appeared.

The final Fantasy Pool Party session disappeared at 14:42:34 after crossing
the 60% threshold, but the export ends before its 60-second continuation timer
expired. The log then contains about five seconds of a restarted LA PLENA
session and ends at 14:42:52 with Chrome destroyed/waiting. There is no final
engine decision for either pending item and no `[probe] stopped` line. They are
unresolved field evidence, not counted as successful or failed scrobbles. A
future run must wait at least 60 seconds after ending the last browser session
before pressing Stop or exporting.

### 14c. Qualifying identity omissions

The three qualifying omissions share one cause. `SessionProbe.titlesMatch`
still uses literal case/whitespace/substring comparison while finalized
candidate corroboration has a more tolerant normalized comparison. The active
latch therefore discarded each correct address-bar id before the following URL
arrived:

| Ended viewing | Correct active evidence rejected | Safe final outcome |
| --- | --- | --- |
| Soy Peor, 271/269 seconds | `ws00k_lIQ9U`; page `Video Oficial` versus MediaSession `Official Video` | Successor `saGYMhApaH8` facts contradicted the ended title and were visibly refused |
| Me Porto Bonito, 193/191 seconds | `saGYMhApaH8`; parentheses around `ft. Chencho Corleone` plus `Video Oficial`/`Official Video` | Successor `jZGpkLElSu8` facts were visibly refused |
| DÁKITI, 215/213 seconds | `TmKh7lAwnBI`; page ended at `(Video Oficial)` while MediaSession added `| EL ÚLTIMO TOUR DEL MUNDO (Official Video)` | Successor `-r687V8yqKY` facts were visibly refused |

This is an important partial pass: v0.8.11 prevented every adjacent track from
creating a mixed immutable payload. It is still a release failure because the
correct qualifying viewings were omitted unnecessarily.

### 14d. LA PLENA credit failure

The raw song title was:

```text
W Sound 05 "LA PLENA" - Beéle, Westcol, Ovy On The Drums
```

The parser's quoted-track rule accepts a quote only when no substantive credit
text follows it. This input therefore fell through to the generic dash rule and
was broadcast as:

```text
artist = W Sound 05 "LA PLENA"
title  = Beéle, Westcol, Ovy On The Drums
```

The required structural interpretation is:

```text
artist = Beéle, Westcol, Ovy On The Drums
title  = LA PLENA
```

This is not a request for a song database. It is one observed fixture for the
generic `publisher/series "quoted work" - trailing artist credits` grammar.

### 14e. Gate decision

The first v0.8.11 physical-device gate **failed**. The generated build preserved
canonical links, block-confirmed every emitted payload, rejected adjacent-track
mixing, kept the two ad-like creatives off-chain through identity failure, and
carried playback through likely screen-off MediaSession churn. It did not meet zero qualifying omissions or
correct song-credit output, and the run did not include an independent
History/profile reconciliation.

## 15. v0.8.11 field-follow-up correction (implemented; automated gate passed)

The runtime correction is deliberately limited to generic validation and
structural parsing. Exact ids/titles/artists appear only in unit-test fixtures
and historical evidence, never in the APK's runtime decision data.

### 15a. Shared structure-aware title corroboration

The duplicated active/final title predicates now use one pure matcher:

1. Normalize Unicode, case, diacritics, punctuation and whitespace.
2. Remove only existing recognized promo-only wrappers such as `(Official
   Video)` and `(Video Oficial)`; retain names, track words, album/event words
   and credits.
3. Accept equal whole-token structure or conservative contiguous containment
   of the complete shorter structure, requiring at least three tokens when the
   two normalized forms are not equal.
4. Keep the independent page/session duration contradiction unchanged: a
   difference must exceed both 5 seconds and 5% before duration disproves the
   id.
5. Use the matcher only to validate an id already observed from that browser
   track. It must not derive, choose or map a video id from a title.

The exact positive tests are the Soy Peor, Me Porto Bonito and DÁKITI pairs in
§14c. Exact negative tests keep Soy Peor/Me Porto Bonito,
Me Porto Bonito/TQG, DÁKITI/Gata Only and the observed ad/organic pairs as
contradictions. These negative controls, including a bare two-word artist versus
a longer title, are present at both the shared matcher and finalized guard
boundaries.

### 15b. Quoted work with trailing credits

One parser branch now runs before the generic top-level dash rule for:

```text
prefix "quoted work" - trailing credits
```

It applies only with balanced top-level quotes/separator, nonempty components,
channel agreement supporting the prefix as publisher/series, trailing credit
structure, and no recognized promo/event suffix. A proven shape returns the
quoted work as title and trailing credits as artist. Ambiguous cases retain the
current conservative whole-title/channel fallback rather than guessing.

LA PLENA is pinned as the positive raw-title/channel fixture. Negative fixtures
include `Artist "Song" - Official Video` and `Artist "Song" - Live at Wembley`
so format/event text cannot become an artist merely because it follows a dash.

### 15c. Implementation and release gates

The implementation and automated portion are complete:

1. the shared matcher is used by both active and finalized corroboration;
2. the conservative quoted-work/trailing-credit branch is present;
3. 54 focused title/corroboration/parser tests passed with no failures or skips;
4. the full uncached `testDebugUnitTest assembleDebug lintDebug` gate passed:
   295 tests, 0 skipped, 0 failures, 0 errors; debug assembly and lint succeeded;
5. the corrected version 0.8.11, version-code 31 artifact was generated at
   `dist/rustedwax-0.8.11.apk`, SHA-256
   `1b682f582dd17f287d69acd2b22313c227acffb13f13d266288c4e6df65639d5`; and
6. the then-remaining gate was to repeat §13e. Log 17 performed a broad second
   device round and is recorded in §16. It proved the corrected log-16 cases and
   transport boundary, but exposed new defects and did not exercise every exact
   §13e fixture, so the artifact is not field-approved.

The follow-up must not change URL generations, explicit visible-ad policy,
snapshot isolation, same-track duration refinement, thresholds, loop/dedup
rules, kind immutability, mandatory hyperlinks, manual/automatic parity or the
no-historical-rebroadcast rule. Keeping version 0.8.11/version code 31 identifies
this as the corrected candidate for the same unreleased checkpoint; the APK at
the install path now refers to the checksum above, not the artifact exercised
in log 16.

## 16. v0.8.11 second physical-device field record: log 17

**Run window:** 2026-08-01 15:55:52 through 19:18:18 local time
(20:55:52–00:18:18 UTC). **Artifact tested:** corrected
`dist/rustedwax-0.8.11.apk`, version code 31, SHA-256
`1b682f582dd17f287d69acd2b22313c227acffb13f13d266288c4e6df65639d5`.
**Evidence:** `debug/rustedwax-log (17).txt`, signed-in YouTube History, both
live `skiptvads.vidz` profile sections, and independent account-history queries
to `api.hive.blog` and `api.openhive.network`. Unlike log 16, this round has a
complete History/profile/blockchain reconciliation. Profile Today/Yesterday
headings are UTC groupings; reconciliation therefore uses video id rather than
the displayed day boundary.

### 16a. Counts and transport/profile outcome

| Check | Result |
| --- | --- |
| Finalized tracks | 123 |
| Engine decisions | 67 broadcasts plus 56 skips |
| Skip breakdown | 23 below 60%, 17 under the 10-second floor, 6 unresolved ids, 6 finalized-snapshot contradictions, 4 no-duration/too-little decisions |
| Payload kinds | 60 `song`, 7 `video` |
| Confirmation | 67 in block; 0 mempool-only, queued, accepted-unconfirmed or failed outcomes |
| Independent Hive comparison | 67/67 transaction ids present on each of two nodes; 67/67 payload JSON values exact; blocks 108653616–108657522 |
| Profile comparison | 60/60 song payload entries in Music; 7/7 video payload entries in Videos; 0 expected ids found only in the wrong section |
| Links and duplicates | 67/67 canonical watch hyperlinks; 67 unique transaction ids; no duplicate transaction |
| Runtime failures | 0 exceptions/crashes; 15 watch-page timeouts and 2 YouTube Music timeouts degraded through existing fallbacks |

There are 66 distinct emitted video ids because `xKKeqlBQ3Js` was played and
qualified twice about three hours apart. Those are two real listens, not a
duplicate finalization. Seven playback-position wraps were observed; none
created an extra transaction for the same continuous viewing.

### 16b. Confirmed v0.8.11 improvements

- Finalized snapshot isolation kept successor facts out of every ended-track
  payload. No mixed identity reached Hive even when the following URL was the
  only id left at finalization.
- The corrected log-16 cases succeeded on device: Soy Peor used
  `ws00k_lIQ9U`, Me Porto Bonito used `saGYMhApaH8`, DÁKITI used
  `TmKh7lAwnBI`, and LA PLENA used `F1_aOX0acbY` with artist
  `Beéle, Westcol, Ovy On The Drums` and title `LA PLENA`.
- Every attempted payload completed the local-to-Hive-to-profile path exactly.
  The release failure is therefore pre-broadcast capture/metadata behavior,
  not signing, transport, confirmation or profile ingestion.
- No advertisement-like payload reached Hive. The hard floor and mandatory-id
  boundary also kept the observed short creatives off-chain without using
  brand/title guesses.

### 16c. History-confirmed qualifying omissions

Eight qualifying organic viewings were visible in signed-in YouTube History
but had no log-17 transaction:

| Correct id and viewing | Final progress | App-side cause |
| --- | --- | --- |
| `QBq6rY0ZpKM` — VYBZ KARTEL WHEN SINCE | 138/147 seconds | The resolver returned zero search candidates and failed closed |
| `CJjvg7PbE4w` — Nunca Me Amó | 205/204 seconds | Exact channel-key inequality treated resolver credits and the MediaSession uploader as contradictory roles |
| `TapXs54Ah3E` — Ay Vamos | 268/266 seconds | Canonical page title had two tokens and failed the three-token unequal-title containment floor |
| `at1axdFpcgI` — Esta Noche | 241/239 seconds | Same two-token corroboration failure |
| `NgFx3aq52Vg` — Me Reclama | 196/194 seconds | Same two-token corroboration failure |
| `tdZsL8i5ASA` — Recuerdos | 267/266 seconds | One-token canonical title failed corroboration |
| `tGLP74uofTo` — Voy Después | 216/211 seconds | Two-token canonical title failed corroboration |
| `5r5UePOgMQU` — +57, later replay | 301/301 seconds | The address bar did not yield the current id; 65 search candidates failed verification even though the same id had been verified earlier in this monitoring run |

The five short-title cases are a regression in the implemented log-16
follow-up, not proof that Chrome's address bar remained physically frozen. The
correct ids were initially observed and latched, then rejected when the
YouTube Music fallback returned the canonical one/two-word work title. The URL
later advanced, and finalized snapshot isolation correctly refused the
successor rather than corrupting the ended payload. The safety boundary passed;
capture completeness failed.

`Best movie!!! #movie #shorts` was a ninth qualifying local viewing at 137/173
seconds. Search found two uploads with indistinguishable title, channel and
duration (`PlTiqSpwzTI`, `VL_1TfgB2pw`) and refused both. Neither id was visible
in the currently loaded History page, so this remains a safe ambiguous omission
rather than one of the eight History-confirmed app-side misses. The planned
patch must preserve that fail-closed outcome.

### 16d. Metadata defects that reached Hive

The ids and links below are correct, but the immutable payload metadata is not:

| Video id | Log-17 payload defect | Required generic parsing outcome |
| --- | --- | --- |
| `saGYMhApaH8` | artist `Bad Bunny`; title retained `Bad Bunny ft. Chencho Corleone - Me Porto Bonito \| Un Verano Sin Ti` | artist `Bad Bunny`; title `Me Porto Bonito` |
| `GtSRKwDCaZM` | artist `Bad Bunny`; title retained `BAD BUNNY - YO PERREO SOLA \| YHLQMDLG` | artist `Bad Bunny`; title `YO PERREO SOLA` |
| `AnKdQ5p5Ks8` | artist `Ayer ft. Dj Nelson`; title `Anuel` | artist `Anuel`; title `Ayer ft. Dj Nelson` |
| `qA6FBDYncGk` | artist `🌡105F RMX`; title was the full Kevvo/featured-artist list | title `🌡105F RMX`; artist is the top-level trailing credit list |
| `lA8OhVn-o7M` | artist `Me Llama Todavía [Remix]`; title was the Super Yei/featured-artist list | title `Me Llama Todavía [Remix]`; artist is the top-level trailing credit list |
| `UWV41yEiGq0` | artist `Diles`; title was the Bad Bunny/Ozuna/featured-artist list | title `Diles`; artist is the top-level trailing credit list |
| `34Na4j8AVgA` | title `Starboy ft. Daft Punk ft. Daft Punk` | remove only the exact repeated trailing credit, preserving one `ft. Daft Punk` |

The first two expose the current conservative fallback whenever the right side
of a top-level dash contains another top-level pipe. The next four show that
channel agreement can match a featured/uploader token and reverse conventional
artist-first input, while track-first titles followed by explicit multi-artist
credit lists need separate structural evidence. The Starboy case requires
exact duplicate-credit cleanup, not general removal of feature credits.
Existing on-chain entries are historical evidence and must not be repaired or
rebroadcast.

### 16e. Kind and advertisement evidence

`KoWNsyNVR28`, title `Just how many superpowers does he have? #movie #foryou
#edit #funny`, was published as `song` and appears in Music. Its watch page
timed out, the music-client microformat reported `category=Music`, YouTube Music
provided no music-video type, and MusicBrainz did not match. The v0.8.11
classifier let the uploader category outrank this explicit movie/edit format.
This likely kind false positive became a v0.8.12 regression, with
music-video/song-title negative controls so a bare word such as “movie” is not
made a universal veto.

No explicit visible `Sponsored`/`Ad` label was captured in log 17. The 15-second
P&G and stadium creatives remained off-chain because no canonical id could be
verified, not because transition-safe ad evidence was exercised. Therefore the
absence of ad payloads is a positive safety result, but the exact
stadium-ad-to-`IW524Zl2Pus` regression remains device-unproven.

### 16f. Gate decision and incomplete fixture coverage

The second v0.8.11 physical-device gate **failed**, with considerable progress.
Transport, exact hyperlinks, profile placement, loop deduplication, corrected
log-16 cases and no-successor-contamination all passed. Eight
History-confirmed qualifying organic viewings were omitted, seven emitted songs
had malformed credits, and one likely movie Short was placed in Music.

This broad round was not the complete §13e matrix. Log 17 does not contain the
`aZaxQG3ggng → 2QqyPy2itXw` handoff, explicit `IW524Zl2Pus` ad transition,
`5YrJf3CpHNk` duration-refinement case, or original parser ids
`vG4h2KkwMDA`, `VpXRPrwezQ8`, `z5WrgDzNIZ0`, and `oNg3M9IJJlY`.
Consequently those device gates remain pending independently of the newly found
defects.

## 17. v0.8.12 correction implemented; log-18 result in §18

The v0.8.12 runtime and exact automated regressions are implemented from the
corrected v0.8.11 source. Log 17 remains immutable field evidence. The generated
review artifact uses version `0.8.12` and version code `32`. Log 18 attempted
this matrix incompletely and failed for the independent defects in §18.

### 17a. Implemented sequence

1. Replace the boolean-only shared title result with an evidence-ranked result
   (`exact`, `strong containment`, `weak short canonical core`, or
   `contradiction`). Keep active and finalized call sites on the same function.
   Weak short cores are usable only for the current-generation browser-observed
   id when duration independently agrees.
2. Change final channel corroboration from exact key inequality to a
   role-aware guard. Channel disagreement alone is absence of support for an
   already observed id; it becomes a veto only alongside a real identity
   contradiction. Keep search-only resolution strict.
3. Add a bounded, memory-only verified-identity candidate cache and
   presentation-cleaned resolver query variants. Re-corroborate every reused id
   against the immutable snapshot, clear the cache at lifecycle boundaries, and
   retain multiple-match refusal.
4. Extend the top-level parser with primary-separator/multi-suffix handling,
   featured-credit-safe artist-first orientation, structurally proven
   track-first credit lists, and exact duplicate-feature collapse.
5. Add a strong narrative movie-format rule above bare uploader category but
   below distributor/YouTube Music provenance. Keep this entirely separate from
   ad detection.
6. Update version name/code and only then change documentation from “planned”
   to “implemented” for behavior covered by tests. Do not modify or rebroadcast
   any log-17 Hive entry.

### 17b. Exact automated regressions

| Boundary | Positive fixtures | Required negative controls |
| --- | --- | --- |
| Short canonical titles | `TapXs54Ah3E`, `at1axdFpcgI`, `NgFx3aq52Vg`, `tdZsL8i5ASA`, `tGLP74uofTo` retain their already observed ids | `Bad Bunny`/different Bad Bunny song, reordered short words, successor id and material-duration conflict remain contradictions |
| Channel roles | `CJjvg7PbE4w` credits/uploader difference does not veto matching id/title/duration | Same title on a different unobserved upload and any title/duration contradiction remain refused |
| Resolver recovery | Later `5r5UePOgMQU` is recovered as a re-corroborated same-run candidate; cleaned WHEN SINCE query variants reach strict candidate evaluation | expired/cleared cache, changed metadata, ambiguous duplicate uploads and “Best movie!!!” remain refused |
| Multi-separator credits | Exact seven ids from §16d produce their documented artist/title output | nested separators, promo/event suffixes, ordinary artist-first, ordinary track-first and non-duplicate feature credits remain unchanged |
| Kind | `KoWNsyNVR28` is `video` from strong movie/edit format in the absence of hard music provenance | real music videos, Topic/Art Tracks, YouTube Music types and ordinary song titles containing “movie” remain `song` |

The old log-14 and log-16 regressions remain mandatory. New tests may use exact
field strings and ids under `app/src/test`, but `app/src/main` must have no
fixture map, known-song list, known-channel list, brand inference or title/id
branch.

### 17c. Automated and artifact gate

Run the focused tests while developing, then the complete clean gate:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest assembleDebug lintDebug --rerun-tasks
```

Report the authoritative total, skipped/failure/error counts, assembly and lint
outcomes, final APK path and SHA-256. The APK is for review/field testing only;
do not commit, push or open a PR without explicit approval.

The exact gate passed: 314 tests, 0 skipped, 0 failures and 0 errors; debug APK
assembly and lint succeeded. The review artifact is
`dist/rustedwax-0.8.12.apk`, SHA-256
`3390df660053ca9c2c0c7665d320e67e820ce09513d4f025a172a018aa0080b3`.

### 17d. Physical-device gate used for log 18 (incomplete; failed)

Repeat all of §13e, then deliberately add:

1. the five-song short-canonical-title sequence from §16c with the toolbar both
   visible and hidden;
2. `CJjvg7PbE4w`, `QBq6rY0ZpKM`, and two separated qualifying plays of
   `5r5UePOgMQU` in one monitoring run;
3. all seven §16d metadata fixtures and `KoWNsyNVR28`;
4. one stable explicitly labeled YouTube ad and the exact stadium-ad to
   `IW524Zl2Pus` transition; and
5. screen-off MediaSession recreation, seven-or-more loop opportunities,
   deliberate playlist navigation, one manual Broadcast, and a final wait of at
   least 60 seconds before Stop/export.

Reconcile every History-backed qualifying id against the log and Hive, not
only emitted entries. The gate requires zero app-side omissions for the known
fixtures, zero mixed identities, zero ad payloads, correct artist/title/kind,
one transaction per continuous looped viewing, exact canonical hyperlinks and
honest delivery/profile placement. A genuinely ambiguous external lookup may
fail closed, but its competing ids and visible reason must be recorded.

## 18. v0.8.12 physical-device field record: log 18

**Run window:** 2026-08-01 22:48:54 through 2026-08-02 02:29:39
local time (EST/Panama). **Artifact:** `dist/rustedwax-0.8.12.apk`, version
code 32, SHA-256
`3390df660053ca9c2c0c7665d320e67e820ce09513d4f025a172a018aa0080b3`.
**Primary evidence:** `debug/rustedwax-log (18).txt`, 6,309 lines. The
reconciliation additionally used signed-in YouTube History, the Music and
Videos views for `skiptvads.vidz`, and immutable account history from
`api.hive.blog`, `api.deathwing.me`, `api.syncad.com`, and
`api.openhive.network`. Browser inspection was read-only. YouTube History is
audit evidence only; the app does not read it at runtime.

The exported log ends after resolving Amarillo to `KHAgoT4FZbc`, before its
broadcast line. All four Hive nodes and the live Music profile prove that the
same finalized run subsequently emitted one additional operation:
block `108666132`, transaction
`8199121759bb5f126a2fd09c7f1e6100fe34fa54`, song `J Balvin — Amarillo`,
duration `2:45`, 100%, canonical URL and media timestamp
`2026-08-02T07:26:58.000Z`. It is counted in the complete run totals below but
not invented as a line inside the exported log.

### 18a. Counts, transport and immutable-payload reconciliation

| Check | Result |
| --- | --- |
| App lifecycle | 2 app session-start markers; 1 listener connection; 1 probe start; Auto-scrobble enabled once |
| Finalizations in the log | 129 |
| Logged payloads | 73 broadcasts; all 73 later reported `scrobbled (block)` |
| Logged skip decisions | 55: 29 below 60%, 24 unverified hyperlink, 1 under 10 seconds, 1 ambiguous identity |
| Complete on-chain run | 74 operations: 55 songs, 19 videos, 73 unique ids |
| Duplicate-id audit | `ko70cExuzZM` appears twice from separate full Taylor Swift listens 62 minutes apart, with separate timestamps and transactions |
| Payload-schema audit | 0 errors: required fields present, canonical watch URLs, durations at least 10 seconds, percent values 62–100 |
| Delivery failures | 0 broadcast failures, queued outcomes, mempool-only claims or accepted-unconfirmed claims |
| Runtime failures | 0 app exceptions/crashes, watch-page timeouts or YouTube Music timeouts; 14 MusicBrainz lookup failures degraded safely |

Every one of the 73 logged transaction ids and serialized payloads matched the
exact immutable `custom_json` returned by all four configured Hive nodes. The
nodes returned the same account-history fingerprint
`bf20333bc9bf846fe6b7078c712bc63f164313f2fd5a21bcf89233b87f70ba5f`;
there was no missing transaction or payload mismatch. The refreshed profile
showed every logged operation in the section declared by its payload kind, plus
Amarillo at the top of Music. This is a complete signing, transport,
confirmation and profile-ingestion pass; section consistency does not excuse a
wrong kind chosen before broadcast.

Signed-in History contained all 73 unique on-chain ids. Older Shorts were
visible only after paging the Today's Shorts carousel, so checking only the
initial History viewport would have produced false omissions. This external
check also established that the four qualifying resolver refusals in §18d were
real viewings rather than synthetic app sessions.

### 18b. Isolation, ads, loop caps and payload safety

Nine playback-position wraps were logged. Eight qualifying continuous
viewings reached the engine's `playback loop detected — first viewing kept`
cap; none produced a loop duplicate. The ninth wrap remained unbroadcast and
unverified. Thirty-two ids were unlatched after successor/ad separation.
The clearest race was `CzkwUsUpvEM`: the old “Hello peter” id was unlatched when
successor metadata arrived, yet the frozen ended snapshot later broadcast its
own correct payload without successor title, channel, duration or id.

Promotional-looking sessions remained off-chain, normally because no canonical
id could be verified. No explicit `[ad]` or visible-label evidence line occurred,
so the stable labeled-ad detector and exact stadium-ad-to-`IW524Zl2Pus`
transition were not physically exercised. The result is a positive no-ad/
successor-isolation observation, not proof of the explicit-label gate.

Every emitted URL was canonical and every emitted id existed in History. No
mixed successor payload, loop duplicate, malformed transport state or missing
hyperlink reached Hive.

### 18c. Two permanent metadata/kind defects

The field gate failed on two operations whose transport was correct but whose
immutable content was not:

| Video and transaction | Emitted payload | Required generic outcome | Cause |
| --- | --- | --- | --- |
| `6swmTBVI83k`, `c2e5252d8d15615ec1416752220c2b4ed8f509d8` | artist `Your Name`; title `Lil Nas X - MONTERO` | artist `Lil Nas X`; title `MONTERO (Call Me By Your Name)` | The `Track (by Artist)` regex accepted arbitrary words before `by`, so `(Call Me By Your Name)` was misread as a credit |
| `9jI-z9QN6g8`, `41e19fec2c7ce82710efaea48c598932528935d6` | `kind=video`, Flow La Movie — Te Bote Remix | `kind=song` with the parsed artist/title | Generic channel keyword `movie` ran before the id-bound YouTube Music `MUSIC_VIDEO_TYPE_OMV` result and bare `category=Music` |

The Te Bote entry therefore appears consistently in the Videos profile but is
still semantically wrong under the contract: YouTube Music's id-bound OMV
provenance must outrank a generic word inside a music owner's channel name.
Neither historical operation is to be repaired, rewritten or rebroadcast.

One additional 59-second Short, `DWpPhwhBD3A` (“Halsey deserves better”), was
classified as song from `category=Music` with no YouTube Music type. That is
permitted by the v0.8.12 ladder and is recorded as a playlist-quality question,
not a release-contract failure.

### 18d. Four qualifying safe omissions

| History-backed id | Final progress | Final resolver result |
| --- | --- | --- |
| `lZizLbWxr_E` — Spice, Sean Paul, Shaggy / Go Down Deh | 181/185 seconds | No verified id among 108 unique candidates; the search byline `Spice Official` did not equal compound session channel `SpiceOfficialVEVO` |
| `dn3d8awSA0c` — Kybba, Ryan Castro, Sean Paul & Busy Signal / BA BA BAD REMIX | 148/149 seconds | No verified id among 100; YouTube's collaborative byline was not treated as the same owner role |
| `napM9rZUzmU` — Blaiz Fayah X Maureen / Money Pull Up | 118/137 seconds | No verified id among 91; YouTube's `Blaiz Fayah and 2 more` collaborative byline was rejected |
| `cD5T1Y4b7wA` — Feid, Young Miko / Classy 101 | 194/195 seconds | Correctly refused because `cD5T1Y4b7wA` and `DwUA6misBRg` both matched title, channel and duration |

The first three are conservative false negatives and motivate only bounded
channel-presentation/collaborator handling. Classy 101 must remain off-chain
unless the current-generation address-bar watcher supplies its exact id; the
resolver may not choose between indistinguishable uploads.

### 18e. v0.8.12 fixture coverage and gate decision

Log 18 physically exercised `saGYMhApaH8` and `5r5UePOgMQU`. It did not
exercise the five short-title fixtures `TapXs54Ah3E`, `at1axdFpcgI`,
`NgFx3aq52Vg`, `tdZsL8i5ASA`, `tGLP74uofTo`; channel fixture
`CJjvg7PbE4w`; WHEN SINCE `QBq6rY0ZpKM`; ambiguous movie ids
`PlTiqSpwzTI`/`VL_1TfgB2pw`; the seven §16d parser ids; or movie fixture
`KoWNsyNVR28`. Some appear in History from older sessions, which cannot count
as physical evidence for this monitoring run.

The log contains cache `remembered` lines but no later cache-recovery hit. It
also lacks a Stop/reset/cache-clear boundary, explicit labeled-ad event, manual
Broadcast parity case and the exact remaining §13e transition/duration
fixtures. Those boundaries remain device-pending.

The v0.8.12 log-18 field gate **failed**. Transport, node agreement, canonical
links, profile ingestion, snapshot isolation, loop caps and no-ad-payload
behavior were excellent. MONTERO carried wrong credits, Te Bote carried the
wrong kind, three uniquely recoverable qualifying songs were omitted, and the
complete mandatory fixture matrix was not run. Classy 101's ambiguity refusal
was correct and must be preserved.

## 19. v0.8.13 targeted log-18 correction

The correction is deliberately narrow and generic. Runtime source contains no
log-18 id, song, artist or channel lookup table; exact field values live only in
tests and this immutable incident record.

1. Restrict the second-artist parser shape to literal `(by Artist)` and
   `(performed by Artist)`. Ordinary parentheses that merely contain the word
   `by` fall through to the normal top-level separator parser, preserving title
   text such as `(Call Me By Your Name)`.
2. Keep explicit tutorial/reaction/trailer/episode format vetoes above hard
   music provenance, but move generic channel-name words below YouTube Music
   and MusicBrainz. A bare `movie` channel word still beats weak
   `category=Music`; an id-bound OMV or confirmed recording wins.
3. Normalize stacked YouTube owner suffixes one layer at a time and recognize a
   search byline's leading owner only when the card carries YouTube's explicit
   collaborator-dialog marker. Exact normalized title and bounded duration
   remain mandatory, all queries/results remain capped, and the complete
   candidate set must still contain exactly one id. Two matching Classy 101
   uploads remain an ambiguity refusal.
4. Preserve all v0.8.12 snapshot, ad-generation, mandatory-link, threshold,
   loop/dedup, manual/automatic parity, honest-transport, memory-only cache and
   no-historical-rebroadcast boundaries. The runtime never consults YouTube
   History.

The patch is version `0.8.13`, version code `33`. Exact positive and negative
regressions cover MONTERO, both legitimate `(by ...)` forms, non-credit
parentheses, Te Bote, weak movie-channel evidence, stacked owner suffixes, the
three unique resolver misses, collaborator-marker absence and the two-upload
Classy 101 refusal. The focused parser/classifier/resolver suite passed before
the complete gate below was run. The full uncached gate then passed 320 tests
with 0 skipped, failures or errors; debug assembly succeeded and lint completed
with 0 errors and 23 warnings. The generated review artifact is
`dist/rustedwax-0.8.13.apk`, SHA-256
`ddfeb3e51cfe60fcf8fa2f13c05891989215154da948686650ae720e4ca9e026`.

## 20. v0.8.13 physical-device field record: log 19 and v0.8.14 implementation

`debug/rustedwax-log (19).txt` is the 5,261-line export from the v0.8.13/
version-code 33 APK. It begins at 11:48:33 and ends at 19:44:40 local time on
2026-08-02. Two app session-start markers appear; after key verification the
listener/probe started, Auto-scrobble was enabled, and the user exercised a
Stop/Start boundary at 11:50:18–11:50:19 before the accessibility watcher
connected at 11:50:30.

### 20a. Exact decision, transport and profile ledger

The log reconciles without a missing final decision:

| Evidence | Count/outcome |
| --- | --- |
| Finalizations | 114: 105 continuation expiries and 9 direct track changes |
| Broadcast payloads | 53 unique: 51 `song`, 2 `video` |
| Visible skips | 61: 42 unverified hyperlinks, 14 below threshold, 3 ordinary duration-floor refusals, 2 deduplications |
| Direct block reports | 52 |
| Offline queue | Gangsta's Paradise queued once at 18:26:41 and later reported one block transaction at 19:08:36 |
| Loops | One end-to-start wrap; the engine kept the first viewing and emitted one transaction |
| URL/identity safety | 48 stale or contradictory ids were unlatched; no successor-mixed payload was found |
| Canonical links | 53/53 use `https://www.youtube.com/watch?v=` plus one 11-character id |
| Profile | All 53 ids appear in the app-declared live Music/Videos section |

Four independent calls to `condenser_api.get_account_history`—one through each
configured Hive node—returned the exact 53 logged payload objects and the exact
53 logged transaction ids. Each node returned the same latest operation,
Good Love transaction `63efa2a6a0456e1d4223b2ef63b3ef60b6e23e7f`; no later
account operation existed at reconciliation time. The queued Coolio payload is
transaction `cb98d3d7681d0a01eb72d557f95c8a154d2bd9fb` and matches its
original immutable timestamp rather than the later retry time.

There are 54 account operations whose payload timestamps fall within the first
and last RustedWax payload timestamps. The additional No Clarity operation,
transaction `0fca967d12f727f3ca8590c7fe513ea243d8874f`, is not in the app
log and uses `platform: youtube embed` with a `https://youtu.be/` URL. That is
not RustedWax's mandatory mobile payload form and is recorded as a concurrent
other-client operation, not a missing RustedWax log line or duplicate.

The Stop boundary was positive but shallow: it occurred before any verified-id
candidate was remembered, so it proves calm probe teardown/no finalization but
does not physically prove clearing a populated candidate cache. The log later
contains 58 `remembered` lines and no run-local cache-recovery hit. Manual
Broadcast parity was not exercised. MusicBrainz recorded 9 confirmations, 98
no-match responses and 34 lookup failures; those network outcomes did not cause
a broadcast-state discrepancy. Two resolver search fetches failed for the same
ad-like query and failed closed.

### 20b. Two ordinary watch-page advertisements reached Hive

The only two `video` payloads in this RustedWax run are:

| Id and transaction | Final evidence | External reconciliation |
| --- | --- | --- |
| Namecheap `zUaMtSMZDgg`, `c94c4d17c8572018ed1c000e0b78ea405ab2cbec` | 29/30 seconds, 99%; uniquely resolved by title+channel+duration; public/listed; no YouTube Music type | Present in Videos; exact signed-in History search returned “This list has no videos.” |
| KaoJapan `azTP61YoD2s`, `fe0cd98b6761818d0512c027d5bee453404897cf` | 34/34 seconds, 100%; uniquely resolved; public/listed; title ends `動画広告` | Present in Videos; signed-in History search for `IROKA` returned “This list has no videos.” |

The user noticed Namecheap; the same reconciliation independently identifies
KaoJapan as a second leak. History is evidence for this field audit only and
must never be read by the runtime.

No `[ad]` line appears anywhere in log 19. This is explained by the source, not
by a missing threshold rule: `UrlWatcherService` calls its bounded
`findAdSignal` walker only when `videoIdInSameShortSnapshot` returns a concrete
`/shorts/{id}`. Ordinary `/watch` playback and bare `m.youtube.com` observations
therefore skip the scan. Binding watch-page pre-roll UI to the address-bar id
would also be wrong because that id names the organic content behind the ad.
The v0.8.14 implementation instead binds an exact label to the active MediaSession
track instance.

The other ad-like sessions normally failed closed because no unique canonical
id could be verified, remained under the ordinary 30-second floor, or did not
reach the 60% threshold. That accidental safety does not count as ad detection.
Namecheap passed because 30 seconds is eligible, not “under 30”; KaoJapan passed
at 34 seconds. Both met all ordinary-video rules once the resolver found their
public ids.

### 20c. Other rule outcomes

- **Transport and honest state passed.** All 53 exact payloads reached blocks;
  the one transient failure was reported as queued before its later independent
  block confirmation.
- **Finalized isolation passed observationally.** Forty-eight ids were
  unlatched when page/session metadata contradicted them, and none of the 53
  payloads mixes the ended title/time/progress with a successor id.
- **Threshold/floor accounting passed.** The minimum emitted percentage is 70;
  every below-60% decision stayed off-chain. Three verified ordinary items of
  10, 15 and 29 seconds were refused by the 30-second floor.
- **Loop/dedup passed.** The Alone, Pt. II wrap produced one payload; replayed
  Ring Ding Dong and Nobody listens inside the dedup window were refused.
- **Canonical-link and profile ingestion passed.** No URL-less, wrong-shape or
  missing-profile RustedWax operation was found.
- **Explicit ad evidence failed coverage.** No Short or watch-session label was
  captured, and two ads reached Hive.
- **v0.8.13 correction coverage failed.** MONTERO `6swmTBVI83k`, Te Bote
  `9jI-z9QN6g8`, the three unique resolver fixtures `lZizLbWxr_E`,
  `dn3d8awSA0c`, `napM9rZUzmU`, and Classy 101 `cD5T1Y4b7wA` do not occur in
  the log. The broader short-title/channel/cache/movie/transition/manual matrix
  likewise was not run, so no device approval can be inferred from unrelated
  successful songs.

### 20d. Metadata defects and controls

The correct ids and transactions reached Hive, but four payloads are not the
required generic metadata:

| Id | Emitted | Cause and implemented regression |
| --- | --- | --- |
| `TQNW0_RRicI` | Marlon Asher — `Strictly High Grade [Official Video` | `[Official Video 2024]` is retained because the year is not a promo word, then the closing bracket/year cleanup leaves an unmatched fragment. Require paired promo-plus-year cleanup with ordinary year/title negatives. |
| `HtJS32n6LNQ` | `TVXQ! 동방신기 '주문` — `MIROTIC' MV` | Top-level separator scanning protects double quotes but not balanced single-quoted works. Add structural single-quote support plus apostrophe/unmatched/event negatives. |
| `ixkoVwKQaJg` | `Taki Taki ft. Selena Gomez, Ozuna, Cardi B` — `DJ Snake` | `DJSnakeVEVO` does not exactly corroborate spaced `DJ Snake`, so the generic multi-artist branch mistakes the featured list for track-first credits. Add exact collapsed-owner proof while preserving all explicit track-first fixtures. |
| `BVYpT8LsjtA` | BENNETT — `BENNETT - Mamma Mia (feat. Mentissa) - Techno Mix` | A second top-level dash triggers conservative whole-title fallback. Allow only an exact channel-proven primary owner plus a bounded version-shaped suffix; preserve the suffix in the title and keep arbitrary three-part negatives conservative. |

The raw title for `5GYeWpjq54Y` already contains mismatched
`[Loving You Is in My DNA)` delimiters. Preserving that source text is not the
same bug as manufacturing the unmatched Marlon fragment and is an explicit
negative against guessed bracket repair. Conservative uploader/title results
without enough structural evidence are quality limitations, not authority to
invent credits.

### 20e. Gate decision

The v0.8.13 log-19 field gate **failed**. Transport, all four nodes, the durable
queue, profile ingestion, canonical hyperlinks, threshold/floor decisions,
Stop teardown, snapshot isolation, loop caps and deduplication performed well.
Two confirmed advertisements nevertheless became immutable operations, four
song payloads expose parser defects/limitations, and the exact v0.8.13 plus
combined historical fixture matrix was absent. No historical operation is to
be repaired, rewritten or rebroadcast.

### 20f. Implemented v0.8.14 automated matrix and device matrix later failed in log 20

Implemented in this order:

1. Add a pure, testable watch-session ad-evidence model keyed by package plus a
   unique track-instance token/signature. Retain the existing Short-id store and
   provisional/re-observed generation behavior unchanged.
2. Let `UrlWatcherService` scan the existing exact/localized label set whenever
   the visible host is YouTube. Concrete Shorts continue through the current
   id-bound path. Non-Short signals are offered without a video id to
   `SessionProbe`, which may bind them only to one established/unambiguous active
   browser track instance.
3. Freeze accepted watch-session evidence into `Watch`, carry, snapshot, Now,
   manual and automatic decision paths exactly as current Short evidence is
   frozen. Clear provisional state on track conflict/change, label absence,
   Stop, reset, package teardown and expiry. Never attach it to the organic
   watch URL.
4. Add the four generic parser corrections in §20d. Do not add runtime media
   catalogs or field-id branches.
5. Focused ad/store/probe/carry/rules and parser suites pass. The version is
   `0.8.14`, version code `34`, and documentation records only behavior
   actually present.
6. The full uncached gate passed against this versioned source:

   ```bash
   JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
     ./gradlew testDebugUnitTest assembleDebug lintDebug --rerun-tasks
   ```

7. The tested APK was copied to `dist/rustedwax-0.8.14.apk`. The authoritative
   result is 338 tests, 0 skipped, 0 failures, 0 errors; debug assembly passed;
   lint completed with 0 errors/23 warnings. SHA-256 is
   `a9507c733b188f9cf3a481c8b1446535da22449343181cc17ccf556890f298b8`.
   No stage, commit, push or PR was made.

Required automated positives and negatives:

| Area | Positive | Required negatives |
| --- | --- | --- |
| Watch-session ad | Exact label twice on the same 30+ second public ad track vetoes automatic and manual paths; accepted evidence survives session churn | No label, brand/title/channel/duration guesses, ambiguous active sessions, stale signal on the resumed organic track, label disappearance before confirmation, Stop/reset/package clear |
| Watch ad → organic resume | Same `/watch` URL, ad track B labeled, organic track A resumes: B vetoed, A carries only A progress and remains eligible | Ad flag must not bind to URL id, successor metadata or another package/session |
| Paired promo year | `[Official Video 2024]` strips as one paired promo group | `(Summer 2024)`, `[Song 2024]`, `1979`, mismatched open/close pairs |
| Single-quoted work | TVXQ shape yields artist prefix plus intact work containing a dash | `Gangsta's Paradise`, `Don't Start Now`, names/possessives, unmatched quote, quoted event/promo suffix |
| Owner-proven featured title | `DJSnakeVEVO` exactly-collapsed to `DJ Snake`; conventional artist-first title retained | partial/substrings, unrelated owner, existing Anuel featured-channel control, all three explicit log-17 track-first credit lists |
| Version suffix | exact owner plus `Artist - Track - Techno Mix` keeps `Track - Techno Mix` | publisher/event/promo/arbitrary three-part forms, missing/conflicting owner, nested brackets/quotes |

The log-20 physical round was expected to include both log-19 ads while their
exact visible labels are captured, an ad-to-organic resume under the unchanged
watch URL, the
four log-19 parser ids, all six absent v0.8.13 correction ids, the full §§13e/
17d transition/short-title/cache/manual matrix, an offline queue event, a loop,
2× playback, screen-off session churn, and a populated-cache Stop/reset. Export
only after the final continuation window, then reconcile the complete log,
signed-in History, all configured Hive nodes and both profile sections by id.
Section 21 records why that round did not satisfy this matrix.

## 21. v0.8.14 physical-device field record: log 20 and implemented v0.8.15 correction

`debug/rustedwax-log (20).txt` is the 10,945-line, 1,248,861-byte export from
the v0.8.14/version-code 34 APK. It begins at 21:14:46 on 2026-08-02 and its
last line is 10:52:51 on 2026-08-03 local time. The listener and probe started,
Auto-scrobble was enabled and the address-bar watcher reported connected at
21:16:08. No later watcher disconnect/reconnect line appears.

The export was made before the final continuation window completed. Its last
line says a roughly three-second Chrome Rondo session ended and is waiting 60
seconds for a replacement. That incomplete tail could not qualify and is not
part of the 227 finalized decisions below, but it independently fails §20f's
instruction to wait through the final continuation before export.

### 21a. Exact decision, transport, node and profile ledger

| Evidence | Count/outcome |
| --- | --- |
| Completed finalizations | 227 |
| Automatic broadcasts | 107: 105 direct block reports and two durable offline queue writes |
| Visible skips | 120 |
| Fixed test broadcast | One `platform=test` payload before playback, for 108 total account operations in scope |
| Payload kinds | 80 songs and 28 videos including the test payload |
| Canonical YouTube links | 107/107 automatic operations use `https://www.youtube.com/watch?v=` and an 11-character id |
| Duplicate broadcast ids | None among the 107 automatic operations |
| Offline queue | Mbappe Comps and Toe-To-Toe were queued, then independently reported in blocks as `638410bc26d61e807062d78ad1a21f5d0c230336` and `a4f270164705f363de6509f98a3742e41166a88b` |
| Live profile change | +80 Music and +28 Videos, exactly matching all 108 operations |
| Runtime failures | No crash, fatal/uncaught marker or broadcast without URL |

The ledger identity is exact. After the pre-run account boundary, normalized
log payloads and normalized Hive payloads both contain 108 rows and hash to
`7681df2bff0f2cb47be6961977fa0b3ef2d5aa143b5cdcd50fbc1a1d41ae46a6`.
All four configured nodes returned the same 108 exact operation rows; the
four-node normalized row set hashes to
`ecab872ce030c25a7112206eb083f98e726b9b6786de044aee9b743ace142d33`.
The first scoped operation is the fixed RustedWax test in transaction
`4ced349631401d002546fc5d9221eaf580aa83c0`; the last is Metallica — The
Unforgiven in transaction `c10f723ffab75cf1d98732dc9258e3cbb88c24f8`.
No historical operation was modified or replayed.

Signed-in Chrome YouTube History contained 103 of the 107 automatic ids. Exact
Namecheap search returned “This list has no videos.” The other three absent ids
were Metallica `fctnSdDjxiY`, `neHql1zl9wQ` and `Axw30njaMlY`; the log proves
those sessions occurred in Brave, not the signed-in Chrome profile used for the
History audit. Their exact ids, titles, durations and block transactions are
internally consistent. No second ad-like broadcast was identified.

### 21b. Namecheap leak and accessibility-observation outage

The same public Namecheap creative occurred four times:

| Finalization | Accessibility evidence | Decision |
| --- | --- | --- |
| 21:40:04, 30/30 seconds | `Visit Advertiser`, then `Sponsored`, bound to MediaSession track 15 | skipped: visible UI marked the track as an ad |
| 22:47:47, 30/30 seconds | `Visit Advertiser`, then `Sponsored`, bound to track 64 | skipped: visible UI marked the track as an ad |
| 01:07:39, 30/30 seconds | no `[ad]` or current-track `[url]` observation | resolved to `zUaMtSMZDgg`, broadcast at 01:07:49, block transaction `8c22a93cd7d581687055240d279d8713abfcdfc9` |
| 08:54:09, 27/30 seconds | accepted `Sponsored` evidence | skipped: visible UI marked the track as an ad |

The leaking MediaSession began at 01:06:09 with title `See Spike launch his
online business with Namecheap`, artist `Namecheap`, duration 30,081 ms and a
bare `m.youtube.com` notification hint. Search uniquely resolved the public,
listed Science & Technology upload by title, channel and duration. With no
literal ad flag, 30/30 seconds met the ordinary floor and 60% threshold exactly.
This was normal application of the central rules to incomplete evidence, not a
queue resend or a hidden brand exemption.

The missing evidence was not confined to Namecheap. The final accessibility URL
before the interval was `kF4MVeWFiDs`, generation 76 at 00:58:56. The next was
`pSY3i5XHHXo`, generation 77 at 01:35:03. No `[ad]` line appears during that
roughly 36-minute interval even though Super99, Finelo, Superloop, Namecheap,
PO Trade, Hostinger and Panama-telecom advert MediaSessions appeared. The
service still claimed connected and produced no outage warning. Most adverts
remained off-chain only because the resolver, duration floor or identity gate
failed. Namecheap alone was public/searchable and exactly 30 seconds.

There are 608 total `[ad]` diagnostic lines. Accepted MediaSession binding
occurred for 89 unique track tokens, and the final ledger contains exactly 89
explicit-ad skips. The many “0 active MediaSession tracks” refusals were late
page observations after the corresponding sessions ended and did not taint an
organic successor. Thus the implemented track binding worked whenever an
observation arrived; the defect is silent observation coverage/freshness.

### 21c. Other rule outcomes

The 120 skip decisions reconcile exactly:

| Rule | Count | Outcome |
| --- | ---: | --- |
| Exact visible ad evidence | 89 | all stayed off-chain: 57 finalized with `Sponsored`, 32 with `Visit Advertiser` |
| Below configured 60% threshold | 15 | all stayed off-chain |
| Missing duration plus zero played seconds | 6 | all stayed off-chain |
| Duration floors | 2 | one six-second item failed the hard ten-second floor; one unverified 15-second ordinary item failed the 30-second floor |
| Final id corroboration | 8 | seven ads/placeholders safely refused; one qualifying BOOGA song was a false omission (§21d) |

Seventeen position-wrap diagnostics were emitted and 14 completed continuous
viewings explicitly logged “first viewing kept; continuous viewing capped to
one scrobble.” No automatic id was broadcast twice. Six same-track session
restarts carried accumulated progress; one PO Trade advert carry also retained
its explicit ad evidence. Eighty-eight finalizations exercised playback up to
2×. The two offline payloads later confirmed with their original media
timestamps. No successor-mixed identity, missing canonical URL or finalization/
broadcast accounting gap was found.

All three automatic broadcasts shorter than 30 seconds were exact verified
Shorts (`RoPdKANOD4g`, `UxcsVnVIUfY`, `CbVOScRrjWI`) and were found in History
under their exact `/shorts/` identities. Ordinary threshold and duration rules
therefore remained distinct from the Short exception.

This log contains no user Stop/monitor-off event, muted-track refusal or
already-scrobbled dedup refusal. It does not physically prove populated-cache
Stop/reset, mute, replay dedup or a real YouTube manual/automatic parity case.
The fixed test payload is transport evidence, not manual YouTube parity.

### 21d. Qualifying omission and malformed credits

One of the eight id-corroboration refusals was a real organic loss:

- `JmeUtPih4U8`, `CENTRAL CEE - BOOGA (MUSIC VIDEO)`, played 110/110 seconds.
  Search returned one title/channel/duration match and enrichment reported
  category Music plus YouTube Music OMV. Final corroboration rejected candidate
  channel `Central Cee and LIVE YOURS` as contradicting ended channel
  `Central Cee`. Signed-in History contains the exact id. The other seven
  refusals were ad/placeholder sessions and correctly remained off-chain.

Two immutable song payloads used the correct ids and canonical links but
reversed artist/title:

| Id and raw title | Channel | Emitted | Required generic result |
| --- | --- | --- | --- |
| `DGs9TJmazB0`, `6IX9INE - SIP ft. Tyga, Nicki Minaj, Blueface (RapKing Music Video)` | `RapKing` | artist `SIP ft. Tyga, Nicki Minaj, Blueface (RapKing Music Video)`; title `6IX9INE` | artist `6IX9INE`; preserve the full cleaned right-hand work/feature title |
| `z7DbZS6l6Vk`, `Ed Sheeran – Bad Habits Feat. Tion Wayne & Central Cee (Fumez The Engineer Remix) [Official Video]` | `Tion Wayne` | artist `Bad Habits Feat. Tion Wayne & Central Cee (Fumez The Engineer Remix)`; title `Ed Sheeran` | artist `Ed Sheeran`; title `Bad Habits Feat. Tion Wayne & Central Cee (Fumez The Engineer Remix)` |

The deterministic source cause is the conventional-dash branch that reverses
whenever the right side looks like an explicit multi-artist list and the left
does not. Exact collapsed-owner proof runs first, but an unrelated publisher or
featured-artist channel does not prove the left. The existing unrelated-channel
Taki Taki regression currently expects reversal, so it encodes the structural
assumption that log 20 disproved. The correction must distinguish a right-hand
work prefix followed by featured credits from a genuinely bare trailing artist
list, while preserving every proven log-17 track-first fixture.

One classification/credit edge, football compilation `jTg-fpSHLdU`, remains
questionable but is not evidence-strong enough for this patch: its title names
both player and backing song, its channel/category say Sports, and the music
endpoint says UGC. No production rule is authorized from that ambiguity.

### 21e. Gate decision

The v0.8.14 log-20 field gate **failed**. Transport, four-node agreement,
profile ingestion, canonical links, accepted ad-track binding, thresholds,
duration floors, loop caps, playback speed, same-track carry and the durable
queue performed well. One advertisement nevertheless became a second permanent
Namecheap operation during a silent accessibility-observation outage; one
History-confirmed qualifying organic song was refused; two song payloads
reversed credits; the mandatory fixture matrix was absent; and the export ended
before its last continuation window. No historical operation may be repaired,
rewritten or rebroadcast.

### 21f. Implemented v0.8.15 automated matrix and strict physical matrix

The implementation followed the fixed order below. The source, complete
uncached gate and artifact copy/hash are complete; the physical round remains
pending:

1. Add failing focused regressions for a successful clean root scan, an exact
   ad scan, a resolver-only public 30-second track with no scan, stale coverage,
   ambiguity, session carry, Stop/reset/package clear and Browser evidence off.
2. Add a pure track-bound accessibility-coverage/freshness model. Coverage is a
   successful visible YouTube-root inspection, not watcher connection and not
   an “organic” assertion.
3. Refactor `UrlWatcherService` so normal callbacks and a bounded periodic
   refresh share one root-observation routine. Poll only a visible target-
   browser root while monitoring; preserve node budgets/recycling and log one
   evidence-outage transition while target MediaSessions continue.
4. Freeze coverage through Watch/carry/snapshot. With Browser evidence enabled,
   refuse automatic and manual broadcast for a resolver-only track that has no
   current-track coverage. Use an evidence-unavailable reason, never an ad
   claim. Exact current-generation URL observations already supply coverage.
5. Correct feature-list orientation so `Artist - Work ft./feat. A, B` is not
   reversed solely by its right-hand credits. Keep bare trailing credit lists
   and strongly work-shaped log-17 track-first forms intact.
6. Permit BOOGA's collaborative candidate byline only under unique exact-title/
   duration resolution, hard music provenance and exact complete leading-owner
   agreement. Preserve every ambiguity, partial, publisher and mismatch refusal.
7. Run focused watcher/evidence/probe/carry/snapshot/rules/manual/parser/resolver
   suites. Only when they pass, update to v0.8.15/version code 35 and change
   documentation from planned to implemented.
8. Run the complete uncached gate:

   ```bash
   JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
     ./gradlew testDebugUnitTest assembleDebug lintDebug --rerun-tasks
   ```

9. Copy only that tested artifact to `dist/rustedwax-0.8.15.apk`, calculate its
   SHA-256, then perform the complete device gate below. Do not stage, commit,
   push or open a PR before user review and a successful field round.

The focused implementation gate covers 176 tests across accessibility
coverage, exact ad evidence, URL generation, track identity/carry, latch
corroboration, central scrobble rules/manual parity, title parsing, search
candidate parsing, finalized identity corroboration and verified-candidate
cache lifecycle. It passes with 0 skipped, 0 failures and 0 errors. Version name
`0.8.15` and version code `35` were applied only after this result.

The complete uncached
`testDebugUnitTest assembleDebug lintDebug --rerun-tasks` gate then passed 349
tests with 0 skipped, 0 failures and 0 errors. Debug assembly succeeded and lint
completed with 0 errors and 23 warnings. The tested artifact is
`dist/rustedwax-0.8.15.apk`, version code 35, SHA-256
`242d1b76d473754494dec74e035a7731ee1311c4920458926c5dd769e7ee365c`.

Required focused positives and negatives:

| Area | Positive | Required negatives/controls |
| --- | --- | --- |
| Accessibility coverage | Successful clean scan binds only to the one active track; same-track session recreation retains it | connection alone, old scan, new track, ambiguous sessions, other package, null/inactive root, Stop/reset/destroy/expiry do not cover a track |
| Coverage-unavailable rule | Browser evidence enabled + resolver-only track + no current-track scan is refused automatically and manually with the same explicit reason | do not call it an ad; exact current-generation URL path remains eligible; Browser evidence disabled preserves disclosed fallback behavior |
| Exact visible ad | Existing label path still vetoes the track and survives genuine session churn | no brand/title/channel/duration/public-state inference; organic successor never inherits ad or clean coverage |
| Feature orientation | exact SIP and Bad Habits fields above remain conventional artist-first | qA6/lA8/UWV track-first cases, Anuel, Taki Taki, bare co-artist lists, unrelated/featured channels and arbitrary dashes |
| Collaborative byline | BOOGA uniquely resolves under exact title/duration, OMV and leading-owner agreement | arbitrary `and`, substring/prefix, fan/publisher suffix, wrong title/duration, absent hard-music provenance, multiple ids and Classy 101 ambiguity |

The strict physical round specified at implementation time was to include:

1. Namecheap and KaoJapan with literal visible labels; a labeled ad followed by
   organic content under the unchanged watch URL; and an observed
   evidence-outage/resolver-only refusal plus a clean-scanned organic control.
2. Log-20 fixtures `JmeUtPih4U8`, `DGs9TJmazB0`, and `z7DbZS6l6Vk` with exact
   id, artist, title, kind and canonical-link assertions.
3. Log-19 parser ids `TQNW0_RRicI`, `HtJS32n6LNQ`, `ixkoVwKQaJg`,
   `BVYpT8LsjtA` and mismatched-delimiter negative `5GYeWpjq54Y`.
4. The absent v0.8.13 fixtures `6swmTBVI83k`, `9jI-z9QN6g8`, `lZizLbWxr_E`,
   `dn3d8awSA0c`, `napM9rZUzmU`, `cD5T1Y4b7wA`.
5. All §§13e/17d transition and parser fixtures, including `saGYMhApaH8`,
   `GtSRKwDCaZM`, `AnKdQ5p5Ks8`, `qA6FBDYncGk`, `lA8OhVn-o7M`,
   `UWV41yEiGq0`, `34Na4j8AVgA`, the four log-14 parser ids and LA PLENA.
6. A genuine verified Short below 30 seconds, an ordinary under-30-second video,
   one loop, a true dedup replay, mute, 2× playback, an offline queue event,
   screen-off same-track churn and Stop/reset after the candidate cache is
   populated.
7. One Now-card/manual Broadcast attempt for an eligible YouTube track and one
   for each new refusal class, followed by automatic finalization to prove
   central-rule parity.

End every browser session, wait past the final 60-second continuation window,
then Stop and export. Reconcile every finalized decision and every automatic/
manual/test payload against signed-in History, all configured Hive nodes and
both profile sections. The gate requires zero qualifying evidence-backed
omissions, zero advertisement payloads, zero credit reversals, exact decision/
transport/profile accounting, no duplicate id, and every required fixture with
its specified outcome.

## 22. v0.8.15 final physical-device field record: log 21

`debug/rustedwax-log (21).txt` is the final v0.8.15 Chrome device record. It has
14,464 lines, is 1,648,507 bytes, and has SHA-256
`5a999242ff55c9cd32906d47b59efaf101522d2b1ceab2a7da3aa5547b7c183d`.
The surviving export covers 2026-08-03 12:56:49 through 18:06:25 local time.
Android playback timestamps reset into low uptime ranges twice, consistent with
the two reported device reboots; the second restart also left a NUL gap in the
export. No RustedWax exception, fatal, ANR, out-of-memory, watchdog or explicit
crash marker appears. The record cannot establish overheating as the reboot
cause, and neither History nor Hive can reconstruct sessions lost before they
reached a final decision.

### 22a. Complete surviving decision ledger

The export contains 228 `[finalize]` decisions: 98 broadcasts and 130 skips.
Every broadcast reached `[engine] scrobbled (block)`. The 98 payloads contain 98
unique YouTube video ids and 98 unique transaction ids, split into 57 songs and
41 videos; no duplicate payload URL was found.

| Final decision | Count | Audit result |
| --- | ---: | --- |
| Exact visible-ad veto | 85 | Stayed off-chain |
| Below threshold or no qualifying duration/progress | 32 | Stayed off-chain |
| No verified video id | 11 | Stayed off-chain |
| Hard duration floor | 2 | Stayed off-chain |
| Broadcast and block-confirmed | 98 | 98 unique ids/transactions |

The 32 threshold decisions include values displayed as rounded percentages.
For example, 110/185 seconds is 59.46% and correctly remains below a 60% gate
even if the user-facing diagnostic rounds it to `60%`. The two hard-floor
decisions occurred at 6 and 8 seconds. Thirteen loop/repeat diagnostics produced
no duplicate video id. Same-track MediaSession restarts retained progress and
evidence, and 2× playback, playlist changes, Shorts, minimizing Chrome,
screen-off intervals, pause/resume and playback stops all occurred in the run.

### 22b. Hive and profile reconciliation

All 98 log-21 transactions were present on the signed-in `skiptvads.vidz`
profile; the comparison returned no missing id. The profile snapshot contained
440 Music and 231 Videos entries in total, including 153 Music and 69 Videos for
the day. Every scoped log-21 operation reconciled to its logged id and block
transaction. The older immutable Namecheap transaction
`8c22a93cd7d581687055240d279d8713abfcdfc9` visible on the profile belongs to
log 20/v0.8.14, not this build. Log 21 created no advertisement payload found by
the log, chain and profile comparison.

Two naturally served Namecheap ads supplied the most important v0.8.15 field
check. `See Spike launch his online business with Namecheap` was skipped on the
exact `Sponsored` signal, and `Watch Yeti find his ideal web hosting service
with Namecheap` was skipped on `Visit Advertiser`. This is evidence that the
new periodic accessibility observation closed the silent Namecheap path that
failed log 20; it is not a production brand rule.

### 22c. Conservative omissions and metadata limitations

History cross-checking separated the 11 unverified-id refusals into seven ad or
promotional sessions absent from History and four full organic songs present in
History:

- `We Found Love (Album Version)` (`n6N1_sxlBU8`)
- `Poker Face` (`oG-4Uvhm4lI`)
- `I Gotta Feeling` (`hs_yk24ghzA`)
- `Why Don't You Get A Job` (`mQYJYY4VkWA`)

Those four tracks are false omissions caused by conservative identity
resolution. They produced no malformed or unlinked payload. The 85 explicit-ad
decisions contained one additional conservative false omission: `Zun Da Da`
(`KbhLOifTuF4`) played for 303/304 seconds and appears in History, but inherited
a still-active `Visit Advertiser` observation immediately after the preceding
promoted music video `Mi Xico` transitioned to the organic track. The promoted
video itself is absent from History and was correctly kept off-chain. This
transition poisoning is a known false-negative boundary; it did not pollute
Hive.

Eleven accessibility evidence outages were diagnosed and one recovery was
observed; the other outages ended with the track/session or app lifecycle.
No final decision reached the explicit `Browser evidence was unavailable...`
branch because identity resolution failed first in those physical cases. The
central fail-closed branch remains covered by automated regressions.

Two accepted song payloads were useful but conservatively uploader-attributed:
`#TPL BM (OTP) - London View | Pressplay` used artist `Pressplay Media`, and
`CHARLY BLACK & J CAPRI - WHINE & KOTCH - - HEAD CONCUSSION - 21ST HAPILOS`
used artist `Hapilos`. Several music-oriented Shorts classified as songs from
YouTube Music/category evidence; this matches the configured product behavior
and the user's acceptance criteria.

### 22d. Coverage boundary and release decision

Log 21 exercised Chrome only. Brave continues to share the same production
path and has prior device evidence, but was not independently replayed in this
round. The exact SIP, Bad Habits, Taki Taki and BOOGA historical fixtures were
not replayed; their v0.8.15 corrections remain automated-regression evidence,
not new physical evidence. Mute, a deliberate true dedup replay, a populated-
cache Stop/reset and every item of the broad §21f fixture list were likewise not
forced in one session.

For the observable qualifying population, the practical capture estimate is
98 / (98 broadcasts + 4 resolver misses + 1 transition-poisoned organic track),
or approximately **95.1%**. This excludes threshold/floor refusals and tracks
lost before finalization during reboots, so it is a field estimate rather than
a formal accuracy or recall benchmark.

The strict §21f zero-omission/every-fixture matrix was therefore not literally
passed. The user accepted v0.8.15 as the final Phase 4 product because the broad
real-use run produced no new ad payload, reconciled 98/98 broadcasts exactly,
showed no duplicate or crash signature, and exceeded the stated 80% practical
cataloging target. The five known organic false omissions, two uploader-credit
limitations and incomplete historical fixture replay are documented release
exceptions. No immutable historical operation will be repaired, rewritten or
rebroadcast. Native YouTube and YouTube Music app ingestion begins separately
in v0.9; it is not part of this release.

## Running the unit tests

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest
```

The corrected v0.8.11 source gate ran the full `testDebugUnitTest assembleDebug lintDebug
--rerun-tasks` sequence: 295 tests, 0 skipped, 0 failures, 0 errors; debug APK
assembly and lint both completed successfully. The physical-device gate is
separate evidence: the first artifact failed as recorded in §14, and the
corrected artifact failed the second field round as recorded in §16.

The v0.8.12 source gate then ran that same uncached sequence: 314 tests, 0
skipped, 0 failures and 0 errors; debug assembly and lint succeeded. Log 18
then failed its physical-device gate exactly as recorded in §18.

The v0.8.13 source gate ran the exact full sequence from §17c: 320 tests, 0
skipped, 0 failures and 0 errors; debug assembly succeeded and lint completed
with 0 errors and 23 warnings. The review artifact is
`dist/rustedwax-0.8.13.apk`, version code 33, SHA-256
`ddfeb3e51cfe60fcf8fa2f13c05891989215154da948686650ae720e4ca9e026`.
Its physical-device gate later failed in log 19 as recorded in §20.

The v0.8.14 source gate ran the same exact uncached sequence: 338 tests, 0
skipped, 0 failures and 0 errors; debug assembly succeeded and lint completed
with 0 errors and 23 warnings. The review artifact is
`dist/rustedwax-0.8.14.apk`, version code 34, SHA-256
`a9507c733b188f9cf3a481c8b1446535da22449343181cc17ccf556890f298b8`.
Log 20 then failed its physical-device gate as recorded in §21. The v0.8.15
focused source gate passes 176 tests with no skips, failures or errors. Its
complete uncached gate passes 349 tests with no skips, failures or errors plus
debug assembly and lint with 0 errors/23 warnings. The tested APK and hash are
recorded in §21f; its physical-device gate remained pending at artifact time.
Log 21 then supplied the accepted Phase 4 field record in §22. It did not
literally complete the historical every-fixture matrix, and its documented
conservative omissions remain release exceptions.

The suite includes kind classification, title parsing, threshold/floor/loop
rules, continuation expiry tokens, frozen carried identities/ad evidence,
watch-page provenance, independent-node status aggregation, dhive-compatible
crypto/serialization vectors, payload construction, id resolution, rejected
live-id corroboration, exact YouTube ad labels, notification binding, YouTube
Music parsing, playback-speed scaling, dedup, and MusicBrainz matching.
Use the Gradle result as the authoritative count; this list describes coverage
rather than freezing a number that drifts whenever a regression is added.

## Known gaps after the accepted v0.8.15 log-21 field round

- **The strict historical matrix never reached a zero-defect pass.** Log 16 confirmed three
  qualifying omissions caused by inconsistent active/final title
  corroboration plus malformed LA PLENA credits. The generic correction in §15
  fixed those exact field cases. Log 17 then reconciled 67/67 emitted payloads
  but confirmed eight other qualifying organic omissions, seven malformed
  metadata payloads and one likely movie-Short classification error. The
  generic correction is implemented and source-tested in §17. Log 18 then
  passed the full transport/profile reconciliation but found the two permanent
  MONTERO/Te Bote payload defects, three uniquely recoverable resolver misses
  and incomplete fixture coverage in §18. The v0.8.13 correction passed its
  source gate in §19; log 19 then reconciled 53/53 RustedWax payloads but
  admitted two ordinary watch-page advertisements, exposed four parser
  defects/limitations and again omitted the required fixture matrix. The
  v0.8.14 correction in §20 is implemented and passed its full source gate.
  Log 20 then reconciled all 108 scoped operations but admitted a second
  Namecheap ad during a silent accessibility-observation outage, refused one
  qualifying BOOGA song, reversed two conventional featured titles and again
  omitted the required fixture matrix. The implemented v0.8.15 correction in
  §21 then produced the accepted log-21 field result in §22: no new ad payloads,
  exact 98/98 chain/profile reconciliation, four resolver omissions and one
  transition-poisoned organic veto. The remaining limitations are accepted for
  the Phase 4 final product, not erased from the record.
- **Desktop and phone together will double-scrobble.** Dedup is per-device; the
  cross-device check is not implemented. If you run the extension and the app on
  the same account at the same time, expect two scrobbles per listen.
- **No biometric gate** on the stored key — an unlocked phone can sign.
- **Video-id recovery can still miss, but cannot create an unlinked entry.** The
  address bar is exact when it reports, but can expose only a bare host for long
  stretches. Playlist and search/watch-page recovery require a unique title,
  channel, and duration match. If YouTube omits those signals, changes its
  undocumented markup, or returns ambiguous duplicate uploads, the viewing is
  shown in **Not logged** and remains off-chain. Recovery requests need
  **Look videos up** enabled.
- **No privacy mode.** All payloads are plaintext on-chain. Phase 5.
- **A broadcast takes roughly 15 seconds longer** when block inclusion is not
  seen immediately, because every independent healthy node is polled for up to
  five block intervals. Block, mempool, and unavailable evidence are displayed
  separately; that delay is the intended trade.
- **A normal track can appear roughly one minute late.** When Chrome destroys
  the old controller and creates a different one, the old item waits through
  the continuation window before finalizing; different metadata might be a
  mid-roll ad before the original returns, so it is not sufficient evidence to
  close the old item immediately. Hive confirmation time follows that wait.
- **Visible ad detection depends on browser accessibility output.** v0.8.9
  rejects a public promoted Short when YouTube exposes an exact visible ad
  control and Browser evidence access is on. If no such label is exposed, the
  public video is identical to organic playback in the remaining evidence and
  can still scrobble. Log 19 proved that v0.8.13 never inspected an exact label
  unless the same snapshot had a concrete Short id; Namecheap and KaoJapan
  therefore reached Hive as normal public videos. The implemented v0.8.14
  track-instance path closes the unscanned watch-page case whenever an
  accessibility observation actually arrives. Log 20 proved the service can
  remain nominally connected while URL/ad observations go silent for about 36
  minutes; a public 30-second Namecheap ad then passed the ordinary resolver.
  v0.8.15 records successful current-track scan coverage separately and refuses
  resolver-only broadcasts during an evidence outage. Log 21 correctly vetoed
  two naturally served Namecheap ads and produced no new ad payload, while also
  exposing one conservative ad-evidence carry into the following organic track.
  It still does not
  fill a genuinely label-free, successfully scanned page with channel/title
  heuristics; §9a's mute remains the fallback, and **Short clips** is the kill
  switch if you'd rather have neither.
- **Playback speed is trusted as reported.** Played time is scaled by
  `PlaybackState.playbackSpeed`, clamped to 4× and falling back to 1× for absent,
  zero or non-finite values. A browser that misreports the rate would mis-measure
  progress in proportion; nothing observed does.
- **Short-clip scrobbling needs both other switches.** With *Browser evidence access*
  or *Look videos up* off there is no proof a clip is a real short, so the
  ordinary 30-second floor applies and the setting does nothing. Stated in the
  UI rather than left as an unexplained no-op.
- **~1 in 8 legitimate short clips is still dropped.** Enrichment failed on ~12%
  of ids in the 2026-07-29 session, and it is required to stay non-blocking
  (D8), so a clip whose fetch times out is held to 30 seconds. Deliberate
  direction: a missed entry can be earned again, a false one is permanent. The
  reason shows in **Not logged**.
- **Untagged music can be missed.** Since v0.5.1 the last-resort default is
  `video`, so a fan upload of a real song with a bare title, no category and
  no MusicBrainz entry is filed as a video. Deliberate: a missed song costs one
  playlist entry, a false song is permanent curation debt.
- **The site canonicalizes by first writer.** scrobble.life keeps one record
  per video id, seeded by the *first* scrobble's kind — a pre-fix `song` entry
  keeps that video listed as music for everyone, whatever later ops say. Not
  fixable app-side; raised with the site's developer.

### Implemented in v0.8.11 (source gate passed; both field gates failed)

- **Finalization could mix adjacent identities.** Finalized snapshots now carry
  their own resolver context, and structured candidate/facts evidence is
  corroborated against the ended title, channel, and duration. The exact
  `saGYMhApaH8`/`3mchJ-EW9rM` and `aZaxQG3ggng`/`2QqyPy2itXw` handoffs refuse
  successor facts.
- **A transition-frame ad label could poison an organic successor.** URL
  generations plus provisional/re-observed state pin the stadium-ad to
  `IW524Zl2Pus` ordering while retaining stable explicit-ad vetoes.
- **Exact duration equality split continuous playback.** Same normalized
  title/artist/album with missing-to-known duration or ≤2,000 ms drift preserves
  progress and evidence; the `227125 → 227124` Cardi B fixture reaches one
  eligible decision.
- **Song credits split inside syntax or remained reversed.** Top-level scanning,
  explicit quoted/performance shapes, and conservative channel agreement pin
  `vG4h2KkwMDA`, `VpXRPrwezQ8`, `z5WrgDzNIZ0`, and `oNg3M9IJJlY`.

### Fixed in v0.8.9

- **Public promoted Shorts ignored YouTube's visible ad state.** Exact
  accessibility-visible YouTube ad labels are now bound to the current
  `/shorts/` video id and veto both automatic and manual broadcasts. Brand,
  channel, and title guesses are explicitly excluded.
- **A rejected later URL could become an ended track's identity.** The
  corroboration fallback no longer resurrects a rejected live id. Identity and
  explicit ad evidence are frozen with progress through Chrome's one-minute
  continuation delay and consumed together by a replacement.

  This remains true for that probe-level fallback. Log 14 later proved that an
  unresolved ended snapshot could still acquire successor facts/id downstream;
  v0.8.11 closes that separate finalize-to-broadcast boundary above.

### Fixed in v0.8.8

- **A 42-second unlisted Shorts ad cleared the ordinary floor.** Explicit
  `isUnlisted == true` now vetoes a `/shorts/` item before duration and progress
  are evaluated.
- **`MUSIC_VIDEO_TYPE_PODCAST_EPISODE` counted as catalogue music.** It is now
  explicitly excluded from song evidence.
- **A watch-path timer loop produced two transactions.** A strict end-to-start
  playback-position reset is detected inside one media session or across Chrome
  recreation, carried to the final snapshot, and caps every kind to one.

### Fixed in v0.8.7 (contract reconciliation)

- **Looping verified Shorts were erased above 200%.** The rule now keeps the
  earned first viewing, logs a probable loop above 125%, and relies on the
  existing video-kind cap for exactly one transaction.
- **Session fragments were finalized before being carried.** Disappearance now
  opens a one-minute continuation window. A replacement consumes progress; only
  a real track end or continuation expiry finalizes.
- **`Confirmed on-chain` covered three different states.** Independent block,
  independent mempool, and accepted-without-confirmation now remain distinct,
  and the accepting node never confirms itself.
- **Stop left URL evidence alive and read removed-notification titles.** Both
  paths now obey the hard Stop boundary.
- **Queued entries could be signed with another account's key and lost
  silently.** Queue ownership, percent/video metadata, storage errors, and
  terminal retry exhaustion are now explicit.
- **UI and manual broadcast used different rules.** The Now threshold uses the
  configured value and manual session broadcasting runs the same eligibility
  decision as automatic finalization.
- **YouTube Music fallback length impersonated watch-page proof.** Provenance is
  now stored explicitly and legacy cache entries without it are refreshed.

### Partially fixed in v0.8.5–0.8.6 (the session-churn incident)

- **A video watched to 80% produced no entry.** Chrome recreates its media
  session mid-video around ad breaks and playlist transitions, which reset the
  play-time counter, so a 196-second video arrived as three fragments of 24%, 12%
  and 43% and every one was skipped. v0.8.5 carried play time across the restart,
  but still finalized each fragment first; v0.8.7 completes the fix by deferring
  that finalization.
- **`NxNN` episode numbering wasn't recognised** (0.8.6). `Season 6 Ep 19` and
  `S06E19` matched, but `3x1` and `11x24` didn't — the notation TV clip channels
  actually use — so two Walking Dead clips went on-chain as `kind: song`.

### Fixed in v0.8.4 (the frozen-node incident)

- **The app reported seven scrobbles it never made.** A node froze 77 minutes
  behind the chain and kept answering RPCs; "no error" was being read as success,
  and the tx id shown in History was computed locally rather than confirmed.
  v0.8.4 added transaction lookup. v0.8.7 separates block, independent mempool,
  and unanswered confirmation instead of displaying all three as confirmed.
- **No node freshness check.** Enforced at broadcast *and* when reading the chain
  head, since that's what the transaction is built from — a stale node's clock
  yields an expiration that's already in the past.
- **A per-block rate limit was treated as permanent** and the listens discarded.
  Transient refusals now queue and retry.
- **Chain errors logged uninterpolated templates** (`${a}`, `${n}`) instead of the
  actual values, which is what hid the stall for a whole session.
- **Dead node removed** (`hive-api.arcange.eu`) and the frozen one moved off the
  front of the failover list.

### Fixed in v0.8.3 (from the 2026-07-29 late run)

- **A promoted public video reached the chain as an entry.** It was
  indistinguishable in the evidence v0.8.3 consumed, so History gained **Never
  scrobble this again**, keyed by video id and bound on the manual path too.
  v0.8.9 adds the explicit visible-label path described in §6b.
- **The address bar went quiet for 13 minutes and nothing said so.** Cost five
  urls and four entries outright. Now warned after three consecutive tracks with
  no video id.

### Fixed in v0.8.2 (from reading the desktop extension)

- **A stale video id put a wrong `url` on-chain.** A Danger Man track latched the
  previous playlist entry's id because the address bar was 7 seconds late, and the
  title-based corroboration failed open — the page fetch for that id had timed
  out, so there was no title to compare. Now cross-checked against duration too
  (226 s against 193 s, which was sitting right there).
- **Enrichment failing no longer means no evidence at all.** The YouTube Music
  lookup is 10 KB against ~615 KB for the watch page and carries the length,
  category and listed flag, so it stands in when the page is unavailable.
- **`album` was never populated**, despite being a payload field since Phase 0.
- **A long video with a dash in its title could scrobble as a song.** Upper
  duration gates at 8 and 15 minutes, both adopted from the extension.
- **Songs MusicBrainz has never heard of are now recognised** via the YouTube
  Music catalogue, which matches on video id rather than on a parsed string.

### Fixed in v0.8.1 (from the 2026-07-29 Chrome run)

- **A shorts-feed ad reached the chain.** v0.8.0 gated the 10-second floor on the
  video resolving on its watch page, assuming ad creatives have none. They do.
  The gate is now `isUnlisted`, and an absent field fails it rather than passing.
- **Playback speed was never counted.** Played time was wall-clock seconds
  compared against the video's duration, so a trailer watched to 79% at 1.25×
  went on-chain as 67% — and at 2× a fully-watched video read 50% and produced no
  entry at all.
- **`FactsCache` didn't carry the new flags**, so a cache hit would have
  inherited a silent "public". Both are nullable and round-trip.

### Fixed in v0.8.0 (from the 2026-07-29 46-video run)

- **A third of the shorts feed produced no entry.** All 24 of the session's
  30-second-floor rejections were `/shorts/` URLs and none were `/watch` — the
  floor was doing nothing on the path it was written for. Verified shorts now
  count from 10 s.
- **10 shorts were skipped as "no duration"** while `videoDetails.lengthSeconds`
  for those same ids was already being fetched and discarded. Now the fallback.
- **Skips were invisible.** Every reason was computed and sent only to the event
  log, which made a strict rule and a broken app look identical. New **Not
  logged** tab.
- **Hashtag runs went on-chain as part of the title** (`katter — #guitar
  #dubstep #djdubstep #fnaf`), which also meant the same clip reposted with
  different tags dedups as a different listen. Trailing runs are stripped;
  a title that is *only* tags can no longer be a `song`.
- **The kind was computed and then never consulted when building credits**, so a
  trailer went out as `artist: "Fall 2: Deadpoint (2026) Official Trailer 2"` /
  `title: "Harriet Slater, Arsema Thomas"` — a film name and a cast list.
  `Artist - Track` splitting is now song-only.
- **`FactsCache` silently dropped `autoGenerated`**, so a cache hit downgraded a
  distributor-fed Art Track to whatever the title heuristics made of it. The
  session took 22 cache hits.

### Fixed since v0.3.x

- Stale/cross-tab site hints (per-session binding + taint, v0.4.0); trailers
  and film clips scrobbled as `song` (evidence-layered classifier, v0.4.1);
  the finalize-time address-bar race, which both lost video ids and attached
  the *wrong* id to a track (latching at track start, v0.4.2); looping shorts
  double-scrobbling (song-only double-listen, v0.5.0); tutorials and music-news
  bulletins that YouTube itself categorizes as *Music* (format evidence now
  outranks the category, v0.5.1); MusicBrainz lookups timing out in the
  rate-limit queue before their request started, which is why early testing saw
  "— (not checked yet)" everywhere (v0.5.1); TV episode compilations reading as
  `Artist - Track` offline (v0.5.2); **`url` silently missing on ~15% of
  scrobbles** because nothing re-ran identity when the address bar updated, so
  the video id was only ever picked up if the app happened to be open
  (v0.5.3) — and a notification hint with an unparseable host vetoing a
  perfectly good video id (v0.5.3); **the manual Broadcast button bypassing the
  dedup ledger**, which duplicated listens both against the later automatic
  finalize and against a second tap of its own (v0.5.4).
- **Brave on a physical device is verified** — a full day of tablet use
  (2026-07-23/24) confirmed the notification sub-text behaves as Chrome's does.
