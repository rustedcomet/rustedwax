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
