# WHA-Magic Changelog

## [0.2.0] - 2026-07-12
### Added
- Implemented Multi-Sigil Recognition! The spell parser can now recognize multiple separate sigils and signs within a single drawing and compile them together.
- Added a Local Recognition Preview (toggle with F3 while drawing) to debug recognized bounding boxes, element types, and signs.
- Added proper missing textures for the Ink Wand and Spell Paper items.

### Fixed
- Fixed a bug where drawing a circle slowly would cut off mid-stroke due to strict network point limits (increased point limits).
- Upgraded the Ring Detector to properly identify spell circles drawn using multiple strokes, seamlessly merging them together.
- Fixed item definitions to correctly load custom models in modern versions (1.21.4+ / 26.2).

## [0.1.0] - 2026-07-11
### Added
- Integrated initial spell simulator recognition engine into Minecraft.
- `SpellPaperItem` to allow players to draw sigils and signs using a UI.
- Initial dictionary of element sigils and signs based on the Witch Hat Atelier magic system.
- Gestural drawing interface with ink rendering.

### Fixed
- Fixed an issue where the circular ring stroke was incorrectly classified due to its center of mass, which caused any spell with a ring to default to a Wind spell.
- Fixed an issue where large core sigils (like the Earth sigil) were improperly fragmented into "sign" strokes.
- Improved stroke identification logic to measure average point radius rather than stroke center of mass, vastly improving recognition accuracy for complex sigils.

### Changed
- Mod version updated from `1.0.0` to `0.1.0` to better reflect the current early alpha stage of development.
