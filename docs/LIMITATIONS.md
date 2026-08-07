# Limitations vs the desktop extension

What the desktop connector can do that this cannot, and why.

[← Back to the README](../README.md)

---

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

## Limits measured on the device, not inherited from the platform

These are known, accepted, and recorded here rather than left to be rediscovered:

- **Playback nothing published can be lost in silence.** RustedWax measures what the phone tells it.
  Measured 2026-08-06: a 227-second video published a MediaSession for **seven seconds**, ninety-four
  seconds in, and nothing before or after — so twelve seconds was a complete account of everything
  it was ever shown. Where the player was when the app first saw it is not evidence that it played
  that far in this session (a resumed video opens mid-track having played nothing now), so it is
  never credited; it is now *stated* on the finalize line instead.
- **A Short played with its audio stopped may be lost without a line.** The check for "something is
  playing that nothing is counting" needs Usage Access and needs the audio to be started. Without
  both, only the case where the observer can see no window at all still reports, and it says
  outright that it does not know whether anything was playing. Under-reporting was chosen
  deliberately: the previous, louder version produced 111 reports in a day, almost none of them
  real, which teaches the reader to ignore the one line that matters.
- **Two live windows of one app screen can be confused for each other.** Visibility is tracked per
  activity class, because activity instance ids are not public API. Two live instances of the same
  class share a key, and stopping either reads as stopping both — which fails toward "not visible",
  so it under-credits rather than over-credits.
- **A Short YouTube draws no progress bar for is timed, not measured.** Its seconds come from
  wall-clock on the same audio-plus-visible-window evidence, so they are reported as inferred and
  never as measured. Until identity resolves, the app does not know how long the Short is; the
  ceiling until then is the format's own three-minute maximum, and the real length replaces it the
  moment a bar appears or the video's own page is read.
- **Picture-in-picture credit is inferred, and says so.** Another app playing audio while a paused
  YouTube PiP window sits on screen is indistinguishable from playback and would be credited. That
  is the cost of counting PiP at all; every such listen states how much was measured and how much
  deduced.
