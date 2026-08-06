# RustedWax

**An independent Android app that writes what you listen to on your phone to the Hive blockchain.**

Brave and Chrome on Android support no extensions, so the desktop scrobbling flow has nowhere to
run. RustedWax watches playback on the device itself — the native YouTube app and, optionally, the
browser — verifies what was actually played, and posts the same `custom_json` format the desktop
tools already read.

```
playback → measured → identified → verified → signed → Hive
```

Every entry requires a verified video id and a real hyperlink. Anything that cannot be proven stays
off-chain and is listed, with its reason, under **Not logged**.

---

## Documentation

| | |
| --- | --- |
| [Overview](docs/OVERVIEW.md) | Why this exists and what problem it solves |
| [Setup](docs/SETUP.md) | Install, grants, and adding your posting key |
| [How it works](docs/HOW_IT_WORKS.md) | The detection pipeline, end to end |
| [Detection sources](docs/DETECTION.md) | Native YouTube apps and optional browser evidence |
| [Identifying the video](docs/IDENTITY.md) | Lookups, watch history, MusicBrainz, and the YouTube policy tradeoff |
| [Scrobble rules](docs/SCROBBLE_RULES.md) | What counts as a listen, and why each refusal exists |
| [On-chain format](docs/ON_CHAIN_FORMAT.md) | The exact payload, artist/title derivation, and `kind` |
| [Limitations](docs/LIMITATIONS.md) | What the desktop extension can do that this cannot |
| [Roadmap](docs/ROADMAP.md) | Designed but not yet built |

### Engineering records

| | |
| --- | --- |
| [Behavior contract](BEHAVIOR_CONTRACT.md) | The canonical product invariants and as-built audit |
| [Field results](docs/FIELD_HISTORY.md) | Every device round from v0.8.8, with the evidence behind each fix |
| [Field session 2026-08-05/06](FIELD_2026-08-05.md) | The most recent sessions: PiP measurement, the identity rebuild, the owner acceptance day, and what §5.1 turned out to be |
| [Release history](docs/RELEASE_HISTORY.md) | Version-by-version specification status |
| [Testing](TESTING.md) | How the suite is structured and run |
| [Development plan](DEVELOPMENT_PLAN.md) | Phased build plan and the port map from the extension |

Phase documents ([Phase 0](PHASE0.md), [Phase 4](PHASE4.md),
[native apps](PHASE_NATIVE_APPS.md), [Shorts](PHASE_NATIVE_SHORTS.md),
[playlists](PHASE_NATIVE_PLAYLIST.md), [playlist identity](PHASE_NATIVE_PLAYLIST_IDENTITY.md),
[watch history](PHASE_NATIVE_HISTORY.md)) are historical records. They do not override the behavior
contract.

---

## Status

Prototype. Personal concept-testing project, not a policy-cleared release.

### Not affiliated with scrobble.life

**RustedWax is a personal, unofficial project.** It is not part of the
[scrobble.life](https://scrobble.life) project, not built by them, not endorsed by them, and not
supported by them. It is not affiliated with Hive Scrobbler or Web Scrobbler either.

It writes the same `custom_json` format those projects read, so entries it posts show up alongside
theirs — but that is the whole of the relationship. **Do not report RustedWax problems to
scrobble.life or to the Hive Scrobbler maintainers.** Anything broken here is broken here.

### YouTube access

The optional **Look videos up** feature fetches YouTube watch, playlist and search pages and calls
an undocumented YouTube Music endpoint. It is deliberately retained during prototyping, but it
relies on unsupported interfaces, can break without notice, and conflicts with YouTube's written
restrictions on automated access. Personal use creates no exception to those terms. A distribution
candidate should replace or remove that path and ship a privacy policy.

Full detail: [YouTube policy and prototype tradeoff](docs/IDENTITY.md).

---

## Development

See [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md) for the phased build plan, the file-by-file port map
from the extension, and the compatibility test vectors that must pass before shipping. Native-app
implementation and device acceptance are tracked in [PHASE_NATIVE_APPS.md](PHASE_NATIVE_APPS.md).

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
```

## License

[MIT](LICENSE). Portions adapted from upstream projects remain subject to their original attribution
and license requirements.
