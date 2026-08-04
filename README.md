# RustedWax

> **Specification status:** [BEHAVIOR_CONTRACT.md](BEHAVIOR_CONTRACT.md) contains
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

> ### Prototype status and YouTube access
>
> RustedWax is currently a personal concept-testing project, not a policy-cleared release. Its
> optional **Look videos up** feature automatically fetches YouTube watch, playlist and search pages
> and calls an undocumented YouTube Music player endpoint. That is technically useful and deliberately
> retained during prototyping, but it relies on unsupported interfaces, can break without notice, and
> conflicts with YouTube's written restrictions on automated access, scraping and undocumented APIs.
> Personal or non-commercial use does not create an exception to those terms.
>
> A YouTube entry now requires a verified video id and canonical hyperlink. The exact id can come
> from optional address-bar evidence without a lookup; when the browser exposes only a host, recovery
> uses the unsupported playlist/search/watch-page path above. If neither source verifies an id, the
> viewing stays off-chain and appears in **Not logged**. A distribution candidate should replace or
> remove the unsupported lookup path and ship with an appropriate privacy policy. See
> [YouTube policy and prototype tradeoff](#youtube-policy-and-prototype-tradeoff).

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

> **v1 scope: YouTube in Brave and Chrome, exclusively.** A media session names the app, never the
> site, so the app only scrobbles playback it can *prove* is YouTube. Anything it can't prove is
> skipped rather than guessed — wrong attribution on an immutable chain is worse than a missing
> scrobble. Media sessions from anything that isn't a target browser are not watched at all.

---

## How it works

1. You grant the app **Notification Access**. It's required twice over: Android gates
   `MediaSessionManager.getActiveSessions()` behind it, and the browser's media notification is the
   default source for the page's origin (`youtube.com`) when optional Browser evidence access is off.
2. The notification listener — which the system keeps alive for as long as the grant is held — hosts
   the session watcher. No foreground service, no persistent notification.
3. When the browser plays media, the OS media session exposes title, artist, duration and playback
   position. The app accumulates content played against the configured threshold (60% by default).
4. When a track ends, the app first requires one verified YouTube video id, builds the same
   `custom_json` payload with its canonical watch link, signs it with your posting key on-device,
   and broadcasts it. Before calling it a scrobble, it normally confirms that a
   healthy independent node has included it in a block. A transaction seen relaying in an
   independent healthy node's mempool is reported separately and is not retried, to avoid creating
   a permanent duplicate. Acceptance with no available confirmation is also reported separately; see
   [Why a scrobble is confirmed, not assumed](#why-a-scrobble-is-confirmed-not-assumed).
   Definite failures and offline sends are queued.
5. When it *doesn't* broadcast, the reason lands in the **Not logged** tab. A scrobbler that
   silently declines things is indistinguishable from a broken one.

**Stop** cuts all of that off. It tears down the session watcher, stops reading notification and
address-bar callbacks, and clears notification, URL, playlist, and carried-progress evidence —
nothing is observed while it's stopped, and the track playing when you press it is discarded rather
than scrobbled on the way out. Scrobbles already earned remain in the
offline queue and are eligible to send the next time the queue is flushed. Automatic scrobbling is
a separate, inner switch: turning *it* off leaves the app watching, which is how you check what a
title would have parsed to without writing anything to the chain.

```
Brave (playing) ──▶ Android MediaSession ─────┐
       media notification (origin) ───────────┼──▶ RustedWaxListenerService
       browser evidence (optional: url, id, ad UI) ┘   │  SessionProbe
                                                       │  track ends (last active evidence frozen)
                                          prefilter — reject what no lookup could rescue,
                                          so a shorts feed doesn't fetch per finalize
                                                       │
                                     verified-id recovery when needed, then
                                     enrichment (optional): YouTube Music catalogue,
                                     watch-page category + length + description
                                     credits, MusicBrainz
                                                       │
                                     ScrobbleRules (configured %, +160% songs only, length floor)
                                                       │
                                     MusicClassifier → kind: song / video
                                                       │
                                          DedupLedger + MutedVideos
                                                       │
                                       HiveScrobblePayload  ◀── same schema as extension
                                                       │
                                       local secp256k1 signing (posting key)
                                                       │
                                  broadcast to a node proven current,
                                  then confirm the tx reached a block
                                                       │  not confirmed / rate-limited
                                                BroadcastQueue ──▶ retry w/ backoff
```

## On-chain format

Identical to the extension — do not change these without changing the indexers:

- `custom_json` id: `hive_scrobble_ai`
- authority: `required_posting_auths: [<username>]`
- `app`: `hivescrobblesai/1.0`
- Payload: [`HiveScrobblePayload`](https://github.com/Holozing1/hivescrobble/blob/master/src/core/scrobbler/hive/hive.types.ts)

Scrobble rules, from `hive-scrobbler.ts#finalize`, with one deliberate deviation:

- **Song / video** — 1 tx at the configured threshold (60% by default). Mobile
  has no dedicated podcast payload path.
- **Songs only** — a 2nd tx at ≥160% (a genuine double-listen), capped at 2. Upstream doubles every
  kind; RustedWax caps non-song kinds and every `/shorts/` viewing at one tx. The path cap matters
  even when a music Short is correctly classified as `song`, because the browser still auto-loops it.
- **Movie / episode** — 1 tx at ≥80%. *Not implemented on mobile* (see Limitations).
- **Minimum length: 30 s**, or **10 s for a verified short**. Not upstream — added because YouTube
  pre-roll ads publish their own media session carrying the *video's* title and a ~6-second
  duration, which would otherwise scrobble the song on every ad. Pre-roll is a watch-page
  phenomenon, and field data confirmed the floor was rejecting only shorts and never a watch-path
  track, so shorts get their own floor. See "Short clips" below.
- **A continuous looping video still produces at most one scrobble.** An observed playback-position
  reset from the final 20% of an item to its first 20% is logged as a detected loop and caps that
  continuous viewing to one transaction even when its payload kind is `song`. Progress above 125%
  on a verified short remains a fallback probable-loop diagnostic. Neither signal erases the
  qualifying first viewing. A separate later session earns its own new
  scrobble; the inherited ≥160% branch only applies when no loop/reset evidence
  was observed.
- **Progress is measured in content, not clock time.** Played time is scaled by the playback rate,
  because it's compared against `duration`. Watching a 76-second trailer to the end at 1.25× is
  100%, not the 67% of wall-clock that elapsed — and at 2× a fully-watched video used to read 50%
  and never scrobble at all.
- `now_playing` is never broadcast on-chain.

### Artist and title

`Artist - Track` splitting is a **music** operation — it asserts the text left of the dash names a
performer. So it only runs for `kind: song`. For a `kind: video` entry the **channel is the artist**
and the **whole title is the title**, unsplit.

That distinction is not cosmetic. Before v0.8.0 a film trailer went on-chain as
`artist: "Fall 2: Deadpoint (2026) Official Trailer 2"` with `title: "Harriet Slater, Arsema
Thomas"` — a film name in the artist field and a cast list in the title.

A trailing run of hashtags is stripped from either kind (`Rüyamda seni gördüm #dizi #blutv` →
`Rüyamda seni gördüm`). Interior hashtags are kept, because `Song #2 of the series` is doing work.
Beyond tidiness: the tag run used to be part of the title, so the same clip reposted with different
tags counted as a different listen and landed twice.

Other shapes the parser understands, several adapted from the desktop extension: `Artist "Track"`,
`Track (by Artist)`, a leading `[genre]` tag as noise, and album/vinyl track numbers (`03.`, `A1.`).
A leading **CJK** bracket is the opposite — `【Bring Me The Horizon】…` names the *artist*, which is
where the guitar-cover bug that started Phase 4 was fixed.

`album` is populated for Art Tracks, read from the fixed shape of an auto-generated description
(`Provided to YouTube by …` / `Song · Artist` / `Album`). Never guessed from a hand-written
description, and never set on a video.

### How `kind` is decided

Evidence, strongest first — a stronger layer always beats a weaker one:

0. **Auto-generated provenance** → song, above every title rule: music.youtube.com, a `- Topic`
   channel, or a description beginning *"Provided to YouTube by …"*. These are distributor feeds —
   the title is catalogue metadata, not a human description — so title heuristics don't apply.
   It's why a game soundtrack's track called `Tutorial` or `Trailer 2` stays music.
0b. **A title that is only hashtags** → video, whatever the category says. `#guitar #dubstep #fnaf`
   names no work, and a song entry that names no track is permanent playlist debt. Below real
   provenance because a distributor feed's title is catalogue metadata and never looks like this.
1. **Format evidence** → video, beating even the category (uploaders do categorize tutorials and
   music-news bulletins as *Music*): podcast / how-to / gameplay / review titles, `Official
   Trailer`, music-news headlines (`… Releases New Single …`), TV episode numbering
   (`Season 6 Ep 19`, `S06E19`, `11x24` — but not music's `EP 2`, nor `1920x1080`/`16x9`),
   clip channels (`… Movies`, `… Cinema`),
   game playthroughs without an instrument.
2. **The YouTube Music catalogue** (needs "Look videos up") → song. Keyed by video id, so it
   answers where a string-matched lookup can't — most Spanish-language and small-channel uploads
   in field testing. Positive-only: indie, live and personal-channel uploads simply aren't in the
   catalogue, so absence is never evidence *against* music.
   `MUSIC_VIDEO_TYPE_PODCAST_EPISODE` is explicitly excluded: being present in YouTube Music does
   not turn a podcast—or a timer mislabelled as one—into a song.
3. **YouTube's `Music` category** (needs "Look videos up") → song.
4. **A MusicBrainz match** on artist + recording → song.
5. **Commentary words that are also song titles** (`reaction`, `tutorial`, `interview`, `episode`,
   `Trailer 2`) → video — below provenance and MusicBrainz on purpose, so "Chain Reaction" and a
   soundtrack's "Tutorial" are rescued while the video formats are caught.
6. **Cover / playthrough vocabulary** → song, when instrument-qualified. Below the words above,
   because "Bass Cover Tutorial" is a tutorial about a cover, not a cover.
7. **A decisive non-music category** → video: Film & Animation, Gaming, News, Sports, Education…
   then **music vocabulary** → song: VEVO/label channels, lyrics / instrumental / remix /
   live-performance / official-audio wording.
8. **Weak evidence** — an `Artist - Track`-shaped title — is accepted only for ordinary videos of
   unknown category, never for shorts, sub-90-second clips, `#shorts`-tagged titles, three-part
   clip captions (`Blade II | Sewers of the Damned | ClipZone…`), **never above 8 minutes** without
   a positive music signal, and never when a known category said not-music. The long-form gate
   matters as much as the short one: a 45-minute podcast titled `Host - Guest Name` is not a song.
9. **No evidence at all** → **video**. (Revised from the original song-default: two days of field
   data showed every default-song hit was a news clip, movie scene or vlog. Real music virtually
   always carries a signal above — and MusicBrainz is the safety net for untagged uploads.)

The Now tab shows **kind because**, **category**, **yt music**, **musicbrainz** and **listed** lines
explaining every verdict
before anything goes on-chain — check them there, because on-chain is forever.

## Setup

1. Install the APK.
2. Enter your **Hive username** and **posting key** (WIF, starts with `5…`).
   The app derives the public key and checks it against your account's on-chain `posting.key_auths`
   before accepting it — a wrong key is rejected immediately, offline-verifiable against
   a current healthy Hive node.
3. Grant Notification Access when prompted.
4. Optionally enable **Browser evidence access** (for `url`, lookups, and exact visible YouTube ad
   labels) — on Android 13+, allow restricted settings from App info first.
5. Play something in Brave or Chrome. The **Now** tab shows the live session and the exact payload
   it would broadcast; **History** shows the newest 50 results for the current app process, with tx
   ids. History is diagnostic memory, not permanent local storage; the chain remains authoritative.

The Now tab's **Broadcast this scrobble** button applies the same configured threshold, duration
floors, verified-short gate, mute list, kind cap, and dedup ledger as automatic finalization. The
Account tab's synthetic broadcast remains a separate transport test; it does not claim to represent
a watched video.

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
  evidence means no scrobble. Every entry additionally needs a video id, supplied exactly by the
  address-bar watcher or recovered through the playlist/search/watch-page resolver. Failure to
  verify one is a visible refusal, never a URL-less transaction.
- **No movie/episode scrobbles.** `videoKind` detection, IMDb ids and season/episode numbers all
  came from the connector layer. Media sessions expose none of it. (Artist verification, which
  desktop gets from Wikipedia/Wikidata, is covered on mobile by the MusicBrainz check instead.)
- **No dedicated podcast payload path.** Podcast-shaped titles are classified as `video`; Android
  media sessions do not provide the richer disposition the desktop connector uses.
- **Metadata quality depends on the site.** Whatever the page passes to the MediaSession API is what
  you get. Sites that don't set MediaSession metadata are invisible to the app.
- **No guest (Google) ingest path.** Mobile is Hive-only by design.

## Browser evidence access (optional)

Off by default. The media notification tells the app the origin but never which video. Browser
evidence is the preferred exact source; without it, **Look videos up** can still attempt recovery
from title, channel and duration. If both routes fail, no YouTube scrobble is broadcast.

Enabling it lets the app read the address bar in Brave and Chrome, which gives the exact site and —
when the browser exposes the full URL rather than just the host — the video id. While the current
page is an identified YouTube `/shorts/` item, it also looks for a small exact-match list of visible
YouTube ad controls such as `Sponsored`, `Ad`, or `Skip ad` (including supported Spanish and
Portuguese labels). It does not classify brand names, titles, or arbitrary page text as ads.

The service is pinned to those browser packages in its config, so the **system** prevents it seeing
any other app; that isn't a promise made by app code. Unmatched page text is not stored. A matched
label is kept only as local evidence against the current video id and is written to the diagnostic
log; it is never included in the Hive payload.

**v0.8.11 transition protection:** the Shorts URL can advance before the old ad
overlay disappears. Log 14 captured a new organic id and the preceding ad's
stale `Sponsored` label in one accessibility snapshot. URL changes now receive
track generations; a label first seen in a transition generation is provisional
until re-observed for that same instance. Another URL, label disappearance,
Stop, or package reset clears it. Accepted evidence travels with the track.

Two caveats worth knowing before you turn it on:

- The address bar describes the **foreground tab**, which isn't always the tab that's playing. So a
  YouTube URL only settles identity when the notification agrees or there's a single session, and a
  non-YouTube URL is never treated as evidence against a session — it may just be another tab.
- On Android 13+ a sideloaded APK's accessibility toggle is greyed out until you allow it: **App
  info → ⋮ → Allow restricted settings**. It looks broken rather than blocked.

### Looking videos up

With a video id available, the app can ask two things about it, cached, from outside the browser.
These are additional requests from the app, separate from the browser session: they disclose the
phone's network address and exact video id to Google even when the browser was signed out, blocking
trackers, or using different cookies. It is therefore a visible switch. Neither metadata lookup is
required for a video whose id was already captured, but a verified id is required before any
YouTube payload can be broadcast.

**The watch page** (one GET) gives YouTube's category — a far better answer to song-vs-video than any
title heuristic — the video's length, whether it is publicly listed, and the description, where cover
uploads credit the original artist and Art Tracks name the album.

**The YouTube Music catalogue** (one small POST to `music.youtube.com`) gives `musicVideoType`: does
YouTube Music hold this video as a recording? Adapted from the desktop extension, and the strongest
music signal available, because it is keyed by **video id** rather than by a parsed artist/track
string — it answers for the Spanish-language and small-channel uploads MusicBrainz returns `no match`
for. Read positive-only: absence is not evidence against music.

On an **Art Track** (`MUSIC_VIDEO_TYPE_ATV`, what a `- Topic` channel serves) it also gives canonical
credits — `Daddy Yankee` / `Con Calma` instead of whatever the uploader typed. On an official music
video it deliberately does *not*: there the "author" is just the channel, so a guitar cover would
come back as `Elena Verrier` / `Metallica - Blackened (guitar cover)` when the title parse already
yields `Metallica` / `Blackened`.

At ~10 KB against ~615 KB for the watch page, it is also the fallback when the page fetch fails —
which happened on ~12% of ids in field testing, and is how a stale video id once reached the chain
with the wrong `url`.

The video id is **latched when the track is first identified** and kept for the track's lifetime —
the address bar is correct at track start and routinely stale by track end (it shows the *next*
short by then). The latch is then corroborated two independent ways: the fetched page's own title
must match what the session is playing, **and** its length must agree with the session's duration.
Either disagreeing discards the id, because no `url` beats a wrong `url`. A rejected live id cannot
be returned merely because no older latch exists.

When Chrome removes the media session, the app freezes the ended track's identity before opening
the one-minute continuation window. Any replacement that claims the carried progress also claims
that frozen identity. If no replacement arrives, expiry finalizes the frozen value — it never
consults the then-current address bar, which may already name a later Short.

Both checks are needed because either can be unavailable. The title is the stronger signal but
absent whenever the fetch failed — and that is exactly how a playlist track once went on-chain
linking to the *previous* entry: the bar was 7 seconds late, the page fetch for the stale id had
timed out, so there was no title to compare. The durations were 226 s against 193 s.

When the bar never names the video at all — a playlist advancing behind a hidden toolbar fires no
accessibility event, so it can stay silent for tens of minutes — the app recovers the id two more
ways, in order:

1. **The playlist listing.** If the bar ever mentioned a `list=`, the app remembers that playlist for
   three hours and fetches its page once. That listing names every track with its exact video id,
   title, channel and length, so the entry being played is identified precisely — and every
   remaining track in the playlist is then free.
2. **A YouTube search** for the session's title and channel, when there's no playlist or the track
   isn't in the part of it that loaded. The resolver understands ordinary result cards and the
   current Shorts lockup shape. Since a Shorts card omits channel and duration, up to eight
   highest-ranked plausible ids are checked against their own watch pages before one can qualify.

The playlist is tried first because it is *exact* where search is only plausible: searching for one
tested track returned a different upload of the same song by the same artist at the same length,
which no amount of matching strictness can separate. Either way the match must agree on title,
duration and channel, and multiple matching ids are refused as ambiguous. Anything less certain is
recorded in **Not logged** and never reaches the chain. URL-less YouTube entries are no longer a
supported fallback.

> This scrapes an undocumented blob out of the watch page and **will** break when YouTube changes it.
> Failures are logged as `EXTRACTION FAILED` precisely so breakage is distinguishable from a video
> that simply had nothing to add.

### YouTube policy and prototype tradeoff

The lookup path is an explicit prototype compromise, not a claim of YouTube compliance:

- watch, playlist and search metadata is extracted from YouTube web-page internals;
- the music verdict comes from the undocumented `youtubei/v1/player` endpoint with a `WEB_REMIX`
  client identity; and
- the fetches use a desktop browser user-agent because the mobile shell does not contain the same
  data.

YouTube's Terms of Service prohibit automated access such as scraping without prior written
permission, and its API Developer Policies separately prohibit scraping YouTube applications and
using undocumented APIs without express permission. The app does not download media, extract audio,
alter playback, automate engagement or block ads; the concern is the metadata-access method itself.

This behavior is retained for concept testing because category, listed status, catalogue membership
and fallback id recovery materially improve fidelity. Before broader distribution, the intended
compliant direction is to measure what the native YouTube/YouTube Music media sessions expose, keep
the Android-only best-effort path, and remove or replace unsupported lookups. Turning **Look videos
up** off exercises that conservative path today.

Official references, checked 2026-07-30:
[YouTube Terms of Service](https://www.youtube.com/static?template=terms) and
[YouTube API Services Developer Policies](https://developers.google.com/youtube/terms/developer-policies).

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

### Short clips

A **verified** YouTube short scrobbles from 10 seconds instead of 30. Verified means three things:
the address bar showed a `/shorts/` path, the video resolved on its own watch page, and that page
said the video is **publicly listed**. Because that proof comes from the two switches above, this
one greys out unless both are on — it would otherwise be a setting that silently does nothing.

The `/watch` path keeps the 30-second floor and uses the same configured progress threshold.

Why proof instead of a length rule: an ad and a real 12-second clip are the same length, so length
can't tell them apart. The first version of this guard stopped at "the page resolved", on the
assumption that ad creatives have no public watch page — they do, and an 18-second shorts-feed ad
reached the chain. What actually separates them is that an ad creative is **unlisted**, while a
short you reach by scrolling the feed is public. An explicitly unlisted `/shorts/` item is rejected
regardless of its duration, progress, or the Short-clips setting. If that field ever goes missing
from the markup, a sub-30-second clip is held to the ordinary floor rather than admitted, so drift
closes the lowered floor instead of re-opening the leak.

The tradeoff is that when a lookup times out (~12% of the time in field testing) a legitimate short
is still held to 30 seconds. That's the direction worth failing in; a missed entry can be earned
again by watching it, and a false entry is permanent.

When playback reaches the end and its position returns to the beginning, the log names a
**detected loop** and keeps the continuous viewing to one transaction regardless of kind. The
position-wrap signal survives a Chrome media-session recreation. When Chromium does not publish a
usable position reset, a verified short remaining active beyond 125% is still named as a
**probable loop**. In both cases the first qualifying viewing is kept.

### Advertisements, and what can be filtered

Ad rejection uses two independent pieces of explicit evidence:

- A dedicated `/shorts/` **ad creative** is normally unlisted. An explicitly unlisted Short is an
  unconditional veto, including one longer than the ordinary 30-second floor. It lands in **Not
  logged** as *"a short and the video is unlisted — almost certainly a feed ad"*.
- With **Browser evidence access** enabled, a visible YouTube ad control such as `Sponsored`, `Ad`,
  or `Skip ad` is bound to the exact current `/shorts/` video id only when that concrete URL appears
  in the same accessibility snapshot. That is also an unconditional veto, so it covers a public
  video that YouTube inserted as a promotion without attaching a later label to an older host-only
  URL. The evidence survives a Chrome media-session recreation and the one-minute continuation
  delay.

This mirrors the desktop connector's use of YouTube's explicit page ad state, adapted to the
evidence Android exposes. It deliberately does **not** guess from a channel, brand, title, video
length, or music classification: `PONDS CAM` is not itself proof that a video is an ad.

There is still a platform limit. Some browser/YouTube combinations may not expose the visible ad
label to Android accessibility, and the feature is unavailable when Browser evidence access is
off. A public promoted video is then indistinguishable from the same public video reached
organically and can still scrobble. Use **Never scrobble this again** for that fallback case; it is
keyed by video id and applies to the manual Broadcast button too. It cannot remove an entry already
on-chain.

Pre-roll ads on `/watch` are not scanned as Shorts ads. They inherit the real video's id, and the
identity corroboration normally rejects the mismatch; ordinary watch clips under 30 seconds also
fail the duration floor.

### Play time survives the browser rebuilding its session

Chrome destroys and recreates its media session while a video is still playing — around ad breaks
and playlist transitions. Each recreation is a new session as far as Android is concerned, so the
app's played-time counter used to restart with it.

The effect was a video watched to 80% producing nothing at all: a 196-second track arrived as three
fragments of 47s, 24s and 85s, each scored against the 60% threshold on its own and each skipped.
Progress is now carried across the restart, keyed by browser package plus the session's
title/artist/album/duration signature. A disappearing session waits up to one minute for a matching
replacement instead of finalizing its fragment. Its identity and explicit ad flag are frozen with
the progress. A replacement consumes that complete bundle; if none appears, expiry finalizes the
aggregate once without reading whatever URL is live then.

Three deliberate limits: the carried time is consumed exactly once (two sessions must never inherit
the same play time), the metadata signature must match, and it is dropped on **Stop** (which
discards the in-flight track by design). A separate replay with identical metadata inside the
one-minute continuation window remains intrinsically ambiguous; after expiry it starts fresh.

The visible cost is latency. When Chrome replaces one track's controller with a
different controller instead of publishing an in-place metadata change, the
old item can remain pending for the full minute, followed by Hive confirmation
time. RustedWax cannot close it immediately merely because different metadata
appeared: that different session may be a mid-roll ad before the original item
returns. A pending entry is delayed, not lost.

### Why a scrobble is confirmed, not assumed

`condenser_api.broadcast_transaction` returns an empty result on success and validates only against
the node you happened to ask. "No error" therefore means "that node didn't object" — which is not the
same as "this is on the chain".

On 2026-07-30 a node in the failover list froze 77 minutes behind the chain and kept answering RPCs
normally. It swallowed five scrobbles into a pending block it would never produce and reported no
error for any of them, so the app displayed five successes with transaction ids that didn't exist.
Then it refused the next two with a per-block rate limit — an error that can only fire repeatedly if
the node's block never advances, which is what gave the stall away.

Three things changed as a result:

- **A node must prove it's current** before anything is sent to it, judged against the phone's clock
  rather than the node's own head time. That check also guards the call that reads the chain head,
  because a stalled clock produces an expiration that's already in the past.
- **The accepting node never confirms itself.** The app asks every other current healthy node and
  selects the strongest answer, so an early `unknown` cannot hide a later block result.
- **Three accepted states remain distinct.** `Confirmed in a block`, `seen relaying in an
  independent node's mempool`, and `accepted but confirmation unavailable` have different UI and
  History wording.
- **Rate limits queue instead of vanishing.** Hive caps `custom_json` operations per account per
  block. That's a capacity limit, not a rejection, so those retry — where before they were discarded
  permanently.

There's one deliberate asymmetry. If an independent node sees a transaction in its mempool, or the
accepting node succeeded but every independent confirmation service was unavailable, the app does
not retry automatically. Retrying would rebuild it with a new expiration and a new id, and if the
original did land the result is a permanent duplicate. Neither state is labelled block inclusion.

### Offline queue behavior

The queue is an atomic JSON file, but it is not a continuously scheduled background worker. Entries
retain their account, payload, percent, and video id. A queued entry is only signed when the saved
account matches its owner; changing accounts leaves the old entry untouched. Due entries are
flushed when the notification listener connects, when the app explicitly triggers a flush (including
**Retry now**), and after relevant account/app lifecycle events. Exponential backoff controls which
entries are eligible during a flush; it does not itself wake the process at the deadline. An entry
can therefore remain waiting until the next flush trigger. After eight failed queued attempts it is
removed and a terminal History result is added. Read/write failures are written as `STORAGE ERROR`
log entries; an unreadable queue file is preserved with a `.corrupt-<timestamp>` suffix.

### When browser evidence goes quiet

The video id comes from the browser's address bar, and the bar can stop reporting
one — Chromium collapses the omnibox to a bare host while a feed scrolls. Once
that happens, entries lose their `url` **and** shorts under 30 seconds stop
counting at all, because without a `/shorts/` path there's no proof they're shorts.

That happened for thirteen minutes in field testing and cost nine entries before
anyone noticed, so the app now says so: after three consecutive tracks with no
video id, a banner names both costs and offers a shortcut to the Accessibility
settings. Counted per track rather than on a timer — a long video legitimately
reports its id once and then stays quiet for an hour, and that's fine.

### Not logged

The counterpart to History, and there for one reason: without it a strict rule and a broken app look
identical. Watching twenty shorts and getting six entries used to leave no artifact anywhere in the
UI saying the other fourteen were seen, let alone why.

Each row carries the reason the engine already computed — `track is 7s, under the 10s minimum`,
`played 34%, below 60% threshold`, `a short, but its watch page didn't resolve`, `already scrobbled
this listen` — plus how long it played and how long it was.

Tracks that played under three seconds are left out. Those are page-load transitions, where the
browser swaps a placeholder title for the real one; in one field session they were 165 of 198
entries and buried the 33 worth reading.

### v0.8.8 rule corrections

The v0.8.7 device run found three rules that looked correct in the prose but
were narrower in code. v0.8.8 makes an explicitly unlisted `/shorts/` item
ineligible at any duration, excludes
`MUSIC_VIDEO_TYPE_PODCAST_EPISODE` from song evidence, and carries a detected
end-to-start playback-position reset through Chrome session recreation so a
continuous looping item cannot receive the song-only second transaction.

### v0.8.9 ad evidence and delayed-identity corrections

The v0.8.8 device run exposed two remaining browser-evidence gaps. A public promoted Short could be
indistinguishable from organic content after the app ignored YouTube's visible `Sponsored` state,
and a track pending the one-minute continuation delay could be finalized against a later Short's
address-bar id. v0.8.9 binds exact visible ad labels to the current Short as an unconditional veto,
prevents a rejected live identity from being returned, and freezes identity plus ad evidence
together with carried progress until replacement or expiry.

### v0.8.10 mandatory hyperlinks and Shorts-aware recovery

Log 12 showed six recent Video entries with plain, unlinked titles. Their
payloads were already on-chain without `url`: Chrome exposed only the bare
`m.youtube.com` host, YouTube search rendered the candidates as modern Shorts
lockups the parser did not understand, and the engine treated unresolved URL as
an allowed degradation.

v0.8.10 recognizes the modern Shorts and ordinary-video lockups, uses bounded
query/fetch retries, strips Shorts presentation noise for identity comparison,
and completes title-only Short candidates from their watch pages before
requiring title, channel, and duration to agree. Multiple matching uploads are
refused. More importantly, unresolved identity now stops before payload
construction; the builder and central broadcaster independently enforce the
same rule for automatic, manual, and queued YouTube payloads. An unresolved
viewing can be missed, but it cannot create another unlinked profile entry.

### v0.8.10 evidence, the generated v0.8.11 build, and its field results

Log 14 verified the irreversible boundary: 109/109 emitted payloads were
block-confirmed, visible in the expected Music/Videos section, and linked to the
same YouTube id. No advertisement-like payload reached the profile.

It also proved that “every emitted payload is correct and linked” is not the
same as “every qualifying viewing became a payload.” Four qualifying viewings
were lost before broadcast:

- two ended sessions acquired the following track's resolved id/facts later in
  the asynchronous finalize pipeline;
- a one-millisecond duration refinement split one continuous play into 48% and
  53%; and
- an organic Short inherited the preceding six-second ad's stale `Sponsored`
  overlay during the URL transition.

Four song entries also exposed malformed/reversed credits from separators
inside parentheses and unsupported `Track - Artist`/performance shapes.

v0.8.11 freezes the finalized metadata, progress, timestamp, identity, resolver
context, loop and ad state through payload construction; resolver results are
structured and corroborated against that ended snapshot. URL-generation ad
state makes transition labels provisional, semantic track identity preserves
progress across missing-to-known or ≤2,000 ms duration refinement, and song
parsing now understands top-level separators, quoted/performance titles, and
channel-supported orientation. The exact eight log-14 cases are automated
regressions. See [the patch contract](BEHAVIOR_CONTRACT.md#v0811-patch-contract-implemented-automated-gate-passed)
and [the verification matrix](TESTING.md#13-v0811-regression-matrix-automated-gate-implemented).

The first v0.8.11 debug APK was generated as `dist/rustedwax-0.8.11.apk`
(version code 31) after a 290-test source gate plus successful debug assembly
and lint. Its SHA-256 was
`bdf81bcc560dea1c5a430d869193d702480f15f95c19f4d211abbb320ca4e296`.
Log 16 records that artifact's first physical-device attempt. The address-bar watcher did not
freeze: Chrome advanced through URL generations 2–15. Seven payloads were
block-confirmed and the new snapshot boundary refused successor facts instead
of emitting mixed identities. Chrome also destroyed and recreated its media
session repeatedly during the likely screen-off interval; same-track progress
was carried forward at 134, 172 and 177 seconds without a duplicate.

The release gate nevertheless failed. The active latch's older literal title
comparison rejected the correct ids for Soy Peor, Me Porto Bonito and DÁKITI
when page and MediaSession titles differed only by localized presentation,
parentheses or an additional display suffix. Once each URL advanced, v0.8.11
correctly kept the next track out of the ended payload, but the qualifying
ended song was visibly omitted. `W Sound 05 "LA PLENA" - Beéle, Westcol, Ovy
On The Drums` also exposed a quoted-title/trailing-credit shape that the parser
oriented incorrectly before broadcasting.

The implemented follow-up is generic validation and grammar, not a media catalog:
one shared structure-aware title corroborator now serves live and finalized
evidence, and a conservative `publisher/series "Track" - artist credits` parser
shape runs before generic dash orientation.
Exact field strings and ids belong only to unit-test fixtures and historical
documentation; production code will contain no song, video, title or artist
lookup table. The corrected `dist/rustedwax-0.8.11.apk` remains version code 31,
passed 295 tests plus assembly and lint, and has SHA-256
`1b682f582dd17f287d69acd2b22313c227acffb13f13d266288c4e6df65639d5`.
It is source-tested but not yet device-approved. See [the log-16 record](TESTING.md#14-v0811-physical-device-field-record-log-16),
[the implemented correction and retest plan](TESTING.md#15-v0811-field-follow-up-correction-implemented-automated-gate-passed),
and [the canonical follow-up contract](BEHAVIOR_CONTRACT.md#v0811-field-follow-up-contract-implemented-automated-gate-passed).

Log 17 records the corrected artifact's second physical-device round from
15:55:52 through 19:18:18 local time on 2026-08-01. It finalized 123 tracks:
67 payloads were emitted and block-confirmed, while 56 produced visible skip
decisions. Independent reconciliation found all 67 exact payloads on both Hive
nodes, all 60 `song` payload entries in Music, all seven `video` payload entries
in Videos, no wrong-section-only placement, no missing or duplicate transaction,
and no ad payload.
Seven observed playback wraps produced no duplicate transaction. The corrected
Soy Peor, Me Porto Bonito, DÁKITI and LA PLENA identities all used their own
canonical ids; LA PLENA's current payload also carried the corrected credits.

The gate still failed for capture and metadata fidelity. Signed-in YouTube
History confirmed eight qualifying organic viewings without a corresponding
payload: five one/two-word canonical titles were rejected by the new
three-token containment floor, one correct id was vetoed by exact channel-key
inequality, and two safe resolver failures omitted WHEN SINCE and a later +57
replay. A ninth qualifying movie Short remained safely unresolved because two
uploads were indistinguishable. Seven emitted song payloads exposed additional
multi-separator, orientation or duplicate-credit parser defects, and movie
Short `KoWNsyNVR28` was likely filed under Music on uploader category alone
despite no YouTube Music type or MusicBrainz match. No mixed successor identity
reached Hive: finalized isolation continued to fail safely.

The complete log-14/log-16 fixture gate was not exercised in log 17. In
particular, the YCB/Coming Home transition, stadium-ad/`IW524Zl2Pus` race,
`227125 → 227124` duration refinement and four original parser fixtures remain
device-pending. [TESTING.md §16](TESTING.md#16-v0811-second-physical-device-field-record-log-17)
contains the full reconciliation; [the implemented v0.8.12 contract](BEHAVIOR_CONTRACT.md#v0812-field-correction-contract-implemented-automated-gate-passed)
defines the bounded correction; log 18's later field result is recorded below.

v0.8.12 implements evidence-ranked short-title validation, role-aware channel
corroboration for current-generation observed ids, a capped memory-only
verified-identity candidate cache with mandatory re-fetch, cleaned search
variants, the seven structural credit corrections, and strong paired
`#movie`/`#edit` classification above bare `category=Music`. It preserves
ambiguity refusal and all v0.8.11 safety boundaries. The generated review APK
is `dist/rustedwax-0.8.12.apk`, version code 32, SHA-256
`3390df660053ca9c2c0c7665d320e67e820ce09513d4f025a172a018aa0080b3`.
It passed 314 tests with no skips, failures or errors, plus assembly and lint;
log 18 later failed its physical gate as recorded in TESTING §18.

Log 18 is the broad v0.8.12 field record. Its 6,309-line export contains 129
finalizations, 73 logged broadcasts that all reached blocks and 55 visible
skip decisions. Four configured Hive nodes returned the exact same immutable
transactions/payloads; Amarillo completed immediately after the export for 74
total operations (55 songs, 19 videos, 73 unique ids). Every unique id existed
in signed-in YouTube History and every operation appeared in its app-declared
Music/Videos profile section. Canonical links, snapshot isolation, loop caps,
no-ad-payload behavior and transport all passed.

The field gate still failed. MONTERO was emitted as artist `Your Name` after
`(Call Me By Your Name)` was misread as a `by Artist` credit, and Te Bote was
emitted as video because `movie` in channel `Flow La Movie` outranked an
id-bound YouTube Music OMV. Three uniquely recoverable qualifying songs failed
closed on compound/collaborative byline presentation; Classy 101 correctly
remained off-chain because two uploads were indistinguishable. The complete
mandatory fixture matrix was not run. [TESTING §18](TESTING.md#18-v0812-physical-device-field-record-log-18)
is the canonical reconciliation.

v0.8.13 limits its correction to literal `(by Artist)` grammar, hard music
provenance above generic channel words, stacked YouTube owner-suffix cleanup
and parser-proven collaborator bylines under exact title/duration/uniqueness
checks. It does not read History at runtime, add a fixture catalog, loosen
ambiguity refusal or alter historical entries. Version code 33 passed 320 tests
with no skips/failures/errors, debug assembly and lint (0 errors, 23 warnings).
The review APK is `dist/rustedwax-0.8.13.apk`, SHA-256
`ddfeb3e51cfe60fcf8fa2f13c05891989215154da948686650ae720e4ca9e026`.
See [the targeted contract](BEHAVIOR_CONTRACT.md#v0813-log-18-targeted-correction-contract)
and [implementation/test record](TESTING.md#19-v0813-targeted-log-18-correction).

### Log 19 field result and implemented v0.8.14 correction

`debug/rustedwax-log (19).txt` is the 5,261-line v0.8.13 device record from
2026-08-02 11:48:33 through 19:44:40 local time. Its 114 finalizations reconcile
exactly to 53 broadcasts and 61 visible skips. Fifty-two broadcasts reported
direct block inclusion; Gangsta's Paradise was durably queued during a network
failure and later confirmed in a block. All four configured Hive nodes returned
the exact 53 logged payloads and transaction ids, and all 53 appear in the
app-declared Music/Videos profile section. Every RustedWax payload has a
canonical `youtube.com/watch?v=` link. One additional `youtube embed`/`youtu.be`
operation for No Clarity occurred in the same account time window but is absent
from the app log and does not use RustedWax's mobile payload form; it is recorded
as a concurrent other-client operation, not a 54th RustedWax broadcast.

The field gate nevertheless failed. Namecheap `zUaMtSMZDgg` and KaoJapan
`azTP61YoD2s` are present in the Videos ledger but exact signed-in YouTube
History searches returned no result for either. The log contains no `[ad]`
event. The cause is structural: v0.8.13 scans exact accessibility ad labels only
when the same snapshot contains a concrete `/shorts/{id}` URL. It never scans
ordinary watch-page playback, where Chrome can expose a bare YouTube host and
the address bar continues to name the organic content behind a pre-roll. Both
public ad uploads therefore entered the ordinary resolver; at 30 seconds/99%
and 34 seconds/100%, respectively, each passed the normal video floor,
threshold, unique-id and canonical-link rules.

Log 19 also permanently emitted malformed metadata for Marlon Asher
`TQNW0_RRicI`, TVXQ `HtJS32n6LNQ`, DJ Snake/Taki Taki `ixkoVwKQaJg`, and
BENNETT/Mamma Mia `BVYpT8LsjtA`. The first lost a paired promo bracket around a
year, the second split inside a single-quoted work, the third reversed a
conventional artist-first featured-credit title because `DJSnakeVEVO` did not
corroborate `DJ Snake`, and the fourth fell back to the whole multi-dash title.
The mismatched brackets in the source title for `5GYeWpjq54Y` were already
present in YouTube metadata and are not attributed to the parser. Historical
operations are evidence and will not be repaired or rebroadcast.

The implemented v0.8.14 patch remains evidence-only and generic. It extends
exact visible-label scanning to ordinary YouTube watch playback, binds that
evidence to the active MediaSession track instance rather than the organic URL,
and preserves the existing provisional/re-observed transition discipline. It
does not use History at runtime or guess from brands, channels, titles,
duration, playback speed, crawlability, or view counts. It also adds narrow
paired-delimiter, single-quoted-work, owner-corroboration and channel-proven
version-suffix parser corrections with exact negative controls. The canonical
implemented contract is
[BEHAVIOR_CONTRACT.md](BEHAVIOR_CONTRACT.md#v0814-log-19-correction-contract),
and the field evidence plus implementation matrix is
[TESTING.md §20](TESTING.md#20-v0813-physical-device-field-record-log-19-and-v0814-implementation).

Version code 34 passed 338 tests with no skips, failures or errors, debug
assembly, and lint with 0 errors/23 warnings. The review APK is
`dist/rustedwax-0.8.14.apk`, SHA-256
`a9507c733b188f9cf3a481c8b1446535da22449343181cc17ccf556890f298b8`.
Source/artifact verification does not replace the pending physical-device gate.

### Log 20 field result and implemented v0.8.15 correction

`debug/rustedwax-log (20).txt` is the 10,945-line v0.8.14 device record from
2026-08-02 21:14:46 through 2026-08-03 10:52:51 local time. Its 227 completed
finalizations reconcile exactly to 107 automatic broadcasts and 120 visible
skips. One intentional fixed test broadcast makes 108 Hive operations total:
80 songs and 28 videos. Two automatic payloads were durably queued offline and
later confirmed in blocks. All four configured Hive nodes returned the same
108 normalized rows, and the live profile grew by exactly 80 Music and 28
Videos entries. Every automatic YouTube payload has a canonical exact watch
link and no duplicate broadcast video id was found.

The fifth physical gate nevertheless failed. Namecheap `zUaMtSMZDgg` was
correctly vetoed on three appearances where Chrome exposed `Sponsored` or
`Visit Advertiser`, but a fourth appearance produced no accessibility
observation and became transaction
`8c22a93cd7d581687055240d279d8713abfcdfc9`. The last URL/ad scan before that
track was at 00:58:56 and the next was at 01:35:03: the accessibility service
still reported connected, while URL and ad observations silently stopped for
about 36 minutes. Several advert MediaSessions occurred during that interval.
Most stayed off-chain because identity or duration failed; the public,
uniquely resolvable 30-second Namecheap upload alone met every ordinary-video
rule. Exact signed-in History search returned no Namecheap result. This is a
fresh immutable operation, not a retry of the log-19 Namecheap transaction.

The log also refused qualifying Music/OMV `JmeUtPih4U8`, CENTRAL CEE — BOOGA,
after unique title/channel/duration resolution because candidate channel
`Central Cee and LIVE YOURS` contradicted ended channel `Central Cee`. Signed-in
History contained the exact id. Two correct song ids reached Hive with reversed
credits: `DGs9TJmazB0` emitted the SIP title/feature list as artist and
`6IX9INE` as title; `z7DbZS6l6Vk` emitted the Bad Habits title/feature list as
artist and `Ed Sheeran` as title. The current generic multi-artist branch treats
an explicit featured list on the right as proof of track-first orientation even
when the raw form is conventional `Artist - Work ft. Featured`.

The remaining observed rules were strong: all 89 track instances that accepted
literal ad evidence were vetoed; 15 threshold, six missing-duration, two
duration-floor and eight identity refusals complete the 120-skip ledger; loop
caps prevented repeat broadcasts; 2× playback, same-track session carries and
the durable queue behaved correctly. A real Stop/reset with populated caches,
mute, dedup replay and YouTube manual/automatic parity were not exercised. The
four v0.8.14 parser fixtures, all six missing v0.8.13 fixtures and the broader
§§13e/17d matrix were again absent. The export also ends while the final Chrome
continuation window is still pending.

The implemented v0.8.15/version-code 35 patch is deliberately narrow. A
successful visible YouTube-root scan is now an explicit fresh coverage fact
bound to one package/MediaSession token/signature, separate from watcher
connection and separate from positive ad evidence. Accessibility callbacks and
a five-second refresh share one depth/node-bounded observation routine; after
15 seconds without a successful target-root scan, one evidence-outage
transition is logged while retries continue and the watcher remains honestly
connected. Coverage expires after 30 seconds unless refreshed, freezes at a
track's active-lifetime end, carries only with a genuine same-track session
recreation, and is invalidated by another token/signature, URL generation,
package or lifecycle epoch.

With Browser evidence enabled, automatic and manual rules now refuse a
resolver-only track without frozen current-track coverage as evidence
unavailable; they do not call it an advertisement. Exact current-generation URL
tracks and the Browser-evidence-disabled notification/lookup fallback retain
their existing behavior. Conventional right-hand featured titles remain
artist-first unless positive work/version or genuinely bare byline structure
proves track-first orientation. A collaborative candidate byline is compatible
only after unique strongest title/duration corroboration, explicit collaborator
presentation, hard YouTube Music provenance and exact complete leading-owner
agreement. The 176-test focused evidence/carry/rules/parser/resolver gate and
the complete uncached 349-test gate pass with no skips, failures or errors;
debug assembly succeeds and lint reports 0 errors/23 warnings. The tested APK
is `dist/rustedwax-0.8.15.apk`, SHA-256
`242d1b76d473754494dec74e035a7731ee1311c4920458926c5dd769e7ee365c`.
The canonical implemented contract is
[BEHAVIOR_CONTRACT.md](BEHAVIOR_CONTRACT.md#v0815-log-20-correction-contract-implemented),
and the correction ledger and original complete device checklist are in
[TESTING.md §21](TESTING.md#21-v0814-physical-device-field-record-log-20-and-implemented-v0815-correction).

### Log 21 final v0.8.15 field result

`debug/rustedwax-log (21).txt` contains the surviving 14,464-line final device
round from 2026-08-03 12:56:49 through 18:06:25 local time. Two device reboots
reset Android uptime and lost earlier runtime coverage, but the surviving log
contains no RustedWax exception, fatal, ANR, out-of-memory or crash marker. Its
228 final decisions reconcile to 98 block-confirmed broadcasts and 130 skips.
The broadcasts contain 98 unique video ids and transaction ids—57 songs and 41
videos—and every one appears on the signed-in `skiptvads.vidz` profile.

No new advertisement payload was found. Two naturally served Namecheap ads
were correctly rejected from the literal `Sponsored` and `Visit Advertiser`
controls; the Namecheap operation still visible on-chain is the immutable
v0.8.14/log-20 failure. Of the 130 skips, 85 carried explicit ad evidence, 32
were under threshold, 11 lacked a verified id and two failed the hard duration
floor. History identified four full organic songs among the unresolved set and
one full organic song conservatively vetoed after ad evidence lingered across a
promotion-to-track transition. All stayed safely off-chain.

The exact SIP, Bad Habits, Taki Taki and BOOGA correction fixtures were not
physically replayed in log 21, and Chrome—not Brave—supplied this round. Their
generic behavior remains covered by the automated suite and prior shared-path
evidence. The practical observable capture estimate is approximately 95.1%
(98 / (98 broadcasts + 5 known organic omissions)); it excludes threshold/
floor decisions and reboot-interrupted sessions and is not a formal accuracy
benchmark. Under the user's stated real-use bar, v0.8.15 is the accepted Phase
4 final product with those limitations documented. The authoritative final
field ledger is [TESTING.md §22](TESTING.md#22-v0815-final-physical-device-field-record-log-21).

The app does not automatically repair, rewrite, or rebroadcast any immutable
historical entry described by any field record.

## Development

See [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md) for the phased build plan, the file-by-file port map
from the extension, and the compatibility test vectors that must pass before shipping.

## License

[MIT](LICENSE). Portions adapted from upstream projects remain subject to their original attribution
and license requirements.
