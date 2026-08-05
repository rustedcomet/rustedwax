# Native YouTube playlist-derived video identity — v0.9.5 field record and patch contract

This document records the 2026-08-04 Samsung SM-A125M / Android 12 (SDK 31)
investigation into why native YouTube tracks keep resolving to no video id, or
to the *wrong* video id. It is the focused contract for RustedWax v0.9.5,
version code 41.

The v0.9.1 foreground-Short result remains immutable in `PHASE_NATIVE_SHORTS.md`
and the v0.9.2 structured-music record remains immutable in
`PHASE_NATIVE_PLAYLIST.md`. Canonical shared invariants remain in
`BEHAVIOR_CONTRACT.md`.

Device under test: SM-A125M, Android 12, YouTube 21.30.209, RustedWax 0.9.4
(version code 40) installed and monitoring.

---

## 1. The exact video id is not observable. This is now closed.

Every surface a third-party app can reach was measured on the device. None
carries the id.

| Surface | Measurement | Result |
| --- | --- | --- |
| `MediaMetadata` | live `dumpsys media_session` + exported field logs | 6 keys only: `TITLE`, `ARTIST`, `ALBUM_ARTIST`, `DURATION`, and the non-standard `com.google.android.youtube.MEDIA_METADATA_VIDEO_WIDTH_PX` / `HEIGHT_PX`. No `MEDIA_ID`, `MEDIA_URI`, artwork URI. |
| MediaSession queue | `dumpsys media_session` | `queueTitle=null, size=0` |
| `sessionActivity` PendingIntent | `dumpsys activity intents` | `act=android.intent.action.MAIN cat=[LAUNCHER] cmp=…InternalMainActivity (has extras)` — generic launcher intent; extras are opaque to third parties |
| Notification | `dumpsys notification --noredact` | `allowNoti=false` for YouTube on this device; the media notification carries no id regardless |
| Accessibility tree | `uiautomator dump`, 49 098 bytes, 73 distinct resource ids | no 11-character id token anywhere |
| **YouTube `MainAppMediaBrowserService`** | **throwaway probe APK, framework `android.media.browse.MediaBrowser`** | **connects, then returns root `__EMPTY_ROOT__` with 0 children — for both the default root and the `EXTRA_RECENT` root hint** |
| YouTube Music `MusicBrowserService` | same probe | `onGetRoot` returned null — connection refused outright |

The MediaBrowser probe was the last open question. `__EMPTY_ROOT__` is
YouTube's "caller is not on the allowlist" sentinel: the bind succeeds so the
client does not crash, and the browse tree is empty. There is no direct route
to the currently playing video id, and no further surface worth testing.

**Consequence.** Title/artist/duration recovery by open-world search cannot be
made correct by tuning the matcher. The fix must reduce the candidate set, not
sharpen the comparison.

---

## 2. The current search route is producing wrong ids, not merely missing ones

The user's playlist `Reggaeton 2016,17,18` (`PL7NMzffnWK8RMWFO3rZABAgN-Rk9pCkEm`,
owner `Jhonny Gutierrez`) was fetched and every field-validated resolution was
checked against its membership.

| Track | Search route resolved | In the playlist? | Playlist entry |
| --- | --- | --- | --- |
| Ella Y Yo | `CGjuWHEPxgc` | yes (#52) | correct |
| **Unica** | `7uxTya2PX3c` | **no** | `YbZwlNmnUvw` (#43) |
| **Criminal** | `VqEbCxg2bNI` | **no** | `4ns8D959YtA` (#30) |

Measured watch-page facts:

```
YbZwlNmnUvw  "Unica"                          218 s  Ozuna - Topic            52 478 032 views
7uxTya2PX3c  "Ozuna - Única (Audio Oficial)"  218 s  Temazos de Reggaeton…       746 016 views

4ns8D959YtA  "Criminal"                       273 s  Natti Natasha - Topic   119 481 968 views
VqEbCxg2bNI  "Natti Natasha ❌ Ozuna - …"      273 s  NATTI NATASHA         2 851 011 392 views
```

Both pairs are **duration-identical**. No duration tolerance, however tight,
separates them. In the `Unica` case the search route selected a 746 K-view
third-party re-upload over the official 52 M-view Art Track that is actually in
the playlist.

**Provenance caveat.** The exported logs in `debug/` do not contain the native
sessions for `Unica` or `Criminal`; the one `VqEbCxg2bNI` line present
(`rustedwax-log (17).txt:2100`) is a **Chrome** observation whose id came
straight from the address bar and is therefore correct by construction. It is
not proof about the native matcher. What is proven is structural: title and
duration cannot separate these uploads, and the search route demonstrably
prefers uploads that are not in the playlist being played.

---

## 3. The lock-screen / minimize symptom has the same root cause

Reproduced live with `RustedWax:D` logcat attached.

```
before lock : state=3 (PLAYING) position=6090  speed=2.0 actions=8631
while locked: state=1 (STOPPED) position=0     speed=1.0 actions=8192
```

RustedWax logged:

```
[session] − com.google.android.youtube (session ended)
[session] com.google.android.youtube [session ended] waiting 60s for a replacement session before finalizing
[native-shorts] no active native YouTube accessibility root
```

The session is torn down and a replacement appears with `playedMs = 0`. Whether
the earlier progress is rescued is decided by `TrackProgressCarry`:

```kotlin
if (YouTubeProbe.isNativePackage(packageName) && !trackIdentity.hasExactSourceItemId) {
    return null
}
```

With no exact video id, `remember()` and `claim()` both return null, each
fragment is scored against the 60 % threshold separately, and both fail. This
guard is correct — without an id there is no proof the replacement is the same
upload. Supplying an exact id therefore fixes the split as a side effect.

---

## 4. The playlist is the closed candidate set

### 4.1 What the accessibility tree exposes

With the queue panel **collapsed** — the ordinary watch view — the tree carries:

```
yt:playlist_name      t='Reggaeton 2016,17,18'
yt:position           t=' • 61/120'   cd='61 out of 120'
yt:next_video_title   t='Anuel AA - Ayer 2 (Official Lyric Video) ft. J Balvin, …'
yt:privacy            cd='Anyone can search for and view'
                      cd='like this video along with 174,330 other people'
                      cd='Ronald El Killa, Official Artist Channel 84.2K subscribers'
```

With the queue panel **expanded**, the same information appears under different
ids: `yt:title` + `yt:subtitle` + `yt:position` in the panel header, plus
`yt:playlist_panel_video_item` rows carrying `yt:title`, `yt:channel`,
`yt:video_info` ("110M views • 8 years ago") and `yt:duration`.

**Both layouts must be captured.**

> **Correction (recorded after field testing).** An earlier draft of this
> document claimed the collapsed bar "is visible regardless of scroll
> position". That is **false**. The same video in the same session produced no
> playlist node scrolled down and a complete bar scrolled to the top. The bar is
> scroll-dependent, and so is the watch metadata block (likes, views). Neither
> is a dependable *per-observation* signal; §6.1 draws the consequence.

### 4.2 Position agreement, verified twice

Header read `61/120`; playlist entry 61 is `XdMUC55g6wY`
"Quien Te Va a Amar Como Yo" / Ronald El Killa / 3:47, which is what the device
was playing (`0:02 / 3:46`). Entry 62 is
"Anuel AA - Ayer 2 (Official Lyric Video) ft. J Balvin, Nicky Jam, Cosculluela,
DJ Nelson", matching `yt:next_video_title` verbatim.

With shuffle off, the app's queue order equals the canonical page order exactly
(`Se Preparó`, `Felices los 4`, `Si Tu Novio Te Deja Sola` = entries 1, 2, 3).

### 4.3 Position must nevertheless never be used as an index

Shuffle was toggled on the device and the tree re-dumped:

```
shuffle OFF : yt:shuffle sel=false ck=false cd='Shuffle'   queue: Se Preparó | Felices los 4 | Si Tu Novio Te Deja Sola
shuffle ON  : yt:shuffle sel=false ck=false cd='Shuffle'   queue: Se Preparó | Ella Y Yo (feat. …) | Unica
```

Two facts follow, and together they are decisive:

1. **Shuffle state is not observable.** `selected`, `checked` and
   `contentDescription` are byte-identical in both states. Only the icon tint
   changes, which accessibility does not expose.
2. **`yt:position` still read `1/120` while the queue was demonstrably
   shuffled.**

Therefore position-as-index can silently address the wrong entry and there is
no way to detect the condition. **Closed-set matching against the playlist's
entry set is the only permitted resolution mechanism.** Position and
`next_video_title` may be logged as corroboration; they must never gate a
scrobble.

### 4.4 The shipped parser already works

`PlaylistPageParser.entries` / `.match` were exercised against the live
2026-08-04 playlist markup: **100 of 100 entries yielded both a duration and a
channel**. Every previously failing track resolves to exactly one entry, within
the existing five-second tolerance, with the channel equal to the MediaSession
artist:

| MediaSession title | Measured | Resolved | Page | Channel |
| --- | ---: | --- | ---: | --- |
| Se Preparó | 188 | `JbVP_aZiFSY` | 189 | Ozuna |
| Felices los 4 | 230 | `9rp10tWxZnk` | 230 | Maluma |
| Si Tu Novio Te Deja Sola | 244 | `_WIKDR4K5b4` | 244 | Bad Bunny |
| No Quiere Enamorarse | 213 | `_5LfeT7H1mk` | 214 | Ozuna |
| Criminal | 273 | `4ns8D959YtA` | 274 | NATTI NATASHA |
| Unica | 218 | `YbZwlNmnUvw` | 219 | Ozuna |

`PlaylistPageParser` requires **no change**. Neither does
`VideoIdResolver.resolveEvidenceFromPlaylist`.

### 4.5 The only missing link

`resolverContext.playlistId` is populated exclusively by `UrlWatcherService`
from a browser address bar. Native sessions have no URL, so the value is always
null at `ScrobbleEngine.kt:694` and the playlist route never executes.

The patch does **not** write into `playlistId`. That field stays proven-URL
evidence and nothing else, so a native observation can never be mistaken for an
address-bar fact. Instead the native watch screen contributes a separate
`nativePlaylistName` (plus owner and total), which the engine resolves to an id
at finalization and then hands to the existing, untouched
`resolveEvidenceFromPlaylist`.

---

## 5. Name → playlist id is safe

A playlist-filtered YouTube search (`&sp=EgIQAw%3D%3D`) was measured:

| Query | Playlist results | Exact-title matches |
| --- | ---: | ---: |
| `Reggaeton 2016,17,18` | 20 | **1** (`PL7NMzffnWK8RMWFO3rZABAgN-Rk9pCkEm`, 120 videos) |
| `Reggaeton` | 20 | 0 |
| `Workout` | 18 | 0 |

Requiring an exact normalized title match therefore either yields exactly one
playlist or yields none. Generic names fail closed by construction.

---

## 6. Measured bounds and hazards the patch must respect

1. **Absence of the bar proves nothing, and must never drop the latch.** Four
   independent mechanisms hide it while the user is still in the playlist, and
   none of them is distinguishable from actually leaving:

   | mechanism | measured |
   | --- | --- |
   | pre-roll ad | bar gone for ~16 s |
   | **miniplayer** | bar gone for 103 s while the playlist kept playing |
   | scroll position | same video, same session: gone scrolled down, present at top |
   | fullscreen | no watch panel on screen |

   The first implementation used a 30 s grace and then dropped. In the field it
   dropped a correct latch during the miniplayer, and the following track fell
   through to search, which resolved `Chulo Sin H` to `JZHOizv9G4E` — **not**
   the `2rZtRUDQXqg` that is in the playlist. The latch is now replaced only by
   positive contradiction (a different playlist name) or an explicit reset.

   The miniplayer must additionally be reported as *unobservable* rather than as
   "no playlist here", and `watch_player` / `player_view` cannot make that
   distinction — both survive into the miniplayer. `watch_panel` does:

   | state | `watch_panel` | `watch_player` | `modern_miniplayer_*` |
   | --- | --- | --- | --- |
   | playlist watch, queue open | yes | yes | no |
   | playlist watch, collapsed | yes | yes | no |
   | standalone video | yes | yes | no |
   | **miniplayer over browse** | **no** | yes | yes |

2. **`next_video_title` exists without a playlist** (autoplay up-next was
   observed on a standalone video). It is not a playlist indicator. Only
   `yt:playlist_name` / `yt:position` are.
3. **A stale latch is the safer error.** It cannot produce a wrong scrobble on
   its own — resolution still requires exactly one entry in that playlist
   matching the finalized title, artist and duration, and a track from elsewhere
   matches nothing and falls through. Dropping the latch hands the track to the
   search route, measured selecting the wrong upload three separate times
   (`Unica`, `Criminal`, `Chulo Sin H`).
4. **Deep-linking `watch?v=…&list=…` does not attach playlist UI.** Only
   entering through the playlist does. The capture must not assume the URL form
   implies context.
5. **The playlist page renders at most 100 entries.** For this playlist that is
   the complete available set (100 renderable + 19 `unavailable` markers ≈ the
   claimed 120). A 292-video playlist rendered 99 and exposed a working
   continuation token that chains a further 100 per POST to
   `/youtubei/v1/browse`. Continuation is **out of scope for v0.9.5** — the
   pre-existing browser playlist route has the same bound — and is recorded
   here so it remains a small follow-up.
6. **Accessibility is foreground-only.** Backgrounded or locked, the tree is
   gone (`no active native YouTube accessibility root`). The playlist id and its
   fetched entry set must be latched and cached so later tracks resolve without
   a live tree.
7. `uiautomator dump` requires an idle window and could not run while a video
   was playing, so every tree in §4 was captured with playback paused. The real
   service reads `rootInActiveWindow` and has no idle requirement.

   **Resolved.** v0.9.5 was installed on the SM-A125M and the playlist was
   entered through "Play all" with playback left running. Measured:

   ```
   [native-playlist] latched native playlist "Reggaeton 2016,17,18" (position 1/120)
   [native-playlist] playlist bar not on screen: no native YouTube playlist bar on screen
   …
   [native-playlist] native playlist bar re-observed
   ```

   Capture under live playback works. The pre-roll removed the bar for roughly
   16 s and the latch was retained, as §6.1 requires.

---

## 7. v0.9.5 patch contract

### 7.1 Scope

Applies **only** to `com.google.android.youtube`. `com.google.android.apps.youtube.music`
was not probed in this investigation; its resource ids must not be assumed to
transfer and it stays on the existing path.

The browser path is untouched. This is enforced by Android, not by our code:
`accessibility_service_config.xml` pins the browser watcher to the Chrome and
Brave packages, and `native_shorts_accessibility_service_config.xml` pins the
native observer to `com.google.android.youtube`. The two services are
structurally incapable of reading each other's packages.

Unchanged: `UrlWatcherService`, `UrlEvidence`, `accessibility_service_config.xml`,
`PlaylistPageParser`, `VideoIdResolver.resolveEvidenceFromPlaylist`,
`SearchResultsParser`, `WatchPageParser`, and the whole scrobble/Hive layer.

### 7.2 Required behaviour

A native playlist context may be latched only when all of the following hold:

1. the foreground package is `com.google.android.youtube`;
2. a visible `yt:position` node parses as `N/M` with `1 ≤ N ≤ M`;
3. a playlist name is present, from `yt:playlist_name` (collapsed bar) or from
   the `yt:title` adjacent to that `yt:position` (expanded panel header);
4. the name is non-blank and within a bounded length.

A latched context may be converted to a playlist id only when:

5. a single bounded playlist-filtered search is performed for that name;
6. **exactly one** result's normalized title equals the observed name; zero or
   several refuse;
7. where the tree supplied an owner (`yt:subtitle`) or a total, a result that
   contradicts them is rejected.

A latched playlist id authorizes a scrobble only when:

8. `PlaylistPageParser.match` finds **exactly one** entry matching the finalized
   title, artist and duration under the existing tolerances — a second matching
   entry refuses;
9. every existing gate downstream still passes unchanged: frozen-snapshot
   corroboration, canonical link construction, threshold, kind, mute, dedup,
   queue and source-epoch signing.

The latch must be replaced or cleared when, and only when:

10. a *different* playlist name is observed — immediately; or
11. monitoring stops, the package is disabled, the source epoch changes, the
    session is torn down, or the accessibility service disconnects.

Absence of the playlist bar — for any duration, on a readable screen or an
unreadable one — must **not** drop the latch (§6.1).

Position and `next_video_title` are recorded in the event log for diagnosis and
**must not** influence resolution.

`RD…` mixes and `LL` / `WL` remain refused by the existing
`UNFETCHABLE_PREFIXES` guard.

### 7.3 Non-goals

Playlist continuation beyond 100 entries; YouTube Music; any change to browser
behaviour; any relaxation of the uniqueness rule.

---

## 8. Measured result of v0.9.5

First field run on the SM-A125M, playlist entered through "Play all", playback
left running:

```
[resolve] native playlist "Reggaeton 2016,17,18" → PL7NMzffnWK8RMWFO3rZABAgN-Rk9pCkEm (120 videos)
[resolve] playlist PL7NMzffnWK8RMWFO3rZABAgN-Rk9pCkEm → 100 entries cached
[resolve] resolved "Se Preparó" → JbVP_aZiFSY from playlist PL7NMzffnWK8RMWFO3rZABAgN-Rk9pCkEm
[finalize] com.google.android.youtube [track change] Se Preparó — played 189s of 188s (up to 2.0× speed)
[engine] broadcasting: {…"title":"Se Preparó","artist":"Ozuna","album":"Odisea",
          "url":"https://www.youtube.com/watch?v=JbVP_aZiFSY"}
[engine] scrobbled (block): Ozuna — Se Preparó — tx 1bf9d11cda7690e014df39ad81e8fe49110ef2bc
```

`Se Preparó` is the first row of the `PHASE_NATIVE_PLAYLIST.md` failure table —
previously "no verified id among 61 search candidates". It reached the chain with
the id this document predicted from the playlist page, using one playlist search
and one playlist fetch instead of a per-track candidate sweep.

The same run also produced the counter-example that forced the §6.1 rewrite: with
the latch wrongly dropped in the miniplayer, the next track `Chulo Sin H`
resolved through search to `JZHOizv9G4E`, while the playlist holds
`2rZtRUDQXqg` (entry #80). Playlist route correct, search route wrong, on
consecutive tracks in one session.

After the §6.1 fix, a 90-second miniplayer period produced **zero** latch drops.

## 9. Verification

1. **Unit tests — done.** 454 tests, 0 failures (30 added): the parser across
   both layouts, the miniplayer, ad absence, malformed positions, queue-row
   confusion and invisible bars; the latch across replacement, absence of both
   flavours and reset; the name→id resolver across unique, zero, several,
   contradicting owner and contradicting total.
2. **Build and lint — done.** Debug APK builds; lint reports zero errors.
3. **Field test on the SM-A125M — done.** Second run, after the §6.1 fix, with
   a miniplayer period in the middle and zero latch drops:

   ```
   [resolve] resolved "Si Tu Novio Te Deja Sola" → _WIKDR4K5b4 from playlist PL7NMzffnWK8RMWFO3rZABAgN-Rk9pCkEm
   [resolve] resolved "No Quiere Enamorarse"     → _5LfeT7H1mk from playlist PL7NMzffnWK8RMWFO3rZABAgN-Rk9pCkEm
   [resolve] re-fetching pre-resolved native carry authority 9rp10tWxZnk (RAW_TITLE_CHANNEL) for "Felices los 4"
   ```

   All four tracks in the `PHASE_NATIVE_PLAYLIST.md` failure table now resolve,
   each to the id §4.4 predicted from the playlist page. Repeat plays were
   correctly refused by the dedup ledger
   (`[engine] skipped: already scrobbled [felices los 4|maluma|496074]`).

   `Si Tu Novio Te Deja Sola` also exercised the fragment case — finalized at
   49 s and again at 190 s of 244 s — and the exact playlist id let it be
   carried under `RAW_TITLE_CHANNEL`, which is the §3 lock-screen fix working.

4. **Session-teardown carry — measured.** `Báilame (Remix)` (playlist entry #9)
   was played for ~55 s, YouTube was backgrounded with HOME for 25 s, then
   reopened and resumed. This is the §3 failure path: YouTube tears the
   MediaSession down and a replacement appears with `playedMs = 0`.

   ```
   [session] − com.google.android.youtube (session ended)
   [session] com.google.android.youtube [session ended] waiting 60s for a replacement session before finalizing
   [session] + com.google.android.youtube (YouTube) ← native YouTube
   [native-carry] com.google.android.youtube established immutable carry authority 9mxGT0j1e1U for "Báilame (Remix)"
   [session] com.google.android.youtube resumed "báilame (remix)|release - topic|" after a session restart — carrying 125s of play time forward
   [finalize] com.google.android.youtube [track change] Báilame (Remix) — played 213s of 217s (up to 2.0× speed)
   [engine] broadcasting: {…"percent_played":99,"url":"https://www.youtube.com/watch?v=9mxGT0j1e1U"}
   [engine] scrobbled (block): Release - Topic — Báilame (Remix) — tx 04b0a02e87e301ba53fa268e9a5b26e6deb417f0
   ```

   **125 seconds were carried across the teardown**, keyed on the immutable id
   the playlist supplied. Under v0.9.4 this was two fragments of roughly 125 s
   and 88 s, each below the 60 % threshold, both discarded — the symptom the
   user reported as "it stops indexing when I lock the screen or minimize".

   The resolved id checks out independently: `9mxGT0j1e1U` is "Báilame (Remix)",
   217 s, `Release - Topic` — an exact three-field match with the MediaSession
   tuple, and entry #9 of the playlist.

   The conservative path is intact alongside it: in the same run,
   `exact-ID-less same-metadata duration changed from 12s to 201s; discarded the
   prior fragment with zero carry` — fragments without a proven id still carry
   nothing.

5. **`Te Busco` — the Topic-channel alias.** The playlist resolved
   `7J6xA1_f8as` correctly (entry #23, 234 s), but the scrobble was silently
   dropped because YouTube spells one channel two ways: the playlist page and
   the MediaSession both say `Cosculluela El Principe`, the watch page says
   `Cosculluela - Topic`. Stripping `" - Topic"` leaves `Cosculluela`, still not
   the full stage name, so the enriched-watch-facts pass vetoed a correct id.

   Three defects, each found by the field test of the one before it:

   | # | Defect | Fix |
   | --- | --- | --- |
   | 1 | The watch page's alternate spelling of an already-corroborated channel vetoed the id | Relax the *channel* comparison only, when the id is playlist-verified, the playlist entry's channel already matched the session artist, and title and duration still agree. Native sessions only. |
   | 2 | The carry authority recorded the route as `RAW_TITLE_CHANNEL`, losing playlist provenance, so finalization re-derived from the watch page and hit the same veto | New `NativePreResolvedRoute.PLAYLIST`, re-verified against the playlist that produced the id and required to return the same id |
   | 3 | The observer is event-driven; ordinary playback emits no accessibility events, so six minutes of playback produced **zero** observations and the playlist was never captured | Poll every 5 s while nothing is latched; stop once latched |

   Measured after all three:

   ```
   [finalize] [session continuation expired] Te Busco — played 229s of 234s
   [resolve]  re-fetching pre-resolved native carry authority 7J6xA1_f8as (PLAYLIST) for "Te Busco"
   [resolve]  resolved "Te Busco" → 7J6xA1_f8as from playlist PL7NMzffnWK8RMWFO3rZABAgN-Rk9pCkEm
   [engine]   broadcasting: {…"percent_played":98,"url":"https://www.youtube.com/watch?v=7J6xA1_f8as"}
   [engine]   scrobbled (block): Cosculluela El Principe — Te Busco — tx b2cdc7ca8449b521ab20add36c7da590cecb021b
   ```

### Operational note: updating the APK revokes accessibility

Android disables an app's accessibility services when its APK is replaced, so a
new build cannot inherit a grant given to the old one. Both RustedWax services
were silently switched off partway through this session, and
`dumpsys accessibility` still *listed* them — that list is installed services,
not enabled ones. The authoritative check is:

```
adb shell settings get secure accessibility_enabled          # 1 = on
adb shell settings get secure enabled_accessibility_services # must name both services
```

With them off, native playback loses the playlist route entirely and every track
falls back to search. **After installing a new build, both services must be
re-enabled by hand**, and "it suddenly stopped identifying videos after an
update" should be diagnosed here first.

### Still outstanding

- The controlled test above used **minimize**, not the keyguard. The keyguard
  was not exercised because the test device is PIN-locked and unattended. The
  code path is the same one — MediaSession teardown and replacement — and the
  keyguard adds nothing to it, but that is inference, not measurement.
- YouTube Music remains unprobed and out of scope (§7.1).
- Playlist continuation past 100 entries remains out of scope (§6.5).

---

## 10. The remaining gap: identity outside a playlist

### 10.1 The URL is mandatory. This closes off the easy answer.

`HiveScrobblePayload.url` is nullable and the codebase's own history records
that unlinked entries used to be broadcast ("fifteen of sixty scrobbles were
broadcast with no `url`"). That is **not** an acceptable fallback here: the
indexer builds a page around the video and embeds the player, e.g.

```
https://scrobble.life/music?v=7J6xA1_f8as
```

An entry without a video id has no page to render. So `hasRequiredYouTubeUrl`
stays exactly as it is, and a listen that cannot be resolved to an exact video
id must continue to stay off-chain rather than be broadcast unlinked.

Equally, the uniqueness rule must **not** be relaxed to raise coverage. Three
separate measured cases this session show what that would cost:

| Track | Search route picks | Actually playing |
| --- | --- | --- |
| Unica | `7uxTya2PX3c` (746 K-view re-upload) | `YbZwlNmnUvw` (52 M-view Topic track) |
| Criminal | `VqEbCxg2bNI` | `4ns8D959YtA` |
| Chulo Sin H | `JZHOizv9G4E` | `2rZtRUDQXqg` |

Every one is duration-identical to the right answer. A relaxed rule would put
those URLs on an immutable chain, and each would render the wrong video on the
scrobble page.

### 10.2 What is left to exploit

Inside a playlist the closed entry set makes identity exact. Outside one, the
resolver is back to open-world search over title/artist/duration, which is
exactly where the wrong ids above came from.

The watch screen still publishes signals the resolver does not currently read.
Measured 2026-08-04 on an ordinary (non-playlist) watch screen:

```
cd='Tito "El Bambino", Official Artist Channel 2.85M subscribers'
cd='like this video along with 173,005 other people'
```

The **exact like count** is the strong one. Two uploads of the same song
essentially never share an exact like count, and every candidate watch page
publishes its own, so it is directly comparable — the same disambiguation role
the playlist plays, applied one track at a time.

Known limits, which must shape the design rather than be discovered later:

1. **Scroll-dependent.** The metadata block is only in the tree when the watch
   page is near the top. Scrolled to comments, in the miniplayer, in fullscreen
   or with the screen off, it is absent — the same four mechanisms as §6.1.
2. **Per-track, not per-session.** The playlist is captured once and serves 100
   tracks. This must be captured afresh for every track, so it is far more
   fragile and will simply be unavailable for pocket listening.
3. **Like counts drift.** Comparison needs a tolerance, and a cached value must
   not be compared against a page fetched much later.

So this raises coverage for videos the user is actually looking at, and does
nothing for backgrounded playback outside a playlist. That residue is real and
should be stated plainly rather than engineered around.

### 10.3 Signed-in watch history — the route that actually closes the gap

**Owner decision, 2026-08-04: approved.** Requiring users to sign in to their
Google account inside the app is acceptable, with a plain disclaimer explaining
what the sign-in is for. This is the headline feature of v0.9.6.

`youtube.com/feed/history` names the exact video id for playback the id-less
surfaces cannot describe — backgrounded, screen off, outside any playlist. It is
the only known route that produces an exact id for *arbitrary* native playback.

Measured unauthenticated on 2026-08-04:

```
http=200 bytes=768396  signin wall: yes  ytInitialData: yes  videoIds: 0
```

So the endpoint returns the familiar `ytInitialData` document — the same shape
`WatchPageParser.extractJson` and the existing renderer walkers already handle —
and yields ids only with a session attached. No new parsing strategy is needed,
only the session.

**Constraints that must be designed for, not discovered:**

1. **The YouTube app must itself be signed in, to the same account** as the
   in-app WebView. Signed out, or a different account, and the history read is
   empty or belongs to someone else. This must be detected and surfaced, never
   guessed around.
2. **Watch history must not be paused**, and playback must not be in the YouTube
   app's incognito mode. Either records nothing. Both need an explicit,
   user-visible failure reason.
3. **The official Data API does not expose watch history.**
   `channels.list → contentDetails.relatedPlaylists.watchHistory` has been
   non-functional for years, so this is necessarily an authenticated page read,
   not an API call. Verify before building on the contrary assumption.
4. **A Google session cookie is far more sensitive than anything the app holds
   today.** It must live in the WebView cookie store or the existing encrypted
   storage, never be written to the event log, never leave `youtube.com`, and be
   revocable from within the app. The app already has `KeyVault` and
   `security-crypto` for the Hive key; that is the bar to meet or exceed.
5. **Latency and ordering are unmeasured.** How quickly a play is recorded, and
   whether the newest entry is reliably the current track when other devices are
   active, both need measuring on the real account before the route is trusted.

**Design shape.** History supplies a *candidate* id, never a verdict. It is
corroborated against the frozen MediaSession tuple through the existing
`VideoIdentityCorroborator` exactly as the playlist route is, so a stale or
interleaved history entry refuses rather than mis-links. Resolution priority:

```
browser address bar  →  playlist entry set  →  watch history  →  search
```

The playlist stays ahead of history because it is one fetch per session serving
100 tracks, needs no credentials, and is already proven. History covers the
residue the playlist cannot: non-playlist videos and anything played with the
screen off.

### 10.4 The remaining route, not proposed

**The Lounge / cast pairing protocol.** A paired receiver is told the video id
directly, but pairing moves playback to the receiver, which defeats the point of
observing playback on the phone. Recorded so it is not re-derived.

---

## 11. v0.9.6 plan

> **Implemented 2026-08-04.** Items 0, 1 and 2 are built, unit-tested and
> field-verified; items 3 and 4 were dropped on this section's own gate. The
> build record, the two field measurements item 0g asks for, and the defects the
> field run exposed are in `PHASE_NATIVE_HISTORY.md`.

### 11.1 Non-negotiable constraints

1. **Chrome and Brave behaviour must not change at all.** Enforced structurally
   today: `accessibility_service_config.xml` pins the browser watcher to the
   Chrome/Brave packages and `native_shorts_accessibility_service_config.xml`
   pins the native observer to `com.google.android.youtube`, so neither service
   can read the other's packages. Every new gate must additionally be guarded on
   `session.isNative`, and the browser-path regression tests must stay green.
   Files that must not be touched: `UrlWatcherService`, `UrlEvidence`,
   `accessibility_service_config.xml`.
2. **Every broadcast entry keeps a verified YouTube URL** (§10.1). No unlinked
   scrobbles, no relaxed uniqueness.
3. Unresolvable listens continue to fail closed with an exact logged reason.

### 11.2 Work items, in order

**0 — Signed-in watch history (the headline; §10.3).** Approved by the owner.

- a. A disclosure screen stating plainly what the sign-in is for: reading watch
  history to identify which video is playing, session stored on-device only,
  revocable. Match the tone and specificity of the existing accessibility
  disclosures in `strings.xml`.
- b. A WebView sign-in the **user** performs. The app never handles the
  password; it holds only the resulting session.
- c. Session storage at or above the bar set by `KeyVault` /
  `security-crypto`. Never logged, never sent anywhere but `youtube.com`,
  clearable from the app.
- d. Fetch and parse `youtube.com/feed/history` with the session; take the most
  recent entry's video id as a **candidate only**.
- e. Corroborate that candidate through the existing
  `VideoIdentityCorroborator` against the frozen MediaSession tuple. A
  mismatch refuses; it never overrides.
- f. Detect and surface, with exact reasons: signed out, wrong account, history
  paused, incognito playback, session expired.
- g. Measure before trusting: how soon a play appears in history, and whether
  the newest entry is reliably the current track.

Resolution priority becomes: browser address bar → playlist → history → search.

**1 — Enforce uniqueness inside the playlist (correctness, small).**
`PlaylistPageParser.match` currently returns `entries.firstOrNull { … }`. The
contract in §7.2 rule 8 says a second matching entry must refuse. The code does
not do that; it silently takes the first. Change to a single-match requirement
and add a test with two same-title/same-duration entries in one playlist.

**2 — Refresh the playlist cache on miss (correctness, small).**
`VideoIdResolver.playlistCache` has no TTL and no invalidation, so a song added
to the playlist after the first fetch is never found for the rest of the
process. Refresh when a match fails — but throttled, because tracks that are
legitimately absent (autoplay leaving the playlist) would otherwise refetch on
every single track. A per-playlist minimum interval plus a cap is enough.

**3 — Capture the watch metadata block (only if a residue remains).**

Gate this on the measured coverage from item 0. If watch history resolves
non-playlist and backgrounded playback, this becomes unnecessary complexity and
should be dropped rather than built for its own sake.
Extend `capturePlaylist`'s sibling capture in
`NativeShortsAccessibilityService` to also anchor on the watch metadata block
and record, per track: channel name, subscriber count, exact like count, view
count, upload age. New pure parser + observer alongside
`NativePlaylistParser` / `NativePlaylistObserver`; do not overload those.

**4 — Use the like count to disambiguate search candidates.**
When search leaves more than one candidate, compare each candidate watch page's
like count against the observed one within a tight tolerance and require exactly
one survivor. Never *select* on it alone — it narrows, and the existing
title/channel/duration gates still have to pass. Feed it in as a new corroborator
rather than loosening any existing rule.

**5 — Field-test.** Play a non-playlist video with a known duplicate upload and
confirm it resolves to the right one; confirm a backgrounded non-playlist video
still refuses cleanly.

### 11.3 Operational reminder for the next session

Installing a new APK **revokes both accessibility services**. Verify before
interpreting any field result:

```
adb shell settings get secure accessibility_enabled            # must be 1
adb shell settings get secure enabled_accessibility_services   # must name both
```

`dumpsys accessibility` lists *installed* services, not enabled ones, and will
mislead. Several confusing test runs this session traced to exactly this.

Also: `uiautomator dump` needs an idle window and fails while a video plays —
pause first. The real service reads `rootInActiveWindow` and has no such limit.
