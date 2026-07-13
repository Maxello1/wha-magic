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
allows up to three grouped strokes when every stroke is individually noise or
has path length at most `0.18` and bounding diagonal at most `0.18`. The small
group cap prevents candidate grouping from changing incidental marks into an
error while larger accumulations still fail closed. Larger unexplained or
ambiguous input invalidates compiled spell IR.

## Ring closure overtrace

Ring detection accepts a closed, single-stroke rough-circle profile when its
normalized fit error is at most `0.075`, maximum radial residual is at most
`0.12`, median tangent-to-radius alignment is at most `0.15`, 90th-percentile
alignment is at most `0.28`, and circularity is at least `0.90`. This narrow
profile handles the local path and radial error caused when a player briefly
overtraces a circle's closure. It does not relax symbol recognition thresholds.
Straight-edged polygons remain excluded by their tangent profile, and the
existing stricter ring limits continue to apply to all other candidates.
