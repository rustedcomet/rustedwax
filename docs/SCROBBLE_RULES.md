# Scrobble rules

What qualifies as a listen, what is refused, and why each refusal exists.

[← Back to the README](../README.md)

---

### YouTube watch history (v0.9.6, optional)

**Off, and not connected, unless you sign in for it.**

The native YouTube app publishes no video id anywhere another app can read — MediaSession metadata,
queue, session activity, notification, accessibility tree and even its own exported media-browser
service were all measured empty. Inside a playlist RustedWax works the id out from the playlist. For
a single video, or for anything played with the screen off, the only surface that names the exact
video is the account's own watch history.

If you connect an account, RustedWax reads exactly one page — `youtube.com/feed/history` — and only
to check whether the track that just played is named there. It does not read subscriptions, email,
the Google account, or any other Google service, and it never posts, likes, comments or changes
anything.

- **You sign in, not the app.** Google's own sign-in page opens in a WebView. RustedWax cannot type
  into it and never sees a password or a second factor.
- **The session is encrypted at rest**, in `EncryptedSharedPreferences` under an Android Keystore
  key — the same protection as the Hive posting key, which is the correct comparison because a
  Google session cookie is the more dangerous of the two. It is never written to the event log,
  never included in an exported log, and never attached to any host but `youtube.com`. Redirects are
  not followed, so a redirect cannot walk it somewhere else. The WebView's own plaintext cookie jar
  is wiped as soon as the session has been moved into the vault.
- **Disconnect wipes it**, immediately, from the same row in the app.
- **History is a candidate, never a verdict.** The entry has to pass the same title + channel +
  duration gate every other route passes, against the frozen MediaSession values, and it has to be
  the only recent entry that does. A stale entry, or one written by another device on the same
  account, refuses rather than mis-links.

The YouTube app on the phone must be signed in to the **same** account and not playing in incognito;
otherwise nothing you play is written to the history RustedWax can read. That is detected rather
than assumed: three consecutive tracks that were absent from a successfully-read feed stop the route
outright, and the app says so on the settings row instead of quietly fetching a page per track
forever. A dead session and a paused history are YouTube's own answers and refuse on sight.

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
