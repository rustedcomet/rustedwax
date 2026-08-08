# Native YouTube apps — v0.9.0 implementation and device gate

This document is the focused implementation and physical-test record for
RustedWax v0.9.0/version code 36. The canonical product invariants remain in
`BEHAVIOR_CONTRACT.md`; the accepted v0.8.15 browser history remains immutable.

## Status

v0.9.1/version code 37 now supplies a separate, foreground-only native Shorts
path for `com.google.android.youtube`. It does not make the stale/empty Shorts
MediaSession trustworthy and does not broaden ordinary YouTube Music handling.
The no-broadcast A12 pass verified exact structural proof, position-only
progress, literal Short ad labels, transition stabilization and PiP fail-closed;
the complete implementation/result is in
[PHASE_NATIVE_SHORTS.md](PHASE_NATIVE_SHORTS.md).

The 2026-08-04 ordinary native playlist failure and bounded v0.9.2 correction
contract are recorded separately in
[PHASE_NATIVE_PLAYLIST.md](PHASE_NATIVE_PLAYLIST.md). Four qualifying songs were
measured correctly but remained off-chain because native YouTube supplied
simplified separated music metadata and no exact id, while raw-title resolver
corroboration rejected the presentation-decorated canonical upload titles.

Implemented in source:

- independent opt-ins for `com.google.android.youtube` and
  `com.google.android.apps.youtube.music`, both defaulting off;
- package-origin proof separated from exact video-id proof;
- exact-id routes from MediaMetadata media id, media URI and canonical YouTube
  artwork URI, all normalized to `https://www.youtube.com/watch?v=<id>`;
- existing unique title/channel/duration resolver fallback when **Look videos
  up** is enabled;
- package-scoped identity, progress, resolver candidates and lifecycle epochs;
- clean native title/artist/album preservation and native YouTube Music context
  below explicit podcast/episode/non-music evidence;
- exact native MediaMetadata and PlaybackState diagnostics; and
- no new Android permission. Notification Access remains sufficient.

Partially measured on a Samsung Galaxy A12 / Android 12:

- foreground native Shorts publish no usable identity/progress MediaSession,
  but do expose a structural accessibility player with title, exact channel
  handle, seekbar progress and literal ad labels; and
- PiP removes that accessibility identity/progress surface while the
  MediaSession remains stopped/empty.

That partial gate failed because exact-ID-less native continuation carried an
ordinary video's stale metadata/progress across unrelated Shorts. The complete
evidence and bounded v0.9.1 plan are in
[PHASE_NATIVE_SHORTS.md](PHASE_NATIVE_SHORTS.md).

Still device-pending outside that bounded Shorts implementation:

- which exact identity fields each production app/version publishes;
- whether pre-roll or mid-roll ads publish a literal generic metadata/state
  flag, a distinguishable session shape, or no structured signal at all;
- real screen-off, playlist, playback-speed and controller-recreation behavior;
- notification-listener disconnect/reconnect behavior on the physical device;
  and
- end-to-end Hive/profile outcomes for native sources other than the bounded
  foreground-Short path.

Both native toggles must remain off by default until that device evidence is
reviewed. Passing JVM/build/lint gates is not device approval.

The v0.9.1 combined gate passed 409 tests with no failures/errors/skips,
debug assembly, and lint with 0 errors/23 warnings. The exact source/copy APK
hash is `c6f2f1800a1cc6b6d76c260181d2402a3d648c9ecf7b3bc94ad897eeb1ce0895`.
The authorized test-account pass block-confirmed and independently reconciled
four foreground-Short writes; lookup-off, unresolved and genuine ambiguity
cases remained off-chain. That bounded Shorts gate is approved. Both native
settings remain experimental/default-off pending the broader measurements
above.

v0.9.2/version code 38 implements the bounded ordinary native-playlist
correction in `PHASE_NATIVE_PLAYLIST.md`. Its source-scoped structured music
route requires one fully fetched exact parsed work/artist-credit/duration match,
and its ten-second exact-ID-less STOPPED grace discards same-metadata material
duration replacements without carry or ad inference. The uncached gate passed
416 tests, debug assembly and lint (0 errors/23 warnings). The exact
`dist/rustedwax-0.9.2.apk` was installed on the A12 with SHA-256
`5cf7fbfdd950376c8b61ede0a0effc7f843f25b249f47c06172471f18559d072`.
Post-install logs physically exercised the new grace on `Hey DJ`: same-title
duration phases changed 218→20→207 seconds and both superseded fragments were
discarded with zero carry and no ad inference. The clean 207-second phase then
completed and physically validated structured ambiguity refusal: two exact
`Hey DJ` uploads (`YN-aYhtMHIw`, `1fb9DtJpbHw`) matched, so no id was selected
and no payload was built. A later unique-match write still needs reconciliation;
no historical miss was repaired or rebroadcast.

v0.9.3/version code 39 implements the bounded follow-up recorded in
`PHASE_NATIVE_PLAYLIST.md`: search cards consume the structured page budget only
after exact work and complete-artist proof, stable native tracks may establish
memory-only immutable carry authority while playing, replacements claim time
only after independently resolving the same id, and finalization re-fetches the
same raw or structured route. Equivalent ordinary-player missing-Short
diagnostics are bounded without changing evidence events. The source gate
passed 422 tests, debug assembly and lint (0 errors/23 warnings). The final
14,048,941-byte APK was installed on the A12 with SHA-256
`d18342325d7288f5ccfe16aa549b5752e6ba2fd63d0522cd28e5ae7e65388d88`.
Only events after the final 10:12:21 local install boundary qualify for the
remaining unique-write/controller-carry field gate; earlier code-39 audit builds
are not acceptance artifacts.

The complete uncached source gate passed 372 tests, 0 skipped, 0 failures and
0 errors; debug assembly succeeded and lint completed with 0 errors and 23
warnings. The tested source APK and copied `dist/rustedwax-0.9.0.apk` are
byte-identical with SHA-256
`3f8945e997d592dbf40fac6aa727f69215cbf8a805b5a4b15df031171a0ec58c`.

## Package and lifecycle boundary

The browser allowlist is unchanged. A native controller is read only while its
exact package toggle is enabled. No other native player is admitted.

Each native snapshot carries a package-specific source epoch. Disabling one
toggle, pressing Stop, or resetting/rebuilding the listener invalidates that
epoch before signing. Those hard boundaries discard pending MediaSession
continuations and clear package-scoped verified-id candidates. A naturally
removed controller gets the existing bounded same-track recreation window; if
no replacement appears, its finalization clears the package carry/candidate
state. None of these operations changes either browser path or the other native
package.

Progress carry remains keyed by package plus semantic title/artist/album
identity. A concrete native video id is additional case-sensitive track
evidence: the same id—or a missing-to-known refinement—may retain progress,
while two different ids cannot. Carry is consumed once and retains the existing
60-second ceiling.

Native identity never reads browser notification hints, address-bar state,
playlist context, URL/ad generations, accessibility coverage or browser ad
evidence. Browser evidence callbacks remain scoped to Brave and Chrome.

## MediaSession field inventory

The v0.8.15 code already read these payload inputs:

- `TITLE`, falling back to `DISPLAY_TITLE`;
- `ARTIST`, falling back to `DISPLAY_SUBTITLE`;
- `ALBUM`;
- `DURATION`; and
- PlaybackState position, state, update time and playback speed.

The diagnostics already enumerated all standard text fields: `TITLE`, `ARTIST`,
`ALBUM`, `ALBUM_ARTIST`, `DISPLAY_TITLE`, `DISPLAY_SUBTITLE`,
`DISPLAY_DESCRIPTION`, `ART_URI`, `ALBUM_ART_URI`, `DISPLAY_ICON_URI`,
`MEDIA_URI`, `MEDIA_ID`, `AUTHOR`, `WRITER`, `COMPOSER`, `GENRE` and `DATE`.
They also enumerated `DURATION`, `YEAR`, track/disc counts, bitmap presence, and
every non-standard MediaMetadata key.

Before v0.9, `MEDIA_ID` was diagnostic only. Browser identity could already use
a YouTube watch URI or `i.ytimg.com/vi/<id>` URI, although Chromium field tests
published neither. v0.9 adds the native package-scoped media-id route and strict
URI parsing. It also logs the full native PlaybackState surface without
interpreting it: numeric state, position, buffered position, speed, action
bitmask, error text, update time, active queue id, the API-31+ active flag
(explicitly unavailable below API 31), every custom action and all
state/custom-action extras.

## Identity and resolver order

For an enabled native package, exact identity is attempted in this order:

1. `MEDIA_ID`: an exact 11-character id or a canonical YouTube URL;
2. `MEDIA_URI`: exact `youtube.com`, `m.youtube.com`, `music.youtube.com` or
   `youtu.be` watch/Shorts/embed/live URI; and
3. `ART_URI`, `ALBUM_ART_URI`, then `DISPLAY_ICON_URI`: an exact canonical
   `ytimg.com` `/vi/` or `/vi_webp/` path.

An accepted id is case-sensitive and the output link is always canonical. A
`/shorts/<id>` media URI retains Short-path evidence. A package name by itself
produces site-only identity and cannot build a payload.

When no exact field supplies an id and **Look videos up** is on, the existing
resolver may search from the immutable native title, artist/channel and
duration. It must still return exactly one fully corroborated id; ambiguity,
missing inputs or a contradiction stays off-chain. Run-local candidate reuse is
package-keyed, re-fetched and re-corroborated. Signed-in YouTube History is not
read or scraped at runtime.

The final snapshot guard remains in force. A fetched page with a materially
different title or duration refuses even an exact native id. For a clean short
native title, an exact structured media id plus corroborating duration may
survive a longer page presentation and a channel-vs-artist role difference.

## Metadata and kind

Where a native session supplies title, artist or album, that separated value is
kept. Browser-oriented Artist–Title parsing and fetched page presentation do
not replace it. Missing native fields may still use already-supported fetched
facts.

Native YouTube continues through the existing evidence-ranked classifier.
Native YouTube Music package origin is strong music context, but it runs below:

- a literal YouTube Music podcast-episode type;
- a structured `GENRE` such as Podcast, Episode, Audiobook or Spoken Word; and
- the existing hard title-format rules for podcasts, numbered episodes,
  tutorials, news, trailers and non-instrument game playthroughs.

Classification remains fixed before broadcast construction. Thresholds,
duration floors, Short proof, loop caps, playback-speed accounting, song-only
double-listens, dedup and manual/automatic central rules are unchanged.

## Advertisement boundary

Browser accessibility coverage and exact visible-ad labels never apply to a
native package. No native advertisement is inferred from an id, brand, title,
artist/channel, duration, speed, category, popularity, listed state, resolver
result or YouTube History.

No generic literal native ad signal is currently proven, so v0.9 adds no native
ad veto. An otherwise eligible native item follows the disclosed fallback
threshold/floor/id behavior. The settings and Now diagnostics warn that browser
visible-ad protection does not cover native apps. The exact metadata/state dump
is the instrument for determining whether a future generic veto is justified.

This is the principal release risk and the reason both toggles remain off by
default.

## Physical-device checklist

For every item, export the complete RustedWax log after all 60-second
continuation windows have expired. Reconcile every attempted native broadcast
by video id, transaction id, Hive nodes and the expected profile section. Do
not use signed-in History as runtime input; it may be used only as an external
post-run oracle.

The Short-specific instrumentation part of this checklist was partially run
and failed as recorded in [PHASE_NATIVE_SHORTS.md](PHASE_NATIVE_SHORTS.md).
Use that document's stricter foreground-only gate for the follow-up patch; the
remaining ordinary YouTube and YouTube Music items below are still open.

### Package/settings boundary

- Fresh install/update: confirm both native toggles are off.
- Play in YouTube with only YouTube disabled, then enabled.
- Play in YouTube Music with only YouTube Music disabled, then enabled.
- Enable one native package and confirm the other remains unread/unlisted.
- Confirm Chrome and Brave behavior is unchanged with both native toggles off
  and while either native toggle is on.
- Disable a native toggle mid-track and confirm the in-flight track, pending
  continuation and resolver candidate do not reappear after re-enable.
- Exercise Stop/reset with populated native progress and candidates.
- Toggle Notification Access off/on and confirm listener reconnect clears old
  native state without transferring it to a replacement package/session.
- Switch browser → YouTube → YouTube Music → browser using identical-looking
  metadata and confirm no identity, progress, ad, URL, coverage or resolver
  evidence crosses a package boundary.

### Identity and metadata

- Capture ordinary YouTube videos and record `MEDIA_ID`, `MEDIA_URI`, all three
  artwork URI fields, every non-standard field and the successful id route.
- Capture YouTube Music songs and verify clean title, artist and album survive
  unchanged into the Now payload and any broadcast.
- Capture an item that publishes no exact id; with lookup off it must remain
  unresolved, and with lookup on it may proceed only through one uniquely
  corroborated title/channel/duration result.
- Exercise an intentionally ambiguous/same-song-different-upload result; every
  candidate must remain off-chain.
- Confirm every broadcast link is exactly
  `https://www.youtube.com/watch?v=<same-id>`.
- Recreate the same native MediaSession/controller and confirm accumulated
  progress is retained once.
- Change only the exact native id under otherwise identical metadata and
  confirm progress/evidence is not inherited.

### Content/rules

- Ordinary native YouTube music video/song classification.
- Ordinary native YouTube non-music video classification.
- YouTube Music song classification and album preservation.
- YouTube Music podcast and numbered episode control; record title, genre and
  `musicVideoType` evidence.
- Native Shorts: whether a session exists, exact id/path, 10-second proof when
  available, ordinary 30-second fallback otherwise, and loop cap.
- Playlists and automatic next-track transitions.
- Pause/resume below and above the threshold.
- Screen-off playback and app background/foreground.
- Full playback at 2× and any speed transition.
- A continuous loop and a later separate replay.
- Manual Broadcast and automatic finalization at the same threshold/floor/id,
  mute and dedup boundaries.

### Native advertisements

- YouTube pre-roll, skippable and non-skippable.
- YouTube mid-roll with organic content resuming afterward.
- YouTube Music pre-roll/mid-roll or audio ad, if served.
- Shorts-feed promotion/ad, if native Shorts publish a MediaSession.
- For every ad/content transition, preserve the exact before/during/after
  MediaMetadata and PlaybackState dumps, controller token/session recreation,
  duration, position, actions, custom actions and extras.
- Determine whether a literal structured signal is generic across at least the
  two native packages and multiple creatives. Until then, record the outcome as
  an unproven native-ad limitation rather than inventing a veto.

## Device exit decision

The physical gate may approve native input only after exact source/id/profile
reconciliation and advertisement-shape review. If no generic literal ad signal
exists, approval must explicitly accept the disclosed fallback risk; otherwise
both toggles remain experimental and default-off. Historical Hive operations
must never be repaired, rewritten or rebroadcast during this phase.
