# RustedWax behavior contract

This document is the canonical behavior reference for RustedWax. It separates
observed implementation from intended product behavior so that a test, comment,
or incident note cannot silently become product policy.

The source code decides what a particular build actually does. This document
decides what a conforming build is supposed to do. `README.md` describes the
product, `TESTING.md` verifies this contract, and `PHASE*.md` files are historical
design records rather than current specifications.

> **Current checkpoint:** v0.8.15/version code 35 implements the bounded
> log-20 correction below and passed its 349-test uncached source/build/lint
> gate. Final device log 21 contains 98 unique, block-confirmed broadcasts, all
> reconciled to the signed-in profile, with no new advertisement payload,
> duplicate id or RustedWax crash signature. It also records four conservative
> identity-resolution omissions, one organic veto from ad-evidence transition
> carry, and incomplete replay of the historical fixture matrix. The user
> accepted those documented exceptions as the Phase 4 final product. Logs 14,
> 16, 17, 18, 19, 20 and 21 remain immutable evidence.
>
> Current development source is v0.9.4/version code 40. The native package
> boundary, foreground Shorts route, simplified-music resolver, duration-phase
> isolation, artist-aware candidate budget and immutable same-id continuation
> contract below are implemented. The bounded foreground-Short device/write gate
> passed. The subsequent ordinary-native run block-confirmed unique writes and
> one same-id controller recreation; the v0.9.4 matcher correction is installed
> for continued A12 testing. Both native settings still default off.
> Nothing in v0.9 changes the accepted v0.8.15 browser history or invariants.

## As-built audit: v0.8.6 before reconciliation

This table was written against the v0.8.6 source before the reconciliation work
below changed runtime code. It is intentionally retained as an incident record.

| Area | What v0.8.6 actually does | Source of truth in v0.8.6 | Conflict found |
| --- | --- | --- | --- |
| Qualifying playback | Uses the configured threshold, a 30-second ordinary floor, and a 10-second floor for a `/shorts/` identity whose resolved facts say it is listed | `ScrobbleEngine.onTrackFinalized`, `ScrobbleRules.prefilter`, `ScrobbleRules.decide` | The Now card displays a hard-coded 60% verdict even when the configured threshold differs |
| Looping short | Rejects a verified short under 30 seconds when accumulated progress is above 200%, with `looped unattended rather than watched` | `ScrobbleRules.SHORT_MAX_PROGRESS` and `ScrobbleRules.decide` | `TESTING.md` says both “one scrobble” and “nothing”; the implementation does not detect a loop event, it only rejects high accumulated progress |
| Transaction count | Computes up to two percentages, then caps every non-song kind to one transaction | `ScrobbleRules.decide`, `ScrobbleRules.capForKind` | The existing one-transaction cap already prevents a looping video from producing a second transaction, making the separate total rejection a conflicting policy |
| Session recreation | Finalizes the disappearing fragment first, then stores its accumulated progress for a replacement session with matching package plus title/artist/album/duration metadata | `SessionProbe.Watch.onSessionDestroyed`, `Watch.dispose`, `TrackProgressCarry` | README says the listen is scored once as a whole and says carry is keyed by video; neither statement is true in v0.8.6 |
| Genuine replay near a restart | May inherit stored progress when it reappears with the same metadata within 60 seconds | `TrackProgressCarry.TTL_MS` and metadata-derived `trackKey` | Two separate partial viewings can be combined; only a replay after the TTL is guaranteed to start fresh |
| Stop | Tears down the probe without finalizing current tracks and clears notification hints and carried progress | `RustedWaxListenerService.stopProbe`, `SessionProbe.stop(false)` | It does not clear `UrlEvidence`, and `onNotificationRemoved` still reads the removed notification title while stopped |
| Notification access disclosure | Reads media-notification title, text, and subtext for Brave and Chrome only | `RustedWaxListenerService.onNotificationPosted` | The access banner incorrectly calls the listener a stub and says notification contents are not read |
| Short verification | Treats `VideoFacts.resolvedOnWatchPage` as true whenever `lengthSeconds` is present | `VideoFacts.resolvedOnWatchPage` | A YouTube Music-only fallback supplies a length and can therefore be described as a resolved watch page even though the watch page failed |
| Broadcast success | Reports `Success` for block inclusion, a final mempool observation, no expected transaction id, or a completely unanswered confirmation check | `HiveRpc.broadcast` and `HiveRpc.confirm` | UI says `Confirmed on-chain` for all four cases |
| Confirmation node | Returns the first nonblank transaction status in configured node order, normally starting with the accepting node | `HiveRpc.transactionStatus` | Comments and documentation say confirmation is checked against a different node and that every node is considered |
| Retry queue ownership | Stores the original username and serialized payload, but retries with whichever posting key is currently saved | `BroadcastQueue.Entry`, `ScrobbleEngine.flushQueue` | Changing accounts can sign an old account's queued operation with the new account's key, after which rejection removes it |
| Retry queue durability | Stores JSON in `filesDir`, silently turns read/parse errors into an empty queue, silently ignores write errors, and removes an entry after eight queued failures | `BroadcastQueue.read`, `write`, and `recordFailure` | “Durable” overstates behavior when persistence and terminal loss are not surfaced |
| Queued History data | Stores no percent or video id in the queue; a later successful retry adds a new in-memory History row using 0% and no video id | `BroadcastQueue.Entry`, `ScrobbleEngine.note` | History does not accurately describe the payload that was eventually sent |
| Manual session broadcast | Requires a buildable payload, a non-muted video, and a free dedup claim, but does not apply automatic thresholds or duration/short rules | `MainActivity.onBroadcastSession` | The button is a rules bypass even though it is labelled as an ordinary scrobble action |
| History persistence | Keeps the newest 50 records in a process-memory `StateFlow` | `ScrobbleEngine._recent` | History disappears on process death; current documentation must not imply it is an on-device permanent ledger |

## Product invariants

These are the intended rules. A behavior change must update this section first,
then implementation, tests, UI text, README, and TESTING in that order.

1. **One continuous qualifying video viewing produces at most one scrobble.**
   A video that crossed the configured threshold has earned that scrobble.
   Auto-looping may be recorded diagnostically, but must not erase the earned
   viewing or create a second transaction. A `/shorts/` source stays capped even
   when its payload is correctly classified as `song`. An observed playback
   position reset from the end of the same media item to its beginning is a loop
   for this purpose and also caps the continuous viewing to one transaction,
   regardless of payload kind. A separate viewing after the continuation window
   remains a new listen.
2. **Browser session churn is not a track ending.** Progress may move to a
   replacement session. Only a real track change, a stopped playback state, or
   expiration of the continuation window finalizes the listen.
3. **Stop is a hard observation boundary.** Pressing Stop does not finalize the
   current track, clears notification, URL, playlist, and carried-progress
   evidence, and all notification/accessibility callbacks return before reading
   content while stopped.
4. **Broadcast states remain distinct.** `in block`, `seen in an independent
   healthy node's mempool`, and `accepted but confirmation unavailable` are not
   displayed as the same state. Ambiguous acceptance is not automatically
   retried because that could create an irreversible duplicate.
5. **Queued work is account-bound and visibly durable.** An entry is only signed
   with the matching saved account. Persistence failure and terminal removal
   are visible. History reconstructed from a queue retains percent and video id.
6. **One rule implementation serves automatic behavior, manual session
   broadcast, and UI verdicts.** The configured threshold and the same duration,
   short, mute, and dedup rules apply everywhere. The separate synthetic
   broadcast test remains an explicit transport test and is not a listen.
7. **Evidence provenance is literal.** “Watch page resolved” can only be true
   when the watch-page parser resolved that page. YouTube Music fallback evidence
   is useful metadata but is not renamed into watch-page evidence.
8. **Current specifications and historical notes stay separate.** README and
   TESTING describe the shipping build. PHASE documents may explain earlier
   behavior but cannot override this contract.
9. **Explicit ad evidence is a veto, never a brand-name guess.** An unlisted
   `/shorts/` identity is never scrobbled automatically or manually, regardless
   of duration, progress, the Short-clips setting, or which enrichment source
   supplied the flag. When optional browser accessibility is enabled, an exact
   visible YouTube ad control or label (for example `Sponsored`, `Skip ad`, or
   their supported localized equivalents) bound to the current `/shorts/`
   video id is the mobile analogue of the desktop connector's `.ad-showing`
   state and is also an unconditional veto. The evidence follows the track
   across Chrome session recreation. An id and label first observed together in
   the transition frame immediately after the URL changes are provisional, not
   a veto: the same id/label pair must be re-observed in the same URL generation
   or agree with an already-established active session. Channel names, brands,
   title vocabulary, crawlability, and view counts are not ad rules. A promoted
   public video for which YouTube exposes no explicit ad label remains
   indistinguishable from organic content and is handled by the user mute list.
   Through v0.8.13 this scanner ran only for a concrete `/shorts/{id}` snapshot;
   log 19 proved that an exact label on ordinary watch playback was not even
   inspected. v0.8.14 closes that inspection gap by binding exact label
   evidence to the MediaSession track instance, never to the organic watch URL.
10. **A YouTube Music podcast type is not song evidence.**
    `MUSIC_VIDEO_TYPE_PODCAST_EPISODE` must not classify an item as `song`.
    RustedWax has no dedicated podcast payload path, so absent independent music
    provenance it remains `video`.
11. **Every YouTube scrobble has one verified canonical hyperlink.** A concrete
    11-character video id may come from the active browser URL, a matching
    playlist entry, or search followed by identity corroboration. Search-card
    evidence is not enough when channel or duration is absent: the candidate's
    watch page must complete those fields. Multiple matching ids are ambiguous.
    If no unique id is verified, automatic and manual session paths record a
    refusal and do not broadcast. A URL-less YouTube payload is never an
    accepted degradation mode.
12. **A finalized track is snapshot-isolated through broadcast.** Title, artist,
    album, duration, played time, start timestamp, loop/ad state, identity and
    resolver context are the ended track's immutable evidence bundle. Resolver
    and enrichment work may complete that bundle, but may not consult a later
    foreground track or replace the ended metadata unless the resolved id is
    corroborated against that same frozen bundle. A mismatch is a visible
    refusal, never a payload assembled from two tracks.
13. **Metadata refinement is not a track change.** The same title, artist and
    album remain one continuous track when duration changes only by the small
    MediaSession rounding tolerance or arrives after being absent. Progress,
    identity, loop/ad state and the original timestamp survive that refinement.
    A material duration conflict or a title/artist change remains a real ending.
14. **Song title parsing never splits inside syntax it has not understood.** A
    separator inside balanced parentheses, brackets or quotes is not an
    artist/track boundary. Explicit quoted/performance shapes and channel-aware
    orientation outrank a generic separator. If orientation is still
    ambiguous, retain the cleaned whole title and channel rather than inventing
    confident but reversed credits.
15. **Native packages are separately opt-in and package-isolated.** Browser
    packages remain accepted exactly as in v0.8.15. `com.google.android.youtube`
    and `com.google.android.apps.youtube.music` each require their own persisted
    setting, both default off. Package, source epoch and semantic track identity
    key all progress and continuation state. Stop, opt-out, listener rebuild and
    package teardown invalidate native in-flight snapshots and clear pending
    continuation/resolver candidates. No notification hint, URL, playlist, ad,
    accessibility or resolver evidence moves between browser, YouTube and
    YouTube Music packages.
16. **Native origin is not video identity.** An enabled native package proves
    YouTube origin only. A broadcast still requires one exact 11-character id
    from MediaMetadata media id, canonical YouTube media URI or canonical
    YouTube artwork URI, or exactly one existing-resolver candidate
    corroborated by the immutable native title, artist/channel and duration.
    Every accepted route produces a canonical `youtube.com/watch?v=` link.
    Invalid, contradictory, unresolved and ambiguous identities stay
    off-chain. Runtime History access remains forbidden.
17. **Native metadata and advertisement evidence stay literal.** Supplied
    native title, artist and album remain separated and are not unnecessarily
    replaced by browser-shaped parsing or fetched presentation. Native YouTube
    Music origin is strong music context below explicit podcast/episode,
    structured non-music genre and hard format evidence; native YouTube uses the
    existing classifier. Browser accessibility coverage and visible-ad evidence
    never apply to native packages. A native ad veto requires a proven generic
    literal structured MediaSession signal. Until physical evidence establishes
    one, no title/brand/id/channel/duration/popularity/History heuristic is
    allowed and the default-off UI must disclose the limitation.

## Historical v0.8.7 reconciliation plan

This completed order is retained to explain the v0.8.7 reconciliation. It is
not the active v0.8.11 contract, which appears in its own section below:

1. Remove the high-progress short rejection, expose an inferred loop diagnostic,
   and retain the existing one-transaction cap for videos.
2. Change session disappearance into a continuation window. Consume the pending
   fragment when a matching replacement appears; otherwise finalize once when
   the window expires. Stop cancels pending continuations.
3. Make Stop clear URL evidence and guard notification-removal callbacks before
   reading extras.
4. Give broadcast results explicit block, mempool, and accepted-unconfirmed
   states. Poll every eligible independent node and select the strongest
   response instead of returning the first response.
5. Bind queue retries to the saved username, retain percent/video id, surface
   persistence errors, and record terminal queue failures.
6. Route the Now verdict and manual session button through the configured
   threshold and the same `ScrobbleRules` decision.
7. Store explicit watch-page provenance in `VideoFacts` and invalidate legacy
   cache entries that cannot provide it.
8. Correct disclosures and status wording, add regression tests for every pure
   rule introduced above, then run uncached unit tests, APK assembly, and lint.

## Reconciled implementation: v0.8.7

The working v0.8.7 implementation applies the plan as follows:

| Contract area | v0.8.7 implementation |
| --- | --- |
| Looping video | High progress no longer rejects a Short. `Decision.probableLoop` starts above 125%; the engine logs it and `capForKind` keeps every `/shorts/` source to one transaction regardless of payload kind. This is an inference from accumulated progress, not direct observation of a browser loop event. |
| Session recreation | Session disappearance stores aggregate progress and schedules a token-checked expiry. A matching replacement consumes it before any finalize; expiry finalizes once if no replacement appears. Metadata change and STOPPED remain immediate real endings. |
| Stop | User Stop cancels in-flight continuations, clears `NotificationHints`, `UrlEvidence` including playlist evidence, and carried progress. Posted and removed notification callbacks both return before reading content while stopped. |
| Broadcast evidence | `Success` requires independent block or mempool evidence and records which. `AcceptedUnconfirmed` represents acceptance with no independent answer. Confirmation excludes the accepting node, checks every other current node, and selects block over mempool over unknown regardless of node order. |
| Queue | Entries persist username, serialized payload, label, percent, and video id. Retry requires a matching current username. Writes use an atomic replace where supported; corruption is preserved and storage/terminal failures are logged and added to History when a track record is available. |
| Shared rules | Automatic finalization and manual session broadcast call the same `ScrobbleRules.decide` inputs. The Now card displays the configured threshold. The Account tab synthetic operation remains a transport-only test. |
| Evidence provenance | `VideoFacts.watchPageResolved` is set only by `WatchPageParser`; a YouTube Music-only result leaves it false. Legacy cache entries without the field are treated as misses and refreshed. |
| UI truthfulness | Notification Access disclosure names the media-notification fields read. Confirmation wording distinguishes block, mempool, and unavailable confirmation. Compose observes probe errors rather than reading an unobserved `StateFlow.value`. |

Known limits deliberately left explicit:

- A replacement is matched by browser package plus title/artist/album/duration
  metadata because Android does not put the YouTube video id in MediaSession.
  A genuine identical replay inside the one-minute continuation window is
  indistinguishable from browser churn; after the window it starts fresh.
- History and Not logged retain their newest rows in process memory, not a
  permanent local database.
- Dedup remains local to one device, so desktop and phone can still produce two
  operations for the same listen.
- MediaSession churn, Stop callback delivery, and node behavior require the
  physical-device checks in `TESTING.md`; pure JVM tests cannot simulate Android
  controllers or a live Hive network.

## v0.8.8 patch contract

The 2026-07-30 v0.8.7 device run exposed three narrower contradictions:

| Evidence | v0.8.7 behavior | v0.8.8 required behavior |
| --- | --- | --- |
| A 42-second `/shorts/` ad resolved as `listed=no (unlisted)` | It cleared the ordinary 30-second floor and scrobbled because `isUnlisted` was only consulted while selecting the lowered Short floor | Reject an explicitly unlisted Short before duration and progress rules |
| A two-minute timer returned `MUSIC_VIDEO_TYPE_PODCAST_EPISODE` | Every `MUSIC_VIDEO_*` value counted as catalogue music, so the Education video became `song` | Exclude the podcast type from catalogue music evidence |
| The timer ended near 125 seconds and its replacement session restarted near zero | Only high progress on a verified Short inferred a loop; this watch-path `song` received the ≥160% second transaction | Detect the end-to-start position wrap across callbacks or session recreation, carry the signal, and cap the continuous viewing to one transaction |

The position-wrap rule is deliberately strict: the previous position must be in
the final 20% of the item, the new position in the first 20%, and the backward
jump at least half the duration. Ordinary backward seeking does not qualify.
MediaSession does not expose whether the reset was automatic or user-initiated,
so an immediate replay inside the same continuous session is intentionally
treated as one looped viewing. A later separate session remains eligible as a
new listen.

## v0.8.9 patch contract

The 2026-07-30 v0.8.8 device run exposed two failures that interact at the
continuation boundary:

| Evidence | v0.8.8 behavior | v0.8.9 required behavior |
| --- | --- | --- |
| The desktop connector rejects `document.querySelector('.ad-showing')`, while a public promoted POND'S Short was indistinguishable in MediaSession and watch-page metadata | RustedWax could only reject unlisted creatives or mute a leaked public video after the fact | With optional browser accessibility enabled, inspect visible YouTube UI text for exact ad labels, bind the signal to the current `/shorts/` id, carry it across session recreation, and veto both automatic and manual broadcast |
| A Karol G Short expired after the address bar had moved to `ysY13cbxJR4`; corroboration unlatched that new id but `latchedVideo ?: live` returned the rejected `live` value anyway | The payload used the next Short's title, artist and URL with the ended Short's timestamp, duration and progress | A video id rejected during corroboration cannot be returned on that pass, and a disappearing track freezes the last identity observed while it was active; continuation expiry never consults the later foreground URL |

The accessibility ad detector is deliberately narrow. It accepts explicit UI
labels and controls, not arbitrary occurrences of words such as “ad” in a video
title or description. It only creates a persistent track veto when the same
accessibility snapshot supplies a YouTube `/shorts/` id. Watch-page pre-rolls
keep their existing duration and identity-mismatch guards; marking the watch
URL itself as an ad would incorrectly veto the real content behind the pre-roll.

Identity freezing is also fail-closed. If a disappearing track was only proven
to be YouTube without a video id, continuation expiry keeps that site-only
identity and lets the engine's existing resolver try to recover the id. It does
not borrow a later tab's live id. When a matching replacement MediaSession
claims the continuation, a confirmed identity and explicit-ad flag travel with
the accumulated progress.

## v0.8.10 patch contract

Log 12 exposed six consecutive immutable `kind: video` entries whose profile
titles had no hyperlinks. All six payloads omitted `url`. The address-bar
watcher had only a bare `m.youtube.com` host, and the fallback then reported
zero or non-confident search results before the engine deliberately continued.

The search diagnosis was more specific than “YouTube had no ids.” Current
YouTube search pages render Shorts through `shortsLockupViewModel`: the id is
nested under `reelWatchEndpoint`, the title is plain `content`, and channel and
duration are absent from the card. The parser only understood older
`videoRenderer` nodes with `title`, `ownerText`, and `lengthText`, so it could
discard a page full of Short ids and report zero candidates.

v0.8.10 requires all of the following:

- parse legacy video results, modern ordinary-video lockups, and modern Shorts
  lockups;
- retry network fetches once and try raw-title, noise-stripped title+channel,
  and noise-stripped title search variants;
- treat hashtags, emoji, punctuation, quote style, and title diacritics as
  presentation noise while still requiring channel and duration agreement;
- complete incomplete Shorts candidates from their own watch pages and accept
  only one matching video id; ambiguous ids remain unresolved;
- stop automatic finalization before enrichment, payload construction, dedup,
  or signing when no id is verified;
- make payload construction and the central broadcaster independently reject a
  YouTube listen without a canonical watch URL, including manual and legacy
  queued paths; and
- surface the refusal in Not logged and in the quiet-address-bar warning.

This guarantees hyperlink presence, not perfect capture. YouTube can still
withhold enough identity evidence that a real viewing is not broadcast. That
loss is visible and retryable in a future design; an immutable linkless or
wrong-link transaction is not.

## v0.8.11 patch contract (implemented; automated gate passed)

The 2026-07-31 through 2026-08-01 v0.8.10 field run is recorded in log 14 and
was reconciled against the signed-in YouTube History plus both live
`skiptvads.vidz` profile sections. The transport and hyperlink boundary passed:
109 of 109 broadcast payloads received block confirmation, all 109 were visible,
all carried their matching 11-character YouTube hyperlink, all 54 `song`
payloads appeared under Music, all 55 `video` payloads appeared under Videos,
and no ad-like payload reached the profile.

The run was nevertheless not a clean behavioral pass. v0.8.11 implements the
required outcomes below with exact automated regressions. The APK was generated
and its first physical-device reconciliation was subsequently attempted; log 16
failed that release gate for the separate follow-up defects documented below:

| Evidence | v0.8.10 outcome | v0.8.11 required outcome |
| --- | --- | --- |
| `saGYMhApaH8`, “Me Porto Bonito”, finalized at 193/191 seconds | The payload used the following `3mchJ-EW9rM` La Bebe facts and id | The ended session either broadcasts its own corroborated id and facts or is visibly refused; the next track can never complete it |
| `aZaxQG3ggng`, “Crazy”, finalized at 194/192 seconds | The payload used the following `2QqyPy2itXw` Coming Home facts and id | Resolver, enrichment and payload construction remain bound to the immutable ended snapshot |
| `IW524Zl2Pus`, “The best cosplayer avengers”, finalized at 23/22 seconds | A stale `Sponsored` label from the preceding six-second stadium ad was bound two milliseconds after the URL advanced, so the legitimate Short was vetoed | A transition-frame label is provisional and cannot veto the new Short unless re-observed for that stable URL/session generation |
| `5YrJf3CpHNk`, “Cardi B - Trump”, published duration `227125` then `227124` ms | Exact-duration track keys split one continuous viewing into 48% and 53%; both fragments failed separately | A same-metadata duration refinement within 2 seconds remains one track and qualifies on accumulated progress |
| Four song titles containing `|`, quoted tracks or a dash inside parentheses | Generic separator parsing produced malformed or reversed artist/title pairs | Only top-level separators are candidates; explicit shapes and channel agreement determine orientation, otherwise parsing remains conservative |

### Implemented boundaries

1. **Finalize-to-broadcast isolation.** Capture one immutable finalized bundle,
   including the playlist/resolver context known while the track was active.
   Resolution returns structured evidence rather than a bare id. Before facts
   may replace session title/artist, the candidate id, title, channel and
   duration must be corroborated against the frozen bundle. No coroutine may
   read a later foreground URL or playlist generation on behalf of an ended
   track.
2. **Transition-safe ad evidence.** Give every observed URL change a generation.
   An ad label first seen in the same transition frame is held provisionally and
   becomes a veto only after the same id/label is re-observed in that generation
   or the active session was already established for that id. Clear provisional
   evidence on another URL change, label disappearance, Stop, or package reset.
   Persist the accepted veto with the track instance, not merely the package/id
   for thirty seconds.
3. **Semantic track continuity.** Replace exact string equality on duration with
   a same-track predicate. Equal normalized title, artist and album plus a
   missing-to-known duration or an absolute duration drift no greater than
   2,000 ms is metadata refinement. Preserve accumulated content time and all
   evidence. Larger changes are not automatically equivalent and must pass the
   existing real-track-change rules.
4. **Structure-aware song parsing.** Scan separators only at top level, add
   explicit `Artist "Track" (qualifier)` and `Artist Performs "Track" | Event`
   shapes, and use cleaned channel agreement to decide `Artist - Track` versus
   `Track - Artist`. The four log-14 payloads become regression fixtures. A
   low-confidence pair falls back to channel plus whole cleaned title.

### Regression fixtures and acceptance gates

- `saGYMhApaH8` must never produce `3mchJ-EW9rM` metadata or URL, and
  `aZaxQG3ggng` must never produce `2QqyPy2itXw` metadata or URL.
- The exact stadium-ad to `IW524Zl2Pus` ordering must reject the stadium ad and
  retain the legitimate Short's qualifying view.
- `227125 → 227124` ms with unchanged Cardi B metadata must produce one final
  snapshot with combined progress and one eligible decision.
- Parser outputs are pinned as `Ice Spice` / `Think You The Sh*t (Fart)` for
  `vG4h2KkwMDA`, `Sexyy Red` / `Get It Sexyy` for `VpXRPrwezQ8`,
  `6IX9INE` / `Gotti` for `z5WrgDzNIZ0`, and
  `6ix9ine & Nicki Minaj` / `TROLLZ` for `oNg3M9IJJlY`; no separator inside
  parentheses may split.
- The full existing unit suite, APK assembly and lint must remain green, then a
  physical-device run must again reconcile the log, YouTube History, block
  confirmations, Music, Videos, ads, loops and hyperlink targets.

This patch does not rebroadcast or repair immutable historical entries, change
the 60% threshold, change Hive confirmation semantics, decide the debatable
flashmob classification, or replace the prototype YouTube lookup route. Those
are separate product or distribution decisions.

## v0.8.11 field follow-up contract (implemented; automated gate passed)

The first generated v0.8.11 artifact was `dist/rustedwax-0.8.11.apk`, version
code 31, SHA-256
`bdf81bcc560dea1c5a430d869193d702480f15f95c19f4d211abbb320ca4e296`.
Its source gate completed 290 tests with no skips, failures or errors; debug APK
assembly and lint succeeded. `debug/rustedwax-log (16).txt` records that first
physical-device attempt on 2026-08-01. It includes the earlier log-15 export
plus the continued session, so log 16 is the complete app-log evidence for this
attempt. No independent YouTube History/profile reconciliation was supplied for
the run; block outcomes below come from the app's independent-node evidence.

### What the field attempt proved

- The address-bar watcher was not frozen. Chrome advanced from URL generation
  2 through generation 15, including a deliberate move from the original
  `RDws00k_lIQ9U` mix to `RD2u5UTPEDGAw` after Lollipop.
- Eighteen tracks finalized, eleven produced visible skip decisions, seven
  payloads were broadcast, and all seven received block confirmation. No
  duplicate transaction or ad-like payload was emitted.
- Finalized snapshot isolation failed safely: successor facts were refused for
  Soy Peor, Me Porto Bonito and DÁKITI rather than being mixed into an ended
  payload. That satisfies the no-corruption boundary but not the release
  requirement of zero app-side qualifying omissions.
- During the likely screen-off interval Chrome repeatedly removed and recreated
  its MediaSession. Progress for `2u5UTPEDGAw` was carried at 134, 172 and 177
  seconds without duplicate finalization. The export ended before the pending
  60-second continuation window produced a final engine outcome, so that
  viewing is unresolved evidence rather than a pass or failure.

### Newly confirmed defects

| Evidence | v0.8.11 field outcome | Required follow-up outcome |
| --- | --- | --- |
| Soy Peor: page `BAD BUNNY - SOY PEOR (Video Oficial)`, MediaSession `BAD BUNNY - SOY PEOR (Official Video)`, correct id `ws00k_lIQ9U` | The active latch rejected its own id; after the URL advanced, snapshot isolation visibly refused successor `saGYMhApaH8` | Localized promo wording is presentation, not a different track; retain the observed id when the normalized structures and duration corroborate |
| Me Porto Bonito: page credit without parentheses and `Video Oficial`, MediaSession credit with `(ft. …)` and `Official Video`, correct id `saGYMhApaH8` | The active latch rejected its own id; successor `jZGpkLElSu8` was later refused | Parentheses/punctuation around the same credit and localized promo wrappers do not disprove the observed id |
| DÁKITI: page title ending `(Video Oficial)`, MediaSession title adding `| EL ÚLTIMO TOUR DEL MUNDO (Official Video)`, correct id `TmKh7lAwnBI` | The active latch rejected its own id; successor `-r687V8yqKY` was later refused | A shared structural title core may corroborate an additional display/album suffix; the successor still contradicts it |
| `W Sound 05 "LA PLENA" - Beéle, Westcol, Ovy On The Drums`, id `F1_aOX0acbY` | The correct URL was block-confirmed, but the payload reversed the quoted work and trailing credits: artist `W Sound 05 "LA PLENA"`, title `Beéle, Westcol, Ovy On The Drums` | Recognize a conservatively proven `publisher/series "Track" - artist credits` shape as title `LA PLENA`, artist `Beéle, Westcol, Ovy On The Drums` |

### Implemented generic boundary

This is a validator/parser correction, not an embedded media database.
Production code must not contain a song/video id map, known-title list, artist
catalog, brand inference, or per-fixture branch. Exact field values belong only
to unit tests and incident documentation, which are not packaged as runtime
decision data.

1. **Shared active/final title corroboration.** Extract one pure matcher used by
   both the active latch and finalized candidate guard. Normalize Unicode,
   case, diacritics, punctuation and whitespace; remove only already-recognized
   promo-only wrappers; then require equal whole-token structure or conservative
   contiguous containment of a complete shorter structure of at least three
   tokens for truncation/additional display suffixes. The
   existing independent page/session duration contradiction remains in force.
   This matcher validates an id already observed from the browser; it does not
   discover or choose an id from a title.
2. **Quoted work with trailing credits.** Before the generic dash rule, parse a
   balanced top-level `prefix "quoted work" - trailing credits` only when the
   channel agrees with the prefix, the trailing side has credit structure, and
   it is not a promo/event suffix. Ambiguous inputs retain the conservative
   fallback rather than being reversed by guesswork.
3. **Exact positive and negative regressions.** The three log-16 title pairs
   and LA PLENA raw-title/channel input are under `app/src/test`, alongside negative
   adjacent-track/ad controls: Soy Peor versus Me Porto Bonito, Me Porto Bonito
   versus TQG, DÁKITI versus Gata Only, the observed ad/organic pairs, and quoted
   titles followed by event/promo text. No fixture enters `app/src/main`.
4. **Unchanged safety boundaries.** Keep URL generations, provisional explicit
   ad evidence, immutable final snapshots, mandatory canonical hyperlinks,
   threshold/floor/loop rules, kind immutability, manual/automatic parity and
   historical no-rebroadcast policy unchanged.

The shared matcher, both call-site replacements, and conservative parser branch
are implemented without runtime fixture data. The focused gate passed 54 tests;
the complete uncached gate passed 295 tests with no skips, failures or errors,
and debug assembly/lint succeeded. The corrected artifact remains version
0.8.11/version code 31 at `dist/rustedwax-0.8.11.apk`, SHA-256
`1b682f582dd17f287d69acd2b22313c227acffb13f13d266288c4e6df65639d5`.
Log 17 subsequently tested that artifact. It passed transport and the exact
log-16 corrections but failed the second field gate as recorded in TESTING §16.

## v0.8.12 field-correction contract (implemented; automated gate passed)

Log 17 is the evidence-backed boundary for the next patch. It finalized 123
tracks, emitted 67 payloads and visibly skipped 56. All 67 emitted payloads were
unique, exact and block-confirmed on two Hive nodes; all 60 song payload entries
and seven video payload entries appeared in the expected profile section with
canonical links. The remaining work is therefore limited to pre-broadcast
identity completeness, metadata parsing and one kind-classification false
positive. v0.8.12 implements that scope as version code 32. The automated and
artifact gate passed; the physical-device gate remains pending.

### 1. Evidence-ranked short-title corroboration

Unequal titles must no longer use a single global minimum-token answer. The
shared matcher must return evidence strength rather than only true/false:

- exact normalized structure remains strong;
- ordered containment of at least three complete tokens remains strong; and
- a contained one/two-token canonical work title is weak and may corroborate
  only an id already observed from the same active URL generation, with
  independently compatible duration and no strong identity contradiction.

Weak short-title evidence must never discover an id, rank a search result by
itself, replace a frozen id, or allow a following track to complete an ended
snapshot. Exact regressions cover `TapXs54Ah3E`, `at1axdFpcgI`,
`NgFx3aq52Vg`, `tdZsL8i5ASA`, and `tGLP74uofTo`. Negative controls include
`Bad Bunny` versus `Bad Bunny - Another Song`, adjacent one-word works, reordered
tokens and materially different duration. This is structural validation, not a
title-to-id catalog.

### 2. Role-aware channel corroboration

MediaSession artist/uploader text, a YouTube channel, and resolver credit lists
are related evidence but not interchangeable fields. Exact normalized channel
inequality alone must not veto an id already observed from the current URL when
title and duration independently corroborate it. Conversely, a channel match
alone must not establish an id or rescue a title/duration contradiction.

The exact `CJjvg7PbE4w` case must accept the resolver credit list
`Jon Z, Baby Rasta, & Boy Wonder CF` alongside ended uploader
`Boy Wonder Chosen Few` when the same observed id, title and duration agree.
Search-only candidates retain conservative channel requirements, and negative
fixtures cover same-title different-upload and adjacent-track cases.

### 3. Bounded resolver recovery without a media database

The resolver may keep a small memory-only index of identities verified during
the current monitoring run. It is a candidate cache, not a song database:

- insert only after an id has passed canonical title/channel/duration
  corroboration for that active track;
- key by normalized frozen metadata plus bounded duration, never by a fixture
  id or hardcoded title;
- cap entries and age, and clear them on Stop, package reset, app restart or
  monitoring reset;
- on reuse, re-fetch/re-corroborate the cached id against the new immutable
  snapshot; never broadcast directly from the cache; and
- preserve ambiguity refusal if multiple ids still satisfy the evidence.

This must recover the later `5r5UePOgMQU` replay from the already verified
same-run identity. For zero-result searches such as `QBq6rY0ZpKM`, generate
bounded presentation-cleaned query variants from the frozen title/artist and
apply the existing strict final candidate corroboration. External lookup that
still provides no unique evidence remains a visible safe omission. The
ambiguous “Best movie!!!” two-upload case must continue to refuse both ids.

### 4. Multi-separator and orientation-safe song credits

Top-level parsing must retain the structural work already implemented while
handling the seven exact log-17 payloads:

- parse `Artist - Track | album/display suffix` without falling back to the
  whole raw title when channel agreement and the top-level primary separator
  prove the artist/work boundary;
- do not flip conventional `Artist - Track ft. Featured Artist` merely because
  the channel matches a featured credit on the right;
- recognize `Track - explicit multi-artist credit list` only from structural
  credit-list evidence such as repeated separators/credit markers, not from a
  known title or artist; and
- collapse only an exact repeated trailing feature phrase, preserving one copy
  and preserving all non-duplicate credits.

Tests pin `saGYMhApaH8`, `GtSRKwDCaZM`, `AnKdQ5p5Ks8`, `qA6FBDYncGk`,
`lA8OhVn-o7M`, `UWV41yEiGq0`, and `34Na4j8AVgA`, plus ambiguous dash/pipe,
quoted separator, event suffix, ordinary artist-first and ordinary track-first
negatives. Production code must contain only grammar/evidence rules.

### 5. Strong movie-format evidence above uploader category

An explicit visible content-format marker such as the log-17 `#movie`/`#edit`
combination must be allowed to classify a narrative Short as `video` even when
the uploader or music-client microformat says `category=Music`, provided there
is no distributor provenance, YouTube Music video type, Topic/Art Track status,
or other hard music evidence. The exact `KoWNsyNVR28` fixture must become
`video`. Negative tests must keep genuine music videos, songs containing the
ordinary word “movie”, and hard catalogue provenance as `song`. This rule
classifies content kind; it must not be reused as advertisement guessing.

### 6. Unchanged safety and release boundary

v0.8.12 must preserve every v0.8.11 invariant: immutable ended snapshots,
URL-generation ad evidence using only explicit visible YouTube labels,
same-track duration refinement, mandatory canonical hyperlinks, thresholds and
floors, loop/dedup caps, kind immutability after decision, manual/automatic
parity, honest Hive status, and no historical rewrite/rebroadcast.

The automated patch is complete: the exact unit regressions and full existing
suite total 314 tests with no skips, failures or errors, and debug APK assembly
and lint pass. `dist/rustedwax-0.8.12.apk` has SHA-256
`3390df660053ca9c2c0c7665d320e67e820ce09513d4f025a172a018aa0080b3`.
A new physical-device round must
exercise every §13e fixture plus the log-17 short-title chain, Nunca Me Amó,
WHEN SINCE, the later +57 replay, all seven metadata cases and
`KoWNsyNVR28`. It must reconcile the exported log, signed-in History, two Hive
nodes, both profile sections, ads, loops and hyperlinks by id. A safe ambiguous
external lookup may remain omitted only with its exact visible refusal; an
app-side race or over-strict contradiction may not omit a qualifying known-id
fixture.

## v0.8.13 log-18 targeted-correction contract

Log 18 is the evidence boundary for this patch. Its 6,309-line export contains
129 finalizations, 73 block-confirmed logged payloads and 55 visible skip
decisions. All four configured Hive nodes returned the exact 73 logged
transactions and payloads; the same run's Amarillo operation completed after
the export ended, for 74 total operations (55 songs, 19 videos, 73 unique ids).
All operations appeared in the app-declared live profile section and all 73
unique ids existed in signed-in History. No successor-mixed payload, loop
duplicate, ad-like payload, transport failure or non-canonical link reached
Hive.

That clean delivery boundary does not make the field gate a pass. MONTERO was
permanently emitted as artist `Your Name` because ordinary title text inside
parentheses was mistaken for a `by Artist` credit. Te Bote was permanently
emitted as `video` because generic `movie` in channel `Flow La Movie` outranked
an id-bound YouTube Music OMV result. Three qualifying, uniquely identifiable
viewings failed closed on channel presentation/collaborator bylines; a fourth,
Classy 101, correctly remained off-chain because two uploads were
indistinguishable. TESTING §18 is the canonical count, transaction, omission,
coverage and reconciliation record. Historical Hive entries are never
rewritten or rebroadcast.

The v0.8.13 correction is limited to the following behavior.

### 1. Literal second-artist grammar

The track-first parenthetical form accepts only:

- `Track (by Artist)`; and
- `Track (performed by Artist)`.

No arbitrary prefix is allowed before `by`. Parentheses such as
`(Call Me By Your Name)` and `(inspired by a true story)` remain work/title
text and continue through ordinary top-level separator parsing. Existing
quoted, performed, multi-separator and duplicate-feature rules are unchanged.

### 2. Hard provenance before generic channel vocabulary

The classification ladder keeps explicit content-format vetoes—tutorial,
reaction, trailer, news and episode structures—above YouTube Music and
MusicBrainz. Generic negative words in a channel name move below those two hard
provenance sources but remain above the narrative movie/edit marker and bare
uploader category. Consequently:

- an id-bound YouTube Music OMV or MusicBrainz-confirmed recording owned by a
  channel containing `movie` remains `song`;
- a generic movie/film/cinema channel with only `category=Music` remains
  `video`; and
- a reaction, trailer, tutorial, news item or episode is not rescued merely by
  incidental catalogue/audio matching.

### 3. Bounded collaborative search bylines

Search-only identity still requires exact normalized title, channel evidence,
duration within the existing five-second tolerance and exactly one matching
id across the bounded complete candidate set. Two presentation refinements are
permitted:

1. recognized YouTube owner suffixes may be stripped repeatedly, without ever
   erasing a channel that consists solely of the marker; and
2. the leading owner in `Owner and Collaborator` or `Owner and N more` may
   satisfy channel evidence only when the parsed search card actually carries
   YouTube's collaborator-dialog command.

An arbitrary channel containing `and`, a wrong title, a material duration
conflict, a missing collaborator marker or more than one surviving upload
remains a contradiction/refusal. The app does not read or search signed-in
History at runtime. No field id/title/artist/channel map is permitted in
production source.

### 4. Release and immutability boundary

v0.8.13 uses version code 33. It must keep every prior snapshot, URL-generation
ad, mandatory canonical-link, threshold/floor, loop/dedup, kind immutability,
manual/automatic parity, honest Hive-state, cache-lifecycle and account-bound
queue invariant. A device retest must include MONTERO, Te Bote, the three
unique resolver misses, Classy 101 ambiguity, the complete §§13e/17d matrix and
an explicit visible-ad transition. Exact transaction comparison must again use
all configured Hive nodes and both live profile sections.

The implementation passed the full uncached gate: 320 tests, 0 skipped,
failures or errors; debug assembly succeeded; lint completed with 0 errors and
23 warnings. `dist/rustedwax-0.8.13.apk` has SHA-256
`ddfeb3e51cfe60fcf8fa2f13c05891989215154da948686650ae720e4ca9e026`.
This was source/artifact approval only. Log 19 later failed the v0.8.13
physical-device gate as recorded below.

## v0.8.14 log-19 correction contract

Log 19 is the evidence boundary for the next patch. Its 5,261 lines span
2026-08-02 11:48:33–19:44:40 local time and contain 114 finalizations, 53
broadcast payloads and 61 visible skip decisions. Fifty-two transactions were
confirmed directly and one offline-queued transaction later reached a block.
All four configured Hive nodes returned the exact 53 payload/transaction pairs;
the live profile contains all 51 `song` and two `video` entries. The skip ledger
is exact: 42 unverified hyperlinks, 14 below-threshold plays, three ordinary
duration-floor refusals and two listen deduplications. The Stop boundary, one
position-wrap loop cap, canonical-link gate, durable queue, successor refusal
and block-state reporting behaved correctly.

That transport result does not make the field gate a pass. Exact signed-in
History searches returned no result for Namecheap `zUaMtSMZDgg` or KaoJapan
`azTP61YoD2s`, yet both are permanently present in Videos and on Hive. No
`[ad]` evidence line exists. v0.8.13 calls the bounded accessibility-label scan
only after `videoIdInSameShortSnapshot` returns a concrete `/shorts/{id}`;
ordinary watch ads are never scanned. The resolver then treated both public
uploads as ordinary videos. Namecheap met the 30-second floor exactly and was
99% played; KaoJapan was 34 seconds and 100% played. Unique title/channel/
duration resolution and canonical links made both eligible under the current
rules. This is a coverage defect in literal ad evidence, not authority to add
brand/title ad guessing.

Four additional immutable payloads define the parser boundary:

| Id | v0.8.13 payload defect | Required generic outcome |
| --- | --- | --- |
| `TQNW0_RRicI` | title `Strictly High Grade [Official Video` | paired `[Official Video 2024]` is removed as a promo-plus-year group; artist `Marlon Asher`, title `Strictly High Grade` |
| `HtJS32n6LNQ` | artist `TVXQ! 동방신기 '주문`; title `MIROTIC' MV` | a structurally paired single-quoted work is not split internally; artist `TVXQ! 동방신기`, title `주문 - MIROTIC` |
| `ixkoVwKQaJg` | artist `Taki Taki ft. Selena Gomez, Ozuna, Cardi B`; title `DJ Snake` | conventional artist-first form remains artist `DJ Snake`, title `Taki Taki ft. Selena Gomez, Ozuna, Cardi B` |
| `BVYpT8LsjtA` | artist `BENNETT`; title retained `BENNETT - Mamma Mia (feat. Mentissa) - Techno Mix` | channel-proven primary boundary yields artist `BENNETT`, title `Mamma Mia (feat. Mentissa) - Techno Mix` |

`5GYeWpjq54Y` is a negative incident control: its mismatched `[Loving You Is in
My DNA)` delimiters already exist in the raw YouTube title and must not be
silently “repaired” by guessing. Existing conservative outputs without enough
structural evidence also remain conservative.

### 1. Exact watch-session ad evidence

The accessibility walker may scan for the existing exact/localized YouTube ad
labels whenever the visible browser host is YouTube, including a bare host or
ordinary `/watch` page. A non-Short signal must not be assigned to the address
bar video id: during a pre-roll that id names the organic content behind the
advertisement. Instead, the signal is offered to the active Chrome
MediaSession observation and may become a veto only when it is bound to one
unambiguous track-instance token/signature.

The first label observation for a new track instance is provisional unless the
same instance is already established. The same signal/instance must be
re-observed before acceptance otherwise. A metadata change, conflicting active
session, package/reset boundary, Stop, or expiry clears provisional evidence.
Once accepted, the ad flag follows only that track through Chrome session churn
and final snapshot construction. When the organic content resumes under the
same watch URL, it receives a distinct track instance and remains eligible with
its own carried progress. Automatic finalization, manual Broadcast and the Now
verdict continue to consume the same immutable ad flag and central rule.

History is a field-test oracle only. Runtime code must not read/scrape signed-in
History and must not infer ads from ids, brands, titles, channels, duration,
playback speed, public/listed state, category, crawlability or view counts. If
YouTube exposes no exact accessibility label, the app must continue to say that
the ad is unproven rather than invent evidence.

### 2. Paired delimiter and quoted-work fidelity

Bracket cleanup must preserve opener/closer pairing. A trailing four-digit year
may be ignored inside an otherwise promo-only bracket only when the remaining
tokens contain the existing strong promo marker; `(Summer 2024)`, `[Song 2024]`,
standalone years and mismatched pairs remain content. Single straight/curly
quotes may establish a work boundary only in a balanced structural title form.
Apostrophes in `Gangsta's Paradise`, `Don't Start Now`, names, possessives,
unmatched quotes and quoted event/promo suffixes must not become delimiters or
artist boundaries.

### 3. Orientation and channel-proven version suffixes

Recognized owner-suffix cleanup may compare an exact collapsed owner key so
`DJSnakeVEVO` corroborates `DJ Snake`; partial token overlap and arbitrary
substring containment remain insufficient. That strong left-owner evidence
must outrank the generic track-first multi-artist branch for a conventional
`Artist - Track ft. Featured, Artists` form. Existing explicit track-first
credit-list fixtures remain reversed only when their current structural
requirements are met.

For a title with more than one top-level dash, an exact channel-proven leading
owner may establish only the first artist/work boundary when the trailing
segment is a bounded, explicitly version-shaped suffix such as `Techno Mix`.
The suffix remains part of the work title. Unrelated publishers, event names,
promo slogans, arbitrary three-part titles, missing channel evidence and
conflicting owners retain the conservative whole-title fallback.

### 4. Lifecycle, release and immutability boundary

The patch is implemented as v0.8.14/version code 34. It does not change URL
generation semantics for Shorts, finalized snapshot isolation, threshold/
duration floors, loop/dedup caps, mandatory canonical hyperlinks, kind
immutability, resolver uniqueness, manual/automatic parity, honest Hive states,
account-bound queue behavior, cache lifecycle, or any historical operation.
Exact log-19 ids/titles/channels may appear only in tests and documentation;
production code remains generic.

Implementation was test-first: pure track-bound ad-evidence state and transition
regressions were added before accessibility observations were wired through the
existing probe/final snapshot path; the four parser regressions plus negative
controls then passed with the focused suites. The version/code is now
0.8.14/34 and this documentation describes only behavior actually present.
The complete uncached `testDebugUnitTest assembleDebug lintDebug --rerun-tasks`
gate passed: 338 tests, 0 skipped, 0 failures, 0 errors; debug assembly
succeeded; lint completed with 0 errors and 23 warnings. The tested APK is
`dist/rustedwax-0.8.14.apk`, SHA-256
`a9507c733b188f9cf3a481c8b1446535da22449343181cc17ccf556890f298b8`.
This is source/artifact approval only. The complete physical gate must replay
the log-19 watch-ad cases and the still-missing §§13e/17d/19 fixture matrix.

## v0.8.15 log-20 correction contract (implemented)

Log 20 is the evidence boundary for the next patch. Its 10,945 lines span
2026-08-02 21:14:46 through 2026-08-03 10:52:51 local time. There are 227
completed finalizations: 107 automatic broadcasts and 120 visible skips. One
intentional fixed test broadcast makes 108 Hive operations total, comprising
80 `song` and 28 `video` payloads. The automatic path reported 105 direct
blocks and two durable offline-queue writes; both queued payloads later reached
blocks. Four configured Hive nodes returned identical normalized rows and the
same 108 payload/transaction pairs. The live profile grew by exactly 80 Music
and 28 Videos entries. All 107 automatic YouTube operations carry canonical
exact watch URLs and distinct video ids.

The skip ledger is also exact: 89 tracks with accepted visible ad evidence, 15
below-threshold plays, six no-duration/zero-play cases, two duration-floor
refusals, and eight final identity-corroboration refusals. Fourteen continuous
viewings were capped to one scrobble, 88 finalizations exercised playback up to
2×, six same-track session restarts carried progress, and both offline queue
items preserved their original timestamps. No crash, fatal error, URL-less
broadcast or successor-mixed payload was found.

That accounting does not make the fifth field gate a pass. Namecheap
`zUaMtSMZDgg` appeared four times. Three instances received literal `Sponsored`
or `Visit Advertiser` evidence and were vetoed. The instance beginning at
01:06:09 received no accessibility observation, played 30/30 seconds, resolved
uniquely as a public/listed video and became transaction
`8c22a93cd7d581687055240d279d8713abfcdfc9`. The last URL/ad observation before
the affected interval was at 00:58:56, generation 76; the next was at 01:35:03,
generation 77. The watcher continued to report connected and emitted no
disconnect/reconnect state while both URL and ad observations were silent for
about 36 minutes. Other advert MediaSessions in that interval stayed off-chain
only because identity, duration or progress failed. This cannot prove whether
Android stopped delivering events or Chrome stopped exposing the tree. It does
prove that `watcherConnected` is not evidence that the current track was
actually inspected.

Exact signed-in History search returned no Namecheap result. This transaction
is a new immutable leak, not a retry of log 19's Namecheap transaction
`c94c4d17c8572018ed1c000e0b78ea405ab2cbec`. History contained 103 of the 107
automatic ids; the other three absent ids were Metallica songs played through
Brave rather than the signed-in Chrome profile, so no other ad-like broadcast
was identified. History remains an external field-test oracle only.

Three additional field defects define the remaining correction scope:

| Id | v0.8.14 outcome | Required generic outcome |
| --- | --- | --- |
| `JmeUtPih4U8` | CENTRAL CEE — BOOGA played 110/110 seconds and uniquely resolved as Music/OMV, then was refused because candidate channel `Central Cee and LIVE YOURS` contradicted ended channel `Central Cee` | Accept only a unique exact-title/duration hard-music candidate whose parsed/ended owner exactly matches the leading segment of an explicitly separated collaborative candidate byline; all weaker bylines remain contradictions |
| `DGs9TJmazB0` | artist `SIP ft. Tyga, Nicki Minaj, Blueface (RapKing Music Video)`; title `6IX9INE` | conventional artist-first form remains artist `6IX9INE`, title `SIP ft. Tyga, Nicki Minaj, Blueface (RapKing Music Video)` |
| `z7DbZS6l6Vk` | artist `Bad Habits Feat. Tion Wayne & Central Cee (Fumez The Engineer Remix)`; title `Ed Sheeran` | conventional artist-first form remains artist `Ed Sheeran`, title `Bad Habits Feat. Tion Wayne & Central Cee (Fumez The Engineer Remix)` |

The required physical matrix was again incomplete. The four v0.8.14 parser
fixtures, the six absent v0.8.13 fixtures and the broader §§13e/17d
transition/short-title/cache/manual matrix do not occur. Stop/reset with a
populated cache, mute, dedup replay and real YouTube manual/automatic parity
were not exercised. The export ends with a Chrome session waiting inside its
final 60-second continuation window, so the final item has no recorded decision.

### 1. Track-bound accessibility coverage and watcher freshness

An enabled or connected accessibility service is not proof that a particular
track's visible YouTube tree was inspected. v0.8.15 represents a
successful YouTube-root scan as a separate, immutable coverage fact. Each fact
is bound by package and the same unique MediaSession track instance/signature
used by ordinary watch ad evidence. A scan may report either an exact ad signal
or no ad signal; “covered with no label” is evidence of inspection, not a claim
that the content is organic.

`UrlWatcherService` must use one bounded observation routine for both ordinary
accessibility callbacks and a bounded periodic refresh while monitoring is on.
The refresh inspects only a visible target-browser root and applies the existing
host, node-budget, recycling and exact-label rules. It must not synthesize an
event, scan another package, keep the display awake or interpret a null/inactive
root as a clean observation. While a target MediaSession continues, a prolonged
lack of successful target-root scans must be logged once as an evidence outage
and retried without claiming the service disconnected.

When Browser evidence access is enabled for the run, a finalized track whose id
is recovered only from playlist/search/watch-page/cache evidence may broadcast
automatically or manually only if at least one successful YouTube-root scan was
bound to that track during its active lifetime. Without that coverage it must
fail closed with an explicit reason such as “Browser evidence was unavailable
for this track; a visible YouTube ad could not be excluded.” It must not be
labelled an advertisement. A current-generation exact URL observation already
comes from the same successful root scan and therefore supplies coverage; this
rule targets resolver-only tracks such as the leaking Namecheap instance.

Coverage may carry across genuine same-track session recreation, but never to a
different metadata signature, successor, URL generation or package. It clears
on Stop, reset, watcher disconnect/destroy, package teardown and expiry. A scan
from before a track began cannot cover it. An ambiguous set of active sessions
cannot receive track-bound clean or ad evidence. Now, manual Broadcast and
automatic finalization must consume the same frozen coverage/ad facts and the
same central rule. If Browser evidence access is disabled, the existing
notification/lookup fallback behavior remains unchanged and the UI continues
to disclose that visible-ad protection is unavailable.

The patch must not infer advertisement status from id, brand, title, channel,
duration, playback speed, public/listed state, category, crawlability, view
counts or History. The safety action is an honest evidence-unavailable refusal,
not heuristic classification.

### 2. Featured-title orientation without losing track-first credits

A conventional top-level `Left - Right` form must not reverse solely because
`Right` contains `ft.`/`feat.` followed by multiple people. A right-hand work
prefix before the feature marker is compatible with ordinary
`Artist - Work ft. Featured, Artists` grammar, even when the uploader is an
unrelated publisher or one of the featured artists. Exact collapsed-owner proof
remains strong corroboration but is no longer required merely to preserve this
conventional orientation.

Track-first reversal remains allowed only with positive work/credit structure:
a genuinely bare trailing co-artist list, or a strongly work-shaped left side
such as the existing remix/version fixtures followed by an explicit credit
list. The log-17 `qA6FBDYncGk`, `lA8OhVn-o7M` and `UWV41yEiGq0` outputs, the
Anuel featured-channel control, Taki Taki and ordinary artist-first forms must
remain correct. The current unrelated-channel Taki Taki test must be revised:
unrelated uploader evidence is no longer authority to invert an otherwise
conventional featured title. Exact field values stay in tests/documentation;
production code contains only grammar.

### 3. Strongly corroborated collaborative search bylines

The resolver may treat `Owner and Collaborator` candidate presentation as
compatible with ended channel `Owner` only when all of the following are true:

1. the candidate is unique under the unchanged search/playlist ambiguity gate;
2. cleaned title and duration satisfy the existing strongest corroboration;
3. enrichment provides hard music provenance such as a YouTube Music OMV;
4. the parsed artist/ended channel exactly equals the complete leading byline
   segment after ordinary owner-suffix normalization; and
5. the candidate adds one or more complete collaborator segments using an
   explicit supported separator, never substring/prefix containment.

A generic channel containing `and`, a fan/publisher suffix, a partial owner,
wrong title, material duration conflict, missing hard music provenance, more
than one candidate or any competing id remains a refusal. This is candidate
presentation compatibility, not permission to read signed-in History or weaken
unique-id resolution. The existing Classy 101 ambiguity and every v0.8.13
negative remain unchanged.

### 4. Release and immutability boundary

The implemented patch is limited to the three sections above. It preserves
Short URL-generation ad evidence, finalized snapshot isolation, threshold and
duration floors, loop/dedup caps, canonical hyperlinks, kind immutability,
manual/automatic parity, honest Hive states, durable account-bound queue
behavior, verified-candidate cache lifecycle and every prior parser/resolver
regression. No historical operation may be repaired, rewritten or rebroadcast.

Implementation started with focused failing regressions, then added the pure
coverage/freshness state, watcher refresh wiring, central fail-closed decision,
parser orientation correction and collaborative-byline rule. The 176 focused
evidence/probe/carry/rules/manual/parser/resolver tests pass with no skips,
failures or errors, so the version is now v0.8.15/code 35 and this contract
describes behavior present in source. The complete uncached
`testDebugUnitTest assembleDebug lintDebug --rerun-tasks` gate also passed: 349
tests, 0 skipped, 0 failures, 0 errors; debug assembly succeeded; lint completed
with 0 errors and 23 warnings. The tested APK is
`dist/rustedwax-0.8.15.apk`, SHA-256
`242d1b76d473754494dec74e035a7731ee1311c4920458926c5dd769e7ee365c`.
The original full TESTING §21 physical matrix remains the strict historical
benchmark. The accepted practical field outcome and its exceptions are recorded
in TESTING §22.

### 5. Log-21 field acceptance and remaining boundary

The surviving v0.8.15 field export contains 228 final decisions: 98 unique
block-confirmed broadcasts and 130 skips. All 98 transactions reconcile to the
signed-in profile, including 57 songs and 41 videos. Two naturally served
Namecheap ads were rejected using exact current-track accessibility labels, and
no new ad payload was found. This physically validates the generic periodic
coverage path against the failure shape that produced the log-20 Namecheap
operation; it does not authorize brand, title, channel or History rules.

The safety bias remains conservative. Four complete organic songs failed exact
id verification, and one complete organic track was vetoed when `Visit
Advertiser` evidence from the preceding promoted music session remained bound
across the immediate metadata transition. These are false omissions, not
on-chain false positives. A successfully scanned label-free promotion remains
indistinguishable from ordinary content under the allowed evidence, and a
transition may still suppress its organic successor. v0.8.15 intentionally
prefers those losses to broadcasting an uncertain advertisement.

Log 21 exercised Chrome rather than Brave and did not physically replay every
SIP, Bad Habits, Taki Taki, BOOGA, Stop/reset, mute, dedup and historical
transition fixture. The automated suite is the evidence for those generic
rules. Phase 4 acceptance therefore means practical release acceptance with the
TESTING §22 exceptions, not a claim that the strict §21f zero-omission matrix
was completed. Native YouTube/YouTube Music app support is a separate v0.9
contract and must not weaken these browser invariants.

## v0.9.0 native YouTube apps contract (implemented; device gate pending)

v0.9.0/version code 36 adds two sources without changing the v0.8.15 browser
path. Implementation is present in source and covered by focused regressions;
physical approval remains pending under `PHASE_NATIVE_APPS.md`. The later
native Shorts field run failed on stale exact-ID-less MediaSession continuation
and is evidence for a future patch, not part of this as-built contract; see
`PHASE_NATIVE_SHORTS.md`.

### 1. Admission and state isolation

The target-package decision is exact. Brave and Chrome variants remain
unconditional target packages. Native YouTube and YouTube Music are admitted
only by their own setting. Those preferences persist independently and default
false.

Native snapshots carry a package-specific epoch. Opt-out, Stop/reset and
listener disconnect/rebuild advance the epoch so already-running async work
cannot sign after the boundary. Controller removal may retain same-track
progress only through the existing bounded continuation. Progress keys include
the package and semantic metadata; a concrete case-sensitive native video id
is additional contradiction evidence. A different id cannot claim a same-title
continuation. Package teardown clears pending carry and the verified-candidate
cache for that package.

The native identity path does not consume notification-hint binding,
`UrlEvidence`, playlist/URL generation, `AdEvidence`, `MediaSessionAdEvidence`
or `MediaSessionAccessibilityEvidence`. Native sessions therefore cannot
acquire browser URL/ad/coverage state. Enabling native sessions does not reduce
the browser sole-session evidence test; only browser watches participate in
that count.

### 2. Exact identity and resolver fallback

Exact native routes are ordered media id, media URI, then artwork URI. URI
parsing uses exact YouTube/ytimg hosts and exact 11-character ids; it rejects
suffix-confusion hosts and arbitrary embedded strings. All success routes emit
the canonical watch URL and diagnostics name the exact route. A Shorts media
URI retains Short-path proof.

Package-only identity is site-only. With lookup disabled it cannot broadcast.
With lookup enabled, the existing resolver may run only from a frozen nonblank
title and channel plus duration and must leave one unique matching id after its
complete candidate/ambiguity checks. Final page title and duration
contradictions still refuse the result. An exact structured native id can retain
a clean one/two-word native title when the fetched page is a longer presentation
and duration corroborates; it does not permit a wrong title or duration.

Payload construction and the central broadcast policy continue to require the
canonical URL, providing the same defense in depth for automatic, manual and
queued entry paths. No production fixture catalogue or History lookup is added.

### 3. Metadata, classification and rules

Native title/artist/album values take precedence when present. MusicBrainz may
verify them but does not replace supplied clean fields; fetched page metadata is
fallback for missing fields and corroboration/classification evidence.

Native YouTube Music context is evaluated after literal podcast type, structured
non-music genre and hard title-format rules. Native YouTube receives no package
music shortcut. Both sources keep the v0.8.15 threshold, 10/30-second floor
proof, playback-speed accumulator, loop/double-listen cap, kind decision,
dedup, mute and manual/automatic rule implementations.

### 4. Native ad and instrumentation boundary

No native ad detector is claimed in this source. The package, title, artist,
album, id, URI, artwork, duration, resolver and classifier are not ad evidence.
The settings and Now card disclose that browser visible-ad protection does not
cover native apps.

For physical measurement, every standard MediaMetadata text/numeric/bitmap
presence field and non-standard key is logged. Native PlaybackState logs numeric
state, position, buffered position, speed, action bitmask, error message, update
time, queue id, active flag, custom actions and extras. These are observations,
not heuristics. A future veto requires generic literal structured evidence from
that record and a new contract/test change.

No manifest permission was added; Notification Access supplies active
MediaSession access. The exact device-pending checklist and release risk are in
`PHASE_NATIVE_APPS.md` and `TESTING.md` §23.

The complete uncached source gate passed 372 tests, 0 skipped, 0 failures and
0 errors; debug assembly succeeded and lint completed with 0 errors and 23
warnings. The tested source APK and `dist/rustedwax-0.9.0.apk` both have SHA-256
`3f8945e997d592dbf40fac6aa727f69215cbf8a805b5a4b15df031171a0ec58c`.

## v0.9.1 native foreground Shorts contract (implemented; bounded gate passed)

Foreground native Shorts are a distinct source proof, never a refinement of
the stale YouTube MediaSession. Admission requires the exact YouTube package,
one visible measured Shorts root/player, one non-control title, one exact
normalized owner handle, one valid current/total seekbar and no conflicting
structure. Identity-bearing fields must remain unchanged for 750 ms before a
new organic or ad session exists; current position is excluded from that key.

Only accepted seekbar deltas earn play time. Unchanged values, ordinary rewind,
material forward seek, pause/interaction proof loss, torn frames, PiP and
background add nothing. Samsung's unchanged cached accessibility values do not
move the elapsed bound for the next position delta. Exact scroll/seek events
freeze and reset the baseline. A strict near-end/near-start wrap may add only
traversed seconds and sets the existing loop flag/cap.

Complete proof suppresses the stale native YouTube MediaSession. Missing proof
freezes immediately; a separate freshness watchdog finalizes after the bounded
three-second no-credit grace and releases MediaSession at a fresh zero baseline.
Stop, native opt-out, observer disconnect, listener rebuild and source-epoch
change discard the in-flight foreground observation rather than sign it.

A literal supported ad label inside the proven player is the only native Short
ad evidence. It isolates organic → ad → organic state and reaches the existing
central ad veto shared by manual and automatic paths. Brand, title, handle,
CTA, duration, id, popularity and History are never ad evidence.

Exact-id recovery without browser URL proof requires exact normalized title,
duration within the existing five-second limit, exact canonical owner handle
from `ownerProfileUrl`, and exactly one fully fetched public watch-page
candidate. Missing, contradictory, legacy-cache or ambiguous handle evidence
refuses every id. The recovered canonical id then uses the same threshold,
Short floor, payload, mute, dedup, queue and signing rules as every source.

The implementation gate passed 409 tests, assembly and lint. The A12 proved
progress/seek/pause/wrap/PiP/transitions, two natural literal ads, lifecycle
invalidation and browser/YouTube Music regressions. On the authorized test
account, four exact foreground-Short payloads were block-confirmed and
independently reconciled; lookup-off, unresolved and genuine two-upload
ambiguity cases remained off-chain. The bounded Shorts gate passed. Native
sources remain experimental/default-off while broader v0.9 native-app evidence
is still pending. Artifact SHA-256:
`c6f2f1800a1cc6b6d76c260181d2402a3d648c9ecf7b3bc94ad897eeb1ce0895`.

## v0.9.2 native simplified music metadata contract (implemented; field continuation pending)

The ordinary native YouTube packages may publish a clean separated song title,
artist and duration while omitting every exact video-id route. That source may
use structured recovery only after the global raw title/channel resolver fails.
Every bounded search candidate must be completed from its public watch page;
the existing title grammar must reduce the canonical presentation title to the
exact native work, the exact native artist must be one complete structural
credit, duration must agree within five seconds, and exactly one distinct upload
may satisfy all fields. Final frozen facts repeat the same predicate. Browser,
foreground owner-handle and exact-id paths do not inherit this relaxation, and
structured recoveries do not seed the raw title/channel candidate cache.

An exact-ID-less native STOPPED callback waits at most ten seconds for the
measured metadata replacement shape. If normalized title/artist/album remain
the same but duration becomes materially contradictory, the earlier fragment
is discarded and the replacement starts at zero. No progress carries, no ad is
inferred, and no fragment payload is created. A different title finalizes the
old item immediately; an exact id and browser duration refinement retain their
prior behavior. Monitoring Stop, listener teardown and source-epoch boundaries
invalidate the pending timer before it can sign.

The source gate passed 416 tests, assembly and lint (0 errors/23 warnings).
Version 0.9.2/code 38 and its byte-identical artifact were installed on the A12
with SHA-256
`5cf7fbfdd950376c8b61ede0a0effc7f843f25b249f47c06172471f18559d072`.
Post-install A12 logs physically confirmed the transition guard: the same
`Hey DJ` metadata changed 218→20→207 seconds within the grace and both
superseded fragments were discarded with zero carry and no ad inference. The
clean 207-second phase then completed; structured proof matched two distinct
uploads (`YN-aYhtMHIw`, `1fb9DtJpbHw`), so RustedWax refused the ambiguity and
built no payload. That physically confirms the structured refusal branch. A
unique-match write and reconciliation remain pending; the four pre-patch misses
remain off-chain.

## v0.9.3 artist-aware budget and immutable native continuation contract

Structured native page budgeting counts only search cards that already prove
the exact parsed work and one complete exact artist credit, with compatible
duration where supplied. The eight-page bound, fully fetched final predicate,
uniqueness requirement and genuine ambiguity refusal remain unchanged.

A stable exact-ID-less ordinary native track may resolve while playing. A
unique fully corroborated raw or structured result is memory-only controller-
carry authority, not a MediaSession-published id. A vanished controller may
defer progress only with that authority; a replacement starts at zero and may
claim once only after independently resolving the same immutable id with
compatible semantic metadata and duration. Different ids, no id, ambiguity,
duration replacement, timeout, foreground-Short ownership, Stop, opt-out and
lifecycle invalidation cannot claim the fragment. Finalization re-fetches the
authority through its original raw or structured route and repeats final-facts
corroboration before payload construction.

Equivalent ordinary-player missing-Short diagnostics are rate-limited to one
reminder per 30 seconds after dynamic trigger prefixes and node counts are
canonicalized. Observer events, proof freshness, proof-loss grace and scoring
are unchanged; proof-frozen/expired transitions log immediately.

The source gate passed 422 tests, assembly and lint (0 errors/23 warnings).
Version 0.9.3/code 39 and its 14,048,941-byte artifact were installed on the A12
with SHA-256
`d18342325d7288f5ccfe16aa549b5752e6ba2fd63d0522cd28e5ae7e65388d88`.
The final physical-test boundary is 10:12:21 local. Diagnostic coalescing is
physically confirmed; unique-write and same-id continuation acceptance remain
pending.

## v0.9.4 structured native author/work contract

For the native-only structured music route, an exact search-card or canonical
watch-page author/channel is an independent complete artist credit even when the
presentation title parses to a collaboration. Agreement with the separated
MediaSession artist is still exact after the existing channel normalization;
partial, token-overlap, substring and fuzzy matching remain forbidden.

Candidate and native work comparison applies the same narrow trailing
`ft.`/`feat.`/`featuring` reduction to both sides. This does not remove remix
identity, arbitrary parentheticals or other words. Search-card duration
compatibility, the eight-page limit, full page fetch, exact final duration,
complete-set uniqueness, final re-fetch and fail-closed ambiguity are unchanged.
The generic raw resolver, browser sources, exact-id routes and continuation
contract are outside this patch.

The field case requiring the author rule is uniquely corroborated
`VqEbCxg2bNI`: native `Criminal` / `NATTI NATASHA` / 273s versus canonical
`Natti Natasha ❌ Ozuna - Criminal [Official Video]`, author `NATTI NATASHA`,
273s. Symmetric featured-work normalization exposes multiple `Ella Y Yo`
uploads and therefore must still refuse every id. The irreconcilable
`Si Te Dejas Llevar`, plus the measured `Te Boté`, `La Pregunta` and `Si Se Da`
ambiguities, remain off-chain.

Version 0.9.4/code 40 implements this boundary. Its focused matcher plus adjacent
resolver/carry/browser regressions passed, followed by the complete uncached
425-test suite with 0 failures, errors or skips. Assembly passed; lint passed at
0 errors/23 existing warnings; `git diff --check` passed. The source and copied
14,048,941-byte APKs have SHA-256
`cbaebadc91d0045b6bd7a2abec8aaacefb2e914782a404d705b24890d4c9ccbf`.
The exact artifact connected on the A12 at 10:59:34 local with version, installed
bytes, settings, Notification Access, both accessibility bindings and USB
stay-awake verified. Its joined `El Efecto` immediately retained the measured
two-id ambiguity refusal. Subsequent multi-id and bounded no-result observations
also remained off-chain, provisional/different-duration phases carried zero
time, and an unresolved controller replacement claimed nothing. A stable
416-second `Ella Y Yo` / `Pepe Quintana - Topic` observation uniquely resolved
to `CGjuWHEPxgc`, established immutable authority, finalized at 416/416 seconds,
re-fetched the same page and block-confirmed the one linked payload as tx
`49a46d159658d257706c9a3c6b32eed4ddd29ca1` in block 108,734,261 on two Hive
nodes, with `CGjuWHEPxgc` reconciled on the public profile. The code-40
unique-write acceptance is complete.

## v0.9.5 native playlist-derived identity contract (implemented)

Native YouTube publishes no video id on any surface a third-party app can read.
Every one was measured empty: `MediaMetadata`, the MediaSession queue, the
session-activity `PendingIntent`, the media notification, the accessibility tree
and the exported `MainAppMediaBrowserService`, which returns an
`__EMPTY_ROOT__` with zero children. Identity for native sessions therefore
cannot be sharpened by better comparison; the candidate set has to be reduced.

The playlist being played is that reduced set. A latch may be established only
from `com.google.android.youtube`, only from a visible `yt:position` parsing as
`N/M` with `1 ≤ N ≤ M`, and only with a non-blank bounded playlist name. It is
converted to a playlist id by one bounded playlist-filtered search in which
**exactly one** result's normalized title equals the observed name; zero or
several refuse, as does a result contradicting an observed owner or total.

Absence of the playlist bar never drops the latch — four mechanisms hide it
while the user is still in the playlist (pre-roll ad, miniplayer, scroll
position, fullscreen) and none is distinguishable from leaving. Only a
positively different playlist name, or an explicit lifecycle reset, replaces it.
Position and `next_video_title` are logged and must never gate a scrobble:
`yt:position` was measured reading `1/120` while the queue was demonstrably
shuffled, and shuffle state is not observable at all.

A latched playlist authorizes a scrobble only when `PlaylistPageParser.match`
finds **exactly one** entry matching the finalized title, artist and duration —
a second matching entry refuses — and every existing downstream gate still
passes unchanged. The native playlist name is carried in its own
`nativePlaylistName` field and never written into `playlistId`, which remains
proven-URL evidence only.

The full field record is `PHASE_NATIVE_PLAYLIST_IDENTITY.md`.

## v0.9.6 signed-in watch-history identity contract (implemented)

Resolution priority is `browser address bar → playlist entry set → watch
history → search`. History is native `com.google.android.youtube` only, guarded
on `session.isNative` and on the exact package; browsers and YouTube Music are
structurally excluded, and browser behaviour is unchanged.

**History supplies a candidate, never a verdict.** An entry is accepted only
when it passes the identical three-field gate the search route applies
(`SearchResultsParser.identityMatches`) against the frozen MediaSession tuple,
and only when it is the sole entry in the recent window that does. Two
indistinguishable recent entries refuse every id. A carried history-routed id is
re-derived from the feed at finalization and must come back the same, or the
listen refuses.

**The listen is still measured locally.** History records that a video was
started, never how much of it was played, so the threshold decision remains
MediaSession position and playback rate. Nothing about the scrobble rules
changes.

**Session handling.** The user performs the Google sign-in; the app never sees a
password. Only the resulting `youtube.com` cookie jar is kept, in
`EncryptedSharedPreferences` under an Android Keystore key. It is never logged,
never exported, never returned to a caller, and attached to exactly one
hardcoded origin; redirects are not followed, so a 302 cannot walk it elsewhere.
The WebView's plaintext jar is wiped once the session is in the vault, and
disconnecting wipes the vault.

**Fail-closed reasons are exact.** A dead session (`responseContext.loggedOut`
or a redirect to sign-in) and a paused history (YouTube's own "Turn on watch
history" control) refuse on sight. A YouTube app that is signed out, on another
account, or in incognito is not separable from the feed, so it is diagnosed
rather than guessed: three consecutive absences **from freshly-read feeds** stop
the route and name all three causes. Absence against a cached feed triggers a
re-read instead, and never counts — a lookup 60 ms into a new track was measured
missing a 0.5-second-old cache and falling through to the search route.

The build record is `PHASE_NATIVE_HISTORY.md`.

## Verification mapping

Each invariant must have all four artifacts before a build is considered ready:

| Invariant | Runtime check | Automated check | User-visible check | Documentation check |
| --- | --- | --- | --- | --- |
| One looping video, one scrobble | Position-wrap detection, carried loop flag, and rules kind cap | Position-wrap, carry, and rules regressions | Log distinguishes detected wrap from inferred Short loop | README + TESTING loop cases |
| Session churn is continuous | Deferred finalization plus carry claim | Carry expiry/token tests | Whole accumulated progress in final log | README + TESTING restart case |
| Stop is a hard boundary | Callback guards and evidence clearing | Pure store clear tests plus code audit | Calm stopped state | README privacy wording |
| Honest confirmation | Structured result evidence | Status aggregation tests | Block/mempool/unconfirmed wording | README + TESTING confirmation matrix |
| Account-bound durable queue | Username match and explicit storage outcomes | Serialization/decision tests where possible | Queue/History terminal status | README queue section |
| Same rules in UI/manual/automatic | Shared decision inputs | Threshold/short tests | Configured threshold text and refusal reason | README + TESTING manual case |
| Literal evidence provenance | Explicit source flag | Parser/fallback/cache tests | Accurate listed/resolved explanation | README short verification section |
| Unlisted Short veto | Explicit pre-duration rules veto | 42-second unlisted-ad regression | Not logged names the unlisted feed ad | README + TESTING ad case |
| Visible YouTube ad veto | Exact accessibility-label detector, `/shorts/` id binding, and carried track flag | Positive/localized labels plus false-positive and carry regressions | Now and Not logged name the UI ad evidence | Accessibility disclosure + README + TESTING ad case |
| Frozen continuation identity | Last active identity captured at disappearance and carried to replacements | Rejected-live selection and identity-carry regressions | Final payload cannot acquire the next foreground id | README + TESTING delayed-finalization case |
| Finalized snapshot isolation | Frozen metadata plus structured resolver evidence carried through enrichment/payload construction | Exact Bad Bunny/La Bebe and YCB/Coming Home handoff regressions | A mismatch appears in Not logged; no mixed payload reaches broadcasting | README + TESTING v0.8.11 isolation cases |
| Transition-safe visible ad veto | URL generation plus provisional/re-observed ad state bound to the active track instance | Exact stadium-ad to cosplayer race, stable-ad positive case, Stop/URL-clear cases | Legitimate successor remains eligible; confirmed ad names its literal signal | README + TESTING v0.8.11 ad race |
| Metadata refinement continuity | Semantic same-track predicate with bounded duration drift | `227125 → 227124`, missing-to-known duration, and material-change cases | One aggregate finalization rather than two threshold failures | README + TESTING v0.8.11 duration case |
| Structure-aware song credits | Top-level separator scanner, explicit shapes and conservative channel-aware orientation | Four log-14 parser fixtures plus nested delimiter negatives | Now preview and payload show the same corrected artist/title | README + TESTING v0.8.11 parser cases |
| Evidence-ranked short canonical titles (v0.8.12) | Weak one/two-token cores restricted to the current-generation observed id plus duration agreement | Five exact log-17 omissions plus adjacent/generic-title negatives | Correct id retained or exact contradiction shown | README log-17 record + TESTING §17 |
| Role-aware channel evidence (v0.8.12) | Uploader/credit inequality cannot veto an otherwise corroborated observed id by itself | Nunca Me Amó plus same-title/different-upload negatives | Not logged distinguishes channel absence from identity contradiction | TESTING §§16–17 |
| Bounded verified-id recovery (v0.8.12) | Memory-only capped candidate cache, lifecycle clearing and full reuse corroboration | Later +57 replay, expiry/reset/change and ambiguity regressions | Log names cache candidate and final evidence source without hiding refusal | TESTING §17 resolver cases |
| Multi-separator credit fidelity (v0.8.12) | Primary separator, credit-list orientation and exact duplicate-feature rules | Seven log-17 fixtures plus structural negatives | Now preview and payload agree before immutable broadcast | TESTING §§16d–17b |
| Strong movie-format kind evidence (v0.8.12) | Explicit narrative movie/edit format above bare category, below hard music provenance | `KoWNsyNVR28` plus real-music/ordinary-word negatives | Now card shows Videos before manual or automatic broadcast | TESTING §§16e–17b |
| Literal parenthetical by-credit grammar (v0.8.13) | Only `(by Artist)` and `(performed by Artist)` take the second-artist path | MONTERO plus genuine by-credit and ordinary-phrase negatives | Now/payload preserve the full work title | TESTING §§18c–19 |
| Hard music provenance before generic channel words (v0.8.13) | YouTube Music/MusicBrainz precede channel vocabulary; explicit format vetoes remain higher | Te Bote plus weak movie-channel and reaction/trailer controls | Now shows song for the OMV before any broadcast | TESTING §§18c–19 |
| Explicit collaborative resolver bylines (v0.8.13) | Stacked suffix normalization and parser-proven collaborator leader, with strict title/duration/uniqueness | Three log-18 misses, missing-marker control and Classy 101 ambiguity | Not logged continues to explain ambiguity; unique matches get canonical links | TESTING §§18d–19 |
| Watch-session exact ad evidence (v0.8.14) | Exact accessibility label bound to one MediaSession track instance, never the organic watch URL | Namecheap/Kao positives; ad-to-organic resume, provisional, ambiguity, Stop/reset and label-free negatives | Advert session is Not logged; resumed organic content remains eligible | TESTING §20 |
| Paired promo/quote fidelity (v0.8.14) | Paired brackets plus narrowly structural single-quoted work parsing | Marlon/TVXQ positives; year, apostrophe, mismatch and event negatives | Now/payload keep balanced canonical metadata | TESTING §20 |
| Conventional credits with collapsed owner proof (v0.8.14) | Exact collapsed owner corroboration outranks track-first guessing | Taki Taki positive; existing track-first lists and unrelated-owner negatives | Now/payload retain artist-first credits | TESTING §20 |
| Channel-proven version suffix boundary (v0.8.14) | Exact leading owner plus bounded version suffix proves only the first dash | Mamma Mia positive; arbitrary three-part/event/promo negatives | Title drops duplicated artist but preserves version suffix | TESTING §20 |
| Track-bound accessibility coverage (v0.8.15) | Successful visible YouTube-root scan bound to one MediaSession track; resolver-only tracks fail closed during coverage outages | Clean scan, exact ad, no-event Namecheap shape, stale/ambiguous/Stop/reset/screen-off cases | Not logged distinguishes evidence unavailable from proven ad | TESTING §§21–22 |
| Featured-title orientation (v0.8.15) | A work prefix plus `ft.`/`feat.` on the right does not itself prove track-first orientation | SIP/Bad Habits positives; three log-17 track-first fixtures, Anuel and Taki Taki controls | Now/payload retain conventional artist and full featured title | TESTING §§21–22 |
| Collaborative search byline (v0.8.15) | Unique exact-title/duration hard-music candidate plus exact leading owner segment | BOOGA positive; partial/publisher/no-hard-music/ambiguity/title/duration negatives | Unique compatible id gets canonical link; contradictions remain Not logged | TESTING §§21–22 |
| Podcast type is not music | Catalogue-type allow/deny rule | Real timer type regression | Now card remains `video` | README + TESTING classification case |
| Every YouTube entry is linked | Video-id gate, payload-builder gate, and serialized-broadcast gate | Shorts lockup parser, completed identity, ambiguity, unresolved payload, and serialized policy regressions | Not logged names unresolved identity; quiet-bar banner says items stayed off-chain | README + TESTING v0.8.10 recovery cases |
| Native package opt-ins (v0.9) | Exact package allowlist plus independent persisted settings and source epochs | Default/persistence, enabled/disabled package matrix and epoch invalidation tests | Two default-off switches name their exact packages | README + TESTING §23 + native phase document |
| Native exact identity (v0.9) | Media id → media URI → artwork URI, then unique resolver fallback; canonical payload guard | Route priority, canonical URL, hostile-host/invalid-id, Shorts URI, ambiguity and finalized corroboration tests | Now/log name package, origin, id and exact route or honest refusal | README + TESTING §23 + native phase document |
| Native package isolation (v0.9) | Package-scoped semantic/id carry, resolver cache and lifecycle teardown; no browser evidence calls | Browser/YouTube/YouTube Music carry separation, different-id, package clear and reset-epoch tests | Source package/origin and lifecycle clears are logged | TESTING §23 + native phase document |
| Native metadata and kind (v0.9) | Supplied separated fields take precedence; native Music context below hard non-music evidence | Clean title/artist/album, podcast type/genre, native YouTube song/video tests | Now payload previews exact native fields and kind reason | README + TESTING §23 + native phase document |
| Native ad limitation (v0.9) | No native heuristic/veto without literal structured proof; full metadata/state dump | Rule fallback and source-boundary tests plus production-source audit | Both settings and Now warn browser protection is inapplicable | README + TESTING §23 + native phase document; physical gate pending |
| Foreground native Shorts (v0.9.1) | Exact-package structural proof, 750 ms identity stability, position-delta tracker, bounded missing-proof watchdog and literal in-player ad evidence | Parser/stabilizer/tracker, owner-handle resolver, central ad/manual/automatic, carry and lifecycle regressions | Separate grant/status, exact proof/reason, measured progress and foreground-only disclosure | README + TESTING §25 + PHASE_NATIVE_SHORTS; bounded device/write gate passed |
| Simplified native music metadata (v0.9.2) | Native-only fully fetched parsed work/credit/duration uniqueness plus tokenized STOPPED replacement grace | Four measured shapes, presentation/credit variants, partial/containment/duration/ambiguity negatives and exact-ID-less replacement isolation | Physical zero-carry 218→20→207 replacement and two-upload structured refusal confirmed; unique-write reconciliation pending | README + TESTING §26 + PHASE_NATIVE_PLAYLIST; installed field continuation pending |
| Native artist-aware budget and controller continuation (v0.9.3) | Exact work+complete credit before page budget; pre-resolved immutable id plus independently same-id replacement and route-matched final re-fetch | Budget noise/over-budget, same/different/unresolved id claim, duration replacement, route provenance and dynamic diagnostic-key regressions | Diagnostic coalescing, multiple unique writes and natural same-id `Unica` recreation/final write confirmed | README + TESTING §§27–28 + PHASE_NATIVE_PLAYLIST |
| Exact native author credit and featured-work symmetry (v0.9.4) | Canonical author remains an independent complete credit; the same explicit feature suffix reduces native/candidate works | Criminal exact-author positive, partial/unrelated negatives, Ella ambiguity and Si Te Dejas contradiction fixtures | Exact APK installed; ambiguity/no-result/zero-carry controls retained; unique `CGjuWHEPxgc` write block-confirmed | README + TESTING §28 + PHASE_NATIVE_PLAYLIST |
