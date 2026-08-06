# Detection sources

Where playback evidence comes from: native YouTube apps, and the optional browser evidence
service.

[← Back to the README](../README.md)

---

## Native YouTube and YouTube Music (v0.9.1, experimental)

The native packages are independent sources:

- `com.google.android.youtube`; and
- `com.google.android.apps.youtube.music`.

Each has its own persisted toggle and both default off. Enabling one does not admit the other or any
other media player. Stop, opt-out and listener rebuild invalidate native in-flight state and clear
its progress continuation and resolver candidates without moving them to another package.

The package name proves YouTube origin but never invents a video id. Exact identity is accepted from
`METADATA_KEY_MEDIA_ID`, a canonical YouTube `MEDIA_URI`, or an `i.ytimg.com` artwork URI. Every id
is normalized to `https://www.youtube.com/watch?v=<id>`. If no exact field exists, **Look videos up**
may use the existing title/channel/duration resolver, still requiring exactly one corroborated
candidate. Invalid, contradictory and ambiguous identities remain off-chain. Runtime code never
reads signed-in YouTube History.

Native title, artist and album fields remain separated instead of being unnecessarily re-parsed as
a browser-shaped `Artist - Title`. YouTube Music package origin is strong music context, below
literal podcast/episode types, structured non-music genre and the existing hard format rules.
Native YouTube continues through the full evidence-ranked classifier.

Ordinary native YouTube and YouTube Music still have no proven generic ad flag. Browser evidence is
never reused for them, and the app does not guess from brands, titles, ids, duration or popularity.
Foreground native Shorts are narrower: the separate YouTube-only service requires the measured
Shorts player and accepts only exact literal ad labels within it. Title + exact handle + duration
must remain stable across the transition window before a session exists, preventing outgoing and
incoming frames from mixing. Progress is derived only from seekbar movement; pause, seek, rewind,
missing proof and PiP add nothing. Exact-id recovery must then uniquely corroborate canonical title,
duration and `ownerProfileUrl` handle before the shared rule/payload path can run.

The no-broadcast A12 matrix passed, including two natural Short ads and browser/YouTube Music smoke
tests. The write/profile reconciliation remains pending, so both native toggles and the separate
Shorts grant remain experimental/default-off. Full records:
[PHASE_NATIVE_SHORTS.md](../PHASE_NATIVE_SHORTS.md) and
[PHASE_NATIVE_APPS.md](../PHASE_NATIVE_APPS.md).

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
