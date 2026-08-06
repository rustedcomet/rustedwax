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
Shorts player and accepts only exact literal ad labels within it. Identity must remain stable across
the transition window before a session exists, preventing outgoing and incoming frames from mixing.
Progress is derived from seekbar movement; pause, seek, rewind and missing proof add nothing.

Three parts of that changed once the app met a real day of Shorts use:

- **The on-screen title is not identity** (v0.9.7). YouTube removed the resource ids from the Shorts
  footer, and a Short opened straight into picture-in-picture never shows a title at all. A Short is
  proven by its structural player, its exact `ownerProfileUrl` handle and a readable seekbar;
  identity is resolved at finalize from the account's watch history on handle + duration, and every
  candidate is still re-fetched and corroborated on its own watch page. When a title *is* present it
  must still agree.
- **A page publishes two titles** (v0.9.8). `videoDetails` keeps the uploaded name while the page
  renders an auto-translated one for the viewer, and which of the two the resolver sees depends on
  the language its own fetch asks for. Both are compared and the stronger agreement decides; a
  refusal names both.
- **Picture-in-picture is counted, marked inferred** (v0.9.7). PiP publishes no progress of any kind,
  so elapsed wall-clock is credited on the paired evidence that YouTube holds a visible window and
  media audio is started — never while a readable seekbar exists, capped at the item's own duration,
  and carried separately so every such listen says how much was measured and how much deduced.

A Short publishes **nothing** to its MediaSession — measured 2026-08-06, `active=false`, `state=1`,
`metadata: size=0` — so the accessibility observer is the only thing that can see one at all. When
it cannot, and independent evidence says something is playing that nothing else is counting, the log
says so and for how long. It stays quiet for the ordinary quiet: a latched Short is read by the
service's own poll while YouTube emits no callbacks, and leaving the Shorts feed leaves its views in
the hierarchy where they parse as "no player".

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
