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
        RecognitionQualityMetrics qualityMetrics
) {
    public SymbolRecognitionResult {
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        rejectionReason = rejectionReason == null ? RecognitionRejectionReason.NONE : rejectionReason;
        qualityMetrics = qualityMetrics == null
                ? (recognized ? RecognitionQualityMetrics.NEUTRAL
                        : RecognitionQualityMetrics.UNASSESSED)
                : qualityMetrics;
    }

    /** Historical score-shaped diagnostic retained for compatibility snapshots. */
    public double templateCoverage() {
        return id == null ? 0.0 : score;
    }

    public double candidateExplainedRatio() {
        return qualityMetrics.candidateExplainedRatio();
    }

    /** Historical score-shaped diagnostic retained for compatibility snapshots. */
    public double unexplainedInkRatio() {
        return id == null ? 0.0 : 1.0 - score;
    }

    /** Historical score-shaped diagnostic retained for compatibility snapshots. */
    public double structuralScore() {
        return id == null ? 0.0 : score;
    }

    /** Construct an early-exit rejection without template diagnostics. */
    public static SymbolRecognitionResult rejected(
            String displayName,
            RecognitionRejectionReason reason,
            double thresholdUsed) {
        return new SymbolRecognitionResult(
                false, null, null, displayName, null, null, 0.0,
                null, null, List.of(), 0.0, thresholdUsed, reason,
                RecognitionQualityMetrics.UNASSESSED);
    }
}
