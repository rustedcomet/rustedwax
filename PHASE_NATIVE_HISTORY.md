# Signed-in watch history — v0.9.6 build record

This document records the 2026-08-04 implementation of `PHASE_NATIVE_PLAYLIST_IDENTITY.md` §11.2.
The v0.9.5 field record in that document is immutable; this one continues from it and is the
contract for RustedWax v0.9.6, version code 42.

Device under test: SM-A125M, Android 12 (SDK 31), YouTube 21.30.209.

---

## 1. What was built, against the plan

| §11.2 | Item | State |
| --- | --- | --- |
| 0a | Disclosure screen before any sign-in | done — `strings.xml/watch_history_disclosure`, shown by `YouTubeSignInActivity` before Google's page is loaded |
| 0b | WebView sign-in the **user** performs | done — the app types nothing and has no code path that can read a password field |
| 0c | Session storage at or above the `KeyVault` bar, never logged, clearable | done — `YouTubeSessionVault` |
| 0d | Fetch and parse the history feed; newest entry as a **candidate only** | done — `WatchHistoryParser`, `WatchHistoryMatcher` (see §3 for the one deliberate deviation) |
| 0e | Corroborate through `VideoIdentityCorroborator` | done — the route returns an ordinary `VideoResolution`; every downstream gate is unchanged |
| 0f | Exact reasons: signed out, wrong account, history paused, incognito, expired | done, with one honest limitation — see §4 |
| 0g | Measure latency and whether the newest entry is the current track | **pending the field test**; the instrumentation that answers it is in place (§5) |
| 1 | `PlaylistPageParser.match` must require a single match | done |
| 2 | Throttled refresh-on-miss for the playlist cache | done — `PlaylistRefreshThrottle` |
| 3 | Watch metadata block (likes/views) via accessibility | **not built**, gated on §11.2's own condition — see §7 |
| 4 | Like count as a search disambiguator | **not built**, same gate |
| 5 | Field test | pending |

Resolution priority is now, in `ScrobbleEngine.resolveVideoId`:

```
browser address bar → playlist entry set → watch history → search
```

History is native `com.google.android.youtube` only. It is guarded on `session.isNative` **and** on
the exact package, so YouTube Music (separate history) and both browsers are structurally excluded
in addition to the OS-level scoping of the two accessibility services. `UrlWatcherService`,
`UrlEvidence` and `accessibility_service_config.xml` were not touched.

---

## 2. Measurements taken while building

Two questions had to be answered before the design was fixed, because a wrong answer to either would
have made the feature impossible rather than merely different.

### 2.1 Does Google serve its sign-in flow to an Android WebView?

Google refuses OAuth and sign-in to user agents that announce themselves as embedded browsers
(`disallowed_useragent`), and Android's WebView appends `; wv` to its user agent. Measured
2026-08-04 against `accounts.google.com/ServiceLogin?service=youtube&continue=…`:

| user agent | result |
| --- | --- |
| WebView default, `; wv` present | `http=200`, redirected to `/v3/signin/identifier`, no `disallowed_useragent` |
| same string with `; wv` removed | identical |

Then on the device, in the real WebView with `; wv` removed:

```
chromium: [INFO:CONSOLE] … boq-identity.AccountsSignInUi … /excm=_b,_tp,identifierview
```

Google's ordinary "Sign in — to continue to YouTube" identifier page renders. The route is viable.
The `; wv` removal is retained as cheap insurance; it is not a claim to be a different device.

### 2.2 What does the history page look like with no session?

`youtube.com/feed/history`, desktop UA, no cookies:

```
http=200  bytes=770694  ytInitialData: yes  videoRenderer: 0
responseContext.mainAppWebResponseContext.loggedOut = true
LOGGED_IN = false
messageRenderer: "Keep track of what you watch" + button "Sign in"
```

So signed-out is an **exact, published fact**, not something inferred from an empty list. That
matters more than it sounds: an empty history and a dead session must not read the same, because
only one of them is the user's problem to fix. The paused state is detected from the control the
page offers ("Turn on watch history") and from the phrasings YouTube uses in the empty body;
`Accept-Language: en-US` is sent so those strings stay in the language the parser knows.

### 2.3 An in-app bug found while verifying the grants

After installing the new APK the app's own settings row still read
`Foreground Shorts evidence — Granted`, while the master switch said otherwise:

```
settings get secure accessibility_enabled            → 0
settings get secure enabled_accessibility_services   → …UrlWatcherService:…NativeShortsAccessibilityService
```

`NativeShortsAccessibilityService.isEnabled` read only the *list*, which still names a service that
Android has stopped sending events to. That is the same mistake `dumpsys accessibility` makes and
the one §11.3 warns about — except it was inside the app, telling the user the opposite of the
truth. Fixed by requiring `accessibility_enabled == 1` as well.

`UrlWatcherService.isEnabled` has the identical defect and was **left alone**: §11.1 forbids
touching that file. It is a one-line change whenever the owner wants it.

**Refinement to §11.3's operational note.** The grant check must be taken a few seconds *after* the
install settles, not immediately. Measured across two installs this session: `adb install` returns,
`accessibility_enabled` reads `0` for a short window, and then — for services that were already
granted to the previous APK — Android brings them back on its own:

```
19:09:58  install completed;  accessibility_enabled = 0
19:10:02  [url] address-bar watcher connected
19:10:02  [native-shorts] foreground Shorts observer connected
19:10:0x  accessibility_enabled = 1
```

Reading the flag in that window says "revoked" about a grant that is about to return, which is the
mirror image of the §2.3 bug — a check that is wrong because it was taken at the wrong moment rather
than because it read the wrong key. The reliable evidence is the service's **own connection line in
the event log**, which only a live service can emit. Note this does not always happen: last
session's grants stayed off until re-enabled by hand, so the check still has to be made — just not
in the first few seconds.

---

## 3. The one deliberate deviation from §11.2

§11.2 item 0d says to take *the most recent* entry as the candidate.
`WatchHistoryMatcher` instead takes the only entry within a five-entry window that corroborates.

The reason is that position confers no safety of its own — the corroboration does all of that work
— while "newest" is not stable across the two moments the id is asked for. The route runs once
mid-track, to establish the carry authority that fixes the lock-screen split (§3 of the v0.9.5
record), and again at finalization. By the second of those the user may already be a video or two
further on, and the track that just ended is no longer at the top.

Meanwhile the window version is *strictly stronger* than trusting position 0: it refuses when two
recent entries are indistinguishable, which "take the newest" would silently resolve. The matched
position is recorded in the event log on every hit, which is exactly the data §11.2 item 0g asks
for — whether the newest entry really is the current track — measured rather than assumed.

---

## 4. What "wrong account" detection can and cannot be

The owner's instruction: *refuse to run the history route at all when the YouTube app is signed out
or on a different account than the one logged into RustedWax.*

**What is not available.** Android exposes no API for "which Google account is the YouTube app
using". The accessibility service is package-scoped to YouTube and could in principle read an
account label off a screen, but only while that screen is in the foreground — which is exactly the
condition under which the history route is least needed. Reading it would also be a new, more
invasive capture for a worse answer.

**What is available, and is a proof rather than a guess.** If the YouTube app is signed out, on
another account, or in incognito, then nothing this phone plays is ever written to the feed
RustedWax can read. So a *successful* feed read that does not contain the track that just played is
evidence about the account — and three consecutive ones are a diagnosis. `WatchHistoryHealth`
implements exactly that:

| condition | evidence | behaviour |
| --- | --- | --- |
| dead/expired session | `responseContext.loggedOut`, or a redirect to a sign-in page | refuses on sight, "sign in again" |
| history paused | YouTube's own "Turn on watch history" control | refuses on sight, "turn it back on in YouTube settings" |
| markup changed | `ytInitialData` absent or reshaped | refuses on sight, nothing guessed |
| app signed out / other account / incognito | 3 consecutive successful reads that did not contain the track | refuses, names all three causes |
| network failure | — | records nothing; not evidence about the account |

The three-cause message is the honest limit: those states are indistinguishable *from the feed*, so
the message names all three rather than picking one. A refused route re-probes once every 15 minutes
so the user fixing the account does not need an app restart, and one track landing in the feed
clears the state outright.

The corroborator is the backstop under all of this: even with the diagnosis disabled, an entry from
another account's listening would have to match the frozen title, channel and duration to be used at
all.

---

## 5. Instrumentation for the field measurements

Every hit logs the matched position so §11.2 item 0g can be answered from the log alone:

```
[history] resolved "<title>" → <id> from watch history (entry N of M, 0 = newest)
```

- `N` answers *is the newest entry reliably the current track*.
- The pre-resolution line (`[native-carry] … pre-resolving stable exact-ID-less track`) and the
  `[history] read K watch-history entries` line bracket *how soon a play appears in history*: the
  pre-resolution runs while the track is playing, so a hit there is an upper bound on the latency.

Refusals log their exact reason, including the ambiguity case, so a duplicate-upload pair inside the
recent window is visible rather than silently absent.

---

## 6. Uniqueness inside the playlist, and cache staleness

**Item 1.** `PlaylistPageParser.match` returned `entries.firstOrNull { … }`, so a playlist holding
the same song twice answered a question it could not answer. It now returns the single match or
nothing, with `matches()` exposed so `VideoIdResolver` can log which ids collided. Tested with two
same-title, same-duration entries (`Criminal` at `4ns8D959YtA` and `VqEbCxg2bNI`, the measured
duration-identical pair from §10.1) and with a same-title/different-duration pair that must *not*
become ambiguous.

**Item 2.** `VideoIdResolver.playlistCache` had no TTL, so a song added to a playlist after the
first fetch could never be found again for the life of the process. `PlaylistRefreshThrottle` now
allows a re-read on a miss, bounded three ways: the cached copy must already be older than 10
minutes, attempts are at least 10 minutes apart, and at most 4 are spent per playlist per process.
A freshly fetched playlist is therefore never re-read on the very next track, and a playlist
RustedWax simply is not playing — the autoplay case that produces most misses — costs at most four
extra page loads rather than one per track.

---

## 7. Why items 3 and 4 are not built

§11.2 gates both on the coverage item 0 turns out to have: *"If watch history already resolves
non-playlist and backgrounded playback, drop this rather than building it for its own sake."*

They are also the wrong shape for the residue that is actually left. The watch metadata block
(channel, subscriber count, exact like count, view count) is:

1. scroll-dependent — absent unless the watch page is near the top;
2. per-track rather than per-session, so it must be re-captured for every single track;
3. foreground-only, like all accessibility.

That is, it is unavailable in precisely the situation history exists to cover — pocket listening,
screen off, backgrounded. Building it would add a fragile capture that helps only when the user is
already looking at the screen, where coverage is best anyway.

The decision therefore stands as: **build them only if the field test shows a real class of listens
that history does not resolve and the like count would.** That decision is the field test's to make,
not this document's.

---

## 7a. First sign-in on the device, and what it exposed

The owner signed in on the SM-A125M at 18:44. Two results:

```
[history] connected a YouTube account for watch-history lookups; the session is stored
          encrypted and is never logged or sent anywhere but youtube.com
```

and a grep of the persistent log for `SID=`, `SAPISID`, `LOGIN_INFO` and `__Secure` returned
**0 matches** — the "never logged" requirement holds in practice, not only in intent.

The probe then reported the history as **empty**. That has two completely different causes and the
build could not tell them apart, which is itself a defect:

1. the account genuinely has nothing recent, or
2. the authenticated feed uses markup this parser does not read.

Cause 2 is not hypothetical. Playlist pages already made exactly this move — they dropped
`playlistVideoRenderer` for `lockupViewModel` (§`PlaylistPageParser` KDoc) — and the symptom then
was indistinguishable from an empty page. Two changes followed:

- **The parser now reads both shapes.** Each item in a section is searched for a `videoRenderer` and
  then for a `lockupViewModel`, scoped to that one item so array order — the only ordering this
  parser trusts — is preserved. A row wrapped in a `richItemRenderer` is also read. Both shapes are
  unit-tested.
- **An empty feed now logs a shape report**: counts of the renderer *names* present
  (`videoRenderer`, `lockupViewModel`, `richItemRenderer`, `sectionListRenderer`,
  `itemSectionRenderer`) and the page size. Renderer names and integers only — nothing about what
  was watched. All zeros means the account really is empty; anything non-zero means the feed has
  entries the parser did not read, and names the shape to add.

## 7b. Layout: the fixed top block

Reported from the device: on a 720×1600 A12 the settings card was a fixed block roughly a third of
the screen tall, and it grew with every feature added — the watch-history row made it worse.

The card moved to its own **Settings tab**, inside a `verticalScroll`, so it can grow without
costing any other tab a pixel. What stays fixed is a two-line strip: monitoring state, the one-line
consequence ("Scrobbling at 60% played", "· 3 waiting to send") and the Start/Stop button.
Monitoring is the only control that must be reachable from every tab, since it is the switch that
decides whether anything is read at all; everything else is set once and left, which is exactly why
it should not have been occupying the screen.

## 7c. First field run — the route works, and §11.2 item 0g is answered

Measured on the SM-A125M, 19:03–19:06, native YouTube, no playlist:

```
19:04:01.085  [finalize]     [track change] De La Ghetto … La Formula — played 0s of 236s
19:04:01.121  [native-carry] pre-resolving stable exact-ID-less track "… La Formula"
19:04:03.771  [history]      read 153 watch-history entries
19:04:03.788  [history]      resolved "… La Formula" → gmc4tkVJow8 from watch history
                             (entry 0 of 153, 0 = newest)
19:04:05.643  [native-carry] established immutable carry authority gmc4tkVJow8
19:05:56.438  [finalize]     [track change] … La Formula — played 230s of 236s (up to 2.0× speed)
19:05:56.442  [resolve]      re-fetching pre-resolved native carry authority gmc4tkVJow8 (HISTORY)
19:05:59.197  [history]      resolved … → gmc4tkVJow8 (entry 0 of 153, 0 = newest)
19:05:59.230  [native-id]    verified gmc4tkVJow8 via corroborated resolver (watch history)
```

**Item 0g, both halves:**

| question | measurement |
| --- | --- |
| how soon does a play appear in history? | **within ~2.7 s** — the track change was at `19:04:01.085`, the feed was read at `19:04:03.771`, and the video was already in it |
| is the newest entry reliably the current track? | **yes so far** — position 0 on all three successful lookups, including the finalization two minutes later |

The `(entry N of M)` instrumentation is what makes the second row a measurement rather than an
assumption, and it stays in so a future `N > 0` is visible rather than silent.

**The account was never empty.** The feed carries 153 entries; two songs do not produce 153 entries.
The §7a "empty" result was therefore the markup shape, and the `lockupViewModel` /
`richItemRenderer` fallback added in §7a is what made the route work at all.

### The defect this run exposed

```
19:05:59.683  [finalize]     [track change] Por El Momento — played 0s of 218s
19:05:59.721  [native-carry] pre-resolving stable exact-ID-less track "Por El Momento"
19:05:59.759  [history]      refused "Por El Momento": none of the 5 most recent watch-history
                             entries match … (218s)
19:06:02.397  [resolve]      resolved "Por El Momento" → KdiYZie5dBk by title+channel+duration
```

The lookup ran **60 ms** after the track change, against a feed cached **0.5 s earlier to finalize
the previous track**. The new track could not have been in it. So a working route fell through to
the search route — the one measured three times picking a duration-identical wrong upload.

Worse, that miss was counted toward the "your YouTube app is on another account" diagnosis, so a
pure timing artefact could have stood the whole route down after three tracks.

Both fixed:

- **Absence against a cached feed is not an answer.** It triggers one fresh read and a re-match
  before anything is concluded. The cache still serves repeat lookups of the *same* track, which is
  what it was for.
- **Only absence from a freshly-read feed counts as a miss.** `WatchHistoryMatcher.isAbsence` is now
  one shared predicate rather than a string check at two call sites, so the two questions it decides
  — "look again?" and "does this count against the account?" — cannot drift apart.

## 8. Verification

1. **Unit tests — 496, 0 failures** (was 457; 39 added).
   - `WatchHistoryParserTest` — newest-first ordering across day sections, signed-out vs paused vs
     empty vs markup-changed, duration from the overlay badge when `lengthText` is absent, the
     `lockupViewModel` shape, and a row wrapped in a `richItemRenderer`.
   - `WatchHistoryMatcherTest` — the measured duplicate-upload pairs, position reporting, refusal on
     ambiguity, the window bound, revalidation refusing a moved id.
   - `WatchHistoryHealthTest` — one miss is not a diagnosis, three are; hits clear it; refusals
     re-probe once per interval; dead session and paused history refuse on sight; network failures
     say nothing about the account.
   - `YouTubeSessionVaultTest` — a jar with no session cookie is not a sign-in; `=` inside a cookie
     value survives parsing.
   - `PlaylistPageParserTest` — two indistinguishable entries refuse; a same-titled entry of a
     different length does not make the real one ambiguous.
   - `PlaylistRefreshThrottleTest` — the three bounds above.
2. **Build and lint — done.** Debug APK builds; lint reports **zero errors** (warnings only, all
   pre-existing categories).
3. **On-device smoke test — done.** v0.9.6 installs, the settings row renders its "Not connected"
   state, the disclosure screen renders in full, and Google's real sign-in page loads in the
   WebView with no embedded-browser block.
4. **Field test — done for the route itself** (§7c), **outstanding for the duplicate-upload case**
   (§9.1). It needed two things only the owner could do: the accessibility grants and the Google
   sign-in.

---

## 9. Follow-ups

### 9.1 The remaining field test — §11.2 item 5

The route is proven to work; it is not yet proven to be *right* on the case it exists for. Play a
non-playlist video with a known duplicate upload and confirm history resolves the one actually
played, not the other. Both pairs are duration-identical, which is what makes them the test:

| Track | Played (correct) | The decoy search has been measured picking |
| --- | --- | --- |
| `Criminal` | `4ns8D959YtA` | `VqEbCxg2bNI` — both exactly 273 s |
| `Unica` | `YbZwlNmnUvw` | `7uxTya2PX3c` — both exactly 218 s |

Then confirm a backgrounded non-playlist video either resolves through history or refuses cleanly
with an exact reason. Neither may reach the chain with the decoy id.

### 9.2 The real simplification: can the native search fallbacks go?

This is the item worth doing next, and it removes *risk*, not just code. Plain search,
`NativeStructuredMusicMatcher` and the run-local candidate cache exist only to guess at the native
no-id case, and search is the route measured three times choosing a duration-identical wrong upload
(`Unica`, `Criminal`, `Chulo Sin H`). If history covers that case reliably, those paths are a
liability rather than a safety net.

Decide it from the log, not from taste: count how many native tracks history resolves against how
many still reach search, over a real listening session. The `[history]` and `[resolve]` lines
already carry everything needed.

Note what must **not** be removed on the same reasoning: the browser address bar (free, instant, and
works signed out), the playlist route (one fetch per hundred tracks, no credentials) and all
MediaSession measurement (history says a video was *started*, never how much was played).

### 9.3 `UrlWatcherService.isEnabled`

Has the §2.3 defect — reads only `enabled_accessibility_services`, so a revoked service still
reports as granted. One line, deliberately untouched here because §11.1 forbids changing
browser-path files without the owner's sign-off.

### 9.4 Smaller open questions

- Which renderer the authenticated feed actually uses. The signed-out shape is measured; the
  signed-in shape is now read two ways (`videoRenderer` and `lockupViewModel`, wrapped or not) and
  an empty result logs the shape report of §7a, so the log answers this rather than leaving it
  ambiguous.
- Whether an account display label can be read from the feed at all. The extraction is best-effort
  over three known spellings and degrades to "the connected account" in the UI.
- Out of scope and recorded so they are not re-derived: playlist continuation past 100 entries,
  YouTube Music, and the Lounge/cast pairing protocol (pairing moves playback to the receiver,
  which defeats the point of observing playback on the phone).
