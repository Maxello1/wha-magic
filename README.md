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
This project is licensed under the MIT License. See the `LICENSE` file for details.
Algorithm concepts and gesture templates are adapted from the [WHA Spell Simulator](https://github.com/Maxello1/wha-spell-simulator). See `THIRD_PARTY_NOTICES.md` for more information.
