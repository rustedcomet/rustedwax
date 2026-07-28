# RustedWax — Development Plan

Working spec for RustedWax — an independent Android app, **not** affiliated with or supported by
scrobble.life, Hive Scrobbler or Web Scrobbler. The upstream extension below is a reference for the
on-chain format and behaviour only.
app/service for Brave Browser. This document is the working spec: what gets copied, what gets
rewritten, in what order, and what must be proven before shipping.

Upstream reference clone (read-only, for line-by-line porting):

```bash
git clone --depth 1 https://github.com/Holozing1/hivescrobble.git reference/hivescrobble
```

---

## 1. Scope

**Goal:** identical on-chain behaviour to the extension for music-shaped content, with local signing
instead of Hive Keychain and OS media sessions instead of DOM connectors.

**In scope (v1)** — narrowed to **YouTube in Brave** (2026-07-22). Other sites and native players are
observed as control cases but never broadcast; they widen once detection is proven.
- Brave Android media-session detection, gated on provable YouTube identity
- Username + posting key entry, on-chain validation, Keystore-backed storage
- Local `custom_json` construction, secp256k1 signing, broadcast with node failover
- 60% / 160% music thresholds, dedup, offline retry queue
- Privacy mode (AES-256-GCM envelope), key-compatible with desktop

**Out of scope (v1)**
- movie / episode kinds (no DOM → no reliable `videoKind`). *Artist verification, the other thing
  desktop's Wikipedia/Wikidata layer provided, landed in Phase 4 as a MusicBrainz check instead.*
- guest (Google / scrobble.life) ingest path
- editing/correcting scrobbles before broadcast

---

## 2. Tech decisions

| Decision | Choice | Why |
| --- | --- | --- |
| Language / UI | Kotlin + Jetpack Compose | The detection layer is entirely platform API; a JS runtime kept alive in a foreground service costs battery and complexity for no gain. |
| Crypto | BouncyCastle (`org.bouncycastle:bcprov-jdk18on`) secp256k1 | Needs RFC-6979 deterministic ECDSA + canonical-signature grinding; BC exposes `HMacDSAKCalculator` directly. |
| Hive RPC | OkHttp + kotlinx.serialization, `condenser_api` | Matches the endpoints the extension already uses. |
| Key storage | `EncryptedSharedPreferences` + Android Keystore, `BiometricPrompt` gate | Raw posting key on device — see §7. |
| Persistence | Room | Dedup ledger + offline broadcast queue must survive process death (extension used `storage.session`; Android needs durable). |
| Min SDK | 26 (Keystore AES-GCM, `MediaSessionManager` stability); target latest | |

### Why not Capacitor / React Native

It would let `hive-scrobbler.ts`, `privacy-cipher.ts` and `posting-key-verify.ts` be copied verbatim,
but the detection layer, the foreground service and the Keystore integration all still need native
Kotlin, and a long-lived JS context in a background service is the worst part of the stack to debug.
The Hive layer is ~1,450 lines of TS total; porting it 1:1 to Kotlin is a bounded, one-time cost.

Everything language-agnostic **is** copied verbatim: payload schema, constants, thresholds, dedup
key formats, node lists, the challenge string, and the wire format of the privacy envelope.

---

## 3. Port map

`reference/hivescrobble/src/...` → `rustedwax-mobile/...`

| Upstream file | LOC | Target | Port |
| --- | --- | --- | --- |
| `core/scrobbler/hive/hive.types.ts` | 51 | `core-hive/model/HiveScrobblePayload.kt` | **Verbatim** — field names are the on-chain contract. Serialize with `@SerialName`, omit nulls. |
| `core/scrobbler/hive/privacy-cipher.ts` | 93 | `core-hive/privacy/PrivacyCipher.kt` | **Verbatim semantics** — AES-256-GCM, 12-byte random IV, wire format `base64(IV‖ct+tag)`. `javax.crypto` with `GCMParameterSpec(128, iv)`. |
| `core/scrobbler/hive/privacy-secret.ts` | 205 | `core-hive/privacy/PrivacySecret.kt` | **Adapt** — drop `signChallenge` (Keychain/tab plumbing); sign `zingit:privacy-key:v1` locally. Keep `CHALLENGE`, `SHA-256(sig bytes)` derivation, per-username caching. |
| `core/scrobbler/hive/posting-key-verify.ts` | 203 | `core-hive/keys/HiveKeys.kt` | **Adapt** — keep base58 decode, `decodeHivePubkey` (STM prefix, 33-byte compressed), `fetchPostingPubkeys` (`condenser_api.get_accounts`, same 3 nodes). Replace pubkey *recovery* with direct derivation from the WIF. |
| `core/scrobbler/hive/hive-scrobbler.ts` | 769 | split, see below | **Adapt** — see §3.1. |
| `core/content/hive-relay.js` | 123 | — | **Dropped.** Replaced by `core-hive/tx/`. |
| `core/storage/options.ts` (privacy keys) | — | `storage/Options.kt` | **Verbatim keys** — `hivePrivacyMusic`, `hivePrivacyVideos`, `hivePrivacyMoviesTv`, `hivePrivacyPodcasts`, `scrobblePercent`. |
| `connectors/*` (406 files) | — | — | **Dropped.** Replaced by `core-detect/`. |
| `core/content/connector.ts` | 1101 | `core-detect/PlaybackTracker.kt` | **Rewritten** — the state machine idea survives (start / progress / finalize); the DOM scraping does not. |
| `core/object/pipeline/*` + `@web-scrobbler/metadata-filter` YouTube rules | ~290 + | `core-detect/pipeline/` | **Phase 3, required** (promoted from Phase 5 by Q5 — see PHASE0.md run 2). Browser metadata gives `ARTIST = "KornVEVO"` (the channel) and `TITLE = "Korn - Trash (Official Audio)"`, so `Artist - Title` splitting and suffix stripping are prerequisites for a correct payload, not polish. `metadata.ts` (Wikipedia/Wikidata) was deferred; Phase 4 covered its artist-verification role with a MusicBrainz check (`enrich/MusicBrainzVerifier.kt`). |

### 3.1 Splitting `hive-scrobbler.ts`

| Upstream member | Target | Notes |
| --- | --- | --- |
| `finalize()` thresholds | `core-hive/ScrobbleRules.kt` | Copy exactly: `txCount = min(2, 1 + floor(max(0, progress - 0.6)))`; `percentPlayed = min(100, round((progress - i) * 100))`. |
| `isFinalized` / `markFinalized` / `pruneOldFinalizedKeys` | `core-hive/DedupLedger.kt` (Room) | Keep the `finalized_<startTimestamp>` key shape and the 6-hour prune. |
| `contentDedupKey` / `hashString` | `DedupLedger.kt` | djb2, `+ 0x4_0000_0000` bias, hour bucket — port as-is even though v1 doesn't scrobble video; the video branch lands later and must dedup identically. |
| payload assembly in `broadcastScrobble` | `core-hive/PayloadBuilder.kt` | Music branch only in v1. Keep `duration` as `"m:ss"`, `timestamp` as ISO-8601 of `startTimestamp * 1000`. |
| `maybeEncryptPayload` / `privacyFlagForKind` | `core-hive/privacy/PrivacyEnvelope.kt` | Envelope `{app, kind, timestamp, private, v: 1}`; **fail closed** — privacy on with no secret means *skip*, never plaintext. |
| Keychain relay call | `core-hive/tx/TransactionSigner.kt` + `HiveRpc.kt` | New — §4. |
| `ingestAsGuest` | — | Dropped. |

---

## 4. The one genuinely new component: local signing

`core-hive/tx/`

```
dynamicGlobalProperties()          → head_block_id, time
  ref_block_num    = head_block_number & 0xFFFF
  ref_block_prefix = LE uint32 at bytes 4..8 of head_block_id
  expiration       = head_block_time + 60s   (chain time, not device time)

tx = { ref_block_num, ref_block_prefix, expiration,
       operations: [["custom_json", {
           required_auths: [],
           required_posting_auths: [username],
           id:   "hive_scrobble_ai",
           json: <compact JSON string>
       }]],
       extensions: [] }

digest = sha256( CHAIN_ID_BYTES ‖ serialize(tx) )
sig    = canonicalSign(digest, postingKey)
POST condenser_api.broadcast_transaction([tx + signatures:[sig]])
```

Notes that cause real bugs if missed:

- **Chain id** for Hive mainnet is `beeab0de00000000000000000000000000000000000000000000000000000000`
  (the old `0000…0000` value is Steem). Put it in one constant with a test.
- **Serialization** is graphene binary, not JSON: varint-prefixed strings/arrays, op id `18` for
  `custom_json`, `expiration` as LE uint32 epoch seconds. Write it once in `TxSerializer.kt` and pin
  it with golden vectors (§6).
- **Canonical signature grinding** — increment a counter into the RFC-6979 extra-entropy until the
  signature is low-S and both `r` and `s` are exactly 32 bytes with no high bit set. This is the same
  loop `dhive`/`hive-js` run; it must match byte-for-byte or the privacy key won't line up (§6).
- **Compact recoverable format**: `byte[0] = 31 + recid`, then `r`(32) ‖ `s`(32), hex-encoded.
- **Node failover** across the same three nodes as `posting-key-verify.ts`, 8s timeout each.
- **RC exhaustion** and `missing required posting authority` are the two errors users will actually
  hit. Surface them verbatim in the UI; don't retry an authority failure.

---

## 5. Detection layer

`core-detect/`

- `RustedWaxNotificationListenerService` — exists only to obtain the Notification Access grant, which
  is the precondition for `MediaSessionManager.getActiveSessions(componentName)`.
- `MediaSessionWatcher` — `addOnActiveSessionsChangedListener`; keeps a `MediaController.Callback`
  per allowed package. Allowlist defaults to `com.brave.browser`, `com.brave.browser_beta`,
  `com.brave.browser_nightly`; native music apps opt-in.
- `PlaybackTracker` — one state machine per controller:
  - track identity = `(packageName, title, artist, album, duration)`; a change finalizes the old track.
  - progress from `PlaybackState.position` + `lastPositionUpdateTime` + `playbackSpeed`, extrapolated;
    poll every 5s only while `STATE_PLAYING` (accumulate real played seconds, don't trust position
    deltas across seeks).
  - `startTimestamp` = unix seconds at first PLAYING — this is the dedup key and the payload timestamp.
  - finalize on: track change, `STATE_STOPPED`, session destroyed, or position ≥ duration.
- `ForegroundService` — `mediaPlayback`/`dataSync` type, low-priority persistent notification,
  battery-optimisation exemption prompt. Nothing here may run on a `WorkManager` periodic job; the
  sampling has to be continuous while playing.

### Source identification (`YouTubeProbe.kt`)

Media sessions give `packageName`, not a site — so a Brave session must be *proven* to be YouTube
before anything is broadcast:

1. A literal `youtube.com/watch?v=<id>` or `youtu.be/<id>` in `METADATA_KEY_MEDIA_URI`.
2. An artwork URI matching `i.ytimg.com/vi/([\w-]{11})` → recovers the video id.
3. Neither → **skip the session.** No fallback to `platform = "brave"`; unattributable data is not
   worth writing to an immutable chain.

**Phase 0 measured rules 1 and 2 as dead for Chromium** — every URI key is unset and artwork arrives
as a raw bitmap. The route that works is rule 4:

4. The page origin from the browser's **media notification sub-text** (`m.youtube.com`), read by the
   notification listener we already hold the grant for. Proves the site, not the video → payload
   carries `platform` but no `url`.

Two rules follow from the measurement (PHASE0.md, run 2):

- **Identity is resolved at finalize time, never at track start.** The notification loses the race
  with the media-session callback by ~300 ms, so an early verdict is made blind.
- **A stale hint is worse than none.** Tie the hint to the track it arrived with; if the browser
  changes tabs mid-playback the origin can be for a different page. Discard hints older than the
  current track's start.

---

## 6. Compatibility gates

These are the tests that decide whether the port is correct. None of them are optional.

| # | Gate | How |
| --- | --- | --- |
| G1 | **Privacy-key parity** | ✅ **Passing.** `HiveVectorsTest` signs `zingit:privacy-key:v1` and asserts the exact 65-byte hex dhive produces, plus the derived `SHA-256` secret. Required replicating *libsecp256k1's* nonce function, not textbook RFC 6979 — see [Rfc6979.kt](app/src/main/java/com/rustedwax/app/hive/Rfc6979.kt). |
| G2 | **Cipher parity** | Phase 4. Encrypt a payload on Android, decrypt it with `privacy-cipher.ts` in Node, and vice versa. |
| G3 | **Serialization parity** | ✅ **Passing.** Serialized-tx hex and signing digest both asserted against dhive output. |
| G4 | **Broadcast** | ✅ **Passing (2026-07-23).** Two scrobbles landed on-chain from the phone. The field-for-field comparison against extension-produced scrobbles caught three divergences (slash escaping, promo-suffix stripping, `kind`) — all fixed in v0.2.2. See PHASE0.md run 3. |
| G5 | **Key rejection** | ✅ **Passing.** Malformed WIFs, bad checksums and public-keys-as-WIFs are all refused; a key not on the account's `posting.key_auths` is rejected at entry, failing closed like `verifyChallengeSignature`. |
| G6 | **Threshold parity** | ✅ **Passing.** `ScrobbleRulesTest` covers the 60% gate, the 160% double-tx boundary, the 2-tx cap and the `min(100, round((progress - i) * 100))` percentages. Also guards the short-item case that ads produce. |

---

## 7. Security requirements

- Posting key: `EncryptedSharedPreferences` (`AES256_GCM`) with a Keystore master key requiring user
  authentication; `setUserAuthenticationRequired(true)` where the device supports it.
- Never log the key, the derived privacy secret, or any signature. Add a lint rule / debug-build check.
- `android:allowBackup="false"`, `android:exported="false"` on everything but the listener service.
- No analytics, no crash reporter that captures memory. If one is added later, scrub `core-hive`.
- Show the account and the key's public key in settings so the user can confirm which key is loaded,
  plus a one-tap "forget key and wipe privacy secret".
- Support the delegated-key pattern in docs (add a dedicated public key to posting authority, revoke
  later) — it's the meaningful mitigation for a raw key on a phone.

---

## 8. Cross-device double-scrobble

New problem: desktop extension and phone both running means two txs for one listen. The extension's
dedup is per-browser `storage.session`.

v1: `DedupLedger` also checks the account's recent `custom_json` ops (last ~15 min, via
`condenser_api.get_account_history` filtered to `hive_scrobble_ai`) for a matching
`(title, artist, hour-bucket)` before broadcasting. Cheap, no server, catches the common case.

v2 (if it proves noisy): an explicit "this device is primary" toggle.

---

## 9. Phases

**Phase 0 — Spike (before any crypto)** — *scaffolded, see [PHASE0.md](PHASE0.md)*
Bare app + notification listener that logs every `MediaMetadata` field Brave emits for: YouTube,
YouTube Music web, Spotify web, SoundCloud, a podcast site, Netflix. Answers the two open questions:
does Brave forward `ART_URI`, and is `DURATION` reliable. Everything downstream depends on this.
Exit criteria and the per-site question matrix live in `PHASE0.md`.

**Phase 1 — Keys** — *done (v0.2.0), except the biometric gate*
WIF parse → derive compressed pubkey → base58/STM encode → compare against on-chain
`posting.key_auths` ([HiveKey.kt](app/src/main/java/com/rustedwax/app/hive/HiveKey.kt),
[KeyValidator.kt](app/src/main/java/com/rustedwax/app/hive/KeyValidator.kt)). Encrypted storage
in [KeyVault.kt](app/src/main/java/com/rustedwax/app/storage/KeyVault.kt), account UI. Gate G5
green. **Biometric gate still outstanding** — see §7.

**Phase 2 — Signing & broadcast** — *done (v0.2.0), pending a real on-chain tx*
[TxSerializer.kt](app/src/main/java/com/rustedwax/app/hive/TxSerializer.kt),
[HiveKey.sign](app/src/main/java/com/rustedwax/app/hive/HiveKey.kt) (RFC-6979 + canonical
grinding), [HiveRpc.kt](app/src/main/java/com/rustedwax/app/hive/HiveRpc.kt) with node failover,
and a manual broadcast button. Gates **G1 and G3 verified** against dhive-generated vectors in
[HiveVectorsTest.kt](app/src/test/java/com/rustedwax/app/hive/HiveVectorsTest.kt) — the Kotlin
signer reproduces dhive's output byte-for-byte, including the privacy-challenge signature Phase 4
depends on. **G4 (a real broadcast landing on-chain) is the one gate still unproven.**

**Phase 3 — Detection & rules** — *done (v0.3.0)*
`ScrobbleRules` (gate G6 green), `DedupLedger`, `BroadcastQueue` with exponential backoff,
`ScrobbleEngine` tying finalize → rules → dedup → sign → broadcast. **This is the first genuinely
usable build.**

**The foreground service was dropped**, and that's a design change worth recording. A
`NotificationListenerService` is already bound and kept alive by the system for as long as the
Notification Access grant is held — and we need that grant regardless, since it's the gate on
`getActiveSessions`. Hosting detection inside it means no persistent notification, no
`foregroundServiceType` declaration, and no exposure to Android 15's `dataSync` runtime cap. It is
also how established Android scrobblers do it. The cost: lifetime is the system's call, so
`onListenerConnected` rebuilds the probe from scratch every time, and the UI observes the
service-owned probe through `ProbeHolder` rather than owning one.

Not carried over from the original plan: **cross-device dedup (§8)**. Desktop and phone running
together will still double-scrobble the same listen.

**Phase 4 — Control, exclusivity, metadata fidelity** — *done (v0.4.0–v0.6.1; see PHASE4.md)*
Stop switch, browser-only watching with per-session hint binding, evidence-layered kind
classification (format evidence > category Music > hard music evidence > non-music category >
contextual words > music vocabulary > weak title shapes > video-by-default), optional address-bar
watcher with track-lifetime id latching, watch-page enrichment, MusicBrainz artist/recording
verification, song-only double-listen, and three-stage video-id recovery — address bar, then the
playlist listing, then a title+channel+duration search — because the bar goes silent for tens of
minutes whenever a playlist advances behind a hidden toolbar.

**Phase 5 — Privacy mode**
`PrivacySecret`, `PrivacyCipher`, `PrivacyEnvelope`, four per-kind toggles matching the extension's
option keys. Gates G1, G2.

**Phase 6 — Polish**
Recent-scrobbles list with tx links, metadata-filter port (`normalize`, `regex-edits`,
`blocked-tags`), manual edit-before-broadcast, RC/authority error surfacing.

**Phase 6b — Native YouTube apps** *(evaluated 2026-07-24, not started)*
Scrobble `com.google.android.youtube` and `…apps.youtube.music` directly, per-app and off by
default. Structurally *easier* than the browser path: the package name is the origin proof, so
notification-hint binding, cross-tab taint and the address-bar watcher are all unnecessary, and
everything downstream of `SessionSnapshot` is already source-agnostic. YouTube Music is the
cheapest win — music by definition, clean artist/title/album metadata. One open measurement: does
the native app publish a video id (`METADATA_KEY_MEDIA_ID` or an `i.ytimg.com/vi/<id>` artwork
URI, both of which `YouTubeProbe` routes 1–2 would consume unchanged)? If not, native scrobbles
lose `url` and category enrichment and fall back to classifier + MusicBrainz — the same position
browser shorts are already in. Also to measure: ad handling inside the session, and whether native
Shorts publish a session at all. No new permissions; the notification-access grant covers it.

**Phase 7 — Stretch**
Platform/URL inference table, cross-device dedup v2, a personal correction list, and — only if
Phase 0 shows enough signal — a `movie`/`episode` path.

*Video-id resolution by title+channel search left this list in v0.6.0, and playlist-based
resolution followed in v0.6.1. Both shipped as part of Phase 4 rather than as stretch work, because
field logs showed a missing `url` was the common case for playlist listening, not an edge case.*

---

## 10. Proposed layout

```
rustedwax-mobile/
├── app/                     # Compose UI: onboarding, settings, recent scrobbles
├── core-detect/             # media session watcher, playback tracker, platform resolver
├── core-hive/
│   ├── model/               # HiveScrobblePayload  (verbatim from hive.types.ts)
│   ├── keys/                # WIF, base58, STM pubkeys  (from posting-key-verify.ts)
│   ├── tx/                  # serializer, signer, RPC, broadcast queue   ← new work
│   ├── privacy/             # secret derivation, AES-GCM cipher, envelope
│   ├── ScrobbleRules.kt     # 60% / 160% / 80%  (from hive-scrobbler.ts#finalize)
│   ├── PayloadBuilder.kt
│   └── DedupLedger.kt
├── storage/                 # EncryptedSharedPreferences, Room, Options.kt
└── reference/hivescrobble/  # upstream clone, gitignored, read-only
```

---

## 11. Open questions

1. Does Brave Android populate `METADATA_KEY_ART_URI`, and does it survive tab backgrounding?
   *(Phase 0 answers this; the whole `url`/`platform` story depends on it.)*
2. Does Keychain's `requestSignBuffer` grinding match `dhive`'s exactly? *(G1. If not, privacy mode
   needs a versioned `v: 2` derivation for mobile-originated blobs and desktop needs to accept both.)*
3. Should the app read `scrobblePercent` from the extension's options semantics, or hardcode 60%?
   Leaning: expose it, default 60, keep the storage key name.
4. Distribution — F-Droid, GitHub releases, or Play? Play's stance on `NotificationListenerService`
   for non-assistant apps is a review risk worth checking before Phase 3.
