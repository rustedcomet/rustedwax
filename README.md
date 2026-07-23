# RustedWax

An independent Android app that writes what you listen to on your phone to the Hive blockchain.

> ### Not affiliated with scrobble.life
>
> **RustedWax is a personal, unofficial project.** It is not part of the
> [scrobble.life](https://scrobble.life) project, not built by them, not endorsed by them, and not
> supported by them. It is not affiliated with Hive Scrobbler or Web Scrobbler either.
>
> It writes the same `custom_json` format those projects read, so entries it posts show up alongside
> theirs — but that is the whole of the relationship. **Do not report RustedWax problems to
> scrobble.life or to the Hive Scrobbler maintainers.** Anything broken here is broken here.

## Why it exists

Brave on Android supports no extensions, so the desktop flow — content script → Hive Keychain →
`custom_json` — cannot run on a phone at all. RustedWax replaces the two pieces that depended on the
browser being a desktop browser:

| Desktop extension | RustedWax |
| --- | --- |
| Per-site DOM connectors | Android `MediaSessionManager` — one universal source |
| Hive Keychain signs the transaction | The app signs locally with your posting key |

The on-chain format, the scrobble thresholds and the dedup rules follow the established ones, so
entries written from your phone look the same on-chain as entries written from a desktop.

> **v1 scope: YouTube in Brave.** A media session names the app, never the site, so the app only
> scrobbles playback it can *prove* is YouTube. Anything it can't prove is skipped rather than
> guessed — wrong attribution on an immutable chain is worse than a missing scrobble. More sites
> follow once the detection story is settled.

---

## How it works

1. You grant the app **Notification Access**. It's required twice over: Android gates
   `MediaSessionManager.getActiveSessions()` behind it, and the browser's media notification is the
   only place the page's origin (`youtube.com`) appears.
2. The notification listener — which the system keeps alive for as long as the grant is held — hosts
   the session watcher. No foreground service, no persistent notification.
3. When the browser plays media, the OS media session exposes title, artist, duration and playback
   position. The app accumulates real played time against the same thresholds as desktop.
4. When a track ends, the app builds the same `custom_json` payload, signs it with your posting key
   on-device, and broadcasts it. If the network is down, the scrobble is queued and retried.

```
Brave (playing) ──▶ Android MediaSession ──┐
                                           ├──▶ RustedWaxNotificationListenerService
       media notification (origin) ────────┘              │
                                                   PlaybackTracker
                                                          │  track ends
                                                   ScrobbleRules (60% / 160%)
                                                          │
                                                   DedupLedger
                                                          │
                                          HiveScrobblePayload  ◀── same schema as extension
                                                          │
                                          local secp256k1 signing (posting key)
                                                          │
                                          condenser_api.broadcast_transaction
                                                          │  on failure
                                                   BroadcastQueue ──▶ retry w/ backoff
```

## On-chain format

Identical to the extension — do not change these without changing the indexers:

- `custom_json` id: `hive_scrobble_ai`
- authority: `required_posting_auths: [<username>]`
- `app`: `hivescrobblesai/1.0`
- Payload: [`HiveScrobblePayload`](https://github.com/Holozing1/hivescrobble/blob/master/src/core/scrobbler/hive/hive.types.ts)

Scrobble rules, copied verbatim from `hive-scrobbler.ts#finalize`:

- **Music / podcast / video** — 1 tx at ≥60% played; a 2nd tx at ≥160% (double-listen), capped at 2.
- **Movie / episode** — 1 tx at ≥80%. *Not implemented on mobile* (see Limitations).
- `now_playing` is never broadcast on-chain.

## Setup

1. Install the APK.
2. Enter your **Hive username** and **posting key** (WIF, starts with `5…`).
   The app derives the public key and checks it against your account's on-chain `posting.key_auths`
   before accepting it — a wrong key is rejected immediately, offline-verifiable against
   `api.openhive.network`, `hive-api.arcange.eu`, `api.hive.blog`.
3. Grant Notification Access when prompted.
4. Play something in Brave. The app's Recent tab shows detected tracks and broadcast tx ids.

### About your posting key

The key is stored in `EncryptedSharedPreferences` backed by the Android Keystore and is gated behind
biometric/device unlock. It never leaves the device — signing is local, and only the signed
transaction is sent to a Hive node.

Be aware this is a **larger blast radius than Keychain on desktop**: a raw posting key can post,
comment, and vote as you, with no per-operation prompt. If you want that reduced:

- Generate a fresh keypair, add its public key to your account's posting authority (from desktop
  Keychain / Hive Blog → Wallet → Permissions), and give the app *that* key. You can revoke it later
  without rotating the posting key you use everywhere else.

The app never asks for your active, owner, or memo key. If anything ever does, it isn't this app.

## Privacy mode

Same scheme as the extension, per content kind (music / videos / podcasts / movies-tv). When enabled,
only `app`, `kind` and `timestamp` stay public; everything else goes into a base64
`IV‖ciphertext+tag` AES-256-GCM blob under `private`, with `v: 1`.

The AES key is derived exactly as on desktop — `SHA-256(signature bytes)` where the signature is a
posting-key signature over the fixed challenge `zingit:privacy-key:v1`. Because Hive's ECDSA is
deterministic (RFC 6979 + canonical grinding), the phone derives the **same key** the extension does,
with no handoff and no Keychain. Blobs written on mobile decrypt on desktop and on zingit-web.

## Limitations vs the desktop extension

These follow from having no DOM access, not from missing work:

- **Only what can be proven.** `url` and `platform` come from a YouTube video id recovered out of the
  artwork URI (`i.ytimg.com/vi/<videoId>/…`) or a literal watch URL in the metadata. No evidence
  means no scrobble.
- **No movie/episode scrobbles.** `videoKind` detection, Wikipedia/Wikidata enrichment, IMDb ids and
  season/episode numbers all came from the connector layer. Media sessions expose none of it.
- **No podcast disposition** unless the media session says so; most web podcasts arrive as `song`.
- **Metadata quality depends on the site.** Whatever the page passes to the MediaSession API is what
  you get. Sites that don't set MediaSession metadata are invisible to the app.
- **No guest (Google) ingest path.** Mobile is Hive-only by design.

## Bonus over desktop

Because it listens at the OS level, the app also sees **native apps** — Spotify, YouTube Music, Poweramp,
podcast players — which the extension can never reach. Off by default; enable per-app in settings.

## Development

See [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md) for the phased build plan, the file-by-file port map
from the extension, and the compatibility test vectors that must pass before shipping.

## License

MIT, matching the upstream project.
