package com.maxello1.whamagic.magic;

/** Direct, recognizer-produced measurements for one selected symbol. */
public record RecognitionQualityMetrics(
        double templateCoverage,
        double candidateExplainedRatio,
        double unexplainedInkRatio,
        double structuralScore
) {
    public static final RecognitionQualityMetrics NEUTRAL =
            new RecognitionQualityMetrics(1.0, 1.0, 0.0, 1.0);
    /** Preserves the recognizer's historical all-zero early-rejection diagnostics. */
    public static final RecognitionQualityMetrics UNASSESSED =
            new RecognitionQualityMetrics(0.0, 0.0, 0.0, 0.0);

    public RecognitionQualityMetrics {
        templateCoverage = finiteUnit("templateCoverage", templateCoverage);
        candidateExplainedRatio = finiteUnit(
                "candidateExplainedRatio", candidateExplainedRatio);
        unexplainedInkRatio = finiteUnit("unexplainedInkRatio", unexplainedInkRatio);
        structuralScore = finiteUnit("structuralScore", structuralScore);
    }

    private static double finiteUnit(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
