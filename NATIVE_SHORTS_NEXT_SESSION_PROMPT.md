# New-session prompt — implement native YouTube Shorts v0.9.1

Copy everything below into a fresh Codex task.

```text
Continue RustedWax development in /Users/destro/Desktop/git/rustedwax.

Implement the bounded native YouTube Shorts follow-up as RustedWax v0.9.1,
version code 37. Do not begin from assumptions or redesign the product. First
read these files completely:

- PHASE_NATIVE_SHORTS.md (the authoritative field findings and patch plan)
- PHASE_NATIVE_APPS.md (the implemented v0.9.0 native-app baseline)
- BEHAVIOR_CONTRACT.md (canonical as-built invariants)
- TESTING.md, especially §§23–24
- README.md

Then inspect git status and the full current diff before editing. The branch is
expected to be codex/v0.9-native-youtube-apps and the v0.9.0 implementation is
currently unstaged/uncommitted. Preserve all existing work and unrelated user
changes. Do not stage, commit, push, or open a PR unless I explicitly ask.

The field conclusion is:

- native YouTube MediaSession is stale/empty during Shorts and can keep
  publishing BELLAKEO while unrelated Shorts play;
- exact-ID-less native controller recreation currently carries that stale
  track's progress and must be fixed first;
- foreground YouTube Shorts accessibility exposes a structural player root,
  exact visible title, exact @handle, current/total seekbar, and literal Ad
  labels;
- PiP removes title/handle/seekbar proof, while MediaSession remains STOPPED at
  position 0, so v0.9.1 must be foreground-only and fail closed in PiP;
- canonical watch pages expose the exact handle in
  playerMicroformatRenderer.ownerProfileUrl, which should extend the resolver;
  and
- signed-in YouTube History was only an external test oracle and must never be
  a runtime input.

Required implementation scope, in this order:

1. Safety fix: exact-ID-less native MediaSession sessions must not remember or
   claim progress across controller teardown/recreation. Resolver-only/site-only
   native identity is insufficient. Preserve the current browser continuation
   contract and exact-id native carry. Add the log-22 BELLAKEO regression.

2. Add a second, separately disclosed AccessibilityService with its own XML
   config OS-restricted only to com.google.android.youtube. Do not broaden the
   existing browser accessibility config. The service must also require
   Monitoring and the Native YouTube toggle. Use bounded scans and recycle
   nodes. No SystemUI, OCR, screen capture, History, notification-content,
   privileged API, or new broad package access.

3. Add a pure structural foreground-Short parser. Require the measured Shorts
   player root/player, one title, one exact @handle, and one valid current/total
   seekbar. Reject Home/search/cards/comments/ordinary watch pages, hidden or
   conflicting data, invalid totals, and unsupported localized shapes. Reuse
   strict literal ad labels only inside that proven player observation; do not
   use brand/title/handle/duration/id/CTA/History heuristics.

4. Add an independent foreground Short lifecycle owned by the existing
   monitoring/source-epoch boundary. Do not merge it with stale MediaSession
   state. Track transitions by title + exact handle + duration + source proof.
   Measure play only from conservative seekbar position deltas, with no
   wall-clock credit; reject seeks/inflation, detect strict end-to-start wraps,
   and freeze during missing proof. In PiP/background, end/finalize at the last
   valid foreground value after only a short bounded no-credit refresh grace.
   Suppress the stale native YouTube MediaSession card only while a complete
   foreground Short is proven.

5. Rejoin the existing SessionSnapshot/ScrobbleEngine/resolver/classifier/
   ScrobbleRules/mute/dedup/queue/Hive path. Do not create a parallel broadcast
   pipeline. Add explicit native foreground-Short source proof; do not rely on
   session.confirmed?.isShort because resolver-only native ids do not populate
   that field. Use the proof consistently for Short floor, classification,
   loop cap, UI, logs, manual and automatic behavior. Keep browser evidence
   outage semantics browser-only.

6. Extend watch-page parsing and identity resolution with the canonical owner
   handle from microformat.playerMicroformatRenderer.ownerProfileUrl. Carry it
   through VideoFacts, FactsCache and VideoResolution as a dedicated field.
   When accessibility supplied a handle, require title + existing duration
   tolerance + exact normalized handle and exactly one candidate. Trim/remove
   one @ and compare case-insensitively, but do not remove underscores or use
   fuzzy display-name matching. Missing, malformed, contradictory, legacy-cache
   or ambiguous handle evidence must fail closed. Preserve bounded network
   work and the current ambiguity/final-corroboration/canonical-link rules.

7. Update the UI/disclosure and docs: native Shorts observer remains
   experimental/default-off, show its grant/coverage and exact skip reason, and
   state that foreground Shorts only are supported and PiP/background time is
   not counted. Native YouTube Music and v0.8.15 browser behavior must not
   change.

Use PHASE_NATIVE_SHORTS.md's complete automated matrix as the minimum test
contract, including the three measured resolver cases @SonaDarus,
@Status_svijet and @Beredist; native continuation isolation; organic/ad/organic
transitions; position delta/seek/wrap behavior; PiP proof disappearance;
Stop/opt-out/disconnect epochs; literal-ad veto; resolver ambiguity; cache
round-trip; central manual/automatic parity; and browser/YouTube Music
regressions.

Run the complete uncached verification gate with the Android Studio JBR:

JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:testDebugUnitTest --no-build-cache --rerun-tasks
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:assembleDebug
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:lintDebug

Use the actual Gradle result as the final test count; do not predict it.

Then use the connected Samsung SM-A125M Galaxy A12 (Android 12/API 31,
YouTube 21.30.209) through:

/Users/destro/Library/Android/sdk/platform-tools/adb

Install and physically test the APK. Start with automatic broadcasts off. Run
the full device gate in PHASE_NATIVE_SHORTS.md: setting/grant combinations,
ordinary-video-to-six-Short rabbit hole, exact-id resolution, two natural ads,
rapid transitions, pause/seek/rewind/loop, PiP before/after threshold,
non-player surfaces, controller churn, Stop/opt-out/accessibility and
Notification Access reconnect, browser smoke tests, and YouTube Music smoke
tests. Export/reconcile the RustedWax log. Chrome History may be checked
manually afterward only as an external oracle if the user's existing session
is available; never add History access to the app.

Do not enable automatic Hive writes until the no-broadcast instrumentation run
passes. If a bounded broadcast pass is safe, use only the existing configured
test setup and reconcile every attempt; do not repair/rewrite/rebroadcast old
operations. If any ad, stale carry, identity transition, PiP accounting,
lifecycle invalidation, or browser regression gate fails, keep the feature
experimental/default-off, diagnose it, fix it generically, and rerun the
relevant gate.

When all gates pass, copy the exact tested APK to dist/rustedwax-0.9.1.apk,
verify its SHA-256 matches the source APK, and update PHASE_NATIVE_SHORTS.md,
PHASE_NATIVE_APPS.md, TESTING.md, BEHAVIOR_CONTRACT.md, DEVELOPMENT_PLAN.md and
README.md with actual—not predicted—results and any remaining limitations.

Use apply_patch for edits, avoid destructive git commands, and leave the phone
and repository in a clean investigation state. Continue until implementation,
automated verification, physical-device verification, artifact hashing and
documentation are genuinely complete, or until a concrete blocker requires my
authority. Report every changed file, test/build/lint result, device outcome,
artifact path/hash, and unresolved risk.
```
