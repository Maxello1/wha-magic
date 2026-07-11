# WHA-Magic Changelog

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
