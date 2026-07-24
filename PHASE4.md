# Phase 4 — Control, exclusivity, and metadata fidelity

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

## 10. Before enabling D4

Song-by-default is irreversible per entry. Run the new classifier over the
existing event log and print what each past track *would* have been classified
as. That is a cheap dry-run, and it calibrates the blocklist against what is
actually watched rather than against guesses.

## 11. Docs to update when this lands

- **README** — v1 scope; delete the native-apps "Bonus over desktop" claim (D3);
  add Accessibility setup incl. the restricted-setting step; state plainly that
  enrichment makes a request to youtube.com from outside the browser; rewrite
  the phone/desktop parity claim (D7).
- **DEVELOPMENT_PLAN §9** — privacy mode renumbered to Phase 5.
- **Version** — 0.3.1 → 0.4.0.
