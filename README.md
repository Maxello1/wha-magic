# WHA Magic

WHA Magic is a Minecraft Fabric mod for Minecraft 26.2 and Java 25 that
introduces a drawing-based magic system. It matches drawn strokes against a
versioned symbol dictionary using point-cloud recognition with geometric and
structural validation.

## Features

- **Server-authoritative parsing:** Spell gestures are evaluated on the server.
- **Dictionary-backed recognition:** Detects spell rings, segments the remaining
  ink, and matches candidates against validated sigil and sign templates.
- **Configurable limits:** Uses `wha-magic-server.json` to configure spell-casting
  rate limits, network point limits, and effect settings.

## Setup

To build the mod:

```sh
./gradlew clean build
```

## Development documentation

- [Dictionary snapshots](docs/dictionary-snapshots.md)
- [Recognition rules](docs/recognition-rules.md)
- [Sample promotion](docs/sample-promotion.md)

## Licensing

WHA Magic uses a mixed licensing structure.

Original WHA Magic code, assets, documentation, integrations, gameplay
systems, and other original materials are licensed under the
[WHA Magic Restricted Use License](LICENSE).

You may use the unmodified official mod on Minecraft servers and include the
unmodified JAR in modpacks. Modification, redistribution of modified builds,
commercial resale of the mod itself, and reuse of original WHA Magic code
require prior written permission.

Certain spell-recognition components are ported or adapted from
[WHA Spell Simulator](https://github.com/ytnrvdf/wha-spell-simulator) and
remain subject to its MIT License.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the applicable
third-party notices and license terms.
