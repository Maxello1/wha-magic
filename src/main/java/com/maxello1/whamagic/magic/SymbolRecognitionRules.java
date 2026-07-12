package com.maxello1.whamagic.magic;

/**
 * Per-symbol recognition configuration, parsed from dictionary JSON.
 * Allows tuning recognition behavior per template.
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
