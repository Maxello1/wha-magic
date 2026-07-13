# Recognition rule defaults

The v0.3.2 data-model integration keeps the point-cloud recognizer's existing
global acceptance values as the per-symbol defaults:

- `minimumScore`: `0.20` for sigils and signs. This value did not change.
- `minimumGap`: `0.02` for sigils and signs. This value did not change.
- `minimumComplexity`: `0.40` for sigils and `0.20` for signs. These existing
  dictionary defaults are now enforced as scale-independent path length divided
  by drawing diagonal.
- `minimumDimensionRatio`: `0.12` for sigils and `0.05` for signs. These existing
  values are now enforced by the point-cloud recognizer when `allowLineLike` is
  false.
- `minimumClosedContours`: `-1` derives the requirement from the canonical
  template. This preserves closed topology without encoding symbol-specific
  geometry in the recognizer.
- `softMinimumStrokeCount`: `1`.
- `softMaximumStrokeCount`: `0` derives the soft maximum from the canonical
  template's stroke count. Counts outside the soft bounds reduce confidence by
  a square-root ratio; they do not cause universal rejection. This permits
  equivalent open symbols drawn with merged or split pen strokes.

No dictionary score or gap threshold was lowered. The former hard rejection at
more than twice the template stroke count was removed because pen-lift count is
not symbol topology; super-candidate selection and unknown-ink validation remain
the safeguards against merged unrelated symbols.

Unknown ink retains the existing micro-noise limits: fewer than four points,
path length below `0.10`, or both dimensions below `0.07`. A new harmless class
is limited to one stroke with path length at most `0.18` and bounding diagonal at
most `0.18`. Those limits describe short incidental marks; larger unexplained or
ambiguous input invalidates compiled spell IR.
