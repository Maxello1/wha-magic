package com.maxello1.whamagic.magic;

import com.maxello1.whamagic.parser.BoundingBox;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Deterministic analysis of drawing precision, layout intent, and unexplained ink. */
public final class SpellQualityAnalyzer {
    /** Confidence is deliberately only one quarter of per-symbol precision. */
    public static final double SYMBOL_CONFIDENCE_WEIGHT = 0.25;
    public static final double SYMBOL_TEMPLATE_COVERAGE_WEIGHT = 0.25;
    public static final double SYMBOL_EXPLAINED_RATIO_WEIGHT = 0.20;
    public static final double SYMBOL_STRUCTURAL_WEIGHT = 0.20;
    public static final double SYMBOL_CLEANLINESS_WEIGHT = 0.10;

    /** Overall weights keep ring craft most visible while retaining every component. */
    public static final double OVERALL_RING_WEIGHT = 0.30;
    public static final double OVERALL_SIGIL_WEIGHT = 0.25;
    public static final double OVERALL_SIGN_WEIGHT = 0.20;
    public static final double OVERALL_LAYOUT_WEIGHT = 0.15;
    public static final double OVERALL_INK_WEIGHT = 0.10;

    public static final double RING_COMPLETENESS_WEIGHT = 0.35;
    public static final double RING_CIRCULARITY_WEIGHT = 0.25;
    public static final double RING_RMSE_WEIGHT = 0.40;

    /** Layout weights favor semantic placement and facing over optional symmetry. */
    public static final double LAYOUT_RADIAL_WEIGHT = 0.40;
    public static final double LAYOUT_ORIENTATION_WEIGHT = 0.30;
    public static final double LAYOUT_CROWDING_WEIGHT = 0.20;
    public static final double LAYOUT_REPEAT_CONSISTENCY_WEIGHT = 0.07;
    public static final double LAYOUT_SYMMETRY_BONUS_WEIGHT = 0.03;

    public static final double STABILITY_OVERALL_WEIGHT = 0.55;
    public static final double STABILITY_RING_WEIGHT = 0.25;
    public static final double STABILITY_LAYOUT_WEIGHT = 0.20;

    static final double ACCEPTED_RING_RMSE_LIMIT = 0.08;
    static final double UNAVAILABLE_CIRCULARITY_SCORE = 0.85;
    static final double NOISE_PENALTY_PER_STROKE = 0.015;
    static final double HARMLESS_PENALTY_PER_STROKE = 0.05;
    /** Current sign semantics are defined for the middle and outer layers. */
    static final double CORE_LAYER_SUITABILITY = 0.55;
    static final double INNER_LAYER_SUITABILITY = 0.80;
    static final double MIDDLE_LAYER_SUITABILITY = 1.00;
    static final double OUTER_LAYER_SUITABILITY = 1.00;
    private static final double INVALID_INK_DIAGNOSTIC_PENALTY = 0.15;

    private SpellQualityAnalyzer() {}

    public static SpellQuality analyze(
            SpellGeometry geometry,
            List<CompiledSigil> sigils,
            List<CompiledSign> signs,
            List<ClassifiedUnknownInk> unknownInk) {
        if (geometry == null) return SpellQuality.UNASSESSED;
        List<CompiledSigil> safeSigils = sigils == null ? List.of() : sigils;
        List<CompiledSign> safeSigns = signs == null ? List.of() : signs;
        List<ClassifiedUnknownInk> safeUnknownInk =
                unknownInk == null ? List.of() : unknownInk;

        double ringPrecision = ringPrecision(geometry);
        double sigilPrecision = averageSigilPrecision(safeSigils);
        double signPrecision = safeSigns.isEmpty()
                ? 1.0
                : averageSignPrecision(safeSigns);
        double layoutPrecision = layoutPrecision(geometry, safeSigils, safeSigns);
        double inkCleanliness = inkCleanliness(safeUnknownInk);
        double overall = clamp01(
                OVERALL_RING_WEIGHT * ringPrecision
                        + OVERALL_SIGIL_WEIGHT * sigilPrecision
                        + OVERALL_SIGN_WEIGHT * signPrecision
                        + OVERALL_LAYOUT_WEIGHT * layoutPrecision
                        + OVERALL_INK_WEIGHT * inkCleanliness);
        double stability = clamp01(
                STABILITY_OVERALL_WEIGHT * overall
                        + STABILITY_RING_WEIGHT * ringPrecision
                        + STABILITY_LAYOUT_WEIGHT * layoutPrecision);
        return SpellQuality.assessed(
                overall,
                ringPrecision,
                sigilPrecision,
                signPrecision,
                layoutPrecision,
                inkCleanliness,
                stability);
    }

    static double ringPrecision(SpellGeometry geometry) {
        double completenessScore = clamp01(
                (geometry.ringCompleteness() - 0.50) / 0.50);
        double circularityScore = geometry.ringCircularity() <= 0.0
                ? UNAVAILABLE_CIRCULARITY_SCORE
                : clamp01(geometry.ringCircularity());
        double normalizedRmseScore = clamp01(
                1.0 - geometry.ringNormalizedRmse() / ACCEPTED_RING_RMSE_LIMIT);
        return clamp01(
                RING_COMPLETENESS_WEIGHT * completenessScore
                        + RING_CIRCULARITY_WEIGHT * circularityScore
                        + RING_RMSE_WEIGHT * normalizedRmseScore);
    }

    public static double symbolPrecision(
            double confidence,
            RecognitionQualityMetrics metrics) {
        RecognitionQualityMetrics safeMetrics = metrics == null
                ? RecognitionQualityMetrics.NEUTRAL
                : metrics;
        return clamp01(
                SYMBOL_CONFIDENCE_WEIGHT * clamp01(confidence)
                        + SYMBOL_TEMPLATE_COVERAGE_WEIGHT * safeMetrics.templateCoverage()
                        + SYMBOL_EXPLAINED_RATIO_WEIGHT * safeMetrics.candidateExplainedRatio()
                        + SYMBOL_STRUCTURAL_WEIGHT * safeMetrics.structuralScore()
                        + SYMBOL_CLEANLINESS_WEIGHT
                                * (1.0 - safeMetrics.unexplainedInkRatio()));
    }

    private static double averageSigilPrecision(List<CompiledSigil> sigils) {
        if (sigils.isEmpty()) return 0.0;
        return sigils.stream()
                .mapToDouble(sigil -> symbolPrecision(
                        sigil.recognitionConfidence(), sigil.qualityMetrics()))
                .average()
                .orElse(0.0);
    }

    private static double averageSignPrecision(List<CompiledSign> signs) {
        return signs.stream()
                .mapToDouble(sign -> symbolPrecision(sign.confidence(), sign.qualityMetrics()))
                .average()
                .orElse(1.0);
    }

    static double layoutPrecision(
            SpellGeometry geometry,
            List<CompiledSigil> sigils,
            List<CompiledSign> signs) {
        double radial = signs.isEmpty()
                ? 1.0
                : signs.stream()
                        .mapToDouble(sign -> radialPlacementScore(geometry, sign))
                        .average().orElse(1.0);
        double orientation = signs.isEmpty()
                ? 1.0
                : signs.stream()
                        .mapToDouble(sign -> orientationScore(geometry, sign))
                        .average().orElse(1.0);
        double crowding = crowdingScore(sigils, signs);
        double repeatedConsistency = repeatedRadialConsistency(signs);
        double symmetryBonus = (
                geometry.radialSymmetryScore()
                        + geometry.bilateralSymmetryScore()
                        + geometry.signBalanceScore()) / 3.0;
        return clamp01(
                LAYOUT_RADIAL_WEIGHT * radial
                        + LAYOUT_ORIENTATION_WEIGHT * orientation
                        + LAYOUT_CROWDING_WEIGHT * crowding
                        + LAYOUT_REPEAT_CONSISTENCY_WEIGHT * repeatedConsistency
                        + LAYOUT_SYMMETRY_BONUS_WEIGHT * symmetryBonus);
    }

    private static double radialPlacementScore(
            SpellGeometry geometry,
            CompiledSign sign) {
        double radialPosition = sign.radialPosition();
        double centerScore;
        if (radialPosition < SpellLayer.CORE_MAX_RADIAL_POSITION) {
            centerScore = 0.70 + 0.30
                    * radialPosition / SpellLayer.CORE_MAX_RADIAL_POSITION;
        } else if (radialPosition <= 1.05) {
            centerScore = 1.0;
        } else {
            centerScore = clamp01(1.0 - (radialPosition - 1.05) / 0.30);
        }
        if (sign.layer() != SpellLayer.fromRadialPosition(radialPosition)) {
            centerScore = 0.0;
        } else {
            centerScore *= switch (sign.layer()) {
                case CORE -> CORE_LAYER_SUITABILITY;
                case INNER -> INNER_LAYER_SUITABILITY;
                case MIDDLE -> MIDDLE_LAYER_SUITABILITY;
                case OUTER -> OUTER_LAYER_SUITABILITY;
            };
        }

        double farthestCorner = 0.0;
        double[] xs = {sign.bounds().minX(), sign.bounds().maxX()};
        double[] ys = {sign.bounds().minY(), sign.bounds().maxY()};
        for (double x : xs) {
            for (double y : ys) {
                farthestCorner = Math.max(
                        farthestCorner,
                        Math.hypot(
                                x - geometry.ringCenter().x,
                                y - geometry.ringCenter().y)
                                / geometry.ringRadius());
            }
        }
        double edgeScore = farthestCorner <= 1.05
                ? 1.0
                : clamp01(1.0 - (farthestCorner - 1.05) / 0.35);
        return clamp01(0.70 * centerScore + 0.30 * edgeScore);
    }

    private static double orientationScore(
            SpellGeometry geometry,
            CompiledSign sign) {
        String directionMode = sign.semantic().directionMode();
        if (directionMode == null) return 1.0;
        String normalizedMode = directionMode.toLowerCase(Locale.ROOT);
        if (normalizedMode.equals("orientation")
                || normalizedMode.equals("none")
                || normalizedMode.equals("unknown")
                || normalizedMode.isBlank()) {
            return 1.0;
        }
        if (!normalizedMode.equals("inward") && !normalizedMode.equals("outward")) {
            return 1.0;
        }

        double dx = sign.centroid().x - geometry.ringCenter().x;
        double dy = sign.centroid().y - geometry.ringCenter().y;
        double length = Math.hypot(dx, dy);
        if (length <= 1.0e-12) return 1.0;
        double radialX = dx / length;
        double radialY = dy / length;
        double radians = Math.toRadians(sign.orientationDegrees());
        double facingX = Math.cos(radians);
        double facingY = Math.sin(radians);
        double desiredSign = normalizedMode.equals("inward") ? -1.0 : 1.0;
        double dot = desiredSign * (facingX * radialX + facingY * radialY);
        return clamp01((dot + 1.0) / 2.0);
    }

    private static double crowdingScore(
            List<CompiledSigil> sigils,
            List<CompiledSign> signs) {
        List<BoundingBox> bounds = new ArrayList<>(sigils.size() + signs.size());
        sigils.forEach(sigil -> bounds.add(sigil.bounds()));
        signs.forEach(sign -> bounds.add(sign.bounds()));
        if (bounds.size() <= 1) return 1.0;

        double overlap = 0.0;
        int pairs = 0;
        for (int left = 0; left < bounds.size(); left++) {
            for (int right = left + 1; right < bounds.size(); right++) {
                overlap += normalizedOverlap(bounds.get(left), bounds.get(right));
                pairs++;
            }
        }
        return clamp01(1.0 - 1.25 * overlap / Math.max(1, pairs));
    }

    private static double normalizedOverlap(BoundingBox left, BoundingBox right) {
        double width = Math.max(0.0,
                Math.min(left.maxX(), right.maxX()) - Math.max(left.minX(), right.minX()));
        double height = Math.max(0.0,
                Math.min(left.maxY(), right.maxY()) - Math.max(left.minY(), right.minY()));
        double intersection = width * height;
        if (intersection <= 0.0) return 0.0;
        double leftArea = Math.max(1.0e-12, left.width() * left.height());
        double rightArea = Math.max(1.0e-12, right.width() * right.height());
        return clamp01(intersection / Math.min(leftArea, rightArea));
    }

    private static double repeatedRadialConsistency(List<CompiledSign> signs) {
        if (signs.isEmpty()) return 1.0;
        Map<Identifier, List<Double>> radialById = new LinkedHashMap<>();
        for (CompiledSign sign : signs) {
            radialById.computeIfAbsent(sign.semanticId(), ignored -> new ArrayList<>())
                    .add(sign.radialPosition());
        }
        double total = 0.0;
        for (List<Double> positions : radialById.values()) {
            if (positions.size() <= 1) {
                total += 1.0;
                continue;
            }
            double mean = positions.stream().mapToDouble(Double::doubleValue)
                    .average().orElse(0.0);
            double variance = positions.stream()
                    .mapToDouble(position -> {
                        double difference = position - mean;
                        return difference * difference;
                    })
                    .average().orElse(0.0);
            total += 1.0 - clamp01(Math.sqrt(variance) / 0.20);
        }
        return clamp01(total / radialById.size());
    }

    static double inkCleanliness(List<ClassifiedUnknownInk> unknownInk) {
        double penalty = 0.0;
        for (ClassifiedUnknownInk ink : unknownInk) {
            if (ink == null || ink.classification() == null) continue;
            int strokeWeight = Math.max(1, ink.sourceStrokeIndices().size());
            penalty += switch (ink.classification()) {
                case NOISE -> NOISE_PENALTY_PER_STROKE * strokeWeight;
                case HARMLESS_UNEXPLAINED -> HARMLESS_PENALTY_PER_STROKE * strokeWeight;
                case AMBIGUOUS, SUBSTANTIAL_UNKNOWN, BUDGET_SKIPPED ->
                        INVALID_INK_DIAGNOSTIC_PENALTY * strokeWeight;
            };
        }
        return clamp01(1.0 - penalty);
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }
}
