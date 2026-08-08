# Platform expansion and correctness — build plan

This document is the plan for the work after the v0.9.x field rounds. It was written on 2026-08-07
from a side-by-side reading of four upstream projects, and it is the contract for the phases below.
It does not override `BEHAVIOR_CONTRACT.md`; where the two disagree, the contract wins and this
document is wrong.

Nothing in it has been started. v0.9.12 and v0.9.13 landed after it was written and were field
repairs, not plan work — but two of them moved facts this plan is built on, recorded in §2.1.

Sources read in full:

| Project | What it is | What it is good for here |
| --- | --- | --- |
| [hivescrobble](https://github.com/Holozing1/hivescrobble) | Browser extension, fork of Web Scrobbler | The on-chain contract RustedWax already ports |
| [web-scrobbler](https://github.com/web-scrobbler/web-scrobbler) | The upstream extension | The per-source connector pattern |
| [metadata-filter](https://github.com/web-scrobbler/metadata-filter) | Small MIT string-cleaning library | Title/artist normalization primitives |
| [pano-scrobbler](https://github.com/kawaiiDango/pano-scrobbler) | Android scrobbler, MediaSession-based | The only one on our platform; app allowlist, ad metadata, per-package artist rules |

Pano is the important one. It solves the same problem on the same OS with the same input, and it
has already been through the scaling step this plan is about.

---

## 1. Decisions taken, with the alternatives that were rejected

| # | Decision | Rejected alternative, and why |
| --- | --- | --- |
| 1 | Native YouTube artist comes from a **credits ladder**, not the MediaSession artist field | Refusing unparseable titles outright (Pano's rule) — unnecessary here, see §3.3 |
| 2 | Non-YouTube sessions produce **no log line at all**, only an aggregate count | Logging package-without-title — timing plus package is still a browsing record |
| 3 | Guest accounts **deferred** pending a documented mobile auth flow from scrobble.life | WebView token harvesting — undocumented use of someone else's endpoint |
| 4 | Onboarding = disclaimer, then one page per grant that deep-links to system Settings | "Accept and we enable everything" — Android forbids it, see §5.1 |
| 5 | Private scrobbles mirror the extension's envelope exactly | A single global toggle — the per-kind split is the whole point |
| 6 | Movies/TV are **classified only**, no IMDb/Wikipedia/poster enrichment | Porting their metadata pipeline — another network dependency for cosmetic fields |
| 7 | Settings split into a simple screen and an Advanced screen | Per-source settings pages — revisit at ~5 platforms |
| 8 | Chapters are **parsed and recorded**, the video still scrobbles once | Per-chapter scrobbling — a sub-project touching the most delicate code |
| 9 | Logging off means **nothing is written**; a separate diagnostic mode is temporary | Hiding the tab while still writing — dishonest if anyone looks |
| 10 | Translation covers UI and skip reasons; the event log stays English | Translating the log — it is the primary debugging artifact |

---

## 2. What was already built, and does not need doing

Two items on the original wishlist were already shipped and were removed from the plan after
checking the source:

- **CJK bracket parsing.** `TitleParser` handles `【】「」『』`, including the leading-bracket-names-artist
  form (`TitleParser.kt` `LEADING_BRACKET_ARTIST`). Added in Phase 4.
- **Cross-source duplicate protection.** `DedupLedger.keyFor` is content-keyed
  (`title|artist|hourBucket`) and persisted, so the same track playing in two sources cannot
  broadcast twice. This is stricter than the extension, which keys music dedup on
  `startTimestamp` in session storage.

One item was found to be **less constrained than assumed**: YouTube chapters are not blocked by the
platform. MediaSession does not carry them, but `YouTubePageResolver` already fetches the watch
page, and chapters are in it. Chapters are a scope decision, not a capability gap. See §6.5.

### 2.1 What v0.9.12–v0.9.13 changed underneath this plan

Field repairs, all recorded in `FIELD_2026-08-05.md` §17–§18. Two of them touch sections below.

- **§3.6 now has a third normalizer.** `OwnerHandle` composes to **NFC** as of v0.9.13, because an
  owner handle may be spelled in any script and the footer and the watch page need not agree on how
  to encode a cedilla. `TrackIdentity` uses NFKC, `DedupLedger` still uses neither. That is three
  components with three answers to "are these the same string", which is exactly the latent
  duplicate-broadcast bug §3.6 exists for — and it is no longer hypothetical, because a dedup key
  now reads `…|@eduardaarebouçass|496153`. Note when doing §3.6 that the shared normalizer cannot
  simply be NFKC everywhere: an **identity** field may only use canonical equivalence, because NFKC
  folds distinct characters together.
- **§3.5's premise is confirmed from the other direction.** The handle pattern was ASCII-only and
  silently refused every non-ASCII creator; `TitleParser` has the same shape of assumption about
  zero-width and RTL marks. Both are "a field that looks correct on screen and does not match", and
  the handle case cost every listen by those creators until it was measured.

Neither is done here — they are notes for whoever does §3.5 and §3.6.

---

## 3. Phase 1 — Correctness

Everything in this phase is currently writing wrong or misattributed data to a ledger that cannot
be edited. It runs first for that reason.

### 3.1 The native artist bug

`ScrobbleBuilder.creditsForKind` short-circuits for native sessions on this reasoning:

> *"Native apps already publish separated title/artist fields."*

That is true of YouTube **Music**, which is a music player and publishes a real artist. It is false
of the YouTube **app**, which publishes the *channel* in the artist slot. One rule covering two
packages, correct for one of them.

Observed on-chain and in `debug/`:

```
MediaSession TITLE:  Snoop Doggy Dogg - Intro
MediaSession ARTIST: King Of Rap          ← the channel, not the artist

broadcast as → artist: "King Of Rap"
                title:  "Snoop Doggy Dogg - Intro"
```

Both fields are wrong. `TitleParser` is never called on this path.

Pano reaches the same conclusion by package list: `com.google.android.youtube` and every fork
(Vanced, ReVanced, SmartTube, mango, TV, tvkids) are in
`DEFAULT_IGNORE_ARTIST_META_WITHOUT_FALLBACK`. `com.google.android.apps.youtube.music` is
deliberately **not**.

### 3.2 The fix: restore the ladder, scoped per package

The browser path already has the right logic in `creditsOf`. The native path bypasses it. The fix
is to stop bypassing it, not to add anything new:

```
1. facts.originalArtist / originalTitle   ← YouTube's own music credits, from the watch page
2. TitleParser.parse(title, channel)      ← the Artist - Track split
3. MusicBrainz confirmation               ← canonical spelling; also fixes reversed pairs
4. fallback → kind=video, channel as uploader
```

Steps 1–3 all exist today and all run on the browser path. YouTube Music keeps its current
trust-the-metadata behaviour. YouTube and its forks get the ladder.

Step 4 is the only new behaviour, and it is what makes this cost nothing: a track whose artist
cannot be established stops claiming to be a `song` and becomes a `video` credited to its channel.
`creditsForKind` already does exactly this for non-song kinds. The channel *is* the uploader, so
the claim is true.

**Net effect: no scrobble is lost.** The artist field becomes correct where it is currently wrong,
and entries that cannot be credited stop asserting a musical artist they do not know.

### 3.3 Why refusal was rejected — the measurement

Refusing unparseable titles (Pano's rule) was the original recommendation. It was dropped after
measuring the actual corpus in `debug/`:

| Package | Unique finalized titles | Containing an `Artist - Track` separator |
| --- | --- | --- |
| `com.google.android.youtube` | 11 | **11** |
| `com.google.android.apps.youtube.music` | 0 | — |

Zero titles without a separator. The sample is small and must not be treated as proof, but it
matches the structural reason: YouTube *video titles* for music uploads are `Artist - Track` by
convention, because that is how uploaders title music. The channel is the unreliable field; the
title is not.

Pano refuses because it has nowhere else to put the scrobble. RustedWax has a `kind` field, so it
does not need to.

### 3.4 The advertisement metadata key

Android publishes `android.media.metadata.ADVERTISEMENT` on MediaSessions that are playing an ad.
RustedWax does not read it. Pano does, in one line
(`MetadataTransforms.android.kt`: `metadata.getLong(METADATA_KEY_ADVERTISEMENT) != 0L`).

It feeds `explicitAdSignal` as a **hard veto**, identical to visible-UI ad evidence — an ad the OS
itself has labelled is not weaker evidence than an ad we inferred from a screen scrape.

Corroborating evidence that this matters: `debug/` contains finalized titles of the form
`094 GP EN 16x9 21s 11`, `114 GP EN 29s 16x9 06`. Those are ad creative slate names.

### 3.5 Invisible characters

`TitleParser` does not strip zero-width characters, RTL/LTR marks, or non-breaking spaces, so
`Artist[U+200B] - Song` fails to split on a title that looks correct on screen.

Primitives to port from `metadata-filter` (MIT): `removeZeroWidth`, `replaceNbsp`,
`replaceSmartQuotes`, `decodeHtmlEntities`, and `normalizeFeature` / `fixVariousArtists` if not
already covered.

### 3.6 Dedup key normalization

`DedupLedger.keyFor` lowercases but does not normalize. `TrackIdentity` uses NFKC. `OwnerHandle`
uses NFC (v0.9.13, §2.1). Three components with three answers to "are these the same string" is a
latent duplicate-broadcast bug, independent of §3.5 — fix them together and share one normalizer.

The shared normalizer is not simply "NFKC everywhere". Presentation fields may fold compatibility
variants; an **identity** field may not, because NFKC maps distinct characters onto each other and
would let two different handles collide. The split is deliberate and has to survive the merge.

### 3.7 Bare `MV` suffix

`Song Title MV` with no brackets. A `TitleParser` rule; small.

### 3.8 The `app` field

`HiveScrobblePayload.APP_NAME` is `hivescrobblesai/1.0` — byte-identical to Hive Scrobbler's. On
chain, RustedWax entries are indistinguishable from theirs, which contradicts the README's
unaffiliated positioning and means any RustedWax defect lands attributed to their app.

Change to `rustedwax/<version>`. The `custom_json` id `hive_scrobble_ai` stays — sharing it is
deliberate and correct.

**Raise with the scrobble.life maintainer before changing it.** They may prefer a distinct
`custom_json` id so their indexer can filter RustedWax out entirely, which is their call to make.
`docs/ON_CHAIN_FORMAT.md` updates with whatever is decided.

---

## 4. Phase 2 — Privacy

### 4.1 Non-YouTube sessions must leave no trace

The leak is not `UrlWatcherService`, which already gates on `YouTubeAdDetector.shouldScanHost`.
It is `SessionProbe`: a `Watch` is created for the browser package, and `logMetadata` writes the
page title to the event log *before* anything has proven the site is YouTube. Chrome publishes a
MediaSession for any video site, so the log accumulates a record of what the user watched
elsewhere.

Required: a session that is not proven YouTube produces **no identifying output** — no title, no
host, no package name. A periodic aggregate line (`3 non-YouTube sessions ignored`) preserves the
"is it running?" signal without recording where the user went.

### 4.2 The logging switch

Off means nothing is written: no buffer, no file, nothing to leak or export. A separate
**diagnostic logging** toggle turns it back on temporarily for troubleshooting, and self-expires
(Pano expires theirs after 15 days).

### 4.3 Disable Shorts — a new setting, not the existing one

`Settings.shortClips` is **not** "disable Shorts". It controls whether verified Shorts get the
lowered 10-second floor; turning it off holds Shorts to the ordinary 30-second minimum but still
scrobbles them.

"Disable Shorts" is a separate setting: never scrobble anything from a proven `/shorts/` path.
Both should exist — they answer different questions, and conflating them in the UI is how a user
ends up believing they turned something off that is still running.

---

## 5. Phase 3 — Architecture

This phase is what makes platform expansion possible. None of it is user-visible on its own.

### 5.1 App allowlist replaces per-app booleans

`Settings.nativeYouTube` and `Settings.nativeYouTubeMusic` are hardcoded switches. That does not
survive a third source, let alone a tenth.

Pano's model:

```kotlin
allowedPackages: Set<String>
blockedPackages: Set<String>
seenApps: Map<String, String>   // package → human label
autoDetectApps: Boolean = true
```

The flow that makes it feel like no configuration at all: when a MediaSession appears from a
package never seen before, fire a notification — *"Detected Deezer. Scrobble from it?"* with
Allow / Block. `PanoNotifications.notifyAppDetected`, called from `SessListener`.

Conflict rule, taken verbatim because it prevents a class of bug: **the allowlist wins**
(`bSet.removeAll(aSet)`).

Existing `nativeYouTube` / `nativeYouTubeMusic` values migrate into the allowlist on upgrade. No
user loses a setting they already made.

### 5.2 Source profiles

`ScrobbleRules` is dense with YouTube-specific reasoning — the 10-second Shorts floor, the
unlisted-video ad heuristic, watch-page proof, `hasShortSourceProof`. None of it applies to a
source like Spotify, which publishes a real track id, clean metadata, and no ads inside the
session.

Without a descriptor, `decide()` grows a branch per source. Web-scrobbler's `Connector` object is
the same pattern: each source declares what it can provide, and shared logic reads the declaration.

Shape, to be refined during implementation:

```kotlin
data class SourceProfile(
    val platform: String,               // the payload's `platform` field
    val publishesExactTrackId: Boolean, // skip the identity stack entirely when true
    val needsCanonicalUrl: Boolean,
    val canonicalUrlPattern: Regex?,
    val requiresAdEvidence: Boolean,
    val minDurationSeconds: Long,
    val trustsMetadataArtist: Boolean,  // §3.2: false for YouTube, true for YouTube Music
)
```

`trustsMetadataArtist` is where §3.2's per-package rule lives, rather than as a package list in
`ScrobbleBuilder`.

### 5.3 Generalize the URL invariant

`HiveScrobblePayload.hasRequiredYouTubeUrl` hardcodes a YouTube watch-URL regex. The invariant is
correct and stays; it moves behind `SourceProfile.canonicalUrlPattern` so each platform declares
its own canonical shape. Without this, a Spotify scrobble either fails the check wrongly or
bypasses it entirely.

### 5.4 Settings restructure

Two tiers. Simple holds what most people touch: scrobbling on/off, the app list, Shorts on/off,
logging on/off, language. Advanced holds thresholds, PiP inference, the short-clip floor, watch
history, diagnostics.

Revisit per-source settings pages at roughly five platforms, when the app list makes them natural.

---

## 6. Phase 4 — Features

### 6.1 Onboarding

**Android does not permit an app to grant itself Notification Access, Accessibility, or Usage
Access.** A disclaimer cannot enable them. The most an app may do is deep-link to the exact system
screen and detect the result on return. Any onboarding design that assumes otherwise is impossible,
and this was the first assumption corrected in planning.

Shape: disclaimer page, then one page per grant — what it is for, a button that opens the system
screen, and detection when the user returns. Optional grants are genuinely skippable.

First install only. Existing users with a key already saved go straight to the app; a page appears
in context only when a new feature needs a grant they do not have.

The disclaimer text is **open** — it must cover the YouTube-access tradeoff already stated in the
README and `docs/IDENTITY.md`, and local posting-key storage. See §8.

### 6.2 Internationalization — English and Spanish

UI labels are easy. The problem is that skip reasons are long English prose assembled in Kotlin
(`ScrobbleRules.progressSurfaceLostReason` and neighbours), and they are user-facing in the
Not-logged tab — they are the best part of the UX and must not stay English.

Scope: UI labels and skip reasons move to string resources with placeholders. The event log stays
English, because it is a debugging artifact that gets exported and read by whoever is diagnosing.

This is a genuine restructure of the reason-building code. It is also better structure regardless
of translation.

Translation source is **open**. See §8.

### 6.3 Private scrobbles

Mirror the extension exactly: the envelope `{app, kind, timestamp, private: <blob>, v: 1}`,
AES-GCM, and four per-kind toggles (music / videos / movies-TV / podcasts).

One deliberate difference, and it is a simplification: the extension derives its secret from a
Keychain signature because it never holds a key. RustedWax holds the posting key locally and can
derive the secret directly — no prompt, no relay, no mid-listen interruption. The failure mode the
extension guards against (privacy on, no cached secret, refuse to broadcast plaintext) still
applies and must be preserved.

Anything the extension can decrypt, RustedWax must be able to decrypt, and the reverse. A user
running both gets one coherent history or the feature is pointless.

### 6.4 Movies and TV

Classify only. `MusicClassifier`-style rules set `kind=movie` or `kind=episode`, with title and
year when parseable. `imdb_id`, `wikipedia_url`, `series_*` and `poster_url` stay absent — the
payload already omits absent fields, and the extension fills them from a DOM and a Wikidata lookup
that RustedWax has no equivalent for.

This fixes the actual defect, which is films being filed as songs. The cosmetic fields can come
later behind the existing "Look videos up" switch if they are ever wanted.

### 6.5 Chapters

Parse the chapter list from the watch page and record it. The video still scrobbles once, as today.

Near-term value is diagnostic only — nothing on chain can be amended, so a recorded chapter list
cannot retroactively split an entry. The reason to do it now is that the parser is the reusable
part: per-chapter scrobbling later needs chapter parsing, per-chapter play measurement from seekbar
position, per-chapter dedup keys, and N transactions from one video. This builds the first of those
and none of the risky ones.

---

## 7. Deferred, with the reason

### 7.1 Guest accounts — blocked on an external dependency

Hive Scrobbler's guest path POSTs to `{origin}/api/ingest/scrobble` with a Bearer token that a
content script captures after the user signs in with Google on scrobble.life. An Android app has no
content script and cannot obtain that token the same way.

Building it via WebView token-harvesting would be undocumented use of someone else's server.
**The decided path is to ask the scrobble.life maintainer for a documented mobile auth flow first**,
as a natural follow-up to the §3.8 conversation.

Status: **pending**. Not to be implemented until that conversation has happened.

### 7.2 Not taken from upstream

| Item | Source | Why not |
| --- | --- | --- |
| Discord RPC, charts, friends, collages | Pano | Social features shaped around the last.fm API; enormous surface, wrong fit |
| 406 DOM connectors | web-scrobbler | Cannot run on Android. The *pattern* transfers (§5.2); no code does |
| Love / unlove | both | No chain semantic for it yet |
| `now_playing` on chain | — | The extension explicitly refuses this. Follow them |
| Raw regex edit rules | Pano | This is the cockpit. Take the **presets** as checkboxes first; raw regex behind an advanced screen only if asked for |
| Proxy, automation ContentProvider | Pano | Real features for power users RustedWax does not have yet |

---

## 8. Open questions

| # | Question | Blocks |
| --- | --- | --- |
| 8.1 | Spanish translation source — the author, a native speaker, or Crowdin (both upstream projects use it)? | §6.2 scope; whether Crowdin setup is in this phase |
| 8.2 | Disclaimer wording — drafted here, or supplied? | §6.1 |
| 8.3 | Does the scrobble.life maintainer prefer a distinct `app` value or a distinct `custom_json` id? | §3.8 |

---

## 9. Order of work

```
Phase 1  correctness   §3.1–3.8   ← writing wrong data today; runs first
Phase 2  privacy       §4.1–4.3
Phase 3  architecture  §5.1–5.4   ← prerequisite for any new platform
Phase 4  features      §6.1–6.5
```

Phase 1 before Phase 3 is deliberate, and it is the one ordering choice worth defending. Building
the architecture first would mean every Phase 1 fix is written once against the new structure
instead of twice — but it also means the artist bug keeps writing incorrect entries to an
unerasable ledger for however long Phase 3 takes. A wrong entry cannot be withdrawn; a refactor can
be redone.
