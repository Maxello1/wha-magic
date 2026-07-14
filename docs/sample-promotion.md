# Development sample promotion

F5 recordings are written to `run/dev-samples` with `sampleRole` set to
`experimental`. They retain unrounded `rawStrokes` plus the recognizer version,
dictionary version/hash, source date, empty expected intent, notes, and whether
the sample influenced a template or threshold. Filenames begin with the sortable
recording timestamp and end with the current validity and recognized symbol
summary, such as `sample_2026-07-14_04-15-30-120_valid_earth_levitation-x2.json`.

Preview and promote a recording with:

```powershell
.\gradlew promoteSample -Psample="run/dev-samples/sample_....json"
```

To print diagnostics without starting the promotion prompts, add
`-PpreviewOnly`:

```powershell
.\gradlew promoteSample -Psample="run/dev-samples/sample_....json" -PpreviewOnly
```

The tool prints the current server-authoritative parser result and bounded-work
diagnostics before prompting for all fixture metadata. It requires one explicit
role:

- `training_candidate`: may be evaluated for future template work;
- `holdout`: independent evaluation data and never training input;
- `negative_confusion`: a known non-symbol or confusion case;
- `experimental`: exploratory data with no training or holdout claim.

Expected sigils and signs are entered separately and preserve multiplicity. The
output uses `strokes` and `expectedIntent`, while copying every numeric value
from `rawStrokes` without normalization or rounding. A holdout cannot be marked
as having influenced a template or threshold.
