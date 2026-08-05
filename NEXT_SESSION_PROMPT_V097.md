# Resume prompt — RustedWax v0.9.7

Paste the block below into a fresh session.

---

Continue RustedWax. Read `PHASE_NATIVE_HISTORY.md` first — it is the complete
record of the 2026-08-04 v0.9.6 session — and `PHASE_NATIVE_PLAYLIST_IDENTITY.md`
for the v0.9.5 measurements it builds on. Do not re-derive their findings; they
were measured on the device, not guessed.

**Where things stand.** v0.9.6 is committed on `codex/v0.9.6-watch-history-identity`
and open as a PR against `main`. 496 unit tests, 0 failures, lint clean,
field-verified on the Samsung SM-A125M.

Resolution priority is now `browser address bar → playlist → watch history →
search`. The watch-history route is native-`com.google.android.youtube`-only,
signed in by the user in a WebView, with the session encrypted, never logged and
clearable. It supplies a **candidate**, never a verdict: the entry has to pass
the same title+channel+duration gate every other route passes, against the
frozen MediaSession tuple, and be the only recent entry that does.

Both open measurements from §11.2 item 0g are answered: a play appears in
history within **~2.7 s**, and the newest entry **was** the current track on all
three lookups. History records that a video was *started*, never how much was
played, so the threshold decision remains local MediaSession measurement.

**Work items, in order:**

1. **Field-test §11.2 item 5, which is still outstanding.** Play a non-playlist
   video with a known duplicate upload and confirm it resolves to the *right*
   one — `Criminal` → `4ns8D959YtA` (not `VqEbCxg2bNI`), `Unica` →
   `YbZwlNmnUvw` (not `7uxTya2PX3c`), both pairs duration-identical. Then
   confirm a backgrounded non-playlist video still refuses cleanly or resolves
   through history.
2. **Then decide whether the native search fallbacks can go.** This is the real
   simplification available, and it removes risk rather than just code: plain
   search, `NativeStructuredMusicMatcher` and the run-local candidate cache
   exist only to guess at the native no-id case, and search is the route
   measured three times choosing a duration-identical wrong upload (`Unica`,
   `Criminal`, `Chulo Sin H`). Decide it from the field data, not from taste —
   count how many native tracks history resolves versus how many still reach
   search, from the log.
3. **`UrlWatcherService.isEnabled` has a known defect**, deliberately left
   untouched under the v0.9.6 no-browser-change constraint: it reads only
   `enabled_accessibility_services`, so after a reinstall the UI reports
   "Granted" for a service Android has stopped feeding.
   `NativeShortsAccessibilityService.isEnabled` was fixed the same day by also
   requiring `accessibility_enabled == 1`. One line, needs the owner's sign-off
   because it touches a browser-path file.
4. **Optional, only if a residue remains:** the watch-metadata capture
   (channel, subscriber count, exact like count, view count, upload age) and
   like-count search disambiguation, i.e. §11.2 items 3 and 4. They were dropped
   on that section's own gate because they are scroll-dependent, per-track and
   foreground-only — unavailable in exactly the situation history covers. Do not
   build them for their own sake.
5. Still out of scope, recorded so they are not re-derived: playlist
   continuation past 100 entries, YouTube Music, and the Lounge/cast pairing
   protocol.

**Hard constraints (unchanged):**

- Chrome and Brave must not change. The two accessibility services are
  package-scoped by Android and cannot read each other's packages; keep it that
  way, guard every new gate on `session.isNative`, keep the browser-path
  regression tests green, and do not touch `UrlWatcherService`, `UrlEvidence` or
  `accessibility_service_config.xml` without asking.
- Every broadcast entry must carry a verified YouTube URL. The indexer builds a
  page around the video and embeds the player
  (`https://scrobble.life/music?v=<id>`), so an unlinked entry has no page.
- Do not relax the uniqueness rule, in the playlist, in history or in search.
- Unresolvable listens keep failing closed with an exact logged reason.
- Never log, export or send the YouTube session anywhere but `youtube.com`. The
  sign-in is performed by the user; never type, request or handle Google
  credentials.

**Before trusting any field result**, confirm the accessibility grants — a new
APK install revokes them, though they may return on their own a few seconds
later:

```
adb shell settings get secure accessibility_enabled            # must be 1
adb shell settings get secure enabled_accessibility_services   # must name both
```

Take the reading a few seconds *after* the install settles, not immediately.
`dumpsys accessibility` lists installed, not enabled, services and will mislead;
the service's own `connected` line in the event log is the strongest evidence.
`uiautomator dump` requires an idle window and fails while a video is playing —
pause first; the real service has no such limitation.

The phone is connected over USB with debugging authorised, and the Hive account
on it is disposable. Ask before rebooting it: it is PIN-locked and the PIN is
not yours to enter.

---

## Useful constants

- Test playlist: `Reggaeton 2016,17,18` by `Jhonny Gutierrez` →
  `PL7NMzffnWK8RMWFO3rZABAgN-Rk9pCkEm`, 120 claimed / 100 renderable / 19 unavailable
- Duplicate-upload test cases, both duration-identical:
  `Criminal` → `4ns8D959YtA` (in playlist) vs `VqEbCxg2bNI`, 273 s
  `Unica` → `YbZwlNmnUvw` (in playlist) vs `7uxTya2PX3c`, 218 s
- `Te Busco` → `7J6xA1_f8as`, the Topic-channel-alias case
- Log tag is `RustedWax`; the persistent log is readable with
  `adb shell run-as com.rustedwax.app cat files/rustedwax-log.txt`
- The history route's own lines are tagged `[history]`; a hit reads
  `resolved "…" → <id> from watch history (entry N of M, 0 = newest)`
- YouTube plays at 2× on this device, so a 234 s track needs ~120 s of wall time
- YouTube shows a "Video paused. Continue watching?" prompt during unattended
  playback; dismiss it or long tests will stall
