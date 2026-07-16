# WHA Magic changelog

## Unreleased

## [0.4.1] - 2026-07-16

### Added

- Added direct per-symbol recognition-quality metrics and deterministic spell-quality tiers.
- Added ring-diameter size scaling, seal-size tiers, and centrally derived spell parameters.
- Added compact quality, size, power, range, and duration estimates to spell previews and saved-paper tooltips.
- Added detailed quality and parameter diagnostics to development previews and recorded samples.

### Changed

- Upgraded authoritative stored spells to format version 4 with authenticated quality, scaling settings, and derived parameters.
- Made version 3 stored spells stale so they are recompiled once from raw strokes.
- Scaled the existing Water, Fire, Wind, and Earth prototypes from bounded compiled parameters instead of repeated-sign counts.
- Added configurable, validated magic-scaling references and multiplier caps.

### Fixed

- Capped elemental particle work, entity-query radii, effect durations, effect amplifiers, and Earth dimensions.

## [0.4.0] - 2026-07-16

### Added

- Added compiled per-sigil and per-sign geometry, identity, confidence, orientation, layer, and source ownership.
- Added ring geometry, directional bias, radial symmetry, bilateral symmetry, and sign-balance metrics to compiled spells.

### Changed

- Upgraded authoritative stored spells to format version 3 with complete geometry authentication.
- Made version 2 stored spells stale so they are recompiled once from their raw strokes.
- Kept current spell casting behavior through derived compatibility views over the compiled glyph lists.

## [0.3.2-alpha.2] - 2026-07-14

### Added

- Added server-bound spell-paper edit revisions with original item/component hashes.
- Added explicit Save and Cancel controls with clear stale-session and drawing-limit feedback.
- Added sortable F5 sample filenames containing validity and recognized symbol labels.

### Changed

- Synchronized drawing limits from the server when opening the editor.
- Replaced double-coordinate save packets with bounded compact points and VarInt collection sizes.
- Added minimum-distance point sampling, cached point totals, debounced previews, and bounded undo/redo history.
- Reduced temporary allocations in point-cloud and ring matching.
- Simplified dictionary publication and development sample serialization.
- Removed unused legacy grouping, layering, and complexity helpers.
- Standardized project documentation, source formatting rules, and build files.

### Fixed

- Rejected saves when the edited paper moved, changed, was replaced, or no longer matched the active session.
- Made Cancel close the editor without changing the held spell paper.

## [0.3.2-alpha.1] - 2026-07-13

### Changed

- Added recognizer-neutral symbol results and propagated sigil semantics into compiled spells.
- Added per-symbol point-cloud acceptance rules, including structural and soft stroke-count controls.
- Added explicit unknown-ink classification so ambiguous, substantial, or budget-skipped ink fails closed while harmless micro-noise remains valid.

### Fixed

- Preserved full recognized-sign provenance and immutable source ownership through the recognition pipeline.
- Allowed equivalent open symbols drawn with merged or split pen strokes without lowering recognition thresholds.

## [0.2.0] - 2026-07-12

### Added

- Added multi-sigil recognition so the parser can compile multiple separate sigils and signs from one drawing.
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
