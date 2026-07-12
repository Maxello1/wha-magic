package com.maxello1.whamagic.magic;

/**
 * Per-symbol recognition configuration, parsed from dictionary JSON.
 * Allows tuning recognition behavior per template.
 *
 * <p><b>Current limitation:</b> These rules are loaded and stored in each
 * {@link com.maxello1.whamagic.parser.PointCloudRecognizer.PointCloudTemplate},
 * but point-cloud acceptance currently uses only the global {@code MIN_SCORE}
 * and {@code MIN_GAP} thresholds. Per-symbol rules are not yet applied during
 * matching. This will be wired in before large-scale dictionary expansion
 * (planned for v0.3.2+).</p>
 *
 * <p>Simple symbols like Column will eventually need different thresholds
 * from complex elemental sigils.</p>
 */
public record SymbolRecognitionRules(
    double minimumComplexity,
    boolean allowLineLike,
    double minimumDimensionRatio
) {
    /** Default rules for symbols without explicit overrides. */
    public static final SymbolRecognitionRules SIGIL_DEFAULTS = new SymbolRecognitionRules(0.4, false, 0.12);
    public static final SymbolRecognitionRules SIGN_DEFAULTS = new SymbolRecognitionRules(0.2, true, 0.05);
}
