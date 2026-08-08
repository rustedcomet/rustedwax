# Release and specification history

The version-by-version specification status that used to open the README. Kept verbatim: it is
the audit trail behind the behavior contract.

[← Back to the README](../README.md)

---

> **Specification status:** [BEHAVIOR_CONTRACT.md](../BEHAVIOR_CONTRACT.md) contains
> the evidence-backed as-built audit through the failed v0.8.14 log-20 device
> round and the bounded implemented v0.8.15 correction, the
> historical v0.8.10 log-14 record, both v0.8.11 field results in logs 16–17,
> and the canonical
> product invariants. The first v0.8.11 physical-device gate failed in log 16. Its
> generic title-corroboration and quoted-credit corrections are now implemented,
> covered by a 295-test automated gate, and packaged in a corrected v0.8.11 APK;
> log 17 tested that artifact and made considerable progress—67/67 emitted
> payloads were block-confirmed and reconciled—but the second device gate still
> failed on eight History-confirmed qualifying omissions, malformed credits and
> one likely movie-Short classification error. The generic v0.8.12 correction
> passed its 314-test/build/lint automated gate. Log 18 then proved a clean
> 74-operation transport/profile path but failed the device gate on two
> permanent payload defects and incomplete fixture coverage. The narrow
> v0.8.13 correction passed 320 tests plus build/lint. Log 19 then reconciled
> all 53 RustedWax transactions exactly, but failed the fourth physical gate:
> two ordinary watch-page advertisements reached Hive, four song payloads
> exposed parser defects/limitations, and the mandatory correction fixtures
> were not replayed. The bounded v0.8.14 correction passed its
> 338-test/build/lint gate and was packaged, but log 20 failed the fifth physical
> gate: a second Namecheap ad escaped during a silent accessibility-observation
> outage, one History-confirmed qualifying song was refused, two song payloads
> reversed artist/title, and the mandatory fixture matrix remained incomplete.
> The generic v0.8.15 correction passed its 349-test uncached source/build/lint
> gate. Final device log 21 then reconciled 98/98 unique broadcasts to the
> signed-in profile with no new advertisement payload, no duplicate id and no
> RustedWax crash signature. Four organic tracks remained unresolved and one
> organic track was conservatively vetoed by carried ad evidence. The user
> accepted these documented exceptions and the incomplete historical fixture
> replay as the Phase 4 final product; v0.9 moves to native YouTube app input.
> Phase documents are historical records and do not override that contract.

> **Current development source:** v0.9.4/version code 40 retains the independent,
> separately granted foreground Shorts observer scoped by Android only to
> `com.google.android.youtube`. It requires a stable structural player, title,
> exact handle and seekbar; counts position deltas only; rejects torn transition
> frames; recognizes literal player ad labels; and fails closed in PiP. An
> uncorroborated exact-ID-less native MediaSession recreation cannot carry
> progress. Owner-
> handle corroboration extends exact-id lookup without weakening ambiguity.
> The v0.9.2 ordinary-native correction added a source-scoped structured music
> resolver for YouTube's simplified separated title/artist metadata. It fully
> fetches candidate pages and requires exact parsed work, one complete exact
> artist credit, duration agreement and one unique upload. Exact-ID-less native
> STOPPED state gets a bounded replacement grace; a same-metadata material
> duration change discards the earlier fragment with zero carry and no ad
> inference.
> v0.9.3 filters that watch-page budget by exact parsed work and complete artist
> credit before counting candidates. It also resolves a stable native track
> while playing: only a unique immutable id can defer a controller fragment,
> and a replacement must independently establish the same id before claiming
> time. Finalization re-fetches with the same raw or structured predicate.
> Repeated equivalent no-Shorts-root diagnostics are bounded without changing
> observer events or proof timing.
> v0.9.4 keeps a canonical search-card/watch-page author as an independent exact
> artist credit when a presentation title parses to a broader collaboration, and
> applies the same narrow trailing-feature work grammar to native and candidate
> titles. Duration, page budget, full-page verification, uniqueness and ambiguity
> refusal are unchanged; raw/browser resolution is untouched.
> The A12 no-broadcast gate passed the progress, transition, two-natural-ad,
> lifecycle, browser and YouTube Music checks. The configured test account was
> then authorized for a bounded write pass: four long foreground Shorts were
> block-confirmed and reconciled exactly on the public profile, while lookup-off,
> unresolved and genuinely ambiguous observations all remained off-chain. The
> bounded foreground-Short gate is approved; native sources remain experimental
> and default-off while the broader native-app measurements remain open. See
> [PHASE_NATIVE_SHORTS.md](../PHASE_NATIVE_SHORTS.md),
> [PHASE_NATIVE_PLAYLIST.md](../PHASE_NATIVE_PLAYLIST.md) and
> [PHASE_NATIVE_APPS.md](../PHASE_NATIVE_APPS.md). The v0.9.3 field continuation
> produced four unique ordinary-native writes in one healthy run and later
> supplied the pending same-id controller-recreation acceptance: `Unica`
> independently resolved to `7uxTya2PX3c` on both sides of a 14-second controller
> gap, carried 87 seconds, finalized once at 215/218 seconds and block-confirmed.
> The four historical misses were not repaired or rebroadcast. Earlier, the live
> `Hey DJ` session exercised the new transition guard: same-title duration
> phases changed 218→20→207 seconds inside the ten-second grace, and each old
> fragment was discarded with zero carry and no ad inference. The clean
> 207-second phase then completed and exercised the structured resolver: two
> exact `Hey DJ` uploads matched, so RustedWax refused the ambiguity and built no
> payload. The next v0.9.2 continuation then proved one healthy unique write
> (`Dile Que Tu Me Quieres`) while title-only budget refusals and a 52%+50%
> controller split exposed the v0.9.3 work. The code-39 run then isolated one
> exact-author matcher defect (`Criminal`) from correct no-result, contradictory
> and multi-upload ambiguity refusals. The v0.9.4 uncached gate passed 425 tests
> with no skips/failures/errors, debug assembly, and lint at 0 errors/23 warnings.
> The exact artifact was installed without navigating YouTube at 10:59:34 local;
> all settings/services and USB stay-awake were preserved. It is
> `dist/rustedwax-0.9.4.apk`, SHA-256
> `cbaebadc91d0045b6bd7a2abec8aaacefb2e914782a404d705b24890d4c9ccbf`.
> The untouched code-40 continuation retained ambiguity, bounded no-result and
> zero-carry replacement refusals, then uniquely resolved the stable 416-second
> `Ella Y Yo` / `Pepe Quintana - Topic` phase to `CGjuWHEPxgc`. It finalized at
> 416/416 seconds, re-fetched the same page and block-confirmed one linked payload
> as tx `49a46d159658d257706c9a3c6b32eed4ddd29ca1` in block 108,734,261 on two
> independent Hive nodes.

---

## v0.9.5 – v0.9.13: the native Shorts field rounds

The block above is the last version-by-version prose written in that style. From
v0.9.5 the record is kept as measurements instead, because every change in this
range was forced by one, and the evidence is more useful than the summary:
[FIELD_2026-08-05.md](../FIELD_2026-08-05.md) §8–§18, with the invariants that
changed in [BEHAVIOR_CONTRACT.md](../BEHAVIOR_CONTRACT.md) under v0.9.7 onward.

**Current development source: v0.9.13 / version code 49.** 594 tests, debug
assembly and lint clean; the installed artifact verifies as
`631e83b9b71de67fd4d109da43029b8078424673575cf773ccad3f88a8869d29`.

| version | what it changed | proven by |
| --- | --- | --- |
| v0.9.5–v0.9.6 | Playlist-derived identity; signed-in watch history as an id route | on-chain writes with linked ids |
| v0.9.7 | The on-screen title stops being identity; recency breaks an untitled tie; `AtVEVO` and collaborator bylines; **picture-in-picture is counted, marked inferred**; a completed listen banks when it completes | a full day of real use, 46 scrobbles |
| v0.9.8 | Either of a page's two published titles may corroborate; watch-history stand-down needs three *different* tracks and says when it is standing down; unmeasured lead-in is stated, never credited | `tx 0d02673d…` on the exact video that had been refused |
| v0.9.9 | The §5.1 outage report fires only when a listen is being lost; an app is visible while any of its activities is started (this one also un-refused picture-in-picture credit) | `tx 4ba912d3…`, plus a staged outage that reported and recovered on cue |
| v0.9.11 | An interrupted Short resumes rather than restarts; a Short with nothing left to earn ends when it is earned; the untitled history window matches the fetch budget | `tx 95163ca6…` |
| v0.9.12 | The empty metadata YouTube publishes on a session restart stops ending the track it interrupted; a handle the enrichment fetch did not carry is absence, not contradiction | `tx 07424882…`, 131s of 135s across a tab switch |
| v0.9.13 | Handing the player to the Shorts route finalizes the video it interrupts instead of deleting it; an owner handle may be spelled in any script; one banked listen is finalized once; a refusal names what it refused | `tx 99dae063…` (142s of 146s), `tx 7eb3a47e…` (`@eduardaarebouçass`) |
| v0.9.10 | The seekbar stops being a precondition — YouTube draws none for a *resumed* Shorts player, which cost 47 of 71 Shorts in 85 minutes; identity now needs the handle plus any one of title or length | `tx 2bf618de…`, then a back-to-back pair on the owner's own reproduction: `tx 80c427db…` inferred and `tx 48f78ee7…` measured |

Native sources and the separate Shorts grant remain experimental and default-off.
