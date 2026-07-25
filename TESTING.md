# Testing RustedWax v0.5.3

`dist/rustedwax-0.5.3.apk` — detection, key handling, automatic scrobbling
(Phase 3), plus the Phase 4 layer: the Stop switch, YouTube-only exclusivity,
evidence-based `kind` classification, the optional address-bar watcher,
watch-page enrichment, and MusicBrainz verification.

RustedWax is an independent app. It is not part of scrobble.life, Hive Scrobbler
or Web Scrobbler, and is not supported by them — see the README.

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
~/Library/Android/sdk/platform-tools/adb install -r dist/rustedwax-0.5.3.apk
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
   `Broadcast accepted by api.openhive.network — tx f0569fe0…`
2. **Log tab**, which also records the exact payload sent, tagged `[hive]`.
   Long-press or use **Export** to get the text off the device.

The id is computed on-device (`sha256(serialized tx)[0..20]`) because
`condenser_api.broadcast_transaction` returns an empty result on success. It's
the same id the explorers use — verified against dhive in the unit tests.

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
| `Couldn't reach a node` | Transport; all three nodes failed |

## 3. Real scrobble from YouTube

1. Play a YouTube video in Chrome (or Brave, on a real device).
2. Now tab → the session card appears.
3. Check the **payload** block — this is what would be written:
   - `artist` should be the *real* artist, not the channel. For
     `"Korn - Trash (Official Audio)"` on channel `KornVEVO`, expect
     `artist: Korn`, `title: Trash`.
   - **kind because** names the rule that decided `song` vs `video`;
     **category** shows what YouTube's own page said (needs address bar +
     lookups on); **musicbrainz** shows `✓ Artist — Title` on a confirmed
     match, `no match`, or `— (not checked yet)`.
   - Titles are normalized to the original recording: `(Live)`,
     `(Instrumental)` and `【Guitar Cover】` are stripped; `(Remix)` is kept.
4. **Broadcast this scrobble** → verify on hiveblocks as above.

The badge tells you whether it's broadcastable:

| Badge | Meaning |
| --- | --- |
| `payload OK` | Proven YouTube *with* a video id — full payload including `url` |
| `payload OK (no url)` | Proven YouTube via the notification origin; no `url` |
| `not proven YouTube` | Can't identify the site — deliberately refuses to guess |
| `no duration` | No DURATION, so percent-played can't be computed |

Non-browser apps (Spotify, podcast players) no longer appear at all — the log
records a single `ignored: <package>` line and nothing is read from them.

## 4. Automatic scrobbling (the Phase 3 feature)

Flip **Automatic scrobbling** on at the top of the screen, then play a track in
Chrome/Brave and **let it finish** — or skip to the next track once you're past
60%. A scrobble fires when the track *ends*, not when it crosses the threshold.

Then check the **History** tab: each entry shows percent played, status, and the
transaction id.

What to try:

| Case | Expected |
| --- | --- |
| Play a song past 60%, then skip to the next | One scrobble at the percent you reached |
| Play a song to the end | One scrobble at 100% |
| Skip out at 20% | Nothing — Log tab says "below 60% threshold" |
| Let the same **song** play twice through (~170%) | **Two** scrobbles: 100% and 70% — the double-listen rule |
| Let a **short** loop past 160% | **One** scrobble — videos are capped at one tx (deviation from upstream) |
| Replay a song immediately | Second one blocked — Log says "already scrobbled" |
| Play with the app closed | Still scrobbles; detection lives in the listener service |
| Turn on airplane mode, play a song through | Scrobble is queued, "N waiting to send" appears; turn networking back on and hit **Retry now** |
| A pre-roll ad | Ignored — Log says "shorter than 30s (probably an ad)" |
| Press **Stop** mid-track past 60% | Nothing broadcast, nothing in History — Stop never scrobbles on the way out |
| Press **Start** again | Monitoring resumes without touching Notification Access |
| YouTube in one tab + another audio site in another | Each session keeps its own origin; closing one tab doesn't kill the other's scrobble |
| Browse shorts with address bar access on | The video id is latched at track start — the `url` on-chain matches the short you watched, not the next one |

Watch the **Log** tab while testing. Every decision is recorded: `[finalize]`
when a track ends, then `[engine]` with either the payload broadcast or the
reason it was skipped.

### Does it survive the app being closed?

This is the one behaviour that needs a **physical device** — BlueStacks won't
tell you anything useful. Swipe the app away, keep playing music, and check the
History tab later. Detection runs inside the notification listener, which
Android keeps bound while the grant is held.

If the screen says "Waiting for Android to start the listener service", toggle
Notification Access off and on.

## 5. Compare against desktop

The strongest check: scrobble the same song from the desktop extension and from
the phone, then compare the two `custom_json` payloads field by field. They
should differ only in `platform`, `url`, `percent_played` and `timestamp`.
Any difference in `title` or `artist` is a metadata-filter gap worth reporting.

## Running the unit tests

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest
```

94 tests: 17 crypto/serialization vectors generated by dhive (the library
Keychain signs with), 24 kind-classification (including every misclassified
video from the 2026-07-24 field samples as pinned regressions), 16
title-parsing, 10 threshold/double-listen, 8 notification-hint binding, 7
address-bar corroboration, 6 watch-page extraction, 6 MusicBrainz matching.

## Known gaps in v0.5.3

- **Desktop and phone together will double-scrobble.** Dedup is per-device; the
  cross-device check is not implemented. If you run the extension and the app on
  the same account at the same time, expect two scrobbles per listen.
- **No biometric gate** on the stored key — an unlocked phone can sign.
- **`url` needs the address-bar watcher.** Without it, scrobbles carry
  `platform` but no `url` — and no category, so `kind` falls back to the offline
  heuristics plus MusicBrainz. With the watcher on, a missing `url` now logs its
  own reason (`broadcasting WITHOUT url — identity was …`); the remaining causes
  are a bar that shows only a host (a feed or channel page rather than a watch
  URL) and a bar that genuinely disagrees with what's playing.
- **No privacy mode.** All payloads are plaintext on-chain. Phase 5.
- **`Title | Channel`-shaped titles** can split as artist|track (a kitten video
  broadcast with the fields swapped). Mitigated for real music since v0.5.1 —
  MusicBrainz is asked about the swapped pair too, and a match rewrites both
  fields into their true roles — but an unrecognized recording still lands
  reversed. Mostly affects `kind: video` entries, where it's cosmetic.
- **Untagged music can be missed.** Since v0.5.1 the last-resort default is
  `video`, so a fan upload of a real song with a bare title, no category and
  no MusicBrainz entry is filed as a video. Deliberate: a missed song costs one
  playlist entry, a false song is permanent curation debt.
- **The site canonicalizes by first writer.** scrobble.life keeps one record
  per video id, seeded by the *first* scrobble's kind — a pre-fix `song` entry
  keeps that video listed as music for everyone, whatever later ops say. Not
  fixable app-side; raised with the site's developer.

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
  perfectly good video id (v0.5.3).
- **Brave on a physical device is verified** — a full day of tablet use
  (2026-07-23/24) confirmed the notification sub-text behaves as Chrome's does.
