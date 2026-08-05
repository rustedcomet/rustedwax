# Native YouTube simplified playlist metadata — v0.9.2 field record and patch contract

This document records the 2026-08-04 Samsung SM-A125M / Android 12 failure
observed after switching the native YouTube app to the public playlist
`Reggaeton 2016,17,18`. It is the focused contract for RustedWax v0.9.2,
version code 38. The v0.9.1 foreground-Short result remains immutable in
`PHASE_NATIVE_SHORTS.md`; the canonical shared invariants remain in
`BEHAVIOR_CONTRACT.md`.

## Live finding

The user left the playlist playing and asked for read-only inspection. No
YouTube navigation, seek, pause, playlist change or RustedWax setting change
was performed. ADB established that Monitoring, Automatic scrobbling, Look
videos up, Native YouTube and Native YouTube Music were all enabled; Notification
Access and both accessibility services were connected.

RustedWax measured four consecutive ordinary native YouTube songs above the
shared 60% threshold, then kept every one off-chain:

| MediaSession title / artist | Measured | Resolver result |
| --- | ---: | --- |
| `Se Preparó` / `Ozuna` | 188/188 s | no verified id among 61 search candidates |
| `Felices los 4` / `Maluma` | 224/230 s | no verified id among 56 search candidates |
| `Si Tu Novio Te Deja Sola` / `Bad Bunny` | 158/244 s | no verified id among 75 search candidates |
| `No Quiere Enamorarse` / `Ozuna` | 207/213 s | no verified id among 64 search candidates |

There was no Hive authentication, RPC, queue or service failure. The engine
stopped before payload construction because this YouTube build published no
`MEDIA_ID`, `MEDIA_URI`, artwork URI or other exact video-id route. The native
Activity launch intent was the generic launcher intent, the MediaSession queue
was empty, and no usable playlist/video id appeared in notification state.

The fallback search did return ordinary candidates, but v0.9.1 requires the
raw MediaSession title to equal the raw search/watch title plus exact channel
and duration. Native YouTube instead publishes separated music metadata such
as `No Quiere Enamorarse` / `Ozuna`, while the canonical page uses a presentation
title such as `Ozuna - No Quiere Enamorarse (Official Lyric Video)`. Exact raw
title comparison therefore rejects the right upload before the existing title
parser can prove that both describe the same artist/work.

YouTube also emitted exact-ID-less same-title fragments whose duration changed
materially before the full song: measured examples include 32 s → 7 s → 188 s
for `Se Preparó`, 6 s → 11 s → 244 s for `Si Tu Novio Te Deja Sola`, and
7 s → 257 s for `Soy Peor`. Those fragments may represent player transitions or
advertising while organic metadata is already installed. Duration, title and
brand are not ad evidence, so the patch must neither label them ads nor carry
their progress into the later song.

## v0.9.2 patch contract

### 1. Source-scoped structured music identity

Add a resolver route used only for exact-ID-less native YouTube/YouTube Music
MediaSessions that already provide separate non-empty title and artist fields.
The ordinary browser, foreground-Short owner-handle and exact-id routes must
remain unchanged.

The route may accept a candidate only when all of the following hold:

1. bounded YouTube search returns a candidate whose displayed duration is
   absent or within the existing five-second tolerance;
2. the candidate's public watch page is fetched and reports its own exact id;
3. the existing structural title parser reduces the canonical page title to a
   track exactly equal to the native MediaSession title;
4. the parsed canonical credits contain the exact native artist as a complete
   credit, never a substring or fuzzy display-name match;
5. the canonical duration is within five seconds of the finalized duration;
6. every search variant and fetched candidate is considered, and exactly one
   distinct upload satisfies the complete predicate; and
7. final frozen-snapshot corroboration, canonical-link construction, threshold,
   kind, mute, dedup, queue and source-epoch signing checks still pass.

Multiple matching official audio/video uploads remain ambiguous and must all
be refused. A channel-name mismatch cannot be ignored unless the canonical
title itself structurally supplies the exact artist credit. Missing credits,
partial credit strings, malformed pages, excessive candidate sets or network
failure remain off-chain with an exact reason.

### 2. Exact-ID-less material-duration replacement

When a native MediaSession keeps the same normalized title/artist/album, has no
exact id on either observation, and changes to a materially contradictory
duration, the earlier fragment is not a complete independently identifiable
track. Discard its progress and begin the replacement from zero. Do not finalize
the fragment, do not carry its time, do not infer an ad, and do not merge it
into the replacement. Exact-id tracks and browser refinement behavior retain
their existing rules.

### 3. Minimum regression matrix

- the four measured title/artist/duration shapes above resolve only when one
  fully fetched structured candidate is unique;
- official-video, official-audio, lyric-video, Spanish `Video Oficial`, pipe
  suffix and channel-display variants normalize through existing grammar;
- a partial artist string, artist text outside structural credits, unrelated uploader, title
  containment without a structural credit, duration conflict and two matching
  uploads all refuse;
- browser title/channel resolution, foreground owner-handle resolution and
  exact native id precedence do not change;
- 7 s → full, 32 s → 7 s → full and other exact-ID-less same-metadata material
  replacements discard old progress, while bounded duration refinement,
  different metadata and different exact ids retain their current behavior;
- shared manual/automatic, canonical-link, threshold, dedup, lifecycle and
  browser/Short regression suites pass.

## Device acceptance

Install the exact v0.9.2 APK without deliberately changing the user's playlist.
Verify version/hash, settings, Notification Access and both accessibility
bindings. Let subsequent natural playlist transitions exercise the patch. A
qualifying entry may reach Hive only when the new log names one unique
`structured native music title+artist+duration` resolution and the existing
final corroboration passes. Do not repair or rebroadcast the four historical
misses.

## v0.9.2 implementation and deployment result

Implemented in version code 38:

- `NativeStructuredMusicMatcher` uses the existing title grammar, extracts a
  bare or parenthetical `ft.`/`feat.` complete credit, and compares exact
  normalized work and complete credit keys; it never accepts substring artist
  agreement;
- `VideoIdResolver` invokes that route only for ordinary exact-ID-less native
  sessions after raw title/channel recovery fails, refuses candidate sets over
  eight pages, fully fetches every plausible page and requires exactly one id;
- `VideoIdentityCorroborator` repeats the same structured predicate against the
  frozen resolution and independently enriched final watch facts; structured
  results cannot seed the raw title/channel cache;
- a tokenized ten-second native exact-ID-less STOPPED grace allows the measured
  replacement phase to arrive; same semantic metadata plus contradictory
  duration cancels finalization, discards old progress and starts from zero;
  playback resume, lifecycle teardown, foreground-Short ownership and source
  reset invalidate the token; and
- browser, foreground owner-handle and exact native id routes are unchanged.

The complete uncached gate passed **416 tests**, 0 failures, 0 errors and 0
skipped. `assembleDebug` passed. `lintDebug` passed with 0 errors/23 existing
warnings, and `git diff --check` passed.

The exact source APK and `dist/rustedwax-0.9.2.apk` are byte-identical,
14,032,557 bytes, with SHA-256
`5cf7fbfdd950376c8b61ede0a0effc7f843f25b249f47c06172471f18559d072`.
The installed A12 base APK has the same hash and reports version 0.9.2/code 38.
Automatic, Monitoring, lookup and both native switches remained on; Notification
Access, both accessibility bindings and USB stay-awake remained enabled.

YouTube was not navigated or force-stopped during installation. It naturally
continued to `Hey DJ` / `CNCO`; RustedWax joined that already-playing item at
approximately 96/218 seconds, so that partial post-install observation is not a
clean structured-resolution acceptance sample.

That live session did immediately exercise the duration-replacement half of the
patch. A 218-second phase entered STOPPED and waited for the bounded grace; 8.3
seconds later the same title/artist arrived with a 20-second duration. RustedWax
cancelled finalization, discarded the 218-second fragment with zero carry and
made no ad inference. The 20-second phase then entered STOPPED; 5.4 seconds later
the same metadata arrived at 207 seconds and RustedWax again discarded the old
fragment with zero carry and no ad inference. No payload was built for either
superseded fragment. This physically confirms the transition guard against the
measured field shape.

The clean 207-second `Hey DJ` phase then completed at 09:34:46 local time. The
raw resolver failed and the new structured path fully evaluated its candidates.
At 09:34:54 it found two distinct uploads satisfying the exact parsed
work/complete artist/duration predicate (`YN-aYhtMHIw` and `1fb9DtJpbHw`). It
logged the ambiguity, refused every id and built no payload. This is the correct
fail-closed outcome and physically confirms the structured ambiguity branch; it
is not a successful-scrobble sample. A later natural item with one unique match
is still required to validate and reconcile the successful-write branch. The
four pre-patch misses remain off-chain.

## v0.9.3 post-deployment field evidence and patch contract

Continued untouched playback on the exact installed v0.9.2/code 38 artifact
proved that the structured route was reachable but exposed two remaining
ordinary-native gaps. The device, account, settings and Hive transport were
healthy throughout:

| Native item | Measured result |
| --- | --- |
| `Krippy Kush` / `Farruko`, 224/231s | structured recovery stopped before page verification because more than eight search cards had the exact parsed work title |
| `Cuatro Babys`, 278/278s | same premature title-only eight-page refusal |
| `Dile Que Tu Me Quieres` / `Ozuna`, 220/227s | uniquely resolved to `jc70ZO9X0XA`, broadcast and block-confirmed as tx `dfb56dd52634f1da35d34a56e6b0b4a328d3a0d3`; the public profile contained the exact id/title |
| `La Rompe Corazones` / `Daddy Yankee`, 205s | native YouTube recreated its exact-ID-less MediaSession; 106s and 103s fragments were independently refused at 52% and 50% although the continuous listen exceeded the threshold |
| `Te Vas`, 190/197s | two fully corroborated uploads (`_JOnM3jmY0o`, `YSmLgTK2epM`); correctly refused as genuine ambiguity |
| `Andas En Mi Cabeza`, 247/247s | premature title-only eight-page refusal |
| `Con Calma`, 187/193s | premature title-only eight-page refusal |

There was no broadcast failure and no queued operation. Automatic, Monitoring,
lookup and both native switches remained enabled. The `Dile Que Tu Me Quieres`
operation was independently returned by `condenser_api.get_transaction` in
block 108,732,200 and was present in the current public profile HTML. Therefore
the missing items are pre-broadcast identity/progress decisions, not a Hive or
profile-ingestion outage.

The v0.9.3 correction is constrained as follows:

1. Structured candidate budgeting must first require exact parsed work and one
   complete exact artist credit from the search card. Only those candidates,
   already duration-compatible where a card duration exists, count against the
   eight-watch-page network budget. Substring/fuzzy artist agreement remains
   forbidden.
2. Ordinary exact-ID-less native tracks with complete stable title, artist and
   material duration may begin the established raw resolver and, if needed, the
   structured resolver while playing. A unique, fully fetched and corroborated
   result becomes memory-only carry authority for
   that exact track generation; it is not treated as a MediaSession-published id
   and structured authority cannot seed the generic raw-title/channel cache.
3. A vanished native controller may defer progress only after that authority
   exists. A replacement controller receives zero progress until it independently
   resolves to the same immutable video id and its semantic metadata/duration
   still agree. Different ids, ambiguity, lookup failure, duration replacement,
   foreground-Short ownership, opt-out, Stop, listener teardown and timeout all
   refuse or discard the carry.
4. Finalization re-fetches the pre-resolved page and repeats the same raw
   title/channel/duration or structured title/complete-credit/duration predicate
   against the frozen snapshot.
   Genuine multi-upload ambiguity remains fail-closed and no arbitrary upload is
   selected.
5. Identical missing-Short diagnostics on an ordinary YouTube player may be
   coalesced to one bounded reminder. Observer events, proof-loss timing and
   foreground-Short scoring are unchanged.

The physical acceptance gate requires at least one formerly budget-refused
popular song to resolve and block-confirm, one controller recreation to log a
same-id carry (when the item uniquely resolves), continued refusal of a genuine
two-upload ambiguity, no fragment payload, and no browser/YouTube Music/Shorts
regression. Historical misses must not be backfilled or rebroadcast.

## v0.9.3 implementation and deployment result

Implemented in version code 39:

- `NativeStructuredMusicMatcher.couldDescribeTrack` now requires the exact
  parsed work and one complete exact artist credit before a search card enters
  the eight-page set; duration-compatible duplicates remain subject to the same
  hard budget and final uniqueness rule;
- `SessionProbe` requests identity for a stable exact-ID-less native item while
  it is playing, records the resolver route separately from MediaSession
  provenance, and stores progress only after unique immutable authority exists;
- a replacement Watch independently resolves its own item, and
  `TrackProgressCarry` releases the fragment only when both exact ids and
  semantic metadata/duration agree; unresolved and different-id claims leave
  the fragment untouched until bounded expiry;
- finalization re-fetches a carried id through its recorded raw or structured
  route and then repeats the existing final-facts corroboration; structured
  authority still cannot seed the generic cache;
- memory-only authority does not bypass the exact-ID-less ten-second STOPPED
  duration-replacement grace, and a MediaSession-published exact id retains its
  prior behavior; and
- `RepeatedDiagnosticThrottle` plus `NativeShortDiagnosticKey` coalesce dynamic
  callback/refresh prefixes and node counts to one 30-second ordinary-player
  reminder while proof-frozen and proof-expired states emit immediately.

The final uncached source gate passed **422 tests**, 0 failures, 0 errors and 0
skipped. `assembleDebug` passed. `lintDebug` passed with 0 errors/23 existing
warnings, and `git diff --check` passed.

The exact source APK and `dist/rustedwax-0.9.3.apk` are byte-identical,
14,048,941 bytes, with SHA-256
`d18342325d7288f5ccfe16aa549b5752e6ba2fd63d0522cd28e5ae7e65388d88`.
The installed A12 base APK has the same hash and reports version 0.9.3/code 39.
Automatic, Monitoring, lookup, both native switches, Notification Access, both
accessibility bindings and USB stay-awake remained enabled. YouTube was not
navigated or force-stopped.

Two provisional code-39 builds were briefly installed during post-install
auditing to correct resolver-route provenance and measured alternating
diagnostic prefixes. They are not acceptance artifacts. The final exact build
connected at **10:12:21 local time**; field reconciliation must use only later
events. It joined `Sexo, Sudor y Calor` / `J Alvarez` at 251 seconds and launched
early resolution immediately. That lookup returned zero parseable candidates,
so it correctly established no carry authority and made no guess. The final
build emitted one equivalent ordinary-player missing-Short diagnostic rather
than the prior callback-rate flood, physically confirming the throttle. A
natural unique native write and a uniquely resolved controller recreation
remain the two open physical acceptance samples. No historical miss was
backfilled or rebroadcast.

The joined `Sexo, Sudor y Calor` phase later completed at 215/251 seconds. Its
final lookup also returned zero parseable candidates, so no id or payload was
manufactured. The next natural `Te Boté (Remix)` phase re-ran after a duration
replacement and found two fully corroborated structured uploads
(`9jI-z9QN6g8`, `bvEf7FOscm8`); it correctly established no carry authority and
remained off-chain. Ordinary-player missing-Short reminders were measured
roughly 30–46 seconds apart instead of at callback rate. These are valid
fail-closed/throttle samples, not the still-pending unique-write or same-id
continuation acceptances.

## v0.9.4 post-deployment field evidence and patch contract

The untouched v0.9.3/code-39 run was captured through 10:44:56 local time before
YouTube was navigated, force-stopped or otherwise disturbed. The A12 was still
connected as `R58R215V2SA`; the installed base APK and release artifact both had
the expected SHA-256
`d18342325d7288f5ccfe16aa549b5752e6ba2fd63d0522cd28e5ae7e65388d88`.
Notification Access, both accessibility services, Automatic, Monitoring, lookup,
both native switches and USB stay-awake (`2`) were all intact.

Every qualifying failure occurred before payload construction. MediaSession
continued to publish clean title/artist/duration but no exact id, progress and 2x
speed accounting reached the expected final percentages, and no post-boundary
controller removal/replacement occurred. Four successful writes in the same run
were independently returned by both `api.hive.blog` and `api.deathwing.me` and
all four exact ids were present on the public `skiptvads.vidz` Music profile:

| Native item | Final decision |
| --- | --- |
| `Sexo, Sudor y Calor` / `J Alvarez`, 215/251s | no search cards were returned for either public query; no id, authority or payload |
| `Te Boté (Remix)` / `Nio Garcia`, 418/418s | two exact structured uploads (`9jI-z9QN6g8`, `bvEf7FOscm8`); correct ambiguity refusal |
| `Si No Te Quiere (feat. D.OZi)` / `Ozuna`, 226/226s | resolved `K6aqdUp-OgY`; tx `035f2a8230acaddb6cdce7abe50aabff10fe0d1f`, block 108,732,998 |
| `Criminal` / `NATTI NATASHA`, 266/273s | the exact page `VqEbCxg2bNI` is titled `Natti Natasha ❌ Ozuna - Criminal [Official Video]`, authored by `NATTI NATASHA`, and 273s; the structured matcher dropped that independent exact author credit after parsing the title's collaboration prefix, so final resolution found no match |
| `La Pregunta` / `J Alvarez`, 270/270s | two duration-compatible exact uploads (`wYow7Sljr_4`, `wsqEBEHZDmQ`); correct ambiguity refusal |
| `Vuelve` / `Daddy Yankee`, 273/280s | resolved `YxZXLWIx6ik`; tx `fc925fd9b329a3ffe1f11bb24c9ce3799561b93b`, block 108,733,148 |
| `Si Te Dejas Llevar` / `Release - Topic`, 228/228s | evidence is irreconcilable: `QkngZ1P3aKw` is 228s but credits Ozuna/Juanka, while the exact `Release - Topic` page `B5eRr5sds-M` is 218s; correct refusal |
| `Ella Y Yo (Feat. Don Omar)` / `Aventura`, 261/268s | the native feature suffix was not reduced to the work on the same grammar used for candidate titles, so the prefilter returned zero; public pages show multiple duration-compatible uploads once that exact suffix is normalized, so the corrected result must be ambiguity rather than an arbitrary id |
| `Si Se Da (Remix)` / `Myke Towers`, 332/332s | two matches during pre-resolution and three at finalization (`CbEst0K063c`, `XuRWW4kvMNg`, `V_wCIyu6OyY`); correct ambiguity refusal |
| `Ahora Dice` / `Chris Jedi`, 270/270s | resolved `Hn2l8LbvXsY`; tx `801fc9a97a0c65e1b62986f4f0ee459863781032`, block 108,733,399 |
| `Vaina Loca` / `Ozuna`, 174/176s | resolved `0XvGIM_hwDc`; tx `cd5042cfebed217f14c7f895f87b484fb0496d03`, block 108,733,446 |

The next correction is deliberately limited to structured native music evidence:

1. A search-card or final watch-page author/channel is an independent complete
   credit even when `TitleParser` extracts a different multi-artist credit from
   the presentation title. It may satisfy the native artist only by exact
   normalized equality; substring, token-overlap and fuzzy agreement remain
   forbidden.
2. The native MediaSession title and candidate parsed track reduce only the same
   explicit trailing `ft.`, `feat.` or `featuring` suffix before exact work
   comparison. No general parenthetical or remix removal is added here.
3. Duration tolerance, the eight-page budget, full page fetch, final re-fetch,
   complete result-set uniqueness and every ambiguity refusal remain unchanged.
   `Ella Y Yo`, `La Pregunta`, `Te Boté (Remix)` and `Si Se Da (Remix)` must still
   refuse when more than one id survives.
4. Raw browser/Brave/Chrome resolution, native exact-id routes, controller carry,
   threshold calculation, payload construction and Hive transport are untouched.

Before release, focused fixtures must prove the unique `Criminal` author-credit
case, exact native featured-work normalization, unrelated-author refusal, the
measured `Ella Y Yo` ambiguity, the `Si Te Dejas Llevar` artist/duration
contradictions and the existing ambiguity/budget controls. Then run the complete
uncached unit/assembly/lint gate, install and hash one version-code-40 artifact,
verify all preserved settings/services, and allow natural A12 playback to test a
unique structured write. Same-id controller recreation remains a separate
physical row unless YouTube naturally produces it; no historical miss is
backfilled or rebroadcast.

## v0.9.4 implementation and deployment result

`NativeStructuredMusicMatcher` now keeps the canonical author/channel as an
independent complete exact credit and reduces the same explicit trailing feature
grammar on the native and candidate work. Three new measured-field tests cover
the unique `Criminal` author case, featured-title ambiguity and the irreconcilable
`Si Te Dejas Llevar` tuple. Focused matcher and adjacent resolver/carry/browser
suites passed, followed by this complete gate:

```text
:app:testDebugUnitTest  PASS — 425 tests, 0 failures/errors/skips
:app:assembleDebug      PASS
:app:lintDebug          PASS — 0 errors, 23 warnings
git diff --check        PASS
```

The source APK and `dist/rustedwax-0.9.4.apk` are byte-identical, 14,048,941
bytes, with SHA-256
`cbaebadc91d0045b6bd7a2abec8aaacefb2e914782a404d705b24890d4c9ccbf`.
The exact artifact installed successfully and connected at 10:59:34 local. The
A12 reports v0.9.4/code 40 and the installed base APK has that same hash.
Automatic, Monitoring, lookup, both native switches, Notification Access, both
accessibility bindings and USB stay-awake (`2`) were preserved. YouTube was not
navigated or force-stopped.

The new build joined `El Efecto` mid-track and repeated the exact two-upload
structured ambiguity (`hEiI7FT84kY`, `gENTa8g6x78`), establishing no carry
authority and preserving the fail-closed control. The untouched continuation
also kept `La Forma En Que Me Miras`, `Diosa`, `Noches de Aventura`,
`Por Amar A Ciegas` and `Hace Mucho Tiempo` off-chain when each produced
multiple exact candidates. `La Occasión` and `Más Que Ayer` exhausted the
bounded structured search without a verified candidate. Provisional or
replacement duration phases (including 15→236 seconds and 185→239 seconds)
were discarded with zero carry; the unresolved `La Forma En Que Me Miras`
controller replacement claimed no time.

The clean code-40 positive then completed naturally. `Ella Y Yo (feat. Farruko,
Ozuna, Arcangel, Anuel AA, Bryant Myers, Kevin Roldan, Ñengo Flow,...)` /
`Pepe Quintana - Topic` settled through 12→30→416-second phases, discarding
the provisional fragments, and uniquely resolved to `CGjuWHEPxgc`. The stable
phase established immutable carry authority, played 416/416 seconds, re-fetched
the same page at finalization and built one canonical linked payload. Hive
block-confirmed tx `49a46d159658d257706c9a3c6b32eed4ddd29ca1`; both
`api.hive.blog` and `api.deathwing.me` independently returned the exact
operation in block 108,734,261, and the public profile indexed
`CGjuWHEPxgc`. No playlist navigation, force-stop, historical backfill or
ambiguity weakening was used.

Immediately before the code-40 installation, the untouched code-39 run supplied
the missing same-id controller acceptance. `Unica` first established
`7uxTya2PX3c`, its controller disappeared for 14 seconds, and the replacement
independently resolved the same id before claiming 87 seconds. The aggregate
finalized once at 215/218 seconds, re-fetched the same page, broadcast one
canonical payload and block-confirmed as tx
`70cc45e0c27cff4a0d758c2cf414b4de6a521b38`. Both `api.hive.blog` and
`api.deathwing.me` returned the exact operation in block 108,733,719 and the
public profile contained `7uxTya2PX3c`. No forced controller churn or historical
backfill was used.
