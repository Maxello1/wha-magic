package com.maxello1.whamagic.magic;

import com.maxello1.whamagic.parser.Point;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class SpellCompiler {
    static final double PLACEMENT_BIAS_WEIGHT = 2.0 / 3.0;
    static final double ORIENTATION_BIAS_WEIGHT = 1.0 / 3.0;
    private static final int MAX_EXACT_BILATERAL_SIGNS = 16;

    private SpellCompiler() {}

    public static SpellIr compile(GlyphAst ast) {
        return compile(ast, true);
    }

    /**
     * Compile an AST and fail closed when any bounded recognition search was incomplete.
     * The partial interpretation is retained for diagnostics, but can never be saved or cast.
     */
    public static SpellIr compile(GlyphAst ast, boolean recognitionComplete) {
        SpellIr result = compileComplete(ast);
        if (recognitionComplete) {
            return result;
        }

        String detail = result.displayName().isEmpty()
                ? "Incomplete recognition search"
                : result.displayName() + " (incomplete recognition search)";
        return new SpellIr(
                SpellState.INVALID,
                GlyphWarning.INCOMPLETE_RECOGNITION,
                result.compiledSigils(),
                result.compiledSigns(),
                result.geometry(),
                result.displayName(),
                "Invalid: " + detail);
    }

    private static SpellIr compileComplete(GlyphAst ast) {
        if (ast.ring() == null) {
            String message = ast.sigils().isEmpty()
                    ? "Drafting: Needs Ring"
                    : "Drafting: " + ast.sigils().getFirst().id().getPath();
            return new SpellIr(
                    SpellState.INVALID, GlyphWarning.MISSING_RING,
                    List.of(), List.of(), null, "", message);
        }

        if (ast.sigils().isEmpty()) {
            return new SpellIr(
                    SpellState.INVALID, GlyphWarning.MISSING_CORE_SIGIL,
                    List.of(), List.of(), compileGeometry(ast.ring(), List.of()), "",
                    "Drafting: Missing Core Sigil");
        }

        List<CompiledSigil> compiledSigils = new ArrayList<>();
        List<String> displayNames = new ArrayList<>();
        boolean invalidGlyph = false;
        for (RecognizedSigil sigil : ast.sigils()) {
            displayNames.add(sigil.displayName());
            if (sigil.element() == null || sigil.centroid() == null || sigil.bounds() == null) {
                invalidGlyph = true;
                continue;
            }
            compiledSigils.add(new CompiledSigil(
                    sigil.id(),
                    sigil.matchedTemplateId(),
                    sigil.displayName(),
                    sigil.element(),
                    sigil.semantic(),
                    sigil.recognitionConfidence(),
                    sigil.centroid(),
                    sigil.bounds(),
                    sigil.orientationDeg(),
                    sigil.sourceStrokeIndices()));
        }

        List<CompiledSign> compiledSigns = new ArrayList<>();
        for (RecognizedSign sign : ast.signs()) {
            Identifier semanticId = Identifier.tryParse(sign.id());
            if (semanticId == null || sign.centroid() == null || sign.bounds() == null) {
                invalidGlyph = true;
                continue;
            }
            double radialPosition = Math.hypot(
                    sign.centroid().x - ast.ring().center().x,
                    sign.centroid().y - ast.ring().center().y) / ast.ring().radius();
            if (!Double.isFinite(radialPosition) || radialPosition < 0.0) {
                invalidGlyph = true;
                continue;
            }
            compiledSigns.add(new CompiledSign(
                    semanticId,
                    sign.matchedTemplateId(),
                    sign.semantic(),
                    sign.confidence(),
                    sign.angleAroundRing(),
                    sign.orientationDeg(),
                    radialPosition,
                    SpellLayer.fromRadialPosition(radialPosition),
                    sign.centroid(),
                    sign.bounds(),
                    sign.sourceStrokeIndices(),
                    false));
        }

        boolean isClosed = ast.ring().isClosed();
        boolean isStrong = ast.ring().completeness() > 0.5;

        SpellState state = SpellState.DRAFT;
        GlyphWarning warning = null;
        if (isClosed) {
            state = SpellState.ACTIVE;
        } else if (isStrong) {
            state = SpellState.PREPARED;
        } else {
            warning = GlyphWarning.WEAK_RING;
        }

        if (invalidGlyph) {
            state = SpellState.INVALID;
            warning = GlyphWarning.INVALID_SIGNS;
        }

        boolean hasAmbiguousInk = false;
        boolean hasSubstantialUnknownInk = false;
        boolean hasBudgetSkippedInk = false;
        for (ClassifiedUnknownInk ink : ast.unknownInk()) {
            UnknownInkClassification classification = ink.classification();
            if (classification == null) continue;
            switch (classification) {
                case AMBIGUOUS -> hasAmbiguousInk = true;
                case SUBSTANTIAL_UNKNOWN -> hasSubstantialUnknownInk = true;
                case BUDGET_SKIPPED -> hasBudgetSkippedInk = true;
                default -> {
                }
            }
        }
        if (hasAmbiguousInk || hasSubstantialUnknownInk || hasBudgetSkippedInk) {
            state = SpellState.INVALID;
            warning = hasAmbiguousInk
                    ? GlyphWarning.AMBIGUOUS_INK
                    : hasBudgetSkippedInk
                        ? GlyphWarning.INCOMPLETE_RECOGNITION
                        : GlyphWarning.SUBSTANTIAL_UNKNOWN_INK;
        }

        Map<Identifier, Integer> signCounts = new LinkedHashMap<>();
        for (CompiledSign sign : compiledSigns) {
            signCounts.merge(sign.semanticId(), 1, Integer::sum);
        }
        String displayName = String.join(" + ", displayNames);
        if (!signCounts.isEmpty()) {
            displayName += " [" + signCounts.entrySet().stream()
                    .map(entry -> entry.getKey().getPath() + " x" + entry.getValue())
                    .collect(Collectors.joining(", ")) + "]";
        }

        String statusPrefix = switch (state) {
            case ACTIVE -> "Active: ";
            case PREPARED -> "Prepared: ";
            case DRAFT -> "Drafting: ";
            case INVALID -> "Invalid: ";
        };
        String statusMessage = statusPrefix + displayName;
        if (warning != null) {
            statusMessage += " (" + warning.name() + ")";
        }

        return new SpellIr(
                state,
                warning,
                compiledSigils,
                compiledSigns,
                compileGeometry(ast.ring(), compiledSigns),
                displayName,
                statusMessage);
    }

    static SpellGeometry compileGeometry(
            RingDetector.RingGlyph ring,
            List<CompiledSign> signs) {
        Point placementMean = placementMean(ring.center(), signs);
        Point orientationMean = orientationMean(signs);
        Point directionalBias = new Point(
                -PLACEMENT_BIAS_WEIGHT * placementMean.x
                        + ORIENTATION_BIAS_WEIGHT * orientationMean.x,
                -PLACEMENT_BIAS_WEIGHT * placementMean.y
                        + ORIENTATION_BIAS_WEIGHT * orientationMean.y);

        return new SpellGeometry(
                ring.center(),
                ring.radius(),
                Math.PI * ring.radius() * ring.radius(),
                ring.radius() * 2.0,
                ring.completeness(),
                ring.circularity(),
                ring.normalizedRmse(),
                directionalBias,
                radialSymmetryScore(signs),
                bilateralSymmetryScore(ring.center(), ring.radius(), signs),
                clamp01(1.0 - Math.hypot(placementMean.x, placementMean.y)));
    }

    private static Point placementMean(Point center, List<CompiledSign> signs) {
        if (signs.isEmpty()) return new Point(0.0, 0.0);
        double x = 0.0;
        double y = 0.0;
        for (CompiledSign sign : signs) {
            double dx = sign.centroid().x - center.x;
            double dy = sign.centroid().y - center.y;
            double length = Math.hypot(dx, dy);
            if (length > 1.0e-12) {
                x += dx / length;
                y += dy / length;
            }
        }
        return new Point(x / signs.size(), y / signs.size());
    }

    private static Point orientationMean(List<CompiledSign> signs) {
        if (signs.isEmpty()) return new Point(0.0, 0.0);
        double x = 0.0;
        double y = 0.0;
        for (CompiledSign sign : signs) {
            double radians = Math.toRadians(sign.orientationDegrees());
            x += Math.cos(radians);
            y += Math.sin(radians);
        }
        return new Point(x / signs.size(), y / signs.size());
    }

    private static double radialSymmetryScore(List<CompiledSign> signs) {
        int count = signs.size();
        if (count <= 1) return 1.0;

        double[] angles = signs.stream()
                .mapToDouble(sign -> normalizeDegrees(sign.angleAroundRing()))
                .sorted()
                .toArray();
        double idealGap = 360.0 / count;
        double squaredGapError = 0.0;
        for (int index = 0; index < count; index++) {
            double next = index + 1 < count ? angles[index + 1] : angles[0] + 360.0;
            double error = next - angles[index] - idealGap;
            squaredGapError += error * error;
        }
        double angularScore = 1.0 - clamp01(
                Math.sqrt(squaredGapError / count) / idealGap);

        double meanRadius = signs.stream()
                .mapToDouble(CompiledSign::radialPosition)
                .average()
                .orElse(0.0);
        double squaredRadiusError = signs.stream()
                .mapToDouble(sign -> {
                    double error = sign.radialPosition() - meanRadius;
                    return error * error;
                })
                .average()
                .orElse(0.0);
        double radialScore = meanRadius <= 1.0e-12
                ? 1.0
                : 1.0 - clamp01(Math.sqrt(squaredRadiusError) / meanRadius);
        return clamp01(angularScore * radialScore);
    }

    private static double bilateralSymmetryScore(
            Point center,
            double radius,
            List<CompiledSign> signs) {
        if (signs.isEmpty()) return 1.0;

        List<Point> positions = signs.stream()
                .map(sign -> new Point(
                        (sign.centroid().x - center.x) / radius,
                        (sign.centroid().y - center.y) / radius))
                .toList();
        Set<Double> axes = new LinkedHashSet<>();
        for (int degrees = 0; degrees < 180; degrees++) {
            axes.add((double) degrees);
        }
        for (CompiledSign sign : signs) {
            double angle = normalizeAxisDegrees(sign.angleAroundRing());
            axes.add(angle);
            axes.add(normalizeAxisDegrees(angle + 90.0));
        }
        if (signs.size() <= MAX_EXACT_BILATERAL_SIGNS) {
            for (int left = 0; left < signs.size(); left++) {
                for (int right = left + 1; right < signs.size(); right++) {
                    double a = normalizeDegrees(signs.get(left).angleAroundRing());
                    double b = normalizeDegrees(signs.get(right).angleAroundRing());
                    double delta = normalizeSignedDegrees(b - a);
                    axes.add(normalizeAxisDegrees(a + delta / 2.0));
                    axes.add(normalizeAxisDegrees(a + delta / 2.0 + 90.0));
                }
            }
        }

        double best = 0.0;
        for (double axisDegrees : axes) {
            double axis = Math.toRadians(axisDegrees);
            double cosine = Math.cos(2.0 * axis);
            double sine = Math.sin(2.0 * axis);
            double totalDistance = 0.0;
            for (Point source : positions) {
                double reflectedX = cosine * source.x + sine * source.y;
                double reflectedY = sine * source.x - cosine * source.y;
                double closest = Double.POSITIVE_INFINITY;
                for (Point target : positions) {
                    closest = Math.min(
                            closest,
                            Math.hypot(reflectedX - target.x, reflectedY - target.y));
                }
                totalDistance += closest;
            }
            double score = 1.0 - clamp01(totalDistance / positions.size() / 2.0);
            best = Math.max(best, score);
        }
        return clamp01(best);
    }

    private static double normalizeDegrees(double degrees) {
        double normalized = degrees % 360.0;
        return normalized < 0.0 ? normalized + 360.0 : normalized;
    }

    private static double normalizeAxisDegrees(double degrees) {
        double normalized = degrees % 180.0;
        return normalized < 0.0 ? normalized + 180.0 : normalized;
    }

    private static double normalizeSignedDegrees(double degrees) {
        double normalized = normalizeDegrees(degrees);
        return normalized > 180.0 ? normalized - 360.0 : normalized;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
