package com.maxello1.whamagic.magic;

/** Per-symbol acceptance and structural rules parsed from dictionary JSON. */
public record SymbolRecognitionRules(
    double minimumScore,
    double minimumGap,
    double minimumComplexity,
    double minimumDimensionRatio,
    boolean allowLineLike,
    int minimumClosedContours,
    int softMinimumStrokeCount,
    int softMaximumStrokeCount
) {
    /**
     * A negative contour count derives the requirement from the canonical
     * template. A zero soft maximum derives it from the template stroke count.
     * Soft bounds reduce confidence; they never reject a candidate by themselves.
     */
    public static final SymbolRecognitionRules SIGIL_DEFAULTS =
            new SymbolRecognitionRules(0.20, 0.02, 0.40, 0.12, false, -1, 1, 0);
    public static final SymbolRecognitionRules SIGN_DEFAULTS =
            new SymbolRecognitionRules(0.20, 0.02, 0.20, 0.05, true, -1, 1, 0);

    public SymbolRecognitionRules {
        requireUnitInterval(minimumScore, "minimumScore");
        requireUnitInterval(minimumGap, "minimumGap");
        if (minimumComplexity < 0 || !Double.isFinite(minimumComplexity)) {
            throw new IllegalArgumentException("minimumComplexity must be finite and non-negative");
        }
        requireUnitInterval(minimumDimensionRatio, "minimumDimensionRatio");
        if (minimumClosedContours < -1 || softMinimumStrokeCount < 0 || softMaximumStrokeCount < 0) {
            throw new IllegalArgumentException("contour and stroke-count rules are out of range");
        }
        if (softMaximumStrokeCount > 0 && softMaximumStrokeCount < softMinimumStrokeCount) {
            throw new IllegalArgumentException("softMaximumStrokeCount must not be below the soft minimum");
        }
    }

    private static void requireUnitInterval(double value, String name) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
    }
}
