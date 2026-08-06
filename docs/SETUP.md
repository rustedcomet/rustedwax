# Setup

Installing, granting access, and adding your posting key.

[← Back to the README](../README.md)

---

## Setup

1. Install the APK.
2. Enter your **Hive username** and **posting key** (WIF, starts with `5…`).
   The app derives the public key and checks it against your account's on-chain `posting.key_auths`
   before accepting it — a wrong key is rejected immediately, offline-verifiable against
   a current healthy Hive node.
3. Grant Notification Access when prompted.
4. Optionally enable **Browser evidence access** (for browser `url` evidence and exact visible
   YouTube ad labels) — on Android 13+, allow restricted settings from App info first.
5. Optionally enable **Native YouTube** and/or **Native YouTube Music**. Both are off by default.
   Foreground native Shorts additionally require the separate **Foreground Shorts evidence**
   accessibility grant, which is OS-scoped only to the YouTube package. PiP/background Shorts are
   intentionally not counted.
6. Play something in Brave, Chrome or an enabled native package. The **Now** tab shows the source
   package/origin, live session and the exact payload
   it would broadcast; **History** shows the newest 50 results for the current app process, with tx
   ids. History is diagnostic memory, not permanent local storage; the chain remains authoritative.

The Now tab's **Broadcast this scrobble** button applies the same configured threshold, duration
floors, verified-short gate, mute list, kind cap, and dedup ledger as automatic finalization. The
Account tab's synthetic broadcast remains a separate transport test; it does not claim to represent
a watched video.

### About your posting key

The key is stored in `EncryptedSharedPreferences` backed by the Android Keystore. **There is no
biometric gate yet** — an unlocked phone can sign. The key never leaves the device — signing is
local, and only the signed transaction is sent to a Hive node.

Be aware this is a **larger blast radius than Keychain on desktop**: a raw posting key can post,
comment, and vote as you, with no per-operation prompt. If you want that reduced:

- Generate a fresh keypair, add its public key to your account's posting authority (from desktop
  Keychain / Hive Blog → Wallet → Permissions), and give the app *that* key. You can revoke it later
  without rotating the posting key you use everywhere else.

The app never asks for your active, owner, or memo key. If anything ever does, it isn't this app.
