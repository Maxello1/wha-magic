package com.maxello1.whamagic.magic;

import java.util.List;

/**
 * Recognizer-neutral result returned by every symbol recognition implementation.
 * Algorithm-specific matchers may compute these values differently, but parser
 * and selection code consume only this immutable contract.
 */
public record SymbolRecognitionResult(
        boolean recognized,
        String id,
        String matchedTemplateId,
        String displayName,
        SymbolKind kind,
        String element,
        double score,
        SigilSemantic sigilSemantic,
        SignSemantic signSemantic,
        List<RecognitionAlternative> alternatives,
        double confidenceGap,
        double thresholdUsed,
        RecognitionRejectionReason rejectionReason,
        double templateCoverage,
        double unexplainedInkRatio,
        double structuralScore
) {
    public SymbolRecognitionResult {
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        rejectionReason = rejectionReason == null ? RecognitionRejectionReason.NONE : rejectionReason;
    }

    /** Construct an early-exit rejection without template diagnostics. */
    public static SymbolRecognitionResult rejected(
            String displayName,
            RecognitionRejectionReason reason,
            double thresholdUsed) {
        return new SymbolRecognitionResult(
                false, null, null, displayName, null, null, 0.0,
                null, null, List.of(), 0.0, thresholdUsed, reason,
                0.0, 0.0, 0.0);
    }
}
