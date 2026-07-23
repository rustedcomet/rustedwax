# Testing RustedWax v0.3.1

`dist/rustedwax-0.3.1.apk` — detection, key handling, and **automatic**
scrobbling. This is the first build that works unattended: turn it on, play
music, and entries land on-chain by themselves.

RustedWax is an independent app. It is not part of scrobble.life, Hive Scrobbler
or Web Scrobbler, and is not supported by them — see the README.

Automatic scrobbling is **off until you enable it**, and the switch is disabled
until a key is saved.

## Before you start

Broadcasts are **permanent and public**. Use a throwaway Hive account, or a
posting key you're willing to rotate. See "About your posting key" in the app.

Ideally: from desktop Keychain, add a fresh public key to your account's posting
authority and give the app that key. Then revoking it later costs you nothing.

## Install

```bash
~/Library/Android/sdk/platform-tools/adb install -r dist/rustedwax-0.3.1.apk
```

## 1. Key validation (no chain writes)

Account tab → enter your Hive username and posting key → **Validate & save**.

The app derives the public key locally and compares it against your account's
on-chain `posting.key_auths`. Things worth trying:

| Input | Expected |
| --- | --- |
| Correct username + posting key | "Key verified … and saved", the `STM…` key shown |
| Correct key, wrong username | Rejected, naming the key it derived vs what the account lists |
| A typo'd key | Rejected as not a valid posting key (checksum fails locally, no network call) |
| Your **public** key pasted in | Rejected — it isn't a WIF |
| Airplane mode | "Couldn't reach a Hive node" — never a silent pass |

Nothing is written to the chain by any of this. The key is stored in
`EncryptedSharedPreferences` under an Android Keystore master key.

## 2. Test broadcast (writes to the chain)

Account tab → **Broadcast a test scrobble**. Sends a fixed payload with
`title: "RustedWax Mobile test scrobble"`, `platform: "test"` so it's obviously
synthetic.

### Where the transaction id appears

In the app, in two places:

1. **Account tab**, in the status line under the buttons:
   `Broadcast accepted by api.openhive.network — tx f0569fe0…`
2. **Log tab**, which also records the exact payload sent, tagged `[hive]`.
   Long-press or use **Export** to get the text off the device.

The id is computed on-device (`sha256(serialized tx)[0..20]`) because
`condenser_api.broadcast_transaction` returns an empty result on success. It's
the same id the explorers use — verified against dhive in the unit tests.

### Where to look it up

Paste the id into any Hive explorer:

```
https://hiveblocks.com/tx/f0569fe0...
```

Alternatives if one is down or slow to index: `https://hivehub.dev/tx/<txid>`,
or `https://hivexplorer.com/tx/<txid>`.

**Easier route — skip the id entirely.** Open your account's history and look at
the most recent operation:

```
https://hiveblocks.com/@your-username
```

A successful scrobble shows as a `custom_json` operation. Expand it and check:

- `id` → `hive_scrobble_ai`
- `required_posting_auths` → `["your-username"]`
- `json` → the payload, with `app: hivescrobblesai/1.0`

**Timing:** a transaction takes ~3 seconds to enter a block, and explorers can
lag a few seconds more. If the tx page 404s immediately after broadcasting, wait
10 seconds and reload before concluding anything went wrong — the account
history view is usually the faster of the two to update.

This is **gate G4** — the one thing the unit tests can't prove. The signing math
is verified byte-for-byte against dhive, but only a real broadcast proves the
whole chain of assumptions.

Errors worth recognising:

| Message | Meaning |
| --- | --- |
| `missing required posting authority` | The key doesn't actually hold posting authority — validation should have caught this |
| `insufficient RC` / `resource credits` | Account is out of resource credits; not a bug |
| `Couldn't reach a node` | Transport; all three nodes failed |

## 3. Real scrobble from YouTube

1. Play a YouTube video in Chrome (or Brave, on a real device).
2. Now tab → the session card appears.
3. Check the **payload** block — this is what would be written:
   - `artist` should be the *real* artist, not the channel. For
     `"Korn - Trash (Official Audio)"` on channel `KornVEVO`, expect
     `artist: Korn`, `title: Trash`.
   - `kind` is `song` when the channel is VEVO/Topic or the title splits into
     `Artist - Track`; `video` otherwise.
4. **Broadcast this scrobble** → verify on hiveblocks as above.

The badge tells you whether it's broadcastable:

| Badge | Meaning |
| --- | --- |
| `payload OK` | Proven YouTube *with* a video id — full payload including `url` |
| `payload OK (no url)` | Proven YouTube via the notification origin; no `url` (the expected case on Chromium) |
| `not proven YouTube` | Can't identify the site — deliberately refuses to guess |
| `no duration` | No DURATION, so percent-played can't be computed |
| `control` | Not a target browser; observed only |

## 4. Automatic scrobbling (the Phase 3 feature)

Flip **Automatic scrobbling** on at the top of the screen, then play a track in
Chrome/Brave and **let it finish** — or skip to the next track once you're past
60%. A scrobble fires when the track *ends*, not when it crosses the threshold.

Then check the **History** tab: each entry shows percent played, status, and the
transaction id.

What to try:

| Case | Expected |
| --- | --- |
| Play a song past 60%, then skip to the next | One scrobble at the percent you reached |
| Play a song to the end | One scrobble at 100% |
| Skip out at 20% | Nothing — Log tab says "below 60% threshold" |
| Let the same song play twice through (~170%) | **Two** scrobbles: 100% and 70%, matching the extension's double-listen rule |
| Replay a song immediately | Second one blocked — Log says "already scrobbled" |
| Play with the app closed | Still scrobbles; detection lives in the listener service |
| Turn on airplane mode, play a song through | Scrobble is queued, "N waiting to send" appears; turn networking back on and hit **Retry now** |
| A pre-roll ad | Ignored — Log says "shorter than 30s (probably an ad)" |

Watch the **Log** tab while testing. Every decision is recorded: `[finalize]`
when a track ends, then `[engine]` with either the payload broadcast or the
reason it was skipped.

### Does it survive the app being closed?

This is the one behaviour that needs a **physical device** — BlueStacks won't
tell you anything useful. Swipe the app away, keep playing music, and check the
History tab later. Detection runs inside the notification listener, which
Android keeps bound while the grant is held.

If the screen says "Waiting for Android to start the listener service", toggle
Notification Access off and on.

## 5. Compare against desktop

The strongest check: scrobble the same song from the desktop extension and from
the phone, then compare the two `custom_json` payloads field by field. They
should differ only in `platform`, `url`, `percent_played` and `timestamp`.
Any difference in `title` or `artist` is a metadata-filter gap worth reporting.

## Running the unit tests

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest
```

37 tests: 17 crypto/serialization vectors generated by dhive (the library
Keychain signs with), 11 title-parsing cases, 9 threshold cases.

## Known gaps in v0.3.0

- **Desktop and phone together will double-scrobble.** Dedup is per-device; the
  cross-device check is not implemented. If you run the extension and the app on
  the same account at the same time, expect two scrobbles per listen.
- **No biometric gate** on the stored key — an unlocked phone can sign.
- **No `url` field.** A media session doesn't expose the video id, so phone
  scrobbles have no link back to the video. Desktop scrobbles do.
- **Stale site hints aren't discarded.** If the browser changes tabs mid-track,
  the origin used for identification could belong to a different page.
- **No privacy mode.** All payloads are plaintext on-chain. Phase 4.
- **Brave unverified.** All detection findings so far come from Chrome on
  BlueStacks. Brave on a physical device is still untested — including whether
  it sets the same notification sub-text Chrome does.
