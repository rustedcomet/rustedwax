# Testing RustedWax v0.8.10

`dist/rustedwax-0.8.10.apk` — detection, key handling, automatic scrobbling
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

Log 14 is the first broad v0.8.10 field-verification record. Its chain/profile
boundary passed completely, but it exposed four pre-broadcast losses and four
song-credit parser failures. The evidence and the documentation-only v0.8.11
regression plan are in §§12–13. No v0.8.11 runtime change exists yet.

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
~/Library/Android/sdk/platform-tools/adb install -r dist/rustedwax-0.8.10.apk
```

Verified debug artifact SHA-256:

```text
1f6508837723a2c2a8671eef85ac6546d844c415b89fff924479477da5e2823a
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

The ad case narrows “same accessibility snapshot.” The snapshot literally held
the new id and old `Sponsored` label together, so same-snapshot id binding was
necessary but not sufficient. URL generation and stabilization are now part of
the planned evidence rule.

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

## 13. Planned v0.8.11 regression matrix (not implemented)

This section is the acceptance plan for the next code patch. It does not
describe current v0.8.10 behavior and must not be marked fixed until both the
automated and physical-device gates pass.

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

### 13e. Physical-device release gate

After targeted tests, run the full uncached unit suite, assemble the APK and run
lint. Then perform another mixed watch-page/playlist/Shorts session with ads,
loops, 2× playback, a long video, title-parser fixtures and deliberate rapid
Short transitions. Reconcile by id against YouTube History and both live profile
sections.

The patch passes only with all of these simultaneously:

- zero qualifying history-backed viewing omitted by an app-side race;
- zero ad-like payloads and zero organic successors falsely vetoed as ads;
- one finalization for bounded duration refinements;
- no mixed identity/facts payloads;
- every emitted payload confirmed or reported in its honest transport state;
- every emitted YouTube payload visible in the correct section with its exact
  hyperlink; and
- the four parser fixtures carrying their intended, non-truncated credits.

## Running the unit tests

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest
```

The suite includes kind classification, title parsing, threshold/floor/loop
rules, continuation expiry tokens, frozen carried identities/ad evidence,
watch-page provenance, independent-node status aggregation, dhive-compatible
crypto/serialization vectors, payload construction, id resolution, rejected
live-id corroboration, exact YouTube ad labels, notification binding, YouTube
Music parsing, playback-speed scaling, dedup, and MusicBrainz matching.
Use the Gradle result as the authoritative count; this list describes coverage
rather than freezing a number that drifts whenever a regression is added.

## Known gaps in v0.8.10

- **Four qualifying log-14 viewings were lost before broadcast.** Two ended
  snapshots acquired the following track's id/facts, one continuous viewing was
  split by a one-millisecond duration refinement, and one organic Short
  inherited the preceding ad's stale `Sponsored` state. These are documented
  v0.8.11 targets, not fixed v0.8.10 behavior.
- **Nested/title-orientation song parsing has four new confirmed failures.** A
  dash inside parentheses can become the artist/track boundary, a quoted track
  with a trailing qualifier can miss the quoted matcher, and `Track - Artist`
  can remain reversed when MusicBrainz cannot repair it. The exact ids and
  expected fixture rules are in §12d.
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
- **`Track - Artist` ordering still lands reversed for songs.** MusicBrainz is
  asked about the swapped pair too, and a match rewrites both fields into their
  true roles — but an unrecognized recording (a Spanish-language upload
  MusicBrainz has never heard of, say) keeps the parser's guess that the left
  side is the artist. No longer affects `kind: video` at all as of v0.8.0:
  videos aren't split.
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
  can still scrobble. No channel/title heuristic fills that gap; §9a's mute is
  the fallback, and **Short clips** is the kill switch if you'd rather have
  neither.
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
  unresolved ended snapshot can still acquire successor facts/id downstream;
  that separate finalize-to-broadcast boundary is planned for v0.8.11 and is
  not listed as fixed here.

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
