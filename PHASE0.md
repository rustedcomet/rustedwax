# Phase 0 — Detection spike (YouTube in Brave)

The build in `app/` does one thing: report exactly what Android's media-session
API exposes about **YouTube playing in Brave**. No keys, no network, no crypto,
no broadcast. It exists to answer the questions that block Phase 1, before any
of the Hive layer gets ported.

## Scope

v1 is deliberately narrow: **YouTube, in Brave, on Android.** Other sites and
native apps are still observed by the probe as control cases — they show what a
rich media session looks like — but they are marked `control` and would never be
scrobbled.

## Build & install

Toolchain is set up: Android SDK 35 + build-tools 35.0.0 + platform-tools in
`~/Library/Android/sdk`, Gradle wrapper 8.11.1 generated, `local.properties`
written. Java comes from Android Studio's bundled JBR 21 — there is no `java` on
PATH, so export it first:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

Build (verified — produces a 9 MB `app-debug.apk`):

```bash
./gradlew :app:assembleDebug
```

Install to a connected device (`adb` is at `~/Library/Android/sdk/platform-tools/adb`):

```bash
./gradlew :app:installDebug
```

Launch **RustedWax** → grant Notification Access → play a YouTube video in
Brave → return to the app.

## What it records

- **Sessions tab** — one card per live media session: the fields that map onto
  `HiveScrobblePayload`, live position, accumulated *real* played time, percent
  of duration, the 60%-threshold verdict, the YouTube identification result, and
  the full raw `MediaMetadata` dump.
- **Log tab** — timestamped stream: sessions appearing/disappearing, every
  metadata change, every playback-state change, every identification attempt.
  Export shares `rustedwax-log.txt` off the device.

## The questions to answer

Play a YouTube video in Brave for at least 2 minutes, then export the log.

| # | Question | Where to look | Consequence if "no" |
| --- | --- | --- | --- |
| Q1 | Is `TITLE` populated? | card header, verdict badge | No payload is possible; the approach fails and the fallback is a WebView browser |
| Q2 | Is `DURATION` present and stable? | `duration` field, `no duration` badge | No percent-played → the 60%/160% rule can't be ported; falls back to a fixed "played N minutes" rule |
| Q3 | Can the session be **proven** to be YouTube? | `video id` / `proven by`, or `not proven YouTube` | v1 cannot ship as designed — see "If Q3 fails" below |
| Q4 | Does the session survive backgrounding Brave / screen off? | gaps in the log, `session ended` lines | Phase 3's foreground-service design needs rethinking |
| Q5 | Is `ARTIST` separate, or is it all in `"Artist - Title"`? | `artist` vs `title` | The Phase 5 metadata-filter port becomes mandatory, not optional |
| Q6 | Do seeks/skips corrupt accumulated played time? | `played` vs `position` after seeking | Tracker needs seek detection before thresholds are trustworthy |

Q1–Q3 are the blocking ones. Q4–Q6 shape Phase 3 but don't threaten the design.

### If Q3 fails

If Brave publishes no URI that proves YouTube, the options, in order of
preference:

1. Ask the user to confirm the source once per session in a notification action
   ("scrobble this as YouTube?"). Cheap, honest, no wrong data.
2. Scrobble Brave sessions with `platform: "brave"` and no `url` — accepts that
   a Spotify-web listen is indistinguishable from a YouTube one.
3. Drop the browser path and ship the native-app path (YouTube app, YouTube
   Music app) instead, which publishes far better metadata.

Option 2 is what earlier drafts assumed. It is **not** the default any more:
`YouTubeProbe` fails closed, because wrong attribution on an immutable chain is
worse than a missing scrobble.

## Test matrix

| Case | Why |
| --- | --- |
| youtube.com — music video, ~4 min | The main case |
| youtube.com — long video, >30 min | Does DURATION hold; does the 60% rule behave |
| music.youtube.com | Does anything distinguish it from plain YouTube (`isMusic`) |
| Autoplay to the next video | Does the track-change finalize fire cleanly |
| Backgrounded Brave + screen off, 5 min | Q4 |
| Seek forward/back mid-video | Q6 |
| YouTube app (control) | What "good" metadata looks like |

## Decisions locked in

These were open questions in DEVELOPMENT_PLAN §11; defaults chosen so Phase 1
isn't blocked on them:

- **Unproven source → skip, never guess.** `YouTubeProbe` fails closed.
- **`kind`** — `"video"` for youtube.com. Only `music.youtube.com`, when the
  evidence proves it, maps to `"song"`. The extension's connectors made a finer
  distinction that isn't reproducible here; overclaiming `song` would poison
  music-only indexes.
- **`scrobblePercent`** — exposed as a setting, default 60, keeping the
  extension's storage key name.
- **Privacy mode** — deferred to Phase 4, off by default.
- **Distribution** — GitHub releases. Play Store review of
  `NotificationListenerService` for a non-assistant app is a risk not worth
  taking on v1.

## Results

### Run 1 — 2026-07-23, Chrome on BlueStacks, YouTube video

Brave will not install on BlueStacks, so this run used Chrome
(`com.android.chrome`). Brave is a Chromium fork sharing the same media-session
plumbing, so the findings are expected to transfer — **confirm on Brave, on a
physical device, before Phase 3.**

| Q | Result | Evidence |
| --- | --- | --- |
| Q1 | **Pass** | `TITLE = "Una experiencia de sabor"`, `ARTIST = "Berard Panamá"` (the channel name lands in ARTIST) |
| Q2 | **Ambiguous** | `DURATION = 6061` — 6 seconds if ms. Either a pre-roll ad was playing, or the units aren't ms. Needs a known-length video to settle; the dump now prints both readings |
| Q3 | **Fail** | `unset: … ART_URI, ALBUM_ART_URI, DISPLAY_ICON_URI, MEDIA_URI, MEDIA_ID …` — every URI key empty. Artwork arrives as `ALBUM_ART = <bitmap 320x180>`, a raw bitmap with no video id |
| Q4–Q6 | Not measured | BlueStacks can't answer Q4 meaningfully; needs a physical device |

The probe behaved correctly: `not proven YouTube: no URI keys populated`, session
marked `[control, not scrobbled]`.

**Consequence:** the `i.ytimg.com/vi/<id>` hypothesis is dead for Chromium
browsers. 320x180 is exactly YouTube's thumbnail size, but a bitmap carries no
video id, so there is no route to `url` from the media session alone.

### The replacement hypothesis (in v0.1.1, untested)

Chromium renders its media notification with the **page origin in the sub-text**
("youtube.com"). We already hold Notification Access, so the listener can read
it. `RustedWaxNotificationListenerService` now harvests that field for target
browsers only and feeds it to `YouTubeProbe` as a third evidence route,
producing a new verdict:

- `Identity.SiteOnly` — proven YouTube, video unknown. Payload gets `platform`
  but **no `url`**. Verdict badge reads `payload OK (no url)`.

If this holds, v1 ships without per-scrobble URLs. That's a real loss — feed
cards can't link back to the video — but the scrobble itself (title, artist,
duration, timestamp, platform) is intact and matches what the extension writes
for sites where it can't resolve a URL either.

If it does *not* hold, fall back to the options under "If Q3 fails" below; the
one-tap confirmation is the preferred one.

### Run 2 — 2026-07-23, Chrome on BlueStacks, "Korn - Trash (Official Audio)"

The replacement hypothesis **holds**:

```
[notification] com.android.chrome → host=m.youtube.com subText="m.youtube.com"
               title="Korn - Trash (Official Audio)" text="KornVEVO"
```

| Q | Result | Evidence |
| --- | --- | --- |
| Q1 | **Pass** | `TITLE`, `ARTIST` both populated |
| Q2 | **Pass — units are milliseconds** | `DURATION = 208441` → 3:28, matching the real track length |
| Q3 | **Pass, via notification** | `subText="m.youtube.com"`. Note the host is `m.youtube.com`, not `youtube.com` — the suffix check in `YouTubeProbe` handles it |
| Q5 | **Fail — filtering is required** | `ARTIST = "KornVEVO"` is the *channel*, not the artist. The real artist is inside the title: `"Korn - Trash (Official Audio)"` |

Q3 and Q2 clear the design. Q5 is the new work.

#### Consequence 1 — identity must be resolved late

The log shows identity decided at `00:46:34.010` (`no notification hint yet ←
would be SKIPPED`) and the notification arriving at `00:46:34.298` — 288 ms
later. The media-session callback wins the race, so the **first** verdict for a
track is routinely made blind.

Fixed in v0.1.2 for the spike (identity re-runs when a hint lands). For Phase 3
the rule is stronger: **resolve identity at finalize time**, not at track start.
The 60% threshold fires minutes later, by which point the hint is always
present. A track must never be skipped because the notification lost a race.

#### Consequence 2 — the metadata-filter port moves into Phase 3

Broadcasting run 2 as-is would write `artist: "KornVEVO"`, `title: "Korn - Trash
(Official Audio)"` — wrong on both fields, and it would not match a desktop
scrobble of the same song, breaking cross-device dedup and any per-artist index.

So `core/object/pipeline/` and the YouTube-specific rules from
`@web-scrobbler/metadata-filter` are **required for v1**, not a Phase 5 polish
item. Minimum needed:

- Split `"Artist - Title"` out of the video title.
- Strip `(Official Audio)`, `(Official Video)`, `[HD]`, `(Lyrics)` and friends.
- Treat the channel name as a *fallback* artist only, and prefer the parsed one.
  A `…VEVO` channel is a strong signal the title carries `Artist - Title`.

This is the one place where copying upstream matters most: those regex tables
encode years of accumulated edge cases, and they port to Kotlin mechanically.

### Run 3 — 2026-07-23, first real broadcasts (gate G4 ✅)

Two scrobbles broadcast from the phone and confirmed on-chain in
`@skiptvads`'s account history. **Gate G4 passes** — local signing, canonical
grinding, serialization and broadcast all work end to end against mainnet.

Comparing the phone's ops against the desktop extension's ops earlier the same
day surfaced three fidelity gaps, all fixed in v0.2.2:

| Gap | Phone (v0.2.1) | Desktop extension | Fix |
| --- | --- | --- | --- |
| Escaped slashes | `"hivescrobblesai\/1.0"` | `"hivescrobblesai/1.0"` | Android's `org.json` escapes `/`; `JSON.stringify` doesn't. Replaced with hand-rolled escaping. The JVM `org.json` in unit tests didn't reproduce it, so tests passed while the device wrote something else — **only the on-chain check caught this** |
| Combined promo suffix | `"Thoughtless (Official HD Video)"` | `"Thoughtless"` | The filter matched `(Official Video)` and `(HD)` separately but not combined. Rewritten to strip a bracketed group when *every* word in it is promotional |
| `kind` | `video` | `song` | Desktop marks these `song`; a mismatch splits one track across two kinds. Now infers music from a VEVO/Topic channel or an `Artist - Track` title |

Still divergent, and not fixable from a media session: desktop carries
`url: https://youtu.be/<id>`, the phone has no `url` at all.

### Still to record

Phase 0 is **closed for Chromium-on-emulator**. The remaining verification is
Q3/Q4 on **Brave, on a physical device** — Brave could plausibly set a different
notification sub-text, and Q4 (surviving backgrounding and screen-off) cannot be
measured on BlueStacks at all.

## Files

| File | Role |
| --- | --- |
| `spike/RustedWaxNotificationListenerService.kt` | Stub — exists only to hold the Notification Access grant |
| `spike/SessionProbe.kt` | Watches sessions, tracks position/played time, logs everything |
| `spike/MetadataDump.kt` | Dumps every metadata key, including unset and non-standard ones |
| `spike/YouTubeProbe.kt` | Q3: proves (or fails to prove) that a Brave session is YouTube |
| `spike/EventLog.kt` | Ring buffer + exportable file log |
| `spike/SessionSnapshot.kt` | Observation model, field names mirroring `HiveScrobblePayload` |
| `ui/MainScreen.kt` | Measurement UI — deleted when Phase 3 lands |

All of `spike/` is throwaway except `SessionProbe`'s position/played-time logic
and `YouTubeProbe`, which graduate into `core-detect/`.
