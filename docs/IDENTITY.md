# Identifying the video

How a viewing becomes a specific video id: lookups, the YouTube policy tradeoff, and MusicBrainz
verification.

[← Back to the README](../README.md)

---

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
2. **Your own watch history** (v0.9.6, native YouTube only, off until you connect an account —
   see "YouTube watch history" below). The feed names the exact video the phone played, which is the
   only surface that answers for a single video with no playlist around it, or for anything played
   with the screen off.
3. **A YouTube search** for the session's title and channel, when there's no playlist or the track
   isn't in the part of it that loaded. The resolver understands ordinary result cards and the
   current Shorts lockup shape. Since a Shorts card omits channel and duration, up to eight
   highest-ranked plausible ids are checked against their own watch pages before one can qualify.

So the full order is **address bar → playlist → watch history → search**. The playlist is tried
before history because it needs no credentials and one fetch serves every track in it; both are
tried before search because search is only *plausible* where they are exact — searching for one
tested track returned a different upload of the same song by the same artist at the same length,
which no amount of matching strictness can separate. Every route must agree on title, duration and
channel, and multiple matching ids are refused as ambiguous — including two entries of one playlist
and two entries of the recent history. Anything less certain is recorded in **Not logged** and never
reaches the chain. URL-less YouTube entries are no longer a supported fallback.

Two amendments came out of the field, both about the *title* half of that agreement:

- **A video has two published names.** YouTube auto-translates titles for the viewer, so a watch page
  carries the uploaded name in `videoDetails` and the rendered one in its page data, and which is
  which depends on the language the fetch asks for — verified by fetching one page as `en-US` and as
  `es-419` and getting the two names swapped. Both are compared against what was playing and the
  stronger agreement decides. Both come from the one page being verified for the one id, so nothing
  new is admitted; duration, channel/handle and the single-match rule are untouched.
- **A foreground Short may have no readable title at all**, and refusing on that threw away a
  measurement that was already complete. Handle + duration + a unique match carry it instead; where
  no title exists and one channel has two uploads of the same length, the most recently watched is
  taken, because candidates arrive newest-first and the Short being identified is the one playing
  now. With a title present, two full matches still refuse.

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
