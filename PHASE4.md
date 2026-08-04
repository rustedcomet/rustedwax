# Phase 4 — Control, exclusivity, and metadata fidelity

> **Chronological engineering record.** Earlier sections describe the problem or behavior at that
> point in development and are superseded by later version addenda. Use [README.md](README.md) for
> the current product description, [BEHAVIOR_CONTRACT.md](BEHAVIOR_CONTRACT.md) for the canonical
> invariants and v0.8.6 as-built audit, and [TESTING.md](TESTING.md) for the current verification matrix.
> The unsupported YouTube lookup retained for concept testing is documented in
> [README.md](README.md#youtube-policy-and-prototype-tradeoff).
> **Phase status:** closed at v0.8.15 after the accepted log-21 field round.
> The strict historical fixture matrix was not completed; TESTING §22 records
> the known conservative omissions and the user's practical release decision.

Phase 3 shipped automatic scrobbling and it ran clean for a full day on Brave /
Android tablet. This phase addresses the three things that day surfaced:

1. There is no way to **stop** monitoring — only to stop broadcasting.
2. Exclusivity is **inferred, not enforced** — other apps are read, and a
   browser hint can bleed across tabs.
3. Music videos that aren't `Artist - Track` shaped are **misindexed** — wrong
   artist, and `kind: video` where `kind: song` was meant.

> **Numbering note.** DEVELOPMENT_PLAN §9 assigns Phase 4 to *Privacy mode*.
> This work preempts it — privacy mode is untouched and moves to Phase 5.
> Nothing here depends on it, and nothing here blocks it.

---

## 1. What the day of testing found

### 1.1 Stop only stops broadcasting

`Settings.autoScrobble` is checked in `ScrobbleEngine.onTrackFinalized`, which
is the *last* step of the pipeline. With it off the probe still watches every
media session on the device, still reads browser media notifications, and still
writes full `MediaMetadata` dumps to the event log. "Off" means "observed but
not published."

There is also a trap in the obvious implementation. `SessionProbe.stop()` calls
`Watch.dispose()`, which calls `finalizeCurrent("session ended")`, which invokes
`onTrackFinalized`. Wiring a Stop button straight to `stop()` **broadcasts a
scrobble as you press Stop**, if the current track is past the threshold. Any
Stop path must be able to tear down *without* finalizing.

### 1.2 Exclusivity is weaker than it looks

Two independent holes:

| Hole | Where | Effect |
| --- | --- | --- |
| Every session gets a `Watch` | `SessionProbe.syncControllers` | Spotify, podcast players, every other app has its title/artist/album dumped to the log. They can't scrobble (`isTarget = false`), but they are being read |
| Hints are keyed by **package**, not session | `NotificationHints.byPackage` | Brave has exactly one hint slot. Two tabs playing audio means last-write-wins |

The second is the serious one. Concretely, with YouTube and any other audio site
open in Brave:

- Non-YouTube audio can inherit a stale `youtube.com` hint and be scrobbled as
  YouTube — the exact misattribution `YouTubeProbe` was written to prevent.
- `onNotificationRemoved` clears the whole package, so closing one tab erases
  the surviving tab's evidence and its scrobble is silently skipped.

### 1.3 Music videos misindexed

Observed: <https://www.youtube.com/watch?v=ICSCJBc9fkY> scrobbled as

```
artist: OLD MOON CHILD                                          ← the channel
title:  【Bring Me The Horizon】I Used to Make Out With Medusa
        (Instrumental) 2023【Guitar Cover】＋Screen Tabs          ← the raw title
kind:   video                                                   ← should be song
```

Traced through the code:

| Step | Why it produced that |
| --- | --- |
| `TitleParser.SEPARATORS` | Contains `「` and `『` but **not** `【`. No artist/track split happens |
| `TitleParser.parse` fallback | With no separator, artist falls back to `cleanChannel(channel)` — the channel name |
| `TitleParser.looksLikeMusic` | Requires a VEVO/Topic channel **or** a separator. Neither holds, so `false` → `kind: video` |

The artist is sitting in the title, inside brackets the parser doesn't know
about.

### 1.4 The description can't be fetched (yet)

PHASE0 run 1 established Chromium publishes no URI metadata keys and ships
artwork as an embedded bitmap, which is why `YouTubeProbe` lands on `SiteOnly`
in normal use. **The app does not know which video is playing**, so it cannot
fetch that video's description. Getting the description requires first getting
the video id, which requires reading the browser's address bar — see §5.

---

## 2. Decisions locked in

Decided 2026-07-23, before implementation.

| # | Decision | Rejected alternative |
| --- | --- | --- |
| D1 | **Stop tears down the probe, keeps the grant.** Notification Access stays granted so Start resumes with no trip to system settings. Auto-scrobble remains a separate inner switch | A single master switch (loses monitor-without-broadcast, which is how parsing bugs get diagnosed); fully unbinding the listener (re-grant friction on every Start) |
| D2 | **Stop discards the in-progress track**, never broadcasts on the way out. The offline queue **keeps draining** — those scrobbles were earned before Stop | Finalizing past-threshold tracks on Stop (a Stop button that writes to an immutable chain is a bad Stop button); freezing the queue (earned scrobbles rot, timestamps drift) |
| D3 | **Non-target packages are never watched.** No `Watch`, no metadata, no snapshot. The log keeps a single `ignored: <package>` line so absence is still debuggable | Watch-all-scrobble-none (keeps reading other apps, which is what we're removing); a diagnostics toggle (third piece of state, easy to leave on) |
| D4 | **`kind: song` by default**, with a non-music blocklist | Video-by-default with a wider allowlist (genuine music videos keep slipping through, which is the reported bug) |
| D5 | **Opt-in Accessibility service** reads the browser address bar for exact origin + video id. Hint hardening happens regardless | Hints only (blind to the video id forever, no `url`, no enrichment) |
| D6 | **Hint fallback stays live** when Accessibility is off. Accessibility upgrades quality, it isn't a precondition | Requiring a verified URL (app does nothing until granted, and silently dies if Android kills the service) |
| D7 | **Titles normalize to the original recording.** `(Instrumental)`, `【Guitar Cover】`, years and tab/gear suffixes are stripped | Preserving `(Live)`/`(Acoustic)`/`(Instrumental)` (covers and originals stay separate entries) |
| D8 | **Enrichment scrapes the watch page**, no API key | YouTube Data API v3 with a user-supplied key (more stable, `categoryId 10` is authoritative, but needs Google Cloud setup) |

### D7 has a cost worth stating

The extension does **not** strip `(Live)`. A live version scrobbled from the
tablet and the same one scrobbled from desktop will no longer produce identical
entries. The README's claim that phone and desktop entries look the same
on-chain stops being true and must be rewritten.

### D8 is the fragile one

`ytInitialPlayerResponse` is undocumented markup that can change without
warning. Mitigations, all mandatory:

- Everything sits behind a `MetadataResolver` interface, so a Data-API
  implementation drops in later without touching the pipeline.
- Every extraction failure logs a distinct line, so **breakage is
  distinguishable from absence**.
- Fail-soft: enrichment never blocks, delays past its budget, or drops a
  scrobble.

---

## 3. Part A — Stop monitoring

New `Settings.monitoringEnabled`, default `true`, and a `MonitorSwitch` object
publishing it as a `StateFlow`. The activity and the listener service live in
the same process, so this is the whole control channel — no intents, no binder.

| File | Change |
| --- | --- |
| `storage/Settings.kt` | `monitoringEnabled` flag |
| `detect/MonitorSwitch.kt` | **new** — persisted `StateFlow<Boolean>`, single source of truth |
| `detect/SessionProbe.kt` | `stop(finalizeTracks: Boolean)`, `Watch.dispose(finalize: Boolean)` |
| `detect/NotificationHints.kt` | `clearAll()` |
| `detect/RustedWaxListenerService.kt` | Collects `MonitorSwitch`; builds/tears down the probe live; `onNotificationPosted` early-returns while stopped |
| `scrobble/ScrobbleEngine.kt` | Defensive re-check of the flag at finalize |
| `ui/MainScreen.kt`, `MainActivity.kt` | Start/Stop control; distinct "stopped by you" state |

Finalize semantics, which are the subtle part:

| Teardown path | Finalize? | Why |
| --- | --- | --- |
| User presses Stop | **No** | D2 — Stop must never write to the chain |
| Session disappears from `getActiveSessions` | Yes | The track genuinely ended; last chance to score it |
| `onListenerDisconnected` | Yes | System teardown, same reasoning |

The UI needs a third state. Today `serviceRunning = probe != null` renders a red
"Waiting for Android to start the listener service" warning — which is exactly
what a user-initiated Stop would look like. Stopped-by-you must read as calm and
deliberate, not as an error.

## 4. Part B — YouTube-only

- **`SessionProbe.syncControllers`** — no `Watch` for packages outside
  `TARGET_PACKAGES`. One `ignored:` line per package, deduped against a seen-set
  so it can't spam the log. Native apps disappear from the Now tab, so README's
  "Bonus over desktop" section describes behaviour that will no longer exist.
- **`NotificationHints`** — a bounded ring of recent hints per package, and
  `bestFor(pkg, title, artist)` binding a hint to a session by matching the
  notification title against the session's `METADATA_KEY_TITLE` (Chromium sets
  both from the same source). Newest-hint fallback only when it is fresh *and*
  exactly one browser session is live. `onNotificationRemoved` removes the
  matching hint, not the package.
- **`Watch.taintedReason`** — once a bound hint resolves to a non-YouTube host,
  that track can never scrobble, even if a YouTube hint arrives later. Cleared
  in `resetForNewTrack()`.
- **`YouTubeProbe`** — explicit host allowlist (`youtube.com`, `www.`, `m.`,
  `music.`, `youtu.be`) replacing the `endsWith(".youtube.com")` suffix match.

## 5. Part C — Kind and title parsing

New `detect/MusicClassifier.kt`. Decision order **as built**:

1. YouTube's own category, when enrichment resolved one → decisive both ways
   (`Music` → song; `Gaming`, `News & Politics`, `Sports`, `Education`,
   `Howto & Style`, `Science & Technology`… → video). Ambiguous categories like
   `Entertainment` don't decide anything.
2. Non-music blocklist hit → `video` (podcast, interview, tutorial, review,
   reaction, gameplay, vlog, trailer, documentary…).
3. Positive music evidence → `song` (music.youtube.com, VEVO, `- Topic`,
   cover/live/lyrics/instrumental vocabulary, a title that names an artist).
4. Long-form with zero music evidence → `video`.
5. Default → `song` (D4).

> Step 1 was promoted above the blocklist during implementation. The plan had
> enrichment feeding step 3, but a category is an *answer* where the blocklist
> is a heuristic, and it should not lose to one. `Gameplay Theme` on a Music
> category is music; a VEVO channel filed under Gaming is not.

Blocklist terms are split by field. `news`, `gaming`, `podcast`, `sports` match
the **channel only** — "Bad News" and "Good News" are songs, and `BBC News` is
not a band.

Long-form duration only downgrades to `video` when there is **zero** music
evidence — otherwise it eats DJ sets and full albums.

> **Ordering constraint.** The classifier must run on the **raw** title, before
> normalization. "Cover", "live" and "lyrics" are the strongest music signals
> available, and §5's normalization strips exactly those words. Classify first,
> then clean, or the parser destroys its own evidence.

`TitleParser` gains a leading-bracket artist rule (`【X】Rest` → artist `X`),
CJK/fullwidth separators, the `＋` suffix cutter, trailing gear/tab segments
(`＋Screen Tabs`, `w/ Tabs`), standalone year tokens, and cover-video vocabulary
in the promo tables. Target for the reported case:

```
artist: Bring Me The Horizon
title:  I Used to Make Out With Medusa
kind:   song
```

### Implementation note — the year strip has to happen after the split

Stripping a trailing year inside `clean()` looked right and was wrong.
`clean()` runs on the whole title, so its "don't erase a title that is only a
year" guard counted words across the *whole string*: `"The Smashing Pumpkins -
1979"` had plenty of words, the year was stripped before the separator was ever
considered, and the track became `"The Smashing Pumpkins"`. The year is now
stripped from whatever ends up being the **track**, after the split, where the
guard sees only `"1979"` and leaves it alone. Caught by a unit test, not on the
chain.

## 6. Part D — Accessibility service

`detect/UrlWatcherService.kt`, off by default, own consent screen.

`accessibility_service_config.xml` scopes `packageNames` to the browser packages
only — the **OS itself** then prevents it reading any other app. That constraint
belongs in the consent copy and the README, not just the config file.

Reads `url_bar` by view id, with an EditText-in-toolbar fallback; publishes to a
`UrlEvidence` holder; parses `v=` for the video id. When present and bound to
the playing session it yields `Identity.Confirmed` — which finally populates
`url` and is the only route to enrichment.

### Q7 — what does Brave's address bar actually expose?

**Unmeasured, and it decides how much Part D is worth.** Chromium on Android
often renders the omnibox as the bare host (`youtube.com`) rather than the full
URL, in which case there is no `v=` to read and no video id — which would leave
enrichment permanently unreachable even with the service enabled.

The watcher logs the raw bar contents verbatim on every change (`[url]` lines),
so one session on the tablet answers it. Outcomes:

| If the bar holds | Then |
| --- | --- |
| Full URL with `v=` | Everything in D and E works as designed |
| Host only | Exact exclusivity still works; no `url`, and E never runs. Fall back to D8's rejected alternative — the Data API — or resolve the id another way |

Three caveats, recorded now rather than discovered on-device:

1. The address bar reflects the **foreground tab**, which is not necessarily the
   tab that's playing. URL evidence is accepted only when the media title
   corroborates it, or a single browser session is live.
2. Screen off → no accessibility events → the URL goes stale. Freshness window
   required.
3. Android 13+ treats accessibility for a **sideloaded** APK as a restricted
   setting. "Allow restricted settings" must be enabled from App info before the
   toggle is even tappable. This goes in the setup instructions.

## 7. Part E — Enrichment

`enrich/YouTubePageResolver.kt`. Fetch the watch URL, extract
`ytInitialPlayerResponse`, read `videoDetails.{title,author,shortDescription}`
and `microformat…category` — that category string ("Music" / "Gaming" /
"Entertainment") is a far better `kind` signal than any title heuristic.
Description mined for `Original song by`, `Song:`, `Artist:` patterns. The
"Music in this video" credits panel is inconsistent for logged-out fetches, so
it is best-effort, never load-bearing.

Hard rules: ~4 s budget, fail-soft to the offline parse, never drops or delays a
scrobble, results cached by video id on disk so the 160% second transaction
doesn't refetch.

This restructures `ScrobbleEngine.onTrackFinalized`: payload construction moves
inside the coroutine, after enrichment, so the dedup key is computed from
corrected values. The Now-tab preview keeps calling `ScrobbleBuilder.from()`
synchronously and shows enrichment only when already cached.

---

## 8. Build order

| Order | Part | Ships independently? | Offline-testable? |
| --- | --- | --- | --- |
| 1 | A — Stop | Yes | Yes |
| 2 | B — Exclusivity | Yes | Yes |
| 3 | C — Kind + parsing | Yes | Yes (unit tests) |
| 4 | D — Accessibility | Needs on-device verification | No |
| 5 | E — Enrichment | Depends on D | Partly (HTML fixture) |

A–C cover most of what actually bit during testing and carry no new permissions.
D–E are the riskier layer.

## 9. Tests

64 tests, all passing.

| Test | Covers |
| --- | --- |
| `TitleParserTest` — incl. the Medusa title, D7 reversal, the year guard | §5 |
| `MusicClassifierTest` — **new** | D4, blocklist calibration |
| `NotificationHintsTest` — **new**, two-session cross-contamination both ways | §4 |
| `WatchPageParserTest` — **new**, trimmed watch-page fixture | §7, and detects markup drift |

The scrape's parsing was split into a pure `enrich/WatchPageParser.kt` so it can
be tested without a device or a network. That's what makes D8's "breakage must
be visible" mitigation real: markup drift fails a test rather than silently
degrading in production.

## 9b. Field findings — 2026-07-24, first day of real 0.4.0 use

Eleven film clips, trailers and shorts scrobbled as `song`. Fetching each
video's watch page showed the failure was **not** missing keywords:

| Failure | Evidence | Fix (v0.4.1) |
| --- | --- | --- |
| `Film & Animation` absent from the category list | 4 of 11 carried it; enrichment fetched it correctly and the classifier ignored it | Added to the decisive non-music categories |
| Ambiguous category fell through to *weak* heuristics | `You and who? \| 🎬 Notting Hill (1999)` (Entertainment) passed the artist heuristic on its `\|` | A known non-music category now **demands explicit** music evidence (cover/lyrics/VEVO/…); a separator or the bare default no longer suffices |
| Exact phrases are brittle | `Official **Final** Trailer` ≠ `official trailer` | Structural rule: film-context word within two words before "trailer", or `Trailer 2` / `Trailer (2026)` |
| Shorts scrobble on defaults | 18-second clip captioned with dashes read as `Artist - Track` | `/shorts/` URLs and anything under 90 s require explicit evidence |
| Preview lied about the kind | Now tab classified from the title alone; enrichment only ran at finalize | Facts are **prefetched** when the video id appears; the Now card, the manual broadcast button and the engine all read the same cache |

The principle that fell out: **evidence is layered, and weak evidence is only
acceptable when nothing better exists.** Category > blocklist/structure >
explicit music vocabulary > title-shape heuristics > default. An uploader who
had "Music" available and picked something else outranks a dash in their title.

Known trade, accepted: a fan upload of a real song titled plain `Artist -
Track`, categorized `Entertainment`, with no music vocabulary, now lands as
`video`. The user's stated priority is playlist purity — a missed song is one
lost entry; a false one is permanent curation debt.

## 9c. Field findings — 2026-07-24 evening: the finalize-time evidence race

Two on-chain failures with the same root cause, both found by reading the
account history back off the chain:

- `17:27` — an Education-category short broadcast as `song` with **no url**.
  The Now card had shown the category correctly while it played; by finalize
  the user had scrolled to the next short, the address bar no longer matched,
  identity degraded to SiteOnly, and with no video id there was no category —
  the D4 default fired.
- `04:07` / `04:11` — two different songs broadcast with the **same url**
  (`v=7FPf8mHXIpY`): a track finalized while the bar showed the other video
  and inherited its id. Misattribution, on an immutable chain.

PHASE0's rule — *resolve identity at finalize, because the notification hint
arrives late* — is exactly wrong for address-bar evidence, which is right at
track **start** and stale at track **end**. The two evidence types age in
opposite directions.

Fix (v0.4.2): **latch on first confirmation, spend at finalize.** The first
Confirmed identity is captured on the Watch and kept for the track's lifetime
(cleared on track change). Once enrichment has the watch page, the page's own
title is checked against the session title; a clear mismatch rejects the id
for the track (fail-closed — no url beats a wrong url) and the id can't
re-latch. Additionally, an uploader-declared `#shorts` tag in the title now
counts as short-form, since it survives losing the URL entirely.

Also observed, deliberately not chased yet: `Title | Channel`-shaped titles
split as artist|track by the separator heuristic (a kitten video broadcast
with artist and title swapped). Harmless while kind=video; worth a look if it
ever shows up on a song.

## 9d. v0.5.0 — the double-listen cap and MusicBrainz

Two additions driven by the same evening's chain data:

**Double-listen is songs-only now.** A 56-second short auto-looped past 160%
and broadcast twice in one block (percents 100 + 76). Upstream doubles every
kind, but the rule exists to record a genuine second *listen*; a video is
watched, not re-listened. `ScrobbleRules.capForKind` trims non-song decisions
to one transaction. Deliberate deviation from upstream, documented in README.

**MusicBrainz verification** (`enrich/MusicBrainzVerifier.kt`). The parser
produces text and text lies — `artist: "Times Cover"` and `artist:
"Shérazade"` (an uploader, not the performer) both reached the chain. The
verifier checks the parsed artist/track pair against MusicBrainz's recording
search; a match requires artist **and** title to both agree (title-only would
claim every clip named like some song — "BlueBird", "Doomed" and "Film" are
all real recordings) plus a healthy search score. A confirmed match:

- is **explicit music evidence** for the classifier — it rescues the
  "Maphra - Doomed" class of short performance clips that the short-form rule
  otherwise files as `video`, and overrules ambiguous categories; it never
  beats the blocklist (a trailer scoring a real song is still a trailer);
- supplies **canonical spelling** for artist and title in the payload.

Gated behind the same "Look videos up" switch (it's off-device traffic).
Results cached including negatives; lookups rate-limited to 1/s per
MusicBrainz etiquette; absent artists are a missed upgrade, never a wrong one.
This is the mobile answer to upstream's Wikipedia/Wikidata layer, which
DEVELOPMENT_PLAN originally deferred.

## 9e. v0.5.1 — the category is wrong in both directions; D4 revised

The 2026-07-24 evening batch, checked against each video's watch page:

| Video | Reality | YouTube category | v0.5.0 verdict |
| --- | --- | --- | --- |
| "How to Throat Sing like in DUNE!" | tutorial | **Music** | song ✗ |
| "Marilyn Manson Releases Heavy New Single…" (47 s) | news | **Music** | song ✗ |
| "MAPHRA - DOOMED = BEST COVER 2026🥵💯" | cover hype clip | Education | video (kept) |
| "Saturday Night Fever: Tony's solo dance" | movie clip | Film & Animation | song ✗ (id lost → no category → old default) |
| `"Exit Wound" REACTION \| Old School Fan Hears…` | reaction | — (id lost) | song ✗ (pipe-split "names an artist") |

The category-first ladder assumed the category is honest; it is — the Manson
channel really does talk about music — and still wrong for *kind*: those
videos are not listens. Changes:

- **Format evidence now beats the category in both directions.** Blocklist,
  trailer structure and a new music-news headline rule (`releases/announces …
  single/album/tour`) outrank category `Music`; hard music evidence
  (MusicBrainz, instrument covers, music.youtube.com) outranks a non-music
  category.
- **Bare "reaction" is a contextual tier**: below MusicBrainz and category
  Music (so "Chain Reaction" is rescued by evidence), above weak title shapes
  (so pipe-split reaction titles stop passing as artists).
- **D4 revised: the last-resort default is now `video`.** Every default-song
  hit in two days of field data was a news clip, a movie scene or a vlog.
  Real music essentially always carries a positive signal — category,
  MusicBrainz, cover/lyrics vocabulary, an artist channel, an `Artist - Track`
  title. The documented cost: an untagged fan upload of a real song with none
  of those now lands as `video` unless MusicBrainz knows the recording.
- **MusicBrainz fixes that made the safety net real**: the 4-second timeout
  wrapped the 1-req/s queue wait, so during shorts browsing most lookups died
  before their HTTP request started (the "— (not checked yet)" the field test
  saw everywhere) — the timeout now bounds only the network call. And the
  engine tries the **swapped artist/track pair** as a second candidate, which
  both widens the rescue and heals the `Title | Channel` reversed-split bug
  for real songs: MusicBrainz returns the fields in their true roles.

Still open, unchanged by this: ids often can't be latched during shorts-feed
browsing (the address bar lags a scroll behind; the title corroboration then
rightly rejects the stale id) — those scrobbles carry no `url`, and category
enrichment can't run for them. MusicBrainz, which needs no id, is exactly the
layer that still works there.

**Addendum (v0.5.2)** — TV episode numbering joined the format tier. "The Big
Bang Theory Season 6 Ep 19 - Best Scenes" showed `song` on the Now card until
its Comedy category arrived: "Ep 19" wasn't recognized offline, and the ` - `
then read as an artist separator. Since the enriched verdict only *previews*
late but *broadcasts* correctly, the real exposure is the no-id case where the
offline verdict is final — so `Season <n> Ep <n>` and `SxxExx` now decide
`video` with no category at all. Bare `EP <n>` is deliberately not matched:
in music, EP is a release format, and "EP 2" names real records.

## 9f. v0.5.3 — the URL path never re-ran identity

Field report: three entries with no hyperlink. Reading the chain back showed
**20 of 133** scrobbles carried no `url`, split into two unrelated causes.

**Twelve were manual broadcasts.** Their `percent_played` values (0–52%) are
below the 60% threshold, which the automatic path cannot emit — so they came
from the Now card's *Broadcast this scrobble* button, which reads
`session.confirmed?.url` at tap time and bypasses both `ScrobbleRules` and
`DedupLedger`. (That bypass is also the source of the duplicate pairs reported
separately — same button, no ledger claim.)

**The rest were the real bug.** `NotificationHints.put` has notified the probe
since v0.1.2, because PHASE0 measured hints landing ~300 ms *after* the
metadata callback that first judges the track. The address-bar watcher shipped
in v0.4.0 with the same race and **no such wiring**: `UrlEvidence.put` stored
the evidence and told nobody. The only thing re-running identity afterwards was
`MainActivity`'s 1-second `tick()` → `publish()` → `snapshot()` → `identityOf`.

So `url` silently depended on the app being open. Every no-url automatic
scrobble in the sample was recorded while browsing with RustedWax in the
background, where the sole remaining triggers are media callbacks — and a video
playing straight through fires very few.

Fixes:

- **`UrlEvidence.onEvidence`**, wired to `reidentify()` exactly as hints are.
  The latch is now established whenever the bar resolves a video id, whether or
  not the UI is alive. `reidentify(trigger)` names which source woke it.
- **A hostless hint no longer vetoes the bar.** `identify` required
  `isYouTubeHost(hint.host)` to confirm, so a hint whose sub-text wasn't a
  parseable host — `host = null` — blocked confirmation even with a video id in
  hand. Absence of information is not disagreement; only a hint naming a
  *different* site contradicts. Pinned by `YouTubeProbeTest`.
- **A missing `url` explains itself**: `broadcasting WITHOUT url — identity was
  …` at broadcast time, so the next occurrence is diagnosable from the log
  instead of from the chain.

`YouTubeProbeTest` is new and needs no Robolectric — route 0 is evaluated
before the metadata routes, so `md = null` exercises it.

Still not guaranteed: Chromium's omnibox sometimes shows only a host (a feed or
channel page rather than a watch URL), and a genuinely disagreeing bar is still
refused. Both leave `platform` without `url`. Closing that gap needs the
Phase 7 item — resolving the id by title+channel search.

## 9g. v0.5.4 — the manual button now shares the ledger

Field report: duplicated entries. The chain showed pairs with **byte-identical
payloads except `percent_played`** — same title, artist, track-start timestamp,
duration and url:

| Pair | Gap | Percents |
| --- | --- | --- |
| Hades Is Unleashed | 3 s | 69 → 70 |
| Gods Full Fight & Final Scene | 3 s | 59 → 60 |
| Against The Kraken | 5.6 min | 29 → 95 |
| Arya Stark Fights Brienne | 75 s | 66 → 100 |

Not the double-listen rule (capped at one tx for `video` since v0.5.0) and not
a dedup-key defect — `keyFor` is hour-bucketed on title+artist, so all four
pairs share a key. The cause: **the Now card's *Broadcast this scrobble* button
never consulted the ledger.** `MainActivity.broadcast()` goes straight to
signing, bypassing both `ScrobbleRules` and `DedupLedger`, which is also why
percents below the 60% threshold (0–52%) appear on-chain at all — the automatic
path mathematically cannot emit them.

Two shapes followed from that. A manual send left no claim, so the automatic
finalize minutes later saw a free key and broadcast the same listen again
(29% → 95%). And the button stayed **enabled during a send**, so two taps a
second apart both went through (69% → 70%, the Now card's percent ticking
between them).

Fixes:

- `ScrobbleEngine.claimManual` / `releaseManualClaim` — the manual path claims
  the same ledger key before sending, so it is idempotent *and* blocks the
  later automatic finalize. Claimed before the send so concurrent taps can't
  both pass; released on failure, because unlike the automatic path there is no
  `BroadcastQueue` to fall back on and a network error would otherwise strand
  the listen permanently.
- Already-claimed taps are **refused with a message** rather than duplicating.
- The button is disabled mid-send (`Sending…`).
- `DedupLedger.release`, and `DedupLedgerTest` pinning what must and must not
  collide — the key is now a contract shared by two code paths.

Deliberately unchanged: the button still ignores the 60% threshold. Manual
override is its entire purpose, and the percent is on screen when it's pressed.

## 9h. v0.6.0 — the address bar goes silent; search recovers the id

An exported device log finally explained the missing `url`s, and the cause was
neither of the two theories that preceded it.

Around one album playlist:

```
17:42:50  [url] host=m.youtube.com video=—  ("m.youtube.com")
          ← 13 minutes with no [url] line at all
17:51:43  [enrich] no video id — offline parse only   → Doomed,      no url
17:55:43  [enrich] no video id — offline parse only   → Happy Song,  no url
17:55:56  [url] video=Ow_qI_F2ZJI ("…watch?v=…&list=…&index=3")
17:58:55  broadcasting: Throne … url ✓
18:02:50  [enrich] no video id — offline parse only   → True Friends, no url
18:07:09  [enrich] no video id — offline parse only   → Follow You,   no url
18:08:54  [url] video=k48k3BUdcXQ ("…&list=…&index=6")
```

The bar jumped from `index=3` to `index=6` — it is not lagging by a track, it
**stops reporting entirely**. A playlist advancing via the History API changes
nothing on screen that fires an accessibility event once the toolbar has
scrolled away, the browser is backgrounded, or the screen is off. Gaps of 15,
35 and 40 minutes appear in the same session; 15 of 60 broadcasts had no `url`,
including four of six tracks in that playlist.

That rules out polling as the fix (there is often no foreground window to poll)
and rules out the v0.5.3 re-identify wiring helping (it only runs when the bar
reports, and the bar never did). It also confirms the upstream extension does
**not** share this problem: `src/connectors/youtube.ts` reads the id from
`ytd-watch-flexy[video-id]` in the DOM and only falls back to the URL on
mobile, with a comment noting `location.href` "could miss the video URL".

**Fix — `enrich/VideoIdResolver.kt`.** When no id was latched, search YouTube
for the session's title + channel and match the results. This needs no event,
no foreground window and no screen, so it covers exactly what the bar cannot.

Matching is strict because the id is *inferred*. The measured results for the
reported track:

| id | length | owner | title |
| --- | --- | --- | --- |
| `CZFTfYYql4k` | 4:35 | Bring Me The Horizon | Doomed |
| `DIEI2YLYg6o` | 4:36 | Maphra - Topic | Doomed |

Both are titled exactly "Doomed" within two seconds of each other — the second
is a **different artist's cover**. Title alone or duration alone picks the
wrong video. So a match requires title **and** channel **and** duration
(±5 s — search displays 4:35 for a 274 s video), and anything less resolves to
nothing, leaving the payload without a `url` exactly as before. Channels are
compared through `TitleParser.cleanChannel`, because search lists the owner as
"Bring Me The Horizon" while the session and watch page say "… - Topic".

Verified against the watch pages: `CZFTfYYql4k` is 274 s by "Bring Me The
Horizon - Topic" — an exact match for what the session reported.

Also fixed: a host-only omnibox reading used to **overwrite** a video id
captured seconds earlier for the same host. The latch protected the current
track, but the next track inherited the degraded evidence. A host-only reading
of the same host is a redraw, not navigation, and is now ignored.

Parsing lives in the pure `SearchResultsParser`, which walks the JSON tree for
video-shaped nodes rather than following a fixed renderer path — the nesting
differs between shells and is reorganised freely, while the item shape is
stable. `SearchResultsParserTest` pins the matching against a fixture trimmed
from the real page, including the cover that must be rejected.

## 9i. v0.6.1 — the playlist is the exact source; the card stops lying

Two follow-ups from testing 0.6.0.

**The Now card contradicted the broadcast.** It rendered the *latch* state and
said "— (no video id available)", but the engine also resolves an id at
broadcast time, so tracks were getting links the card had already written off
("The Black" did; the card said it wouldn't). The card now says the id is not
in the address bar and that it is looked up at broadcast — or, when lookups are
off, that enabling them would recover it.

**Search resolves a plausible upload, not necessarily the played one.**
Measured on the playlist from the field logs
(`PLmGqppZSJ9nXHUioh-WAy7ne8I3nP4FOR`):

| source | id for "Doomed" | length | channel |
| --- | --- | --- | --- |
| search | `CZFTfYYql4k` | 274 s | Bring Me The Horizon - Topic |
| the playlist itself | `5Oc0ja19_GU` | 4:35 | Bring Me The Horizon |

Same song, same artist, same length — **different uploads**. No amount of
title/channel/duration strictness separates them, so the search fallback was
writing a link to a video the user had not played. The playlist page names the
exact entry, and one fetch covers every track in it: all four tracks that lost
their `url` in that session (Doomed, Happy Song, True Friends, Follow You) are
in that single response.

So `resolveVideoId` now tries the playlist first and only falls back to search.
The playlist id is captured from `list=` in the address bar and kept for **3
hours** — far longer than the 5-minute evidence window — because a playlist is
context for a whole sitting, while the bar stops naming individual videos
within seconds.

**Markup note.** Playlist pages no longer use `playlistVideoRenderer`; they
render through `lockupViewModel` (`contentId`, title at
`metadata.lockupMetadataViewModel.title.content`, channel in the first
`metadataRows` entry, duration in a thumbnail badge). Two implementation traps
found while building it:

- The generic "object with `videoId` and `title`" walker that works for search
  finds **nothing** on a playlist page — different field names entirely.
- Taking "the first string under metadata" as the title is unsound:
  `JSONObject.keys()` has no defined order, so it returned the channel roughly
  as often as the title. The title is now read by its own key; only
  `metadataRows` is a JSON array, and arrays *are* ordered, so the channel is
  safe to take as its first row.

The parser was validated against the live page, not only the fixture — all
nine entries extract correctly.

## 9j. v0.7.0 — provenance outranks title heuristics

Two inputs: a 50-video field run, and an ordering flaw reported by the
scrobble.life maintainer with counter-examples from their database.

### The 50-video run

All 50 carried a `url`, including tracks played from several playlists with
Brave in the background — the v0.6.x id-recovery work holds. Kind was right on
47. The three misses:

| Video | Broadcast | Cause |
| --- | --- | --- |
| `Blade II \| Sewers of the Damned \| ClipZone: Heroes & Villains` | song | the weakest rule fired |
| `Blade II \| Blade Fights Nomak \| ClipZone: Heroes & Villains` | song | same |
| `Saturday Night Fever: Tony's solo dance` | song | *not in this run* — 2026-07-24, pre-0.5.1 |

The ClipZone pair share a shape: a three-part title. `TitleParser` split on the
first `|`, yielding artist "Blade II" and track "Sewers of the Damned |
ClipZone…", which made step 8 — *"the title names an artist"*, the weakest rule
in the ladder — return `song`. The category would have caught it at step 7, so
enrichment had produced none for these.

Fix: a title with **three or more separator-delimited segments** no longer
qualifies for that rule. Two-part `Artist - Track` is untouched.

### The maintainer's ordering flaw — confirmed

The blocklist ran at step 1, above `music.youtube.com` and MusicBrainz (step 3)
and the `- Topic`/VEVO rule (step 6). So a Topic channel lost to a word in its
own title, and could not even be rescued by the `Music` category, because step
1 returned first.

Their counter-examples, checked one by one against the lists:

| Title | Hit | Affected |
| --- | --- | --- |
| `Tutorial`, `Tutorial (Puzzle)` (Calum Bowen, *Poinpy*) | `tutorial` | yes |
| `Trailer 2`, `Trailer 3` (*Pikuniku*) | `\btrailers?\s*#?\d` | yes |
| `Fukk A Interview` (Future) | `interview` | yes |
| `Wake Up` | — nothing | no, already correct |

Five of six. Game-OST vocabulary was a systematic blind spot.

**The principle behind the fix.** The blocklist's unstated premise is that a
human wrote a *descriptive* title. A Topic channel breaks that premise: YouTube
generates it from a distributor's feed, so the title **is** catalogue metadata
— a track name. Running human-title heuristics over distributor metadata is a
category error.

That does not reopen the v0.5.1 case that put format evidence above the
*category*, where an uploader had picked "Music" for a tutorial and a news
bulletin. The distinction is **who set the metadata**: a human can be wrong or
chase reach; an auto-generated feed cannot.

Changes:

- **New top tier (step 0):** `music.youtube.com`, a `- Topic` channel, and a
  watch-page description beginning *"Provided to YouTube by …"*. All bypass the
  title heuristics. The last is broader than the channel-name check and costs
  nothing — `WatchPageParser` already read that string for credits; it is now
  surfaced as `VideoFacts.autoGenerated`, catching Art Tracks on Official
  Artist Channels that have lost the `- Topic` suffix.
- **VEVO deliberately not promoted.** Artist channels host interviews,
  behind-the-scenes and tour trailers; trusting them the same way would turn
  "Metallica Interview 2023" into a song. VEVO stays a vocabulary-tier signal.
- **OST-ambiguous words demoted** from the hard blocklist to the contextual
  tier that already sat below MusicBrainz (where `reaction` lives so "Chain
  Reaction" survives): `tutorial`, `interview`, `episode`, `highlights`,
  `lecture`, `debate`, and the `Trailer N` regex. `official trailer` /
  `movie trailer` / `game trailer` stay hard — they name a format outright.

### One reordering the tests forced

Demoting `tutorial` immediately broke `"Bass Cover Tutorial"`, which started
resolving to `song` via the cover rule. Cover and playthrough vocabulary sat
beside MusicBrainz as "hard evidence", but they are still *words in a
human-written title* — a bass cover tutorial is a tutorial, not a cover. They
now sit **below** the contextual tier and above the category, so
`"Duality (Drum Cover)"` filed under Education is still that song played.

Caught by an existing test, not on the chain.

## 9k. v0.7.1 — two resolver bugs the session log exposed

The exported log for the 2026-07-28 session, with the `[resolve]` tracing added
in v0.6.0, made both remaining failures legible.

**Overall: 58 of 62 broadcasts carried a `url`.** The playlist path behaved
exactly as designed — a 90-entry playlist was fetched once and then resolved
four tracks from cache for free.

### VEVO channels could never match (3 of the 4 misses)

```
23:56:39  "System Of A Down - Chop Suey! (Official HD Video)" not found among 90 entries — trying search
23:56:40  no confident match … / "systemofadownVEVO" (208s) among 20 results — leaving url unset
```

The correct video was the **first result**: `CSvFpBOe8eY`, exact title, 3:29
against the session's 208 s. It was rejected on the channel.

VEVO channel names are one word. `TitleParser.cleanChannel("systemofadownVEVO")`
strips the suffix and leaves `systemofadown`; search lists the owner as
`System Of A Down`, normalising to `system of a down`. Those never compare
equal, so **every VEVO track failed to resolve** — `systemofadownVEVO`,
`TheVerveVEVO` and `BonJoviVEVO` are three of the four misses in the session.

Fixed by comparing channels with whitespace removed (`SearchResultsParser.channelKey`,
shared with the playlist matcher). Spaces carry no meaning in a channel name;
different artists still differ.

The fourth miss — "With You" from `Linkin Park - Topic` — is the honest recall
limit: that upload simply wasn't among the 18 results.

### Mix and private playlists wasted a fetch each

```
12:45:31  playlist RD7niq-GjUipw → 0 entries cached
13:35:19  playlist RDMzilxvJS2OI → 0 entries cached
14:29:01  playlist RDz3BMeR6IXUQ → 0 entries cached
12:29:50  playlist fetch failed for RDl69Cq38GgZ4
```

`RD…` is a YouTube Mix — auto-generated, personalised, endless — and has no
fetchable page of entries. Each attempt downloaded roughly a megabyte, parsed
zero entries and fell through to search anyway. `LL` and `WL` (Liked, Watch
Later) are private and behave the same. All three prefixes now skip straight to
search.

### Ecosystem finding — first writer wins on scrobble.life

The site keeps one canonical record per video id, seeded by the **first**
scrobble's kind ("First scrobbled by @…"). A later op with the corrected kind
attaches to the existing record instead of moving it — so one pre-fix `song`
trailer stays listed as music for every future listener, and the desktop
extension's under-claimed music keeps real songs pinned in `/videos`. Not
fixable from any client; raised with the site's developer (options: majority
or latest kind, a server-side category check, curator override).

## 9l. v0.8.0 — the shorts floor, and five bugs the 46-video run exposed

A deliberately adversarial session on 2026-07-29: 46 videos mixing full-length
uploads, shorts, playlists, non-English titles and Spanish-language music
obscure enough that MusicBrainz would have nothing to say about it. The goal was
to make the app miss a hyperlink or misindex music.

**The hyperlink bug is fixed.** 29 of 30 automatic broadcasts carried a `url`.
The one miss is honest and self-reporting — the address bar had moved on to the
next video, the search fallback's fetch failed, and the payload said so on its
way out:

```
[identity] YouTube (site only, no video id) → m.youtube.com (no video id in the bar)
[resolve]  search fetch failed for "THE THOMAS CROWN AFFFAIR Official Trailer…"
[engine]   broadcasting WITHOUT url — identity was address bar + notification → …
```

**The kind hierarchy held.** Only 4 shorts became `song`, every one via
YouTube's own `category=Music`. No trailer, vlog or football clip leaked into
`song` across 64 unique shorts. Enrichment resolved a category for 88 unique
ids and failed on 12 (~12%).

### The shorts gap, and why the floor was the wrong shape

Of 64 unique shorts opened:

| Outcome | Count |
| --- | --- |
| Scrobbled | 20 |
| Blocked by the 30 s floor | 22 |
| Below the 60% threshold | 6 |
| **No duration — never measured** | **10** |
| Never reached the engine | 6 |

Two separate problems sit in that table, and only one is a policy.

Sorting every 30 s-floor rejection by URL path settled the policy question:
**all 24 were `/shorts/`, none were `/watch`.** The floor was doing nothing on
the path it was written for — pre-roll ads are a watch-page phenomenon — while
discarding a third of the shorts feed. The blocked durations clustered hard at
the bottom (`7, 10, 10, 10, 10, 10, 11, 12, 12, 15, 16, …`), so a 10 s floor
recovers 23 of the 24 where 15 s recovers only 15.

The exception is granted on **proof the video exists**, not on its length:
`videoDetails.lengthSeconds` parsing successfully means the id has a public
watch page, which an ad creative does not. Length is a useless ad guard — an ad
and a real 12-second clip are the same length.

Cost, stated rather than buried: enrichment fails ~12% of the time and D8
requires it stay non-blocking, so roughly one in eight legitimate short clips is
still held to 30 s. That is the direction to fail. A missed entry can be earned
again by watching; a false one is permanent.

### The loop bound, and a claim the data corrected

`playedMs` accumulates wall-clock time in `STATE_PLAYING` and only resets on a
track change — and a loop is not a track change. The initial concern was that
the 60% gate would be meaningless below 30 s. The log says otherwise on both
counts:

- The 60% gate **rejected 29 of 64 shorts finalizations** (45%). It is doing
  substantial work, not rubber-stamping.
- The highest overrun in the whole session was **120%** (`played 12s of 10s`),
  with the rest at 102–108% — timing slack, not replays.

So looping never fired during fast-scrolling browsing. The *mechanism* is still
unbounded, and the untested case is the passive one: a phone left face-up on a
10 s short reads thousands of percent. `SHORT_MAX_PROGRESS = 2.0` bounds it,
scoped to the lowered floor only — a genuine song double-listen lands near 200%
and must keep earning its second transaction.

### Duration recovery — a silent loss, not a policy

Ten shorts were skipped as "no duration" before any rule could look at them,
while `videoDetails.lengthSeconds` for those same ids was already being fetched
and thrown away. That is now the fallback.

It forced the rules into two stages, because both the recovered duration and the
watch-page proof arrive only after enrichment. `ScrobbleRules.prefilter` keeps
that from meaning one fetch per finalize: 165 of the 198 "no duration"
finalizations had under three seconds of play time — a browser swapping a
placeholder title for the real one as a page loads — and none of them can clear
any threshold at any recoverable length.

### Silence was the actual bug behind "where are my entries?"

Every skip reason was computed and sent only to the event log. From outside,
"watched 20 shorts, got 6 entries" was indistinguishable from a broken app: no
artifact anywhere in the UI said the other 14 were seen, let alone why. The
**Not logged** tab surfaces the reason string that already existed. A gap you
can explain is a policy; a silent one reads as a bug every time.

Filtered at three seconds of play time for the reason above — otherwise the 165
page-load transitions bury the 33 a person might wonder about.

### Titles were going on-chain as tag runs

On-chain verbatim from this session:

```
katter — #guitar #dubstep #djdubstep #fnaf #fivenightsatfreddy
Rony González — #plena#panama 🇵🇦
```

The cosmetic cost is obvious. The real one is that the tag run is *part of the
title*, so the same clip reposted with different tags dedups as a different
listen and lands twice on a chain that can't be edited. Trailing runs are now
stripped (never interior ones — `Song #2 of the series` is doing work).

Where the tags are all there is, the classifier refuses the `song` kind
outright. `katter`'s clip was `song` on the strength of `category=Music` alone;
the category was arguably right about the content and still produced a permanent
playlist entry naming no track.

### The kind was computed and then never consulted

The clearest bug in the run, and one this session wasn't looking for:

```json
"kind":"video","artist":"Fall 2: Deadpoint (2026) Official Trailer 2",
"title":"Harriet Slater, Arsema Thomas"
```

A film name in the artist field and a cast list in the title. `category=Film &
Animation` had already resolved, `kind` was already `video`, and the channel
(`Lionsgate Movies`) was sitting in the notification — but `creditsOf` ran
`TitleParser.parse` unconditionally, so the `Artist - Track` split happened
anyway.

`Artist - Track` splitting is a **music** operation: it asserts the text left of
a dash names a performer. For a video the channel is the artist and the whole
title is the title. The reversed `Iran threatens to attack UK bases… - Risking
wider war | BBC News` is the same failure — `Track - Artist` ordering is common
in Spanish-language uploads, and MusicBrainz can only arbitrate it for real
recordings, never for a news clip.

MusicBrainz is still asked about the *music* reading of every track regardless of
kind. That is how a video-looking title is discovered to be a real recording, so
the question has to be asked before the kind is known — see
`ScrobbleBuilder.creditsForKind`.

### `FactsCache` was dropping fields the classifier reads

Found while adding `lengthSeconds` to it: `autoGenerated` was parsed from the
page and then never written to the cache, so a **cache hit** downgraded a
distributor-fed Art Track to whatever the title heuristics made of it. The
session took 22 cache hits. Both fields now round-trip, and the class doc states
the invariant.

### Still to verify — ads in the shorts feed

The floor's ad-guard rationale was checked only against the watch path. YouTube
inserts ad items into the Shorts *feed* as their own entries, and this session
hit none, so the log cannot say whether an ad short publishes a media session at
all. See TESTING.md — the next run is on Chrome specifically so ads play.

If an ad short both publishes a session and resolves a real watch page, both
guards fail and a third signal is needed. `Settings.shortClips` is the kill
switch in the meantime.

## 9m. v0.8.1 — the ad guard was wrong, and speed was never counted

The Chrome run §6b asked for, on 2026-07-29 evening: ~50 shorts plus regular
videos, Chrome first so ads would actually play, then Brave, plus a playback-speed
test that hadn't been tried before.

**The shorts floor itself worked.** 46 of 77 unique shorts scrobbled, against 20
of 64 the previous run. Zero 30 s-floor rejections on the shorts path — the one
time that message fired it was correct, for a short whose page hadn't resolved.
40 of 40 broadcasts carried a `url`. All 40 were `kind: video`, with no false
songs; weak evidence for the classifier, though, since the run contained almost
no music.

### The ad guard failed, and the premise was the problem

`Video ad upload channel — Blurry: Formula única` reached the chain:

```
17:09:37  [url] com.android.chrome → video=CYgQQqvwwsY ("m.youtube.com/shorts/CYgQQqvwwsY")
17:09:40  [enrich] CYgQQqvwwsY → category=People & Blogs originalArtist=—
17:09:58  [finalize] Blurry: Formula única — played 19s of 18s
17:09:58  [engine] broadcasting: {…"duration":"0:18","percent_played":100…}
```

Both halves of the v0.8.0 gate were satisfied. **YouTube serves shorts ads at
genuine `/shorts/` URLs backed by genuine, resolvable watch pages**, so
"resolved on its watch page" proved nothing. Note the 18 s duration: that ad
would have been rejected by the old 30 s floor, so the leak was created by the
feature.

Four ads appeared. Three were rejected by luck rather than design — 6 s and 5 s
fell under the 10 s hard floor, and a 42 s one was scrolled past at 4 s played.

Fetching the ad's watch page next to a real short from the same feed found what
actually separates them:

| field | ad `CYgQQqvwwsY` | real short `cq2xXbWGHu8` |
| --- | --- | --- |
| `microformat…isUnlisted` | **true** | false |
| `videoDetails.isCrawlable` | **false** | true |
| `videoDetails.viewCount` | **1** | 96229 |

`isUnlisted` sits in the same `playerMicroformatRenderer` the parser already
reads `category` from — byte offset 561095 against 561152 on the live page — so
it costs nothing to read. An ad creative is unlisted by construction; a short
reached by scrolling the feed is public by construction.

Rejected: matching the channel name. It was literally `Video ad upload channel`
(and `Video ad upload channel for 469-265-4554`) on all four ads, which is
tempting and worked — but it is a string YouTube can rename or localize, where
`isUnlisted` is structural. `isCrawlable` is parsed as corroboration and logged,
but not used as a gate: a legitimately unindexed upload happens, and being
uncrawlable says nothing about being an advertisement.

**The flag is tri-state on purpose.** Absent reads as "not proven public", not
as `false`. Reading absence as public is exactly the direction that let the ad
through, so if YouTube renames the field the floor closes instead of silently
re-opening the leak. D8's rule — breakage must be distinguishable from absence —
applies with teeth here, so the three refusals are separately worded: page
didn't resolve, page says unlisted, page never said.

### Playback speed was never counted

The untried test that found a real bug. `playedMs` accumulated wall-clock time in
`STATE_PLAYING` and ignored `speed`, which every `[playback]` line already
carried:

```
17:31:44  state=PLAYING pos=59873ms speed=1.25 played=50418ms
17:31:44  [finalize] It's Always Sunny… Season 18 Trailer (HD) — played 50s of 76s
17:31:46  [engine] broadcasting: {…"percent_played":67…}
```

Position had reached 59.9 s of a 76 s video — 79% of the content consumed — and
it went on-chain as 67%. The threshold compares against `duration`, so the
accumulator has to be in the same units: content, not elapsed seconds.

Under-reporting is the mild case. **At 2× a fully-watched video reads 50% and
never scrobbles at all.** The session's 2× burst lasted only ~2 seconds so
nothing was lost, but the arithmetic is unambiguous.

Fixed by scaling each play window by the rate in effect during it — read from
`state` *before* the callback assigns the new one, since the window that just
ended was played at the old rate. Two rules worth stating because they are
failure directions, not details:

- A non-positive or missing `speed` falls back to 1.0 rather than counting zero.
  `playingSince` already decides whether a window counts; a `speed=0.0` sample
  landing mid-window would otherwise erase play time that really happened. The
  session logged 36 such samples.
- A non-finite reading falls back to 1.0 rather than to the ceiling, while a
  merely absurd one (500×) clamps to 4×. Clamping NaN to the maximum would
  quadruple a track's play time on the strength of a nonsense sample.

The finalize line now names the rate when it wasn't 1× — `(up to 1.25× speed)` —
so a sped-up listen reads as intentional rather than as a mis-measurement.

### Also fixed

`FactsCache` gained `isUnlisted`/`isCrawlable`, both nullable, so a cache entry
written before this version fails the gate rather than inheriting a silent
"public" and gets the 30 s floor until its page is fetched again.

### Left alone, deliberately

- **Two hashtag-only titles went on-chain** — `#fyp #viral #parati` and
  `#viral #fyp #parati`, same channel. That is the documented fallback working
  (never broadcast an empty title) and both are `video` rather than `song`. It
  does demonstrate the near-duplicate cost: one clip, two entries, differing only
  in tag order.
- **`3 de marzo de 2026` → `3 de marzo de`.** The trailing-year strip ate the
  year from a title that *is* a date; the "≥2 words remain" guard passed because
  four did. Cosmetic, and tightening it risks the `1979`/`1999` case the guard
  exists for.
- **`Paramount+` → `Paramount`**, from `TRAILING_PUNCT`. Pre-existing, cosmetic.

## 9n. v0.8.2 — five things adapted from the desktop extension

The upstream extension was read side by side with this app to find YouTube
handling worth borrowing. Five things came across, one bug was found in the
process, and two candidates were deliberately declined.

### The bug that fell out of the review — a wrong `url` on-chain

Found while testing the extension's YouTube Music endpoint against ids from the
field logs. From log (4):

```
14:22:33  [url] → video=GQwj_FRntp8  (playlist index=13)
14:22:41  [enrich] fetch failed or timed out for GQwj_FRntp8
14:22:49  broadcasting … "Con Calma" / "Daddy Yankee" / url=…GQwj_FRntp8   ← correct
14:27:39  TITLE = "Para Mis Soldados - Danger Man"
14:27:39  [identity] latched video GQwj_FRntp8 for this track              ← stale
14:27:46  [url] → video=RW7Hn24Agyc                                       ← bar 7s late
14:28:06  broadcasting … "Danger Man" / "Para Mis Soldados" / url=…GQwj_FRntp8
```

Both ids verified against their pages: `GQwj_FRntp8` is **Con Calma, 193 s**;
`RW7Hn24Agyc` is **Para Mis Soldados - Danger Man, 227 s**. So that entry links
to a Daddy Yankee track.

The latch corroboration (`SessionProbe.identityOf`) was written for exactly this
and **failed open**: `if (pageTitle != null && …)`, and the page fetch for that
id had already timed out, so there was no title to compare.

Fixed by adding a second, independent check on data v0.8.0 already fetches — the
session said 226 s, the page says 193 s. Tolerance is absolute *and*
proportional, because `lengthSeconds` and the session's `DURATION` differ by a
second of rounding routinely (a live page reported 39 against 39141 ms) while a
percentage alone would let a 30-second gap pass on a two-hour video.

### 1. The YouTube Music catalogue lookup

The extension's authoritative music signal (`getTrackInfoFromYoutubeMusic`), and
the single most valuable thing in the comparison. One POST to
`music.youtube.com/youtubei/v1/player` with a `WEB_REMIX` client identity, keyed
by **video id** — which is what makes it answer where a string-matched MusicBrainz
lookup can't. Verified against ids from the logs:

| video | `musicVideoType` | author / title |
| --- | --- | --- |
| `GQwj_FRntp8` | `MUSIC_VIDEO_TYPE_ATV` | Daddy Yankee / Con Calma |
| `mgoLdQZl_pQ` | `MUSIC_VIDEO_TYPE_ATV` | KAROL G & Nicki Minaj / Tusa |
| `l69Cq38GgZ4` | `MUSIC_VIDEO_TYPE_OMV` | Elena Verrier / Metallica - Blackened (guitar cover) |
| `cq2xXbWGHu8` | *absent* | — (a football short) |
| `CYgQQqvwwsY` | *absent* | — (the shorts-feed ad) |

Both sessions are full of `[musicbrainz] no match` on Spanish-language and
small-channel uploads. The catalogue recognises them immediately.

Read **positive-only**, copying the extension's reasoning verbatim because it is
right: indie, live and personal-channel uploads are absent from the catalogue, so
absence is not evidence against music. It sits below the format blocklist so a
reaction video that picks up a user-generated audio match still stays `video`.

**Credits are taken from `ATV` only.** The extension also trusts `OMV`
author/title; for `l69Cq38GgZ4` that yields author `Elena Verrier` (the channel)
and title `Metallica - Blackened (guitar cover)` — strictly worse than what
`TitleParser` already produces. An Art Track is distributor metadata; an OMV is a
filmed video whose "author" is just the uploader.

It also turned out to carry `lengthSeconds`, `category` and `unlisted` — so at
10 KB and ~0.3 s against ~615 KB for the watch page, it is now the fallback when
the page fetch fails. That closes the hole the wrong-`url` bug came through:
enrichment failing no longer means no evidence at all.

Field names differ between the two sources and must not be "tidied" together:
the music client returns `microformat.microformatDataRenderer` with `unlisted`,
the watch page returns `microformat.playerMicroformatRenderer` with `isUnlisted`.

### 2. `album`, which was never populated

`album` has been a field on `HiveScrobblePayload` since Phase 0 and nothing ever
set it — every entry went on-chain without one. Auto-generated descriptions have
a fixed shape the extension has been reading all along
(`parseYtVideoDescription`):

```
Provided to YouTube by <distributor>

<Song> · <Artist> · <more artists>

<Album>

℗ <year> <label>
```

Restricted to descriptions carrying the `Provided to YouTube by` header, because
on a hand-written description the line after a credit is prose. The `℗`/`©` line
is explicitly excluded — it sits where the album would be when a track has none.
Songs only: release metadata on a trailer is noise.

### 3. Upper duration gates — a real gap

The extension requires a positive music signal above 8 and 15 minutes
(`MEDIUM_FORM_THRESHOLD_SEC`, `LONG_FORM_THRESHOLD_SEC`). RustedWax had a *lower*
bound (90 s) and no upper one, so a 45-minute podcast titled `Host - Guest Name`
with no category reached the weak "title names an artist" rule and became a
`song`. Both thresholds adopted rather than guessed.

Genuine long-form music always carries a real signal — a Music category,
`full album` / `live at` / `mix` vocabulary, an artist channel, or now the
catalogue — and all of those are checked first.

### 4. Four title shapes from `processYtVideoTitle`

- `Artist "Track"` — quotes name the track outright, which beats guessing at a
  separator.
- `Track (by Artist)` / `(performed by …)` — the one common shape where the
  artist comes second.
- Track-number prefixes: `03. `, `A1. `, `12) `. Bounded so `1999` and `7 Rings`
  survive, since those have no separator after the digits.
- A leading `[tag]` as noise.

**ASCII brackets only for that last one.** A first attempt stripped `【…】` too and
broke `【Artist】Song - Live`, which became artist `Song` — this project already
decided the opposite for CJK brackets, because that is where the reported guitar
cover keeps its artist. The existing `LEADING_BRACKET_ARTIST` comment says so;
the test suite caught the contradiction.

### 5. Diagnostics

`yt music` and `listed` rows on the Now card, and `ytmusic=` on the one
`[enrich]` line per video, so the two new signals are visible before anything
reaches the chain rather than only afterwards in a log.

### Declined

- **Their separator table.** It includes bare `:`, `|`, `/` and `~` with no
  surrounding spaces, which is looser than ours and would split titles we
  correctly leave whole.
- **Chapter-based per-track scrobbling** (albums, live sets, DJ mixes). Not
  portable: it reads the chapter title from the DOM, and Chromium's media session
  publishes the *video* title, not the current chapter. Rebuilding it from the
  page's chapter markers plus `positionMs` is a feature, not an adaptation.
  If it is ever built, take their two guards verbatim — both are earned from bug
  reports: `SONG_SECTION_NAMES` (never scrobble a chapter called "Chorus" as a
  track) and `chaptersLookLikeTracks()` (chapters are tracks only when the title
  says album/live/mix, or the video is ≥15 min *and* carries a music signal).

### Their id freshness is not portable either

The extension reads the video id from the page it runs inside, so it cannot go
stale — which is why it needs no latch, no title guard and no duration
cross-check. That advantage comes from being in the page, and nothing adapts it.

## 9o. v0.8.3 — the ad guard is half a guard, and the bar can go quiet

The 2026-07-29 late run tested every open item. 20 broadcasts, 20 confirmed
transactions, and the profile matches the log one-for-one. Verdicts:

| Item | Result |
| --- | --- |
| Speed scaling | Works. `+57 — played 191s of 298s (up to 2.0× speed)` = 64%; the old arithmetic gave ~32% and no entry |
| Loop bound | Works, 3 fires — 233% of 10s, 227% of 25s, 318% of 17s |
| `album` | 6 of 6 songs, first time the field has ever been populated |
| ATV credits | `FloyyMenor & Cris Mj`, `KAROL G, Feid, & DFZM`, `Peso Pluma & Anitta` — canonical multi-artist |
| False songs | **Zero.** 6 songs, all real music |
| Enrichment | 27 resolved, 20 cache hits, **0 failures** (was ~12%) |
| Latch corroboration | Caught 6 stale ids — 5 of them watch-path ads |
| Ad guard | **One caught, one leaked** |
| Long-form gate | Untested — longest video was 298 s |
| Duration cross-check | Never fired; the title was always available |

### At v0.8.3 there were two kinds of Shorts ad, and only one was detectable

Caught, as designed:

```
21:29:18  [engine] skipped: track is 15s, under the 30s minimum
          (a short, but the video is unlisted — almost certainly a feed ad)
```

Leaked — `PONDS CAM — Consigue tu rutina ahora. #Agemiracle`, 10 s, tx
`e2610cac`, with `listed=yes`. Fetching all three pages side by side:

| | Oral B (caught) | POND'S (leaked) | Susy Mouriz (organic) |
| --- | --- | --- | --- |
| `unlisted` | **true** | false | false |
| `isCrawlable` | **false** | true | true |
| `noindex` | **true** | false | false |
| `viewCount` | 3,539,246 | 1,138,564 | 20,886,671 |

Oral B is a dedicated unlisted creative. POND'S is a **fully public video on a
brand channel that YouTube promoted**, and it is identical to organic content on
every field in the response — `adBreakHeartbeatParams`, `paid`,
`playabilityStatus`, `familySafe` all checked. Identical because it *is* ordinary
content: the same video could have arrived in the feed organically.

So `isUnlisted` closes one class and **cannot** close the other. No rule
available to a media-session observer can. v0.8.9 later added a separate,
explicit visible-page-label signal through the optional accessibility service;
§9s supersedes this section's then-current capability without changing the
reason channel-name heuristics were rejected.

Rejected, for the record:

- **Raising the shorts floor.** POND'S was exactly 10 s, but 10–11 s was the
  largest recovered bucket in the 0.8.0 run and the next ad could be 20 s. A
  large certain cost against a small uncertain gain.
- **Channel-name matching.** `PONDS CAM`, `Oral B Latam`, `inDrive`, `Blurry` —
  nothing generalizes, and it risks blocking real channels.

What ships instead is [MutedVideos]: the user's own judgement, applied once, keyed
by video id and never expiring. It cannot unwrite what is on-chain — nothing can —
but a promoted video that comes round again won't count twice. Bound on the
manual path too, for the same reason the manual button was made to share the
dedup ledger in v0.5.4: otherwise the button is a way around it.

### The address bar can go quiet, and nothing said so

The other finding, and the more instructive one. The watcher reported
`connected` at 20:45:47, read the collapsed omnibox once —
`host=m.youtube.com video=—` — and then said nothing for **thirteen minutes**:

```
20:46:21  [url] com.android.chrome → host=m.youtube.com video=—  ("m.youtube.com")
20:59:40  [url] com.android.chrome → host=m.youtube.com video=uQRWIADigL0  ("m.youtube.com/shorts/uQRWIADigL0")
```

The visible cost was five shorts scrobbled with no `url`. The invisible cost was
larger: **four more were lost outright** — 15 s, 25 s, 21 s, 15 s — because with
no id there is no `/shorts/` proof, so the short-clip floor couldn't apply and
they were held to the 30-second minimum. Everything above 30 s got through
without a link; everything below vanished.

The mechanism worked correctly throughout. The search fallback ran nine times and
correctly declined every one (`no confident match … leaving url unset`), because
guessing a url is worse than omitting one. Nothing here is a regression — no
`unlatched` line fired before 21:01, so the new duration check took nothing away.

The defect is that **the app knew and didn't say.** It knew the watcher was
enabled and that nine consecutive finalizes had produced no video id. That is
the same failure the Not-logged tab was built for, one layer down: a silent
degradation is indistinguishable from working.

Now counted per finalize — `ScrobbleEngine.tracksWithoutVideoId` — and surfaced
after three consecutive misses, in the log and as a banner naming both costs.
Counted per finalize rather than on a timer on purpose: a long watch-page video
legitimately produces one id and then silence for an hour, and that must not
warn.

### Still untested

- The long-form gate. Nothing above 298 s has been played yet.
- The duration cross-check, since enrichment hasn't failed since it landed.
- `MUSIC_VIDEO_TYPE_UGC` promoting a short to `song`. It occurred once
  (`Farruko - Quiéreme // Estado para WhatsApp`, People & Blogs) but the loop
  bound rejected it at 233% before the kind mattered.

### Two profile-rendering notes, not bugs

scrobble.life shows only the primary artist (`Yandel`, not `Yandel & Feid`) and
does not appear to surface `album` at all. Both fields go on-chain in full.

## 9p. v0.8.4 — a frozen node, and seven scrobbles that never existed

The worst failure so far, and the one that had been invisible longest: the app
reported seven scrobbles it had not made.

### What happened

`api.openhive.network` — first in `DEFAULT_NODES` — froze at block **108575690,
2026-07-30T03:55:12** and stayed there. Three probes six seconds apart:

```
api.openhive.network   108575690  03:55:12   ← identical every time
api.hive.blog          108577226  05:12:15
                       108577228  05:12:21
                       108577230  05:12:27
```

77 minutes behind and not advancing, while answering every RPC normally.

Five broadcasts went into a pending block that would never be produced. The node
returned no error, so the app reported five successes with transaction ids. Then
it refused the next two:

```
23:54:42  rejected: Account ${'$'}{a} already submitted ${'$'}{n} custom json operation(s) this block.
23:57:36  rejected: Account ${'$'}{a} already submitted ${'$'}{n} custom json operation(s) this block.
```

That error is the tell, and initially it looked impossible: a per-block limit
cannot fire twice for operations three minutes apart. It can when **the block
never advances** — the five stuck operations were forever "this block". The
absurd error was the stall announcing itself.

Verified three ways that nothing landed: the account's 110 `hive_scrobble_ai`
operations end at `02:31:39` (the *previous* session); `find_transaction` returns
`unknown` for both reported ids; and the only operation on the account in that
window was a `notify`/`setLastRead` from a web frontend. RC was at 100%, so that
was never it.

### Four defects, and the one that matters most

**1. Success was assumed, never confirmed.** `broadcast()` returned `Success`
whenever the response had no `error` field, and the transaction id shown in
History was computed locally (`sha256(serialized tx)[0..20]`) because
`broadcast_transaction` returns an empty result. So the app *predicted* an id and
displayed it as fact. `transaction_status_api.find_transaction` now confirms
before anything is called a success — and it is asked of **every** node, so the
question goes to someone other than whoever accepted it.

The confirmation semantics took some care. `within_mempool` on the final attempt
**counts as confirmed**, deliberately. The real question is "does any node other
than the accepting one know this transaction exists" — which is exactly what the
frozen node failed, since healthy nodes answered `unknown`. Treating mempool as
failure would queue the operation, and the retry rebuilds it with a fresh
expiration and therefore a new id: if the original *did* land, that's a permanent
duplicate. A missed entry can be earned again by watching. On ambiguity, don't
retry.

**2. No node freshness check.** Now enforced in two places, and the second is
easy to miss: at broadcast time, and in `getDynamicGlobalProperties`, because
that call is what the transaction is *built* from. A stalled node's `time` yields
`expiration = time + 60s` that can already be in the past when a healthy node
sees it — the frozen node would have poisoned the transaction even if something
else broadcast it. Freshness is judged against the **device** clock: asking a
stalled node whether it is stalled is circular.

**3. `Rejected` was terminal for everything.** A per-block rate limit is about
capacity, not correctness. New `BroadcastResult.Deferred` covers refusals expected
to pass later (rate limits, RC exhaustion) plus accepted-but-never-included, and
the caller queues those instead of discarding them. `Rejected` keeps its old
meaning — a bad signature never becomes good, and retrying it would loop.

**4. `errorMessage` read the wrong field.** It preferred
`data.stack[0].format`, the *uninterpolated template*, over `error.message`, the
interpolated text. That is why the log said `${'$'}{a}` and `${'$'}{n}` instead of
`skiptvads` and `5`. With the numbers filled in, the jammed block would have been
obvious hours earlier. Values from `stack[0].data` are now appended when the
message still carries placeholders.

### Node list

`hive-api.arcange.eu` removed — it answered a broadcast with an empty body and
its DGP call with nothing at all. `api.openhive.network` moved off the front but
kept, because a stall is transient and the freshness check now handles it.
Replacements were probed for a current head block *and* for
`transaction_status_api` support, which confirmation depends on: `api.hive.blog`,
`api.deathwing.me`, `api.syncad.com`. `anyx.io` was considered and dropped —
unreachable on both counts.

### Cost of the incident

Seven listens, unrecoverable. They were never signed onto anything, so there is
nothing to rebroadcast; re-watching is the only way back. The user's own reading
of the situation was right on every point they raised — re-scrobbling the same
video is allowed, the dedup ledger is hour-bucketed and correctly permitted both
music videos, and nothing in RustedWax excluded them.

## 9q. v0.8.5 — a video watched to 80% that produced nothing

The complaint that had recurred for three sessions — "certain videos that are
supposed to be logged are not" — finally showed up in a log short enough to read
end to end.

### Chrome recreates its media session mid-video

`LUNA`, 196 seconds long, on the watch page with a playlist:

```
11:44:02  [session] + com.android.chrome          ← LUNA starts
11:45:05  [finalize] LUNA — played 47s of 196s    → 24%, skipped
11:45:26  [session] + com.android.chrome          ← recreated
11:45:50  [finalize] LUNA — played 24s of 196s    → 12%, skipped
11:46:08  [session] + com.android.chrome          ← recreated
11:47:33  [finalize] LUNA — played 85s of 196s    → 43%, skipped
```

47 + 24 + 85 = **156 s of 196 = 80% watched**, and no entry was written.

The playback positions prove the video never stopped:

```
11:45:26  pos=47039ms  played=36ms   ← position 47s, our counter back to zero
11:46:08  pos=70769ms  played=33ms   ← position 71s = 47+24, zero again
```

`SessionProbe` keys each `Watch` by `controller.sessionToken`. A recreated
session is a new token, so `playedMs` restarted and each fragment was scored
against the 60% threshold on its own. Chrome tears the session down around ad
breaks and playlist transitions — an ad (`Abre la puerta … Mastercard`, 15 s)
appears in this very log between the fragments.

`TQG` in the same session survived only by luck: its first fragment happened to
run 166 s of 197 s = 84%, clearing the bar before the teardown.

### The fix

[TrackProgressCarry] holds a vanished track's progress for 60 seconds, keyed by
package + track, and the replacement session claims it. Three rules are load
bearing and all are pinned by tests:

- **Consumed on read.** Two sessions inheriting the same play time would double
  count it onto a chain that can't be edited.
- **Expires.** Otherwise it could attach itself to a genuine separate viewing of
  the same video later on.
- **Not carried across a user Stop.** D2 discards the in-flight track on purpose,
  and carrying it would smuggle that time into the next session.

`trackStartedAtEpochSec` travels with the play time, so the on-chain `timestamp`
names when the listen began rather than when Chrome rebuilt its session — and the
dedup key stays stable across the restart, which is what stops a fragment that
already scrobbled from scrobbling again.

It lives in its own object rather than inside `Watch` because `Watch` owns a
`MediaController` and can't be unit-tested; the rules worth pinning are pure.

Known cosmetic consequence: the early fragments still land in **Not logged** as
"below 60%" before the track later scrobbles. Honest, momentarily confusing, and
not worth more state to hide.

### Also found in the same review — `NxNN` episode numbering (v0.8.6)

`EPISODE_STRUCTURAL` matched `Season 6 Ep 19` and `S06E19` but not `3x1` or
`11x24`, which is the notation TV clip channels actually use. Two Walking Dead
clips reached the chain as `kind: song` on 2026-07-30 — one split as
`artist: "The Walking Dead 11x24 Negan and Maggie talk…"` / `title: "Rest In
Peace"`, the same `Artist - Track`-on-a-video failure §9l fixed elsewhere.

It didn't go into the existing regex, because `NxNN` needs exclusions a single
pattern can't express:

- **Resolutions can't reach it.** `1920x1080` and `640x480` have too many digits
  on the left for `\b\d{1,2}x\d{1,3}\b` to find a word boundary, so they never
  match in the first place.
- **Aspect ratios have exactly the same shape** as a season and an episode —
  `16x9`, `4x3` — so they are named in `NOT_EPISODE_NUMBERS`.

Genuinely ambiguous titles (`4x4` is a real song) resolve toward `video`, which is
the direction the rest of the class already leans: a misfiled entry costs one
listing, a TV clip in the music index is permanent.

### Note on log 8

Produced by 0.8.3, not 0.8.4 — the log says "Broadcast accepted by", the
pre-confirmation wording. So the frozen-node work was not exercised. Both
broadcasts in it did land on `skiptvads.vidz` (verified on-chain), and
`api.openhive.network` had recovered by then.

## 9r. v0.8.7 — contract reconciliation

The v0.8.6 review found that incident-driven comments and tests had started
acting as product policy without one canonical contract. Most notably, a
high-progress verified Short was deliberately rejected even though the
video-kind cap already guaranteed one transaction; session fragments were
finalized before their progress was carried; and three different Hive evidence
states were all displayed as `Confirmed on-chain`.

[BEHAVIOR_CONTRACT.md](BEHAVIOR_CONTRACT.md) preserves the exact v0.8.6 as-built
behavior and defines the invariants applied in v0.8.7. The implementation:

- keeps the first qualifying Short viewing and logs probable auto-looping;
- defers fragment finalization through a tokenized one-minute continuation
  window;
- makes Stop clear URL/playlist evidence and guard removal callbacks;
- polls every independent healthy Hive node and distinguishes block, mempool,
  and accepted-without-confirmation;
- binds queued work to its account and preserves percent/video metadata;
- applies configured rules to the Now verdict and manual session button; and
- stores literal watch-page provenance instead of inferring it from a duration
  that YouTube Music may have supplied.

Earlier Phase 4 sections remain the chronology of how those policies arose; they
do not override the current contract.

## 9s. v0.8.9 — explicit ad evidence and frozen delayed identity

Log 11 separated two app-side defects from the scrobble.life indexing problem.
The chain transactions missing from the profile were reproduced with the
desktop extension and are deferred to the site's developer. They do not justify
rebroadcasting or changing Hive confirmation semantics here.

### Public promotions need explicit page evidence

The earlier ad rule knew only what the watch page said. That catches dedicated
unlisted creatives but cannot distinguish a public video inserted as an ad from
the same public video reached organically. The desktop Hive Scrobbler connector
has an additional signal because its YouTube content script can inspect the
page: it vetoes while YouTube marks the player `.ad-showing`.

Android does not expose that DOM state, but the existing optional accessibility
service can receive visible YouTube controls. The mobile adaptation is:

1. inspect visible accessibility nodes only while the evidence URL identifies a
   concrete YouTube `/shorts/` video;
2. accept only exact localized ad controls (`Sponsored`, `Ad`, `Skip ad`, and
   maintained Spanish/Portuguese equivalents), never brand/channel/title
   guesses;
3. bind the signal to that exact 11-character video id with a short freshness
   window;
4. carry it into the matching active watch and make it an unconditional
   prefilter/final-decision veto, including the manual session button; and
5. retain unlisted detection and the permanent per-video mute as independent
   fallbacks.

This path is deliberately fail-closed only when explicit evidence exists. If
YouTube or the browser does not expose a visible label through Android
accessibility, a public promotion remains indistinguishable from organic
playback. Expanding the detector to words inside titles would trade that known
gap for false permanent exclusions.

### The late-address-bar identity race

The first item in log 11 was Short `grNk0DpiaEE`. By the time its delayed
finalization ran, the foreground URL had advanced through later items and named
`ysY13cbxJR4`. Corroboration correctly rejected that live id, but the return
expression `latchedVideo ?: live` selected it again when no older latch existed.
That contradicted both the documented no-wrong-URL invariant and the purpose of
corroboration.

The correction has two layers:

- identity selection is a pure operation whose rejected live candidate is never
  eligible as a fallback; and
- when a session disappears, its last stable identity is frozen together with
  played time, start timestamp, dedup state, loop state, and explicit ad
  evidence. A matching replacement consumes that bundle. Continuation expiry
  finalizes the same frozen identity without reading the current address bar.

Regression tests pin the exact rejected-live-id case, carried confirmed
identity, exact ad-label matching, video-id binding, ad veto ordering, and
identity/ad survival through progress carry. Physical-device testing still has
to establish which label YouTube exposes in each supported browser build.

## 9t. v0.8.10 — mandatory hyperlinks and the Shorts search shape

Log 12 put six consecutive Video entries on-chain without `url`. scrobble.life
therefore rendered their titles as plain text rather than links. The immediate
cause was not one race but a permissive chain of three states:

1. Chrome accessibility exposed only `m.youtube.com`, so no exact id was
   latched.
2. The fallback downloaded YouTube search successfully but did not understand
   its current Shorts representation.
3. `ScrobbleEngine` logged `broadcasting WITHOUT url` and continued through
   dedup, signing, and broadcast.

### The parser's “zero results” was false

Current Shorts results use `shortsLockupViewModel`, not `videoRenderer`. The
eleven-character id is nested under `reelWatchEndpoint.videoId`; the title is
`overlayMetadata.primaryText.content`; and the card exposes no channel or
duration. The old generic parser required a node that directly carried both
`videoId` and `title`, so a result page with dozens of Short ids could produce
an empty candidate list.

The resolver now reads legacy video renderers, modern ordinary-video lockups,
and modern Shorts lockups. It searches raw title+channel and bounded
noise-stripped variants, retries a failed fetch once, and treats hashtag tails,
emoji, punctuation, quote styles, and title diacritics as presentation noise.
That relaxation never becomes acceptance by itself: up to eight highest-ranked
incomplete Short candidates are fetched by id and each accepted candidate must
return the same id plus a matching title, channel, and duration. Multiple
matches are logged as ambiguous and refused.

### Hyperlink presence is now a product invariant

Recovery can improve coverage but cannot make withheld evidence appear. The
irreversible guarantee is enforced instead at three boundaries:

- `ScrobbleEngine` stops an unresolved finalize and writes the reason to
  **Not logged**;
- `ScrobbleBuilder` cannot construct a YouTube listen from a missing or malformed
  id; and
- `HiveBroadcaster` validates serialized payloads, so a manual path or a
  URL-less retry-queue entry produced by an older build is also refused.

The quiet-address-bar banner now says unresolved tracks stayed off-chain rather
than warning that entries will have no links. This deliberately chooses a
visible missed scrobble over an immutable unlinked or incorrectly linked one.

## 9u. Log 14 — v0.8.10 transport passed; four pre-broadcast losses remained

The next field run covered the evening of 2026-07-31 through midday
2026-08-01. Its evidence set was deliberately broader than the app log:

- `debug/rustedwax-log (14).txt` supplied session, rule, payload and Hive
  outcomes;
- the signed-in YouTube History page supplied an independent list of watched
  ids where YouTube retained them; and
- the live Music and Videos views for `skiptvads.vidz` supplied section,
  presence and hyperlink targets.

### What v0.8.10 proved

The hyperlink patch held at every irreversible boundary. There were 109
broadcast payloads and 109 block confirmations. All 109 were present on the
profile, all linked to the same id that the payload carried, all 54 song
payloads were under Music, and all 55 video payloads were under Videos. No
advertisement-like payload reached either section. The long Davoo Xeneize test
also behaved as designed: 27:13 duration, 86% played, `kind: video`, correct
link.

The run contained 66 engine skips: 34 below threshold, 15 under the hard
10-second floor, 12 explicit visible-ad vetoes, 3 unresolved-id vetoes and 2
dedup vetoes. The short ad and bumper families seen in the log stayed off-chain.
Thirty-three loop diagnostics capped continuous playback; no Short/video loop
created a second transaction.

This clean transport result matters because it separates the remaining losses
from the earlier scrobble.life indexing incident. The four items below never
became payloads, so waiting for another node or profile refresh could not make
them appear.

### Four qualifying misses, with four different evidence paths

1. **Me Porto Bonito (`saGYMhApaH8`)** finalized at 193/191 seconds. Its own
   watch-page title and the MediaSession's localized title were similar but not
   equal, so the id had been rejected. The finalize coroutine then used the
   following La Bebe id/facts (`3mchJ-EW9rM`) and emitted La Bebe with the ended
   track's timestamp/progress.
2. **Cardi B - Trump (`5YrJf3CpHNk`)** was one continuous viewing. Chromium
   changed `METADATA_KEY_DURATION` from `227125` to `227124` ms while keeping
   title, artist and album unchanged. Because duration is an exact component of
   `trackKeyOf`, the callback finalized 48%, reset, then finalized 53%. The two
   sequential fragments cover the viewing but neither independently reached
   60%.
3. **The best cosplayer avengers (`IW524Zl2Pus`)** followed a six-second
   stadium ad. The URL changed to the organic Short and, two milliseconds later,
   the old UI still exposed `Sponsored`. The detector truthfully read both in
   one accessibility snapshot and bound the stale label to the new id. The
   organic Short later finalized at 23/22 seconds and was vetoed.
4. **YCB Frenzy - Crazy (`aZaxQG3ggng`)** finalized at 194/192 seconds after its
   watch-page title and MediaSession credit differed. Its finalize coroutine
   used the following Coming Home id/facts (`2QqyPy2itXw`) instead.

All four intended ids were visible in YouTube History and absent from both
profile sections. The two mixed-identity cases prove v0.8.9's probe fix was
necessary but not the whole boundary: the rejected-live fallback and carried
probe identity are frozen, yet later resolver/enrichment output can still
replace the ended track's metadata before payload construction.

### The ad rule was precise but not temporally coherent

“Id and label in the same snapshot” prevented package-only poisoning, but the
Shorts UI does not update atomically. During a swipe, the address bar can expose
the successor id before the old overlay disappears. The next rule therefore
needs a URL generation and a provisional state:

- a label first observed in the transition frame is not yet a veto;
- it becomes persistent only when the same id/label is observed again in that
  generation or agrees with an already-established session for that id;
- another URL, label disappearance, Stop or package reset discards it; and
- once accepted, it travels with the track instance across session churn rather
  than poisoning the public id for an arbitrary freshness window.

This intentionally accepts a possible miss on an extremely short ad if its UI
never stabilizes. The alternative demonstrated by log 14 is permanently
dropping the next organic item. Brand/title guesses remain prohibited.

### Exact duration is observation, not identity

The Cardi B split is the same class of mistake as treating a late notification
as a new track: an unstable observation was embedded in the identity key.
For v0.8.11, unchanged normalized title, artist and album plus either
missing-to-known duration or no more than 2,000 ms of duration drift is one
track. The newest valid duration may refine measurement, but progress,
timestamp, identity, loop and ad state remain. A material duration conflict or
different title/artist still ends the track.

### Four parser failures became literal fixtures

The profile preserved exactly what the app broadcast, which isolated these as
parser defects rather than scrobble.life presentation:

- `vG4h2KkwMDA`: a performance sentence before `| BET Awards '24` became the
  artist and only the event became the title; the fixture expects
  `Ice Spice` / `Think You The Sh*t (Fart)`;
- `VpXRPrwezQ8`: the dash in `(Official Video - No Skits)` became the split;
  the fixture expects `Sexyy Red` / `Get It Sexyy`;
- `z5WrgDzNIZ0`: the dash in `(WSHH Exclusive - Official Music Video)` became
  the split; the fixture expects `6IX9INE` / `Gotti`; and
- `oNg3M9IJJlY`: `TROLLZ - 6ix9ine & Nicki Minaj` remained backwards; the
  fixture expects `6ix9ine & Nicki Minaj` / `TROLLZ`.

v0.8.11 must scan only top-level separators, recognize quoted tracks and
performance shapes before generic separators, and use channel agreement to
choose orientation. Ambiguity falls back to channel plus the whole cleaned
title. It does not guess, and it does not alter video-kind behavior.

### v0.8.11 scope and exit

The patch order is deliberately narrow:

1. make the finalized evidence/resolver bundle immutable through payload
   construction and add the two successor-identity regressions;
2. add URL generations and provisional/re-observed ad evidence, pinned by the
   exact stadium-to-cosplayer ordering;
3. replace exact duration track-key equality with the bounded same-track
   predicate and pin the `227125 → 227124` case;
4. make song parsing structure- and channel-aware with the four field fixtures;
5. run the complete test/build/lint gates; then
6. repeat the physical-device log/History/Music/Videos/ad/loop/hyperlink
   reconciliation.

No code was changed while recording this incident and plan. Past on-chain
entries will not be rebroadcast or rewritten. The 60% policy, confirmation
semantics, prototype lookup route and the flashmob Music-vs-Videos product edge
are explicitly outside this patch. The canonical required behavior and full
acceptance matrix live in [BEHAVIOR_CONTRACT.md](BEHAVIOR_CONTRACT.md) and
[TESTING.md](TESTING.md).

## 9v. v0.8.11 — the log-14 correction is implemented

The four-part patch above is now present in the runtime and pinned by exact
automated fixtures:

1. finalized snapshots carry immutable URL/playlist resolver context, and
   asynchronous resolution returns structured id/title/channel/duration
   evidence that is corroborated against the ended metadata before enrichment
   may replace it;
2. address-bar observations receive per-package URL generations, while a label
   first seen in a transition generation remains provisional until the same
   instance is stable; disappearance, another URL, Stop, and package reset clear
   package-level evidence, and an accepted veto travels with the track;
3. normalized title, artist and album form semantic track identity, with
   missing-to-known duration and drift through 2,000 ms treated as refinement;
   progress, timestamp, identity, loop and ad evidence survive; and
4. song credits scan separators outside balanced parentheses, brackets and
   quotes, recognize quoted/performance titles first, and use channel agreement
   to orient generic pairs conservatively.

The regression suite includes both adjacent-id handoffs, the stadium-ad to
`IW524Zl2Pus` transition, `227125 → 227124`, and all four malformed credit ids.
This implementation does not alter the historical v0.8.10 counts in §9u and
does not repair or rebroadcast any entry already on-chain. The v0.8.11
physical-device reconciliation remained the release gate at build time and was
subsequently attempted and failed as recorded below.

## 9w. Log 16 — v0.8.11 safety held, but the device gate failed

`debug/rustedwax-log (16).txt` records the 2026-08-01 13:59:50–14:42:52
physical-device attempt. It includes the earlier log-15 export plus the
continued session. The generated artifact was `dist/rustedwax-0.8.11.apk`,
version code 31, SHA-256
`bdf81bcc560dea1c5a430d869193d702480f15f95c19f4d211abbb320ca4e296`;
its preceding source gate completed 290 tests with no failures and passed debug
assembly/lint.

The address-bar watcher was not frozen: Chrome advanced from URL generation 2
through 15. Eighteen tracks finalized, eleven produced skip decisions, and all
seven emitted payloads were block-confirmed. No ad-like or duplicate payload
was emitted. During likely screen-off churn, Chrome repeatedly recreated the
same MediaSession and v0.8.11 carried Fantasy Pool Party at 134, 172 and 177
seconds without duplicate finalization. The export ended before its final
continuation timer expired, so it has no engine outcome.

Finalized snapshot isolation also did exactly its irreversible job: Soy Peor,
Me Porto Bonito and DÁKITI were never completed with the following track's
facts. However, the active probe had already rejected each correct id because
its literal title predicate disagreed over `Video Oficial`/`Official Video`,
credit parentheses, or DÁKITI's additional album/display suffix. The final
guard therefore made three safe visible omissions. That is better than mixed
on-chain identities, but it fails the zero-qualifying-omission release gate.

The block-confirmed `F1_aOX0acbY` payload exposed a second defect. Its raw title
was `W Sound 05 "LA PLENA" - Beéle, Westcol, Ovy On The Drums`; the parser
broadcast artist `W Sound 05 "LA PLENA"` and title `Beéle, Westcol, Ovy On The
Drums`. The quoted work is the title and the trailing names are credits. The
four log-14 parser fixtures remain fixed, but their grammar did not cover this
new top-level shape.

The run was not independently reconciled against signed-in YouTube History and
both profile sections, so its seven block confirmations prove transport only.
The canonical counts, exact title pairs and unresolved end-of-log state are in
[TESTING.md §14](TESTING.md#14-v0811-physical-device-field-record-log-16).

## 9x. Implemented log-16 follow-up — generic rules, no runtime catalog

The correction remains inside the v0.8.11 checkpoint and is now implemented:

1. extract one pure, structure-aware title matcher for both the active latch
   and finalized candidate guard; normalize presentation only, require equal or
   conservatively contained whole-token structure (at least three tokens for
   unequal forms), and retain the independent duration contradiction;
2. add a conservative balanced `prefix "quoted work" - trailing credits`
   parser shape before the generic dash rule, gated by channel agreement and
   rejection of promo/event suffixes;
3. keep exact Soy Peor, Me Porto Bonito, DÁKITI and LA PLENA values only under
   unit-test fixtures, with adjacent-track, ad/organic and event-suffix negative
   controls; and
4. the 54 focused tests and full 295-test/build/lint gate passed and a corrected
   artifact was generated; the remaining step is to repeat the physical-device
   reconciliation and wait through the final
   60-second continuation window before export.

Production logic remains a fetch/log/filter pipeline. It must not contain a
song/video id map, title or artist catalog, brand inference, or per-fixture
branch. URL generations, explicit visible-ad policy, immutable finalized
snapshots, same-track duration refinement, canonical hyperlinks, thresholds,
kind immutability, manual/automatic parity, dedup/loop rules and the
no-historical-rebroadcast boundary remain unchanged. The canonical implemented
behavior is in [BEHAVIOR_CONTRACT.md](BEHAVIOR_CONTRACT.md#v0811-field-follow-up-contract-implemented-automated-gate-passed).

The corrected `dist/rustedwax-0.8.11.apk` remains version code 31 and has
SHA-256 `1b682f582dd17f287d69acd2b22313c227acffb13f13d266288c4e6df65639d5`.
Log 17 subsequently tested it; the separate result follows.

## 9y. Log 17 — exact transport passed, capture and metadata gate failed

`debug/rustedwax-log (17).txt` records the corrected v0.8.11 artifact's second
device round on 2026-08-01 from 15:55:52 through 19:18:18 local time. This run
was reconciled against signed-in YouTube History, both `skiptvads.vidz` profile
sections and two independent Hive account-history nodes.

The run finalized 123 tracks. Sixty-seven produced payloads and all 67 received
unique block confirmations; both Hive nodes returned exact matching JSON for
all 67 transactions in blocks 108653616–108657522. All 60 song payload entries
appeared in Music and all seven video payload entries in Videos, with no
expected id found only in the wrong section. Every payload had a canonical
watch link. Seven position wraps created no duplicate transaction, and no
ad-like payload was emitted. The two
`xKKeqlBQ3Js` transactions were separate listens about three hours apart.

The corrected log-16 behavior made real progress. Soy Peor, Me Porto Bonito and
DÁKITI retained their own ids, LA PLENA retained `F1_aOX0acbY` with corrected
artist/title, and no following track completed an ended payload. Finalized
snapshot isolation continued to choose a visible omission over an immutable
mixed identity.

The release gate nevertheless failed:

1. the three-token unequal-title floor unlatched five correct ids when the
   canonical page supplied the one/two-word work titles Ay Vamos, Esta Noche,
   Me Reclama, Recuerdos and Voy Después; each qualifying ended track was later
   refused rather than contaminated by its successor;
2. Nunca Me Amó's correct `CJjvg7PbE4w` was rejected because resolver credits
   and the MediaSession uploader had unequal exact channel keys despite matching
   id, title and duration;
3. WHEN SINCE returned no resolver candidate, and a later full +57 replay could
   not reuse the same id already verified earlier in the monitoring run;
4. seven correct-id payloads retained whole multi-separator titles, reversed
   artist/title orientation or duplicated an exact feature credit; and
5. movie/edit Short `KoWNsyNVR28` was emitted as a song on `category=Music`
   alone although no YouTube Music type or MusicBrainz match supported music.

YouTube History confirmed eight qualifying organic omissions: WHEN SINCE,
Nunca Me Amó, the five short-title songs and the later +57 replay. “Best
movie!!!” was a ninth qualifying local omission, but two uploads were
indistinguishable and neither id was present in the loaded History page; its
fail-closed outcome remains required. No explicit visible ad label occurred, so
the exact stadium-ad/`IW524Zl2Pus` transition was not field-tested. The YCB/
Coming Home handoff, `227125 → 227124` refinement and original four parser
fixtures also remain device-pending. Exact counts and payloads are canonical in
[TESTING.md §16](TESTING.md#16-v0811-second-physical-device-field-record-log-17).

## 9z. v0.8.12 implemented — log-17 correction, later tested in log 18

The patch uses version 0.8.12 and version code 32. Its automated implementation
is complete and remains limited to the evidence from §9y:

1. replace boolean title agreement with ranked exact/strong/weak evidence so a
   one/two-word canonical work title may retain only the id already observed
   from the same URL generation when duration independently agrees; weak
   evidence cannot discover or replace an id;
2. make channel corroboration role-aware so uploader and credit-list inequality
   alone does not veto an otherwise corroborated current-generation id, while
   keeping search-only candidates strict;
3. add a bounded memory-only verified-identity candidate cache, cleared at
   monitoring/package lifecycle boundaries and always re-corroborated before
   use, plus presentation-cleaned search query variants for zero-result cases;
4. extend structural parsing for primary dash plus pipe suffixes, featured
   credits, explicit track-first multi-artist lists and exact repeated-feature
   collapse; and
5. place strong explicit movie/edit format evidence above bare uploader
   category when distributor/YouTube Music provenance is absent.

This remains a fetch/log/filter patch, not a media catalog. Runtime code may not
contain fixture ids, known-song/title/artist/channel tables, per-song branches,
brand/title ad guessing or history scraping. Ambiguous uploads still fail
closed. The v0.8.11 snapshot, URL-generation ad, duration, hyperlink,
threshold, loop, kind-immutability, manual/automatic parity, honest-transport
and no-historical-rebroadcast boundaries remain unchanged.

The implementation adds exact log-17 regressions plus negative controls, keeps
the log-14/log-16 tests green, and passed the full unit/build/lint command: 314
tests, 0 skipped, 0 failures and 0 errors; assembly and lint succeeded. The
review APK is `dist/rustedwax-0.8.12.apk`, SHA-256
`3390df660053ca9c2c0c7665d320e67e820ce09513d4f025a172a018aa0080b3`.
No commit, stage, push or PR was made. A later device gate
must run all §13e fixtures and the new log-17 matrix, then reconcile the log,
History, both Hive nodes and both profile sections by id. The canonical planned
contract and test matrix are
[BEHAVIOR_CONTRACT.md](BEHAVIOR_CONTRACT.md#v0812-field-correction-contract-implemented-automated-gate-passed)
and [TESTING.md §17](TESTING.md#17-v0812-correction-implemented-log-18-result-in-18).

## 9aa. Log 18 field result and v0.8.13 targeted correction

`debug/rustedwax-log (18).txt` records the v0.8.12 device round from
2026-08-01 22:48:54 through 2026-08-02 02:29:39 local time. The 6,309-line
export contains 129 finalizations, 73 payload lines that all later reported
block inclusion and 55 visible skip decisions. Amarillo resolved at the end of
the export and then completed on-chain, bringing the run to 74 operations: 55
songs, 19 videos and 73 unique ids.

All four configured Hive nodes returned the exact logged transaction/payload
set, every unique id existed in signed-in YouTube History and every operation
appeared in the profile section declared by its payload kind. Canonical links,
snapshot isolation, loop caps, successor/ad separation and transport passed.
No explicit visible-ad label occurred, so that exact detector remained
device-unproven.

The field gate failed for metadata fidelity and coverage. MONTERO was emitted
as `Your Name — Lil Nas X - MONTERO` because an over-broad by-credit regex read
`(Call Me By Your Name)` as structure. Te Bote was emitted as video because
generic `movie` in `Flow La Movie` ran before its YouTube Music OMV result.
Three qualifying unique resolver candidates were rejected by compound/
collaborative byline presentation, while Classy 101's two matching uploads were
correctly refused. Most of the exact §§13e/17d fixture matrix was not replayed.
TESTING §18 is the detailed immutable audit.

The v0.8.13/version-code 33 patch is limited to literal `(by Artist)` and
`(performed by Artist)` grammar; YouTube Music/MusicBrainz above generic
channel vocabulary while explicit format vetoes stay higher; repeated known
owner-suffix normalization; and collaborator-leader acceptance only when the
search card carries YouTube's collaborator-dialog marker. Exact normalized
title, five-second duration tolerance, bounded candidates and unique-id
acceptance remain mandatory. Classy 101 remains ambiguous. Production code
contains no log-18 lookup table and never reads History.

The full uncached gate passed 320 tests with 0 skipped, failures or errors;
debug assembly succeeded and lint completed with 0 errors/23 warnings. The
review APK is `dist/rustedwax-0.8.13.apk`, SHA-256
`ddfeb3e51cfe60fcf8fa2f13c05891989215154da948686650ae720e4ca9e026`.
No historical operation was rewritten or rebroadcast, and no commit, stage,
push or PR was made. The complete physical gate remains pending.

## 9ab. Log 19 field result and implemented v0.8.14 watch-ad/parser correction

`debug/rustedwax-log (19).txt` tested the v0.8.13 artifact on 2026-08-02 from
11:48:33 through 19:44:40 local time. Its 5,261 lines contain 114 finalizations,
53 broadcast payloads and 61 skip decisions. The accounting is exact: 51 songs,
two videos, 42 unverified-id refusals, 14 threshold refusals, three duration
floors and two deduplications. Fifty-two transactions reported direct block
inclusion; Gangsta's Paradise was visibly queued offline and later confirmed.
All four configured nodes returned every exact payload/transaction pair and all
53 ids appeared in the declared profile section. The Stop boundary, one loop
cap, canonical links, successor isolation and honest queue state behaved well.

The fourth device gate failed. Namecheap `zUaMtSMZDgg` and KaoJapan
`azTP61YoD2s` are absent from exact signed-in YouTube History searches but are
permanently present in Videos. No `[ad]` line exists. The current accessibility
walker only scans labels when the same snapshot supplies a concrete Short id,
so ordinary watch-page ads bypass ad inspection. Search/watch-page resolution
then proved each public upload; both met the ordinary duration, threshold and
link rules. Most other ad-like sessions stayed off-chain only because identity,
duration or progress failed first, which is not an ad-detector pass.

Four song payloads define the additional parser scope: Marlon Asher lost the
closing side of `[Official Video 2024]`; TVXQ split at the dash inside a
single-quoted work; Taki Taki reversed because `DJSnakeVEVO` did not
corroborate spaced `DJ Snake`; and BENNETT's Mamma Mia retained the leading
artist inside a conservative multi-dash title. Exact v0.8.13 correction
fixtures and the broader historical physical matrix were not replayed, so the
artifact is not device-approved despite perfect transport reconciliation.

The implemented v0.8.14/version-code 34 correction is generic and bounded:

1. scan the existing exact/localized accessibility ad labels on any visible
   YouTube host, not only a concrete Short;
2. keep Short evidence on its existing URL-generation/id path, but bind an
   ordinary watch signal only to one unique active MediaSession track instance;
3. require established-instance agreement or provisional re-observation, clear
   provisional state at track/package/Stop/reset boundaries, and carry accepted
   evidence only with that advert track—not the organic address-bar id;
4. add paired promo-plus-year cleanup, narrow balanced single-quoted work
   parsing, exact collapsed owner corroboration, and exact channel-proven
   version-suffix parsing with strong negative controls; and
5. preserve every v0.8.13 snapshot, link, resolver, classification, threshold,
   loop/dedup, queue, manual/automatic and no-historical-rewrite guarantee.

History remains a device-audit oracle, never a runtime resolver/ad mechanism.
Production source may not contain log-19 ids, brands, titles, channels or
heuristic ad tables. A label-free public promotion remains unproven. TESTING
§20 contains the authoritative ledger, exact regression matrix, implementation
record and next physical checklist; BEHAVIOR_CONTRACT contains the canonical
implemented rule. The full uncached gate passed 338 tests plus assembly and
lint (0 errors/23 warnings). The tested review APK is
`dist/rustedwax-0.8.14.apk`, SHA-256
`a9507c733b188f9cf3a481c8b1446535da22449343181cc17ccf556890f298b8`.
The later log-20 physical round failed as recorded below.

## 9ac. Log 20 field result and implemented v0.8.15 evidence/parser/resolver correction

`debug/rustedwax-log (20).txt` tested the v0.8.14 artifact from 2026-08-02
21:14:46 through 2026-08-03 10:52:51 local time. Its 227 completed
finalizations reconcile exactly to 107 automatic broadcasts and 120 skips. A
fixed test broadcast makes 108 scoped operations: 80 songs and 28 videos. Two
automatic payloads were queued offline and later block-confirmed. All four Hive
nodes returned the same normalized rows, profile growth matched exactly and all
automatic YouTube payloads had canonical distinct ids/links.

Accepted v0.8.14 ad binding worked: 89 unique track instances received literal
`Sponsored`/`Visit Advertiser` evidence and all 89 were vetoed. Fifteen
threshold refusals, six missing-duration/zero-play cases, two duration floors
and eight identity refusals complete the skip ledger. Same-track continuation,
loop caps, 2× progress measurement and queue timestamp/state behavior remained
consistent. No URL-less, successor-mixed, duplicate-id or unconfirmed logged
payload was found.

The fifth field gate still failed. Namecheap appeared four times. Three
literal-label instances stayed off-chain; the fourth received no accessibility
observation, resolved uniquely as public video `zUaMtSMZDgg` at exactly 30
seconds and became transaction
`8c22a93cd7d581687055240d279d8713abfcdfc9`. The previous URL/ad scan was at
00:58:56 and the next at 01:35:03, while the watcher remained marked connected.
Multiple advert MediaSessions passed through this roughly 36-minute silent
interval. Namecheap alone also satisfied public identity and duration. This is
an observation-coverage outage, not a failure of track binding after evidence
arrived and not authority for a Namecheap/brand lookup table.

One qualifying organic song, BOOGA `JmeUtPih4U8`, played fully and uniquely
resolved with Music/OMV provenance but was refused because search presentation
`Central Cee and LIVE YOURS` contradicted ended channel `Central Cee`.
`DGs9TJmazB0` and `z7DbZS6l6Vk` reached correct song ids with artist/title
reversed because the generic parser treats multiple featured credits on the
right as proof of track-first orientation. The mandatory historical fixture
matrix was again not replayed; Stop/reset with a populated cache, mute, dedup
replay and real YouTube manual parity remain unproven. The export ended during
the final continuation window.

The implemented v0.8.15/version-code 35 correction is bounded to:

1. track successful visible-YouTube-root scans as immutable per-track coverage,
   separate from watcher connection and from a positive ad flag;
2. share one bounded observation routine between callbacks and a periodic
   watcher refresh, with explicit evidence-outage diagnostics;
3. with Browser evidence enabled, refuse resolver-only automatic/manual tracks
   lacking a current-track scan, naming unavailable evidence rather than
   inventing an ad; preserve exact-URL behavior and Browser-evidence-off fallback;
4. preserve conventional artist-first featured titles unless positive
   track-first work/credit structure exists, retaining all proven log-17 cases;
5. accept BOOGA-shaped collaborative candidate presentation only with unique
   exact title/duration, hard music provenance and an exact complete leading
   owner segment; and
6. preserve every existing URL generation, snapshot, floor, threshold, loop,
   dedup, queue, cache, manual/automatic, ambiguity and immutability invariant.

The patch began with focused failing tests. Its 176 focused tests pass with no
skips, failures or errors, so version/code and documentation now describe the
implemented v0.8.15 behavior. The full uncached gate also passes 349 tests with
no skips, failures or errors; debug assembly succeeds and lint completes with 0
errors/23 warnings. The tested `dist/rustedwax-0.8.15.apk` has SHA-256
`242d1b76d473754494dec74e035a7731ee1311c4920458926c5dd769e7ee365c`.
The complete TESTING §21f physical matrix remained the strict next-device
benchmark at build time. The later practical field acceptance is recorded
below.
History stays an external audit oracle only; production source may not contain
field ids, titles, artists, channels, brands or media catalogs. No immutable
operation is repaired or rebroadcast.

## 9ad. Log 21 final v0.8.15 field result and Phase 4 closure

The surviving final-device export spans 2026-08-03 12:56:49–18:06:25 local
time and contains 228 decisions: 98 broadcasts and 130 skips. All 98 broadcasts
were block-confirmed, used unique YouTube ids and transaction ids, and matched
the signed-in `skiptvads.vidz` profile exactly. The 57-song/41-video payload set
contained no new advertisement. Two naturally served Namecheap sessions were
rejected using the literal `Sponsored` and `Visit Advertiser` controls, directly
exercising the log-20 failure shape without adding a brand-specific rule.

The 130 skips break down into 85 explicit-ad vetoes, 32 threshold decisions, 11
unverified ids and two hard floors. History showed that four unverified items
were complete organic songs. One further complete organic track inherited
still-active `Visit Advertiser` evidence from an immediately preceding promoted
music session and was conservatively vetoed. These five false omissions caused
no chain pollution. Two broadcast song entries retained useful but imperfect
uploader-attributed credits. Music-oriented Shorts classified as songs under
hard Music/YouTube Music evidence, which is accepted product behavior.

The phone rebooted twice and the earlier runtime coverage was lost. The
surviving 14,464-line export contains no RustedWax exception, fatal, ANR,
out-of-memory or crash marker, but it cannot prove the cause of either reboot.
Chrome alone was exercised. SIP, Bad Habits, Taki Taki, BOOGA and the rest of
the exhaustive historical fixture list were not deliberately replayed; their
v0.8.15 evidence remains the automated suite.

The observable qualifying capture estimate is approximately 95.1%, excluding
threshold/floor cases and reboot-interrupted sessions. It is not a formal
accuracy benchmark. The strict §21f every-fixture/zero-omission gate was not
literally passed, but the user accepted the no-new-ad, exact 98/98 reconciliation
and conservative failure shape as the final Phase 4 product. The exhaustive
ledger, hashes and release exceptions are canonical in
[TESTING.md §22](TESTING.md#22-v0815-final-physical-device-field-record-log-21).
The next development line is v0.9 native YouTube and YouTube Music app input;
it must be delivered as a separate phase without changing v0.8.15 history.

## 10. Historical pre-D4 checklist

This checklist predates v0.4.0 and is retained only as Phase 4 history. D4 was
subsequently revised by the field findings above; it is not an outstanding
gate for v0.8.11 or the implemented v0.8.12 correction.

Song-by-default is irreversible per entry. Run the new classifier over the
existing event log and print what each past track *would* have been classified
as. That is a cheap dry-run, and it calibrates the blocklist against what is
actually watched rather than against guesses.

## 11. Historical v0.4.0 documentation checklist (completed)

These were the documentation/version tasks for the original Phase 4 landing,
not the current next-patch plan. The log-20 correction and final log-21
acceptance are in §§9ac–9ad, `BEHAVIOR_CONTRACT.md`, and `TESTING.md`.

- **README** — v1 scope; delete the native-apps "Bonus over desktop" claim (D3);
  add Accessibility setup incl. the restricted-setting step; state plainly that
  enrichment makes a request to youtube.com from outside the browser; rewrite
  the phone/desktop parity claim (D7).
- **DEVELOPMENT_PLAN §9** — privacy mode renumbered to Phase 5.
- **Version** — 0.3.1 → 0.4.0.
