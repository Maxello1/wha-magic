# WHA Magic

WHA Magic is a Minecraft Fabric mod (targeted for Minecraft 26.2, Java 25) that introduces a drawing-based magic system.
It parses drawn strokes into spells based on elements and signs, evaluating geometric and structural characteristics instead of rigid templates.

## Features
- **Server-Authoritative Parsing:** Spell gestures are evaluated securely on the server.
- **Robust Stroke Recognition:** Identifies core ring structures, categorizes strokes by distance and layer, and resolves sigils/signs algorithmically.
- **Configurable:** Uses `wha-magic-server.json` to define limits on spell casting rate, network points, and effects.

## Setup
To build the mod:
```sh
./gradlew clean build
```

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
