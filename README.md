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

The on-chain format, the scrobble thresholds and the dedup rules follow the established ones.

> **Not byte-identical to desktop any more.** Phase 4 normalizes titles to the original recording —
> `(Live)`, `(Instrumental)` and `【Guitar Cover】` are stripped, so a cover lands on the same entry
> as the studio track instead of scattering play counts. The extension keeps those markers. Same
> schema, same id, same rules; the title field can differ. See [PHASE4.md](PHASE4.md) decision D7.

> **v1 scope: YouTube in Brave, exclusively.** A media session names the app, never the site, so the
> app only scrobbles playback it can *prove* is YouTube. Anything it can't prove is skipped rather
> than guessed — wrong attribution on an immutable chain is worse than a missing scrobble. Media
> sessions from anything that isn't a target browser are not watched at all.

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

**Stop** cuts all of that off. It tears down the session watcher, stops reading notifications and
forgets the hints it had — nothing is observed while it's stopped, and the track playing when you
press it is discarded rather than scrobbled on the way out. Scrobbles already earned and waiting in
the offline queue still send. Automatic scrobbling is a separate, inner switch: turning *it* off
leaves the app watching, which is how you check what a title would have parsed to without writing
anything to the chain.

```
Brave (playing) ──▶ Android MediaSession ─────┐
       media notification (origin) ───────────┼──▶ RustedWaxListenerService
       address bar (optional, url + video id) ┘        │  SessionProbe
                                                       │  track ends (video id latched at start)
                                               ScrobbleRules (60%, +160% songs only)
                                                       │
                                     enrichment (optional): watch-page category,
                                     description credits, MusicBrainz verification
                                                       │
                                     MusicClassifier → kind: song / video
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

Scrobble rules, from `hive-scrobbler.ts#finalize`, with one deliberate deviation:

- **Music / podcast / video** — 1 tx at ≥60% played.
- **Songs only** — a 2nd tx at ≥160% (a genuine double-listen), capped at 2. Upstream doubles every
  kind; RustedWax caps `video` at one tx, because YouTube shorts auto-loop and were producing two
  transactions for one sitting.
- **Movie / episode** — 1 tx at ≥80%. *Not implemented on mobile* (see Limitations).
- `now_playing` is never broadcast on-chain.

### How `kind` is decided

Evidence, strongest first — a stronger layer always beats a weaker one:

1. **YouTube's own category** (needs "Look videos up"): `Music` → song; Film & Animation, Gaming,
   News, Sports, Education… → video. An *ambiguous* category (Entertainment, People & Blogs) doesn't
   decide, but it raises the bar: only explicit evidence below can make the entry a song.
2. **Blocklist and structure** → video: podcast/tutorial/gameplay/review-shaped titles, trailers
   recognized structurally (`Official Final Trailer`, `Trailer 2`), clip channels (`… Movies`,
   `… Cinema`, `… Pictures`), game playthroughs without an instrument.
3. **Explicit music evidence** → song: music.youtube.com, a VEVO/`- Topic`/label channel, a
   **MusicBrainz match** on the parsed artist + recording, an instrument-qualified cover or
   playthrough, lyrics/instrumental/remix/live-performance vocabulary.
4. **Weak evidence** — an `Artist - Track`-shaped title — is accepted only for ordinary videos of
   unknown category, never for shorts, sub-90-second clips, or `#shorts`-tagged titles.
5. **Default** → song, for ordinary-length watch-page videos with no signal at all.

The Now tab shows **kind because**, **category** and **musicbrainz** lines explaining every verdict
before anything goes on-chain — check them there, because on-chain is forever.

## Setup

1. Install the APK.
2. Enter your **Hive username** and **posting key** (WIF, starts with `5…`).
   The app derives the public key and checks it against your account's on-chain `posting.key_auths`
   before accepting it — a wrong key is rejected immediately, offline-verifiable against
   `api.openhive.network`, `hive-api.arcange.eu`, `api.hive.blog`.
3. Grant Notification Access when prompted.
4. Optionally enable **Address bar access** (for `url` + lookups) — on Android 13+, allow restricted
   settings from App info first.
5. Play something in Brave. The **Now** tab shows the live session and the exact payload it would
   broadcast; **History** shows what was sent, with tx ids.

### About your posting key

The key is stored in `EncryptedSharedPreferences` backed by the Android Keystore. **There is no
biometric gate yet** — an unlocked phone can sign. The key never leaves the device — signing is
local, and only the signed transaction is sent to a Hive node.

Be aware this is a **larger blast radius than Keychain on desktop**: a raw posting key can post,
comment, and vote as you, with no per-operation prompt. If you want that reduced:

- Generate a fresh keypair, add its public key to your account's posting authority (from desktop
  Keychain / Hive Blog → Wallet → Permissions), and give the app *that* key. You can revoke it later
  without rotating the posting key you use everywhere else.

The app never asks for your active, owner, or memo key. If anything ever does, it isn't this app.

## Privacy mode — planned, not yet implemented

**Every payload the app broadcasts today is plaintext on-chain.** Privacy mode is Phase 5 (it was
originally Phase 4; the control/exclusivity/fidelity work preempted it — see PHASE4.md).

The planned design matches the extension, per content kind (music / videos / podcasts / movies-tv):
only `app`, `kind` and `timestamp` stay public; everything else goes into a base64
`IV‖ciphertext+tag` AES-256-GCM blob under `private`, with `v: 1`. The AES key derives as on
desktop — `SHA-256` of a posting-key signature over the fixed challenge `zingit:privacy-key:v1` —
and because Hive's ECDSA is deterministic, the phone will derive the **same key** the extension does,
so blobs written on mobile will decrypt on desktop and on zingit-web.

## Limitations vs the desktop extension

These follow from having no DOM access, not from missing work:

- **Only what can be proven.** `platform` requires the origin to be established — from the browser's
  media notification, bound to a session by title, or from the address bar if you enabled that. No
  evidence means no scrobble. `url` additionally needs a video id, which only the address-bar
  watcher can supply.
- **No movie/episode scrobbles.** `videoKind` detection, IMDb ids and season/episode numbers all
  came from the connector layer. Media sessions expose none of it. (Artist verification, which
  desktop gets from Wikipedia/Wikidata, is covered on mobile by the MusicBrainz check instead.)
- **No podcast disposition** unless the media session says so; most web podcasts arrive as `song`.
- **Metadata quality depends on the site.** Whatever the page passes to the MediaSession API is what
  you get. Sites that don't set MediaSession metadata are invisible to the app.
- **No guest (Google) ingest path.** Mobile is Hive-only by design.

## Address bar access (optional)

Off by default. The media notification tells the app the origin but never which video, so without
this there is no `url` on any scrobble and no way to look a video up.

Enabling it lets the app read the address bar in Brave and Chrome, which gives the exact site and —
when the browser exposes the full URL rather than just the host — the video id. The service is
pinned to those browser packages in its config, so the **system** prevents it seeing any other app;
that isn't a promise made by app code. It reads one thing: the URL bar.

Two caveats worth knowing before you turn it on:

- The address bar describes the **foreground tab**, which isn't always the tab that's playing. So a
  YouTube URL only settles identity when the notification agrees or there's a single session, and a
  non-YouTube URL is never treated as evidence against a session — it may just be another tab.
- On Android 13+ a sideloaded APK's accessibility toggle is greyed out until you allow it: **App
  info → ⋮ → Allow restricted settings**. It looks broken rather than blocked.

### Looking videos up

With a video id available, the app can fetch the video's own page to read YouTube's category — a far
better answer to song-vs-video than any title heuristic — and to find the original artist credited in
the description of a cover. One GET to youtube.com per new video, cached, from outside the browser.
Google already knows you watched it, but it is still off-device traffic, so it's a switch you can
turn off. It never blocks or delays a scrobble; if it fails, the on-device parse stands.

The video id is **latched when the track is first identified** and kept for the track's lifetime —
the address bar is correct at track start and routinely stale by track end (it shows the *next*
short by then). The fetched page's own title must match what the session is playing, or the id is
discarded: no `url` beats a wrong `url`.

> This scrapes an undocumented blob out of the watch page and **will** break when YouTube changes it.
> Failures are logged as `EXTRACTION FAILED` precisely so breakage is distinguishable from a video
> that simply had nothing to add.

### MusicBrainz verification

The same switch also checks the parsed artist/track pair against [MusicBrainz](https://musicbrainz.org),
the open music database. A match requires the artist name **and** the recording title to both agree —
title-only matching would claim every clip named like some song. A confirmed match:

- counts as **explicit music evidence** (it can qualify a short performance clip that has no music
  words in its title), and
- supplies the **canonical spelling** of artist and title, so entries line up with scrobbles of the
  same recording from anywhere else.

Small or unsigned artists simply aren't in the database — that's a missed upgrade, never a wrong
one; classification proceeds as if the check hadn't run. Lookups honor MusicBrainz's 1-request/second
etiquette and are cached (including "no match") so each track is asked about once.

## Development

See [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md) for the phased build plan, the file-by-file port map
from the extension, and the compatibility test vectors that must pass before shipping.

## License

MIT, matching the upstream project.
