# RustedWax behavior contract

This document is the canonical behavior reference for RustedWax. It separates
observed implementation from intended product behavior so that a test, comment,
or incident note cannot silently become product policy.

The source code decides what a particular build actually does. This document
decides what a conforming build is supposed to do. `README.md` describes the
product, `TESTING.md` verifies this contract, and `PHASE*.md` files are historical
design records rather than current specifications.

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

## Historical v0.8.7 reconciliation plan

This completed order is retained to explain the v0.8.7 reconciliation. It is
not the active v0.8.11 plan, which appears in its own patch contract below:

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

## v0.8.11 patch contract (planned; no implementation yet)

The 2026-07-31 through 2026-08-01 v0.8.10 field run is recorded in log 14 and
was reconciled against the signed-in YouTube History plus both live
`skiptvads.vidz` profile sections. The transport and hyperlink boundary passed:
109 of 109 broadcast payloads received block confirmation, all 109 were visible,
all carried their matching 11-character YouTube hyperlink, all 54 `song`
payloads appeared under Music, all 55 `video` payloads appeared under Videos,
and no ad-like payload reached the profile.

The run was nevertheless not a clean behavioral pass:

| Evidence | v0.8.10 outcome | v0.8.11 required outcome |
| --- | --- | --- |
| `saGYMhApaH8`, “Me Porto Bonito”, finalized at 193/191 seconds | The payload used the following `3mchJ-EW9rM` La Bebe facts and id | The ended session either broadcasts its own corroborated id and facts or is visibly refused; the next track can never complete it |
| `aZaxQG3ggng`, “Crazy”, finalized at 194/192 seconds | The payload used the following `2QqyPy2itXw` Coming Home facts and id | Resolver, enrichment and payload construction remain bound to the immutable ended snapshot |
| `IW524Zl2Pus`, “The best cosplayer avengers”, finalized at 23/22 seconds | A stale `Sponsored` label from the preceding six-second stadium ad was bound two milliseconds after the URL advanced, so the legitimate Short was vetoed | A transition-frame label is provisional and cannot veto the new Short unless re-observed for that stable URL/session generation |
| `5YrJf3CpHNk`, “Cardi B - Trump”, published duration `227125` then `227124` ms | Exact-duration track keys split one continuous viewing into 48% and 53%; both fragments failed separately | A same-metadata duration refinement within 2 seconds remains one track and qualifies on accumulated progress |
| Four song titles containing `|`, quoted tracks or a dash inside parentheses | Generic separator parsing produced malformed or reversed artist/title pairs | Only top-level separators are candidates; explicit shapes and channel agreement determine orientation, otherwise parsing remains conservative |

### Required implementation boundaries

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
| Podcast type is not music | Catalogue-type allow/deny rule | Real timer type regression | Now card remains `video` | README + TESTING classification case |
| Every YouTube entry is linked | Video-id gate, payload-builder gate, and serialized-broadcast gate | Shorts lockup parser, completed identity, ambiguity, unresolved payload, and serialized policy regressions | Not logged names unresolved identity; quiet-bar banner says items stayed off-chain | README + TESTING v0.8.10 recovery cases |
