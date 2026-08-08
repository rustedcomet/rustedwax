# Field results and version corrections

Every device round from v0.8.8 onward, with the evidence that drove each correction.

[← Back to the README](../README.md)

---

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
regressions. See [the patch contract](../BEHAVIOR_CONTRACT.md#v0811-patch-contract-implemented-automated-gate-passed)
and [the verification matrix](../TESTING.md#13-v0811-regression-matrix-automated-gate-implemented).

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
It is source-tested but not yet device-approved. See [the log-16 record](../TESTING.md#14-v0811-physical-device-field-record-log-16),
[the implemented correction and retest plan](../TESTING.md#15-v0811-field-follow-up-correction-implemented-automated-gate-passed),
and [the canonical follow-up contract](../BEHAVIOR_CONTRACT.md#v0811-field-follow-up-contract-implemented-automated-gate-passed).

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
device-pending. [TESTING.md §16](../TESTING.md#16-v0811-second-physical-device-field-record-log-17)
contains the full reconciliation; [the implemented v0.8.12 contract](../BEHAVIOR_CONTRACT.md#v0812-field-correction-contract-implemented-automated-gate-passed)
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
mandatory fixture matrix was not run. [TESTING §18](../TESTING.md#18-v0812-physical-device-field-record-log-18)
is the canonical reconciliation.

v0.8.13 limits its correction to literal `(by Artist)` grammar, hard music
provenance above generic channel words, stacked YouTube owner-suffix cleanup
and parser-proven collaborator bylines under exact title/duration/uniqueness
checks. It does not read History at runtime, add a fixture catalog, loosen
ambiguity refusal or alter historical entries. Version code 33 passed 320 tests
with no skips/failures/errors, debug assembly and lint (0 errors, 23 warnings).
The review APK is `dist/rustedwax-0.8.13.apk`, SHA-256
`ddfeb3e51cfe60fcf8fa2f13c05891989215154da948686650ae720e4ca9e026`.
See [the targeted contract](../BEHAVIOR_CONTRACT.md#v0813-log-18-targeted-correction-contract)
and [implementation/test record](../TESTING.md#19-v0813-targeted-log-18-correction).

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
[BEHAVIOR_CONTRACT.md](../BEHAVIOR_CONTRACT.md#v0814-log-19-correction-contract),
and the field evidence plus implementation matrix is
[TESTING.md §20](../TESTING.md#20-v0813-physical-device-field-record-log-19-and-v0814-implementation).

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
[BEHAVIOR_CONTRACT.md](../BEHAVIOR_CONTRACT.md#v0815-log-20-correction-contract-implemented),
and the correction ledger and original complete device checklist are in
[TESTING.md §21](../TESTING.md#21-v0814-physical-device-field-record-log-20-and-implemented-v0815-correction).

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
field ledger is [TESTING.md §22](../TESTING.md#22-v0815-final-physical-device-field-record-log-21).

The app does not automatically repair, rewrite, or rebroadcast any immutable
historical entry described by any field record.

### 2026-08-06 — the acceptance day, and the three rounds it forced

Field record: [FIELD_2026-08-05.md](../FIELD_2026-08-05.md) §13–§15.

A full day of ordinary use on v0.9.7 — gestures, minimise, lock screen,
picture-in-picture on both Shorts and ordinary videos — produced **46 scrobbles**
and a list of eight items the owner believed had been missed. The 58,000-line log
answered for all eight, and the answer moved the work away from identity:

| what was missed | why |
| --- | --- |
| 1 Short | identity refused it — a page publishes two titles and the wrong one was compared (v0.9.8) |
| 1 Short | the watch-history route had stood itself down over one replayed track (v0.9.8) |
| 1 video | its MediaSession lived 1.5 seconds and published `0s of 35s` |
| 1 video | published a MediaSession for 7 seconds, 94 seconds into 227 |
| 2 videos, 2 Shorts | never published or never observed at all — no title, no handle, no session, nothing |

The owner supplied partial titles for the six that left no trace, which settled
what an id search could not: titles and owner handles are both written *before*
identity resolves, so their absence is absence of observation. **Identity was the
gate for exactly one of the eight, and it is fixed.**

The same day's §5.1 instrumentation was then found to be reporting **111 outages**
on a day that scrobbled 46 tracks — it counted accessibility callbacks, which stop
while a latched Short is being measured perfectly by the service's own poll. It
now reports only when independent evidence says something is playing that nothing
is counting. Staging a single true positive for the rebuilt detector uncovered a
silent defect in shipped code: a departing activity's `ACTIVITY_STOPPED` arrives
after the arriving one's `ACTIVITY_RESUMED`, so YouTube read as gone while
visible — which had been quietly refusing picture-in-picture credit too.

### 2026-08-07 — the night YouTube stopped drawing the Shorts seekbar

Field record: [FIELD_2026-08-05.md](../FIELD_2026-08-05.md) §16.

After a day that scrobbled 77 tracks, Shorts stopped counting: **71 finalized in 85 minutes, 4
scrobbled**, nearly all reading `0s measured from the seekbar`. The device said why —
`reel_time_bar` present and empty, no `SeekBar` node anywhere in the tree, and no bar drawn on
screen while audio played. YouTube's own app version had not changed.

The wall-clock fallback built for picture-in-picture was enabled and correct and did nothing,
because it never ran: a Short could only be *started* from a seekbar reading, and nothing that is
not started can accrue anything. The fix makes a proven, named player with no reading a proof rather
than a refusal, and identity now needs the `@handle` plus any one of title or length.

The owner then found the reproduction, which no amount of scrolling had: **a Shorts player resumed
by tab navigation renders without its bar**, where one opened fresh renders with it. Verified step by
step, and a back-to-back pair both reached the chain — one inferred over 149 seconds with no bar at
any point (`tx 80c427db…`), one measured normally after a swipe restored the bar (`tx 48f78ee7…`).

### 2026-08-07 later — two rounds of owner testing

Field record: [FIELD_2026-08-05.md](../FIELD_2026-08-05.md) §17.

Twice the owner reported that scrobbling had stopped, and twice the log showed a healthy app that
had simply been shown fragments of what was played.

**Shorts, 42 minutes, nothing scrobbled.** Switching between the Home and Shorts tabs outlasts the
3-second proof grace, so each switch finalized the Short and each return restarted it from zero —
one 32-second Short finalized at `0s`, `3s` and `5s` and never cleared the threshold it had earned
in total. A Short that returns with the same identity within 30 seconds now resumes. Two smaller
causes rode along: a seekbar-less Short could not end on its own (one sat active for seven minutes),
and by the time it did its id had fallen out of the recent-history window identity needs.

**A trailer and a Short, interleaved, neither scrobbled.** YouTube recreates its MediaSession on
every tab switch and publishes empty metadata first. Treating that placeholder as a track change
finalized the real trailer against it, repeatedly: 18 seconds banked out of several minutes watched,
with `first seen 154s in` on its own finalize line. Empty metadata is no longer a track. The Short in
that window was lost separately, to a missing `ownerProfileUrl` on an enrichment fetch of a page
whose first fetch had supplied it — absence treated as contradiction.

### 2026-08-07 evening — the hand-off, and a handle with a cedilla in it

Field record: [FIELD_2026-08-05.md](../FIELD_2026-08-05.md) §18.

**A trailer watched to 82%, deleted by opening Shorts.** The owner sent the exact steps: trailer past
60%, minimize, tap the Shorts tab — and the trailer never scrobbled, while the same session done in
the opposite order scrobbled everything. When the foreground Shorts route takes ownership of the
player, the MediaSession stops counting so nothing is scored twice; it was also throwing away
whatever it had accumulated, with no finalization and no log line. Ten listens in one day's log went
that way, up to 168 seconds each. The listen is now finalized at the hand-off unless the Short taking
over is the very same item.

**Every non-ASCII handle was invisible.** The previous session left an open question — a refusal that
said only `expected exactly one exact visible owner handle`, 352 times, with no way to tell an absent
handle from an ambiguous one. Making the refusal say what the footer held answered it in three
minutes: `Go to channel @eduardaarebouçass`, refused on the cedilla by an ASCII-only pattern. Since
v0.9.10 the handle is the one mandatory field, so every creator whose handle is not spelled in ASCII
was never seen at all. Handles are now letters in any script, composed to NFC, on the footer and in
the owner-profile URL alike.

**One listen, finalized twice.** Found while verifying the above: a Short that banks a complete listen
while still on screen was finalized again the moment anything replaced it, and only the dedup ledger
stopped the second write.
