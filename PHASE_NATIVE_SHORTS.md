# Native YouTube Shorts — v0.9.1 implementation and field record

This began as the self-contained handoff from the 2026-08-03 Samsung Galaxy A12
investigation and now records the implemented v0.9.1/version-code 37 result.
It extends the v0.9.0 native-app record in
[PHASE_NATIVE_APPS.md](PHASE_NATIVE_APPS.md). The original implementation order
remains below as the audit trail; the actual source/device outcome is recorded
after the acceptance criteria.

## Outcome

The v0.9.0 native YouTube MediaSession path was not a reliable observer for the
Shorts feed on the tested production app. It can keep publishing an earlier
ordinary video's metadata while visibly different Shorts play. The existing
same-track continuation then carries time from those Shorts into the stale
ordinary track. This is both a false-omission defect for the Shorts and a
potential immutable false-positive for the stale track.

Foreground native Shorts do expose enough literal accessibility structure for
a conservative implementation:

- a native Shorts player root;
- the visible title;
- the exact channel handle;
- a current/total seekbar value; and
- literal YouTube ad labels.

That signal disappears in Picture-in-Picture (PiP). The first patch must
therefore support **foreground Shorts only** and stop counting at the last valid
foreground observation. v0.9.1 implements that boundary without wall-clock time, stale MediaSession
state, SystemUI controls, OCR, screen capture, or signed-in History to fill the
gap.

Exact id recovery also needs one bounded resolver extension. Two of three
field tuples failed only because accessibility supplied an exact `@handle`
while the current resolver compared it with the watch page's display author.
The canonical page already exposes the same handle in
`microformat.playerMicroformatRenderer.ownerProfileUrl`.

The implemented patch additionally stabilizes identity-bearing accessibility
fields for 750 ms. The A12 proved that a swipe can briefly expose an outgoing
title/handle beside an incoming duration or ad seekbar; those torn frames now
freeze the prior item and never become sessions.

## Evidence and environment

### Repository artifacts

- `debug/rustedwax-log (22).txt`: 2,432 lines, exported at the end of the
  original v0.9.0 reproduction.
- `debug/mob001.jpeg`: a different Short is visibly playing in PiP while the
  RustedWax Now card still says BELLAKEO and `waiting for verified video id`.
- `debug/mob002.jpeg`: the BELLAKEO watch surface and its recommended Shorts,
  which were the route into the feed.
- The existing v0.9.0 source and `dist/rustedwax-0.9.0.apk` remain the baseline:
  372 uncached unit tests passed, debug assembly succeeded, lint reported 0
  errors/23 warnings, and the tested APK SHA-256 is
  `3f8945e997d592dbf40fac6aa727f69215cbf8a805b5a4b15df031171a0ec58c`.

### Physical device

- Samsung SM-A125M (Galaxy A12).
- Android 12 / API 31.
- YouTube `21.30.209`.
- RustedWax `0.9.0` / version code 36.
- ADB was authorized and functional through
  `/Users/destro/Library/Android/sdk/platform-tools/adb`.
- RustedWax had Notification Access and the existing browser accessibility
  service enabled.

The direct ADB accessibility probe was run with RustedWax monitoring off, so it
did not create test Hive transactions. A temporary JVM resolver probe and its
temporary Gradle test setting were completely removed afterward. The phone's
temporary stay-awake setting was restored to its original value, YouTube was
left inactive on the launcher, and no investigation-only production source
remains.

### External oracle boundary

The signed-in YouTube History page in the user's already-open Chrome session
was refreshed only to reconcile which organic Shorts YouTube recorded. It is
external test evidence, not an application input. The runtime must never read,
scrape, or depend on signed-in History. Ads being absent from History helped
classify the sampled field observations, but absence from History is not a
production ad detector.

## Original log-22 failure

The reproduction began with ordinary native YouTube playback, entered the
Shorts feed through recommendations beneath the watched video, then played
several Shorts including in PiP. YouTube History showed BELLAKEO followed by
six distinct Shorts. RustedWax never saw any of those Short titles or ids.

Instead, the log contains 26 metadata callbacks whose title is still:

```text
BELLAKEO (Video Oficial) - Peso Pluma, Anitta
```

There is one additional BELLAKEO occurrence in a finalization line. Across
repeated native session removal/recreation, RustedWax logged carried totals of
6, 11, 20, 30, 31, 37, 47, and finally 71 seconds. Those seconds included time
spent on unrelated Shorts. The MediaSession supplied no `MEDIA_ID`,
`MEDIA_URI`, canonical artwork URI, or other exact video-id field, so the Now
card remained site-only and waited for resolver verification.

The user's theory was directionally correct: following recommendations into
the feed can leave the MediaSession on the earlier selection. The narrower
finding is that this is a native YouTube Shorts MediaSession publication
failure, not evidence that RustedWax should follow only search results.

### Immediate safety consequence

The current native continuation rule is too permissive for exact-ID-less native
YouTube sessions. Semantic title/artist/album equality is not sufficient when
the producer demonstrably keeps that metadata stale across unrelated Shorts.
Before adding Short support, v0.9.1 must refuse to remember or claim a native
MediaSession continuation unless the MediaSession itself supplied a concrete
exact id route. A resolver-only id learned after finalization cannot
retroactively prove that two live MediaSessions represented the same item.

Native snapshots in `NONE`/`STOPPED` with empty or stale metadata must also not
compete with a currently proven foreground Short in Now or scoring.

## Foreground accessibility findings

### Stable player shape

The foreground Shorts player exposed stable YouTube-owned resource structure,
including:

- `reel_watch_fragment_root`;
- `reel_watch_player`; and
- `reel_time_bar`.

Within that structure, the active Short exposed:

- the full visible title;
- an exact handle both as `@handle` and in `Go to channel @handle`;
- a sound/music label;
- player controls; and
- a live seekbar description such as
  `0 minutes 7 seconds of 0 minutes 20 seconds`.

A Home-feed dump also exposed card titles, resource ids and content
descriptions, but a card in the feed is not playback proof. The implementation
must require the Shorts player structure and its current seekbar; it must not
accept arbitrary YouTube Home/search/recommendation text.

### Sampled organic Shorts

| Visible title or identifying prefix | Accessibility handle | Accessibility duration | Canonical result |
| --- | --- | ---: | --- |
| `Dura - Becky G 🔥` | `@fansclubbeckyg394` | 20 s | Exact id not recorded in this probe |
| `Spider - Man ...` | `@Etzy-Am` | 92 s | Exact id not recorded in this probe |
| `🎭 He Put on a Magic Mask! \| The Mask (1994)😂#shorts #movierecap` | `@SonaDarus` | 158 s | `s8ZQSxuKPb0` |
| `Which is your favorite team? ⚡ Team Iron Man or 🛡️ Team Captain America? #Marvel` | `@Status_svijet` | 41 s | `Bf7Qtyr-2IQ`; canonical page says 42 s |
| `Hackers' Skills...` | `@Beredist` | 139 s | `orsMh4bNeGE` |

Title, handle, duration and seekbar position changed together as the user moved
through these samples. This is materially better transition evidence than the
stale MediaSession. The existing five-second resolver duration tolerance covers
the observed 41/42-second presentation difference.

The refreshed History oracle also showed these organic test ids during the
broader run:

```text
QjR-m6q0wN4
lw8InLbiBfM
gckFwiFgtyg
sMyyk-OlizA
Bf7Qtyr-2IQ
s8ZQSxuKPb0
orsMh4bNeGE
```

This list is evidence for the run only. It must not become a fixture catalogue,
runtime lookup source, allowlist, or production special case.

## Native ad findings

Native Shorts ads exposed literal UI evidence in the same YouTube accessibility
tree. One sampled creative contained:

```text
Ad
Finelo
Ad
Investing made simple — begin learning now
Learn investing
Learn more
```

It also exposed a 39-second seekbar. A later creative exposed
`Deepsearch AI Search Assistant` plus `Ad`. Neither ad appeared in signed-in
History.

This supplies the generic native Short ad signal that MediaSession lacked:
YouTube's own exact visible `Ad` label within the proven active Shorts player.
The implementation may reuse the existing strict literal-label detector, but
only after binding the label to the same valid native player observation. It
must not infer ads from brand, title, handle, duration, CTA vocabulary,
popularity, video id, resolver result, or History.

An ad observation is a hard lifecycle boundary:

- finalize the preceding organic Short using only progress earned before the
  ad;
- never create an eligible session for the ad;
- retain the literal refusal in the log/Not logged view; and
- start the following organic Short with fresh progress and evidence.

## MediaSession findings

During direct foreground Short playback, native YouTube's active MediaSession
was unusable for identity or progress:

- state was `STATE_STOPPED` (`1`) while video visibly played;
- position remained `0`;
- actions were `8192` or `0`;
- `isActive` could disagree with visible playback;
- metadata description was null/empty;
- the only non-standard metadata could be YouTube video width/height; and
- no title, handle, duration, URI, media id, artwork id, queue id, custom action,
  or exact Short marker appeared.

The implementation must not merge this stale/empty session into the
accessibility Short merely because both have package
`com.google.android.youtube`. MediaSession remains useful for ordinary native
videos when it publishes coherent metadata; foreground Shorts need an
independent observation lifecycle.

## Picture-in-Picture and background findings

In PiP, visual playback continued on the launcher. The YouTube accessibility
window still existed, but the title, handle and seekbar description were gone;
only containers/resource ids remained. The MediaSession simultaneously
reported `STOPPED`, position `0`, and actions `0`.

Tapping the PiP overlay displayed Samsung/SystemUI previous, pause, next,
expand and close controls. Those controls were not part of the
YouTube-package tree. Reading SystemUI would broaden the accessibility scope
well beyond the disclosed source package and still would not provide title,
handle or exact id.

The v0.9.1 contract is therefore:

- foreground native Shorts only;
- freeze progress immediately when the complete YouTube Short proof
  disappears;
- allow a short bounded observation grace for transient tree refreshes, without
  accumulating time during the gap;
- finalize/end at the last valid foreground observation if proof does not
  return; and
- never count PiP, background, screen-off, launcher, or lock-screen time.

SystemUI allowlisting, OCR, MediaProjection/screen capture and inferred
wall-clock continuation are out of scope.

## Privileged ADB finding

`dumpsys activity top` exposed a field shaped like:

```text
PlaybackStartDescriptor VideoId:fYnaCDEPI70
```

It remained stale across later Shorts and even ads. More importantly, ordinary
apps cannot read the required activity dump because the `DUMP` privilege is
signature/privileged. This is test-only instrumentation and is neither correct
nor available as a production identity mechanism.

## Resolver experiment

A temporary unit probe ran the real `VideoIdResolver` against three field
tuples. It was removed after the measurement.

| Field tuple | Result under current resolver |
| --- | --- |
| The Mask title + `@SonaDarus` + 158 s | Resolved `s8ZQSxuKPb0`; display author `Sona Darus` happened to match after current space normalization |
| Team Iron Man title + `@Status_svijet` + 41 s | Failed closed among 65 candidates; watch display author is `Status` |
| Hackers title + `@Beredist` + 139 s | Failed closed among 78 candidates; watch display author is `Beredits` |

Direct canonical watch pages for all three exposed exact title, duration,
`channelId`, and these owner profile URLs:

```text
http://www.youtube.com/@SonaDarus
http://www.youtube.com/@Status_svijet
http://www.youtube.com/@Beredist
```

The proposed resolver extension is narrow:

1. Parse a canonical single-segment `@handle` from
   `playerMicroformatRenderer.ownerProfileUrl`.
2. Round-trip it through `VideoFacts`, `FactsCache`, and `VideoResolution`.
3. Carry the accessibility handle as a dedicated resolver input; do not
   overload artist/display-channel fields.
4. When an exact handle was observed, require title + duration + exact
   normalized owner handle. Normalization may trim, remove one leading `@`, and
   compare case-insensitively; it must not remove punctuation, underscores, or
   invent fuzzy display-name equivalence.
5. Keep the current duration tolerance, bounded fetch/search budgets, complete
   candidate-set check, and exactly-one-match ambiguity gate.
6. If the owner handle is missing, contradictory, malformed, or matches more
   than one id, refuse every id.
7. Treat legacy cache entries without the new handle as insufficient for this
   handle-required route and refresh/fail closed rather than silently falling
   back to display author.

This is an inference from the canonical pages and should be proven by the next
patch's automated and physical tests. The field experiment did not implement
or run the extension.

## v0.9.1 implementation contract

Target version: `0.9.1`, version code `37`. Preserve v0.8.15 browser behavior
and the current v0.9.0 default-off native YouTube/YouTube Music switches.

### 1. Fix unsafe native continuation first

Change the native MediaSession continuation boundary before adding another
source:

- a native `Watch` may remember/claim continuation only when its live
  MediaSession supplied a concrete exact source id;
- resolver-only, site-only, empty, `NONE`, or `STOPPED` native state cannot
  carry progress across controller destruction/recreation;
- exact ids remain case-sensitive contradiction evidence;
- browser continuation behavior stays byte-for-byte/rule-for-rule equivalent;
  and
- Stop, opt-out and listener-epoch invalidation remain hard discard boundaries.

Add a regression modeled on log 22: repeated exact-ID-less BELLAKEO sessions
must never accumulate the time spent on successor Shorts.

### 2. Add a separately disclosed, OS-scoped service

Do not add `com.google.android.youtube` to
`accessibility_service_config.xml`; that would silently broaden the existing
browser grant. Add a second `AccessibilityService` component and a separate XML
config whose OS-enforced `packageNames` contains only
`com.google.android.youtube`.

The new component should:

- be optional and independently visible in Android Accessibility settings;
- do nothing unless Monitoring and the existing Native YouTube toggle are on;
- use bounded tree depth/node budgets and recycle every node;
- observe callbacks plus a bounded foreground refresh while a Short is active;
- disclose that it reads only foreground YouTube Short player text/controls for
  title, handle, progress and exact ad labels; and
- expose connection/coverage/outage state in diagnostics.

No new general permission, SystemUI package, notification-content read, OCR,
screen capture, History access, or hidden/privileged API belongs in this patch.

### 3. Keep parsing pure and structural

Create a pure parser/model that can be unit-tested from synthetic accessibility
node snapshots. A valid organic observation requires all of:

- exact package `com.google.android.youtube`;
- visible native Shorts player root/player structure;
- one non-control title;
- one syntactically valid exact `@handle` bound to the active player;
- a parseable current and total duration from the active seekbar; and
- no literal ad signal in that same player observation.

Reject Home/search cards, comments, related items, channel pages, ordinary
watch pages, hidden nodes, multiple conflicting titles/handles/seekbars,
zero/invalid totals, malformed/localization-unknown time descriptions, and any
observation that exceeds the scan budget. Unknown localization must fail
closed, not guess.

Use resource ids and semantic relationships for structure. English visible
phrases may parse the measured device fixture, but add locale fixtures for every
supported time/ad phrase and refuse unsupported shapes explicitly.

### 4. Track Shorts independently of MediaSession

Add a foreground Short tracker owned by the same monitoring lifecycle as
`SessionProbe`, but do not graft the observation onto a stale native
MediaSession token.

The track key is the structural Short proof plus title, exact handle and
duration, scoped by native source epoch. On a key transition:

- freeze/finalize the preceding organic Short;
- clear any ad/identity candidate state that cannot cross tracks; and
- start the successor at zero.

Progress comes only from accepted seekbar position deltas:

- positive sequential movement may accumulate;
- a material forward jump is a seek and must not be credited as watched;
- an ordinary backward jump is not credited;
- a strict near-end to near-start wrap records `loopDetected` and may count only
  the content actually traversed;
- unchanged position adds nothing; and
- missing proof adds nothing, regardless of elapsed wall time.

Because seekbar values are integer seconds, allow small sampling jitter but
bound any accepted delta by monotonic elapsed time plus an explicit small
tolerance. Do not assume playback speed when no literal rate is exposed.

### 5. Rejoin the existing central pipeline

The Short tracker may have a separate observation lifecycle, not a separate
broadcast implementation. Its final immutable observation must become a
`SessionSnapshot` (or a narrowly compatible source snapshot) and pass through
the existing:

```text
ScrobbleEngine
  → exact-id resolver and final corroboration
  → MusicClassifier / ScrobbleBuilder
  → ScrobbleRules
  → mute / dedup
  → durable queue / Hive broadcaster
```

Add explicit source proof such as a native foreground-Short enum/field to the
snapshot. Do not rely on `session.confirmed?.isShort`: resolver-only native
sessions currently have no confirmed source identity even after an id is found.
Use the new proof consistently in rules, classification, loop cap, UI and log.

The existing central literal `explicitAdSignal` veto may accept a signal from
the new tracker as long as the tracker bound it to the same proven player
observation. Browser outage/coverage semantics must remain browser-only; add
native Short coverage wording rather than pretending it is browser address-bar
coverage.

### 6. Resolve exact ids by handle-aware corroboration

Implement the owner-handle changes described above. Search/watch-page access
remains behind **Look videos up** and retains the existing prototype-policy
disclosure. With lookup off, a foreground Short has useful Now diagnostics but
cannot broadcast because a canonical exact id remains mandatory.

With lookup on, title + duration + owner handle must resolve exactly one id.
The final enriched page must still pass all existing contradiction, public/
unlisted, canonical-link and ambiguity gates. Accessibility never supplies an
id by itself.

### 7. UI, settings and lifecycle

- Keep native sources and the new observer default-off/experimental.
- Show whether the native Shorts accessibility component is granted and offer
  the existing Accessibility settings route.
- Explain `foreground Shorts only; PiP/background time is not counted`.
- Show the source package, foreground-Short proof, title, handle, current/total,
  measured played time, ad signal/coverage, resolver outcome and exact skip
  reason.
- If a Short is active, suppress any stale native YouTube MediaSession card for
  the same package from Now/scoring. Do not suppress an independently coherent
  ordinary native video when there is no complete Short proof.
- Stop, Native YouTube opt-out, accessibility disconnect, listener rebuild and
  source-epoch change must invalidate in-flight Short observations and async
  resolver/signing work. User Stop discards rather than finalizes.
- Native YouTube Music and all browser behavior remain unaffected.

## Automated test matrix

At minimum, add deterministic coverage for:

### Native continuation safety

- exact-ID-less same-metadata controller churn does not carry;
- stale BELLAKEO metadata cannot absorb successor Short time;
- exact-id native same-track recreation may carry once;
- differing exact ids never carry;
- browser continuation regressions remain unchanged; and
- Stop/opt-out/reconnect invalidates pending native work.

### Accessibility parser

- each measured organic player fixture parses title, handle, current and total;
- Home/search/recommendation cards do not parse as active playback;
- control/sound/channel-navigation labels do not become the title;
- malformed, missing and conflicting title/handle/seekbar observations refuse;
- measured singular/plural time phrases and supported locales parse exactly;
- unsupported locales fail closed;
- scan depth/node budgets are enforced; and
- literal `Ad`/supported exact labels bind only inside the proven player.

### Tracker and lifecycle

- sequential position deltas accumulate without wall-time credit;
- pause/unchanged, forward seek and ordinary rewind do not inflate progress;
- strict wrap sets `loopDetected` and remains capped to one Short scrobble;
- organic → organic, organic → ad → organic and rapid swipe transitions isolate
  title, handle, duration, progress and evidence;
- temporary tree loss freezes time and bounded recovery resumes the same key;
- PiP/background loss finalizes at the last foreground value;
- stale MediaSession state is suppressed only while a complete Short is proven;
  and
- Stop/opt-out/accessibility disconnect/source epoch discard correctly.

### Resolver and central pipeline

- parse valid `ownerProfileUrl` handles and reject hostile/malformed hosts or
  multi-segment paths;
- round-trip handle through facts/cache/resolution;
- exact measured `@SonaDarus`, `@Status_svijet`, and `@Beredist` cases;
- 41/42-second tolerance without widening the existing five-second limit;
- display-author mismatch succeeds only through exact owner-handle proof;
- missing/contradictory/ambiguous handles refuse every id;
- legacy cache entries cannot bypass handle proof;
- native source proof activates Short floor/classifier/cap after resolver-only
  id recovery;
- exact ad signal vetoes manual and automatic paths;
- lookup-off, unresolved and ambiguous observations remain off-chain; and
- canonical URL, mute, dedup, queue and source-epoch signing guards remain
  shared.

## Physical device gate

Use the connected Galaxy A12 first, with automatic broadcasting off. Install
the new debug APK, explicitly enable the separate native Shorts accessibility
component, then record/export complete logs for:

1. Both native toggles and the new observer off after upgrade/restart.
2. Native YouTube on but observer off: no accessibility Short ingestion.
3. Observer on but Native YouTube off: no Short ingestion.
4. Ordinary video → recommendation → at least six organic Shorts; every Now
   transition must follow the visible Short, never the prior video.
5. Four organic samples long enough to cross the configured threshold, with
   lookup on; reconcile title, handle, duration, exact id and canonical page.
6. One lookup-off run, one unresolved run and one deliberately ambiguous run;
   all must remain off-chain with exact reasons.
7. At least two naturally served Short ad creatives; their literal signals must
   veto them and must not poison adjacent organic Shorts.
8. Rapid swipes, pause/resume, forward seek, rewind and a continuous loop.
9. PiP before and after the threshold; verify PiP time is never counted.
10. Home, search, comments, channel and ordinary watch surfaces; none may be
    misread as an active Short.
11. Session/controller destruction during Shorts; no MediaSession carry may
    inflate the Short or stale ordinary item.
12. Stop, native opt-out, accessibility grant off/on, Notification Access
    reconnect and app/process restart with in-flight observations.
13. Browser Chrome/Brave regression smoke tests and native YouTube Music smoke
    tests with the new service enabled and disabled.

Only after the no-broadcast instrumentation pass reconciles should a throwaway
Hive account be used for a bounded automatic/manual pass. Reconcile every
attempt by exact id, payload, transaction id, independent node result and
profile section. YouTube History may be consulted manually afterward as an
external organic-play oracle; it remains forbidden as runtime input.

### Acceptance criteria

- Zero sampled native Short ads reach payload construction or Hive.
- Zero organic Short transitions inherit an adjacent title, handle, id,
  progress or ad signal.
- Zero exact-ID-less native MediaSession continuations carry progress.
- Every accepted Short has complete foreground proof, one uniquely
  corroborated exact id, a canonical link, public watch-page proof, and the
  shared rule result.
- PiP/background time is demonstrably excluded.
- Stop/opt-out/reconnect invalidation prevents late signing.
- Browser and YouTube Music automated suites and physical smoke tests do not
  regress.
- The complete uncached test/build/lint gate passes, the tested source APK is
  copied to `dist/rustedwax-0.9.1.apk`, and the source/copy SHA-256 values match.

The new artifact must remain experimental/default-off if any acceptance item
fails. Do not repair, rewrite, or rebroadcast historical Hive operations.

## v0.9.1 implementation and 2026-08-04 result

Implemented in version code 37:

- exact-ID-less native MediaSession recreation cannot carry progress; browser
  continuation and native exact-id continuation retain their prior rules;
- a second accessibility service is separately disclosed and OS-scoped only to
  `com.google.android.youtube`; the browser service configuration is unchanged;
- a pure bounded parser requires one structural Shorts root/player, exact title,
  exact owner handle and current/total seekbar, with exact measured player-control
  exclusions and comments/non-player refusal; a standalone semantic hashtag
  chip is excluded from title candidates without weakening prose ambiguity;
- a 750 ms identity stabilizer rejects cross-page torn frames before the
  independent foreground tracker sees them;
- progress comes only from conservative seekbar deltas, supports strict wraps,
  freezes on pause/seek/missing proof, and is ended by an independent freshness
  watchdog after the three-second no-credit grace;
- stale native YouTube MediaSession state is hidden only while complete foreground
  Short proof owns playback; loss releases it at a fresh zero baseline;
- exact literal ad labels create an ad observation and feed the existing central
  manual/automatic veto; and
- canonical watch facts, disk cache, resolver candidates and final corroboration
  carry the exact normalized owner handle.

The final uncached source gate passed **409 tests**, with 0 failures, 0 errors
and 0 skipped. `assembleDebug` succeeded. `lintDebug` succeeded with 0 errors
and 23 warnings (the existing dependency/manifest warnings).

The Samsung SM-A125M Galaxy A12 / Android 12 no-broadcast pass kept automatic
scrobbling off throughout and established:

- Native YouTube on/observer off and observer on/Native YouTube off ingest no
  foreground Short; both services reconnect independently;
- the measured Sona, Status and Beredist shapes acquire exact title, normalized
  handle, duration and seekbar proof, while stale stopped/empty MediaSession
  controllers are suppressed;
- sequential and sparse seekbar changes accumulate, a 41-second Short crossed
  threshold and wrapped, a forward scrub reset the baseline, rewind earned no
  credit, and a 33-second pause added zero;
- PiP both below and above threshold froze immediately and finalized at the last
  valid value; the final above-threshold sample ended at 33/41 seconds;
- rapid organic transitions no longer admitted the measured 200–250 ms torn
  title/duration/ad frames after the stability fix;
- two natural, visually distinct creatives (Finelo and Pocket Toons) exposed
  literal `Ad`, remained isolated from adjacent organic Shorts, and were never
  broadcast because automatic scrobbling was off;
- Home, search, channel, sound, comments and ordinary-watch surfaces did not
  parse as foreground Shorts;
- Stop, native opt-out, accessibility grant removal/restoration, Notification
  Access removal/restoration, process reinstall, and exact-ID-less controller
  churn discarded or refused stale state; and
- Chrome, Brave and YouTube Music physical smoke tests retained their existing
  source-specific MediaSession and exact browser-id behavior.

The configured Hive account was confirmed to be a test account, so the bounded
write gate proceeded only after the no-broadcast pass. Four long lookup-on
foreground Shorts crossed threshold, resolved by exact title, duration and
owner handle, produced canonical links, and were confirmed in blocks:

| Video id | Owner | Measured | Transaction | Block |
| --- | --- | ---: | --- | ---: |
| `s8ZQSxuKPb0` | `@sonadarus` | 110/158 s (70%) | `d57d3087890bfd3c96f82b4e68d825c28fd1c61a` | 108722003 |
| `orsMh4bNeGE` | `@beredist` | 103/139 s (74%) | `69a8934932c388fbbd57a0b132f0a6a3957e7b00` | 108722091 |
| `lw8InLbiBfM` | `@poppinus_official` | 45/48 s (94%) | `4b70556927a08195593a6cc4f32d3099b9806d21` | 108722137 |
| `sMyyk-OlizA` | `@willianpaivap` | 73/95 s (77%) | `6a8ce57cd8513fa6cad8ad50db543914491fbb33` | 108722284 |

Each exact payload was independently read from `api.hive.blog`,
`api.deathwing.me` and `api.openhive.network`. The public scrobble.life Videos
profile showed `Today · 4 scrobbles · 4 unique` with exactly those four ids.
The fourth sample exposed a standalone clickable `#hack` accessibility node;
the parser now ignores exactly one bare hashtag chip, the measured Samsung
fixture covers that shape, and the complete gate was rerun before reinstalling
the exact rebuilt artifact.

The negative set also passed on that rebuilt artifact. With lookup disabled,
`QjR-m6q0wN4` refused because every YouTube entry requires a verified hyperlink.
With lookup on, `Bf7Qtyr-2IQ` remained unresolved because search supplied no
exact title+duration+handle candidate. A genuine same-owner pair,
`PlTiqSpwzTI` and `VL_1TfgB2pw`, normalized to the same title and fell within
the duration tolerance; the resolver named both and refused every id. None
reached Hive, and the profile remained at four writes. A foreground Short has
no buildable manual payload until finalization resolves its id, so the live Now
card remains deliberately fail-closed; shared manual/automatic ad and rule
parity is exercised by the automated gate.

The bounded v0.9.1 foreground-Short matrix is therefore **approved**. The native
settings remain experimental/default-off by design while the broader v0.9
native-app measurements remain open; this is not permission to loosen identity
or ad rules.

The exact assembled source APK and `dist/rustedwax-0.9.1.apk` are byte-identical,
14,032,557 bytes, with SHA-256
`c6f2f1800a1cc6b6d76c260181d2402a3d648c9ecf7b3bc94ad897eeb1ce0895`.

## Explicit non-goals

- Native YouTube PiP, background, screen-off, or lock-screen Short accounting.
- SystemUI accessibility access.
- OCR, screenshots, MediaProjection, audio fingerprinting or network traffic
  interception.
- Privileged `dumpsys`/`DUMP` APIs in production.
- Signed-in YouTube History ingestion.
- Display-name, title, brand, CTA, duration, popularity, id, or History-based ad
  heuristics.
- A second threshold/dedup/payload/broadcast implementation.
- YouTube Data API integration or policy clearance of the existing unsupported
  resolver.
- Changes to YouTube Music ingestion beyond regression preservation.

## Repository handoff state

The v0.9.0 and v0.9.1 implementation remains unstaged and uncommitted on branch
`codex/v0.9-native-youtube-apps`, together with this documentation. No file was
staged, committed, pushed or submitted as a pull request.
