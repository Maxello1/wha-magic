# Atomic dictionary snapshots

Dictionary resources are parsed and validated into temporary definitions. Both
recognizers then build their preprocessed template state before one active
snapshot is published. A failed initial load remains unloaded; a failed reload
retains the previous snapshot.

Every entry requires a semantic `id`, `displayName`, `semantic`, and
`strokeTemplate.strokes`; sigils also require `element`. An optional
`templateId` identifies a visual variant and defaults to the semantic ID.
Variant IDs must be unique. Multiple variants may share a semantic ID only when
their symbol kind, display name, element, semantics, and recognition rules are
identical.

Validation rejects missing resources or fields, malformed/non-finite values,
invalid identifiers and semantics, duplicate visual IDs, empty geometry, and
point-cloud templates beyond supported meaningful-stroke complexity. Snapshot
metadata exposes a deterministic dictionary version and SHA-256 resource hash.
