# Planned, not yet implemented

Work that is designed but not built. Nothing here is in the app today.

[← Back to the README](../README.md)

---

## Privacy mode — planned, not yet implemented

**Every payload the app broadcasts today is plaintext on-chain.** Privacy mode is Phase 5 (it was
originally Phase 4; the control/exclusivity/fidelity work preempted it — see PHASE4.md).

The planned design matches the extension, per content kind (music / videos / podcasts / movies-tv):
only `app`, `kind` and `timestamp` stay public; everything else goes into a base64
`IV‖ciphertext+tag` AES-256-GCM blob under `private`, with `v: 1`. The AES key derives as on
desktop — `SHA-256` of a posting-key signature over the fixed challenge `zingit:privacy-key:v1` —
and because Hive's ECDSA is deterministic, the phone will derive the **same key** the extension does,
so blobs written on mobile will decrypt on desktop and on zingit-web.
