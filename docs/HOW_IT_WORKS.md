# How it works

The detection pipeline, end to end.

[← Back to the README](../README.md)

---

## How it works

1. You grant the app **Notification Access**. It's required twice over: Android gates
   `MediaSessionManager.getActiveSessions()` behind it, and the browser's media notification is the
   default source for the page's origin (`youtube.com`) when optional Browser evidence access is off.
2. The notification listener — which the system keeps alive for as long as the grant is held — hosts
   the session watcher. No foreground service, no persistent notification.
3. When an accepted browser—or an independently enabled native YouTube package—plays media, the OS
   media session exposes title, artist, album, duration and playback position. The app accumulates
   content played against the configured threshold (60% by default).
4. When a track ends, the app first requires one verified YouTube video id, builds the same
   `custom_json` payload with its canonical watch link, signs it with your posting key on-device,
   and broadcasts it. Before calling it a scrobble, it normally confirms that a
   healthy independent node has included it in a block. A transaction seen relaying in an
   independent healthy node's mempool is reported separately and is not retried, to avoid creating
   a permanent duplicate. Acceptance with no available confirmation is also reported separately; see
   [Why a scrobble is confirmed, not assumed](#why-a-scrobble-is-confirmed-not-assumed).
   Definite failures and offline sends are queued.
5. When it *doesn't* broadcast, the reason lands in the **Not logged** tab. A scrobbler that
   silently declines things is indistinguishable from a broken one.

**Stop** cuts all of that off. It tears down the session watcher, stops reading notification and
address-bar callbacks, and clears notification, URL, playlist, and carried-progress evidence —
nothing is observed while it's stopped, and the track playing when you press it is discarded rather
than scrobbled on the way out. Scrobbles already earned remain in the
offline queue and are eligible to send the next time the queue is flushed. Automatic scrobbling is
a separate, inner switch: turning *it* off leaves the app watching, which is how you check what a
title would have parsed to without writing anything to the chain.

```
Brave (playing) ──▶ Android MediaSession ─────┐
       media notification (origin) ───────────┼──▶ RustedWaxListenerService
       browser evidence (optional: url, id, ad UI) ┘   │  SessionProbe
                                                       │  track ends (last active evidence frozen)
                                          prefilter — reject what no lookup could rescue,
                                          so a shorts feed doesn't fetch per finalize
                                                       │
                                     verified-id recovery when needed, then
                                     enrichment (optional): YouTube Music catalogue,
                                     watch-page category + length + description
                                     credits, MusicBrainz
                                                       │
                                     ScrobbleRules (configured %, +160% songs only, length floor)
                                                       │
                                     MusicClassifier → kind: song / video
                                                       │
                                          DedupLedger + MutedVideos
                                                       │
                                       HiveScrobblePayload  ◀── same schema as extension
                                                       │
                                       local secp256k1 signing (posting key)
                                                       │
                                  broadcast to a node proven current,
                                  then confirm the tx reached a block
                                                       │  not confirmed / rate-limited
                                                BroadcastQueue ──▶ retry w/ backoff
```
