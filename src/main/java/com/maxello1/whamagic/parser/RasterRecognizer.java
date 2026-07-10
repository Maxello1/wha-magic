package com.maxello1.whamagic.parser;

import com.maxello1.whamagic.WitchHatMod;

import java.util.ArrayList;
import java.util.List;

/**
 * Raster-based template matcher ported from wha-spell-simulator's templateMatcher.js.
 *
 * Instead of comparing raw point clouds mathematically, this engine:
 * 1. Renders both the candidate drawing and the dictionary template onto a 40x40 pixel grid.
 * 2. Simulates three ink thicknesses (Core=1px, Soft=2px, Loose=4px).
 * 3. Measures pixel-level overlap using Dice coefficients and region grid analysis.
 * 4. Combines ink overlap with structural features (stroke count, aspect ratio, stroke profiles).
 *
 * This approach is practically immune to minor handwriting variations and scales perfectly
 * because it measures visual space instead of mathematical point distance.
 */
public class RasterRecognizer {

    private static final int INK_SIZE = 40;
    private static final int CORE_RADIUS = 1;
    private static final int SOFT_RADIUS = 2;
    private static final int LOOSE_RADIUS = 4;
    private static final int SAMPLES_PER_STROKE = 40;
    private static final int REGION_GRID_SIZE = 10;
    private static final double MIN_CONFIDENCE = 0.48;
    private static final double AMBIGUITY_GAP = 0.065;

    private static final List<RasterTemplate> templates = new ArrayList<>();

    // ---- Data Structures ----

    public static class RasterTemplate {
        public final String id;
        public final String displayName;
        public final String kind; // "sigil" or "sign"
        public final String element;
        public final List<List<Point>> rawStrokes;
        public final InkLayers ink; // pre-rendered at load time
        public final TemplateFeatures features;

        public RasterTemplate(String id, String displayName, String kind, String element, List<List<Point>> strokes) {
            this.id = id;
            this.displayName = displayName;
            this.kind = kind;
            this.element = element;
            // Filter out single-point strokes (decoration markers in the JSON)
            List<List<Point>> filteredStrokes = new ArrayList<>();
            for (List<Point> stroke : strokes) {
                if (stroke != null && stroke.size() >= 2) {
                    filteredStrokes.add(stroke);
                }
            }
            this.rawStrokes = filteredStrokes;

            TemplateNormalizer.NormalizedResult norm = TemplateNormalizer.normalize(filteredStrokes, SAMPLES_PER_STROKE);
            this.ink = renderInk(norm.strokes, 0);
            this.features = extractTemplateFeatures(filteredStrokes, norm);
            WitchHatMod.LOGGER.info("Loaded raster template '{}': {} strokes, aspect={}, coreInk={}",
                    id, filteredStrokes.size(), norm.sourceAspectRatio, this.ink.coreInk);
        }
    }

    public static class InkLayers {
        public final byte[] coreMask;
        public final byte[] softMask;
        public final byte[] looseMask;
        public final int coreInk;
        public final int softInk;
        public final int looseInk;

        public InkLayers(byte[] core, byte[] soft, byte[] loose, int coreInk, int softInk, int looseInk) {
            this.coreMask = core;
            this.softMask = soft;
            this.looseMask = loose;
            this.coreInk = coreInk;
            this.softInk = softInk;
            this.looseInk = looseInk;
        }
    }

    public static class TemplateFeatures {
        public final double aspectRatio;
        public final int strokeCount;
        public final double[] strokeProfile;

        public TemplateFeatures(double aspectRatio, int strokeCount, double[] strokeProfile) {
            this.aspectRatio = aspectRatio;
            this.strokeCount = strokeCount;
            this.strokeProfile = strokeProfile;
        }
    }

    public static class RecognitionResult {
        public final boolean recognized;
        public final String id;
        public final String displayName;
        public final String kind;
        public final String element;
        public final double score;

        public RecognitionResult(boolean recognized, String id, String displayName, String kind, String element, double score) {
            this.recognized = recognized;
            this.id = id;
            this.displayName = displayName;
            this.kind = kind;
            this.element = element;
            this.score = score;
        }
    }

    // ---- Public API ----

    public static void addTemplate(String id, String displayName, String kind, String element, List<List<Point>> strokes) {
        templates.add(new RasterTemplate(id, displayName, kind, element, strokes));
    }

    public static void clearTemplates() {
        templates.clear();
    }

    public static int getTemplateCount() {
        return templates.size();
    }

    /**
     * Recognize drawn strokes against all registered templates.
     * Uses the raster ink overlay + structural feature scoring.
     */
    public static RecognitionResult recognize(List<List<Point>> strokes) {
        if (strokes == null || strokes.isEmpty()) {
            return new RecognitionResult(false, null, "No strokes", "unknown", null, 0);
        }

        // Normalize candidate strokes
        TemplateNormalizer.NormalizedResult candidateNorm = TemplateNormalizer.normalize(strokes, SAMPLES_PER_STROKE);
        if (candidateNorm.strokes.isEmpty()) {
            return new RecognitionResult(false, null, "No valid strokes", "unknown", null, 0);
        }

        InkLayers candidateInk = renderInk(candidateNorm.strokes, 0);

        // Extract candidate structural features
        double[] candidateProfile = computeStrokeProfile(strokes);
        double candidateAspectRatio = candidateNorm.sourceAspectRatio;
        int candidateStrokeCount = strokes.size();

        // Score against every template
        double bestScore = -1;
        double secondScore = -1;
        RasterTemplate bestTemplate = null;

        for (RasterTemplate template : templates) {
            // 1) Ink overlap score
            InkScores inkScores = compareInk(candidateInk, template.ink);

            // 2) Structural compatibility score
            double aspectScore = aspectCompatibility(candidateAspectRatio, template.features.aspectRatio);
            double countScore = strokeCountCompatibility(candidateStrokeCount, template.features.strokeCount);
            double profileScore = profileCompatibility(candidateProfile, template.features.strokeProfile);

            double structuralScore = clamp(aspectScore * 0.54 + profileScore * 0.28 + countScore * 0.18);

            // 3) Combined contextual score (matching symbolRecognizer.js weights)
            double contextual = inkScores.inkScore * 0.68 + structuralScore * 0.13 + 0.1 + 0.04 + 0.05;
            double confidence = clamp(Math.min(contextual, inkScores.inkScore + 0.035));

            // Apply contamination cap
            if (inkScores.unexplainedInkRatio > 0.36 && inkScores.templateCoveredRatio < 0.82) {
                double cap = clamp(0.62 - (inkScores.unexplainedInkRatio - 0.36) * 0.8, 0.2, 1.0);
                confidence = Math.min(confidence, cap);
            }

            WitchHatMod.LOGGER.info("  vs '{}': ink={} explained={} covered={} dice={} struct={} (aspect={} count={} profile={}) -> conf={}",
                    template.id,
                    String.format("%.3f", inkScores.inkScore),
                    String.format("%.3f", inkScores.candidateExplainedRatio),
                    String.format("%.3f", inkScores.templateCoveredRatio),
                    String.format("%.3f", inkScores.softDiceScore),
                    String.format("%.3f", structuralScore),
                    String.format("%.3f", aspectScore),
                    String.format("%.3f", countScore),
                    String.format("%.3f", profileScore),
                    String.format("%.3f", confidence));

            if (confidence > bestScore) {
                secondScore = bestScore;
                bestScore = confidence;
                bestTemplate = template;
            } else if (confidence > secondScore) {
                secondScore = confidence;
            }
        }

        if (bestTemplate == null) {
            return new RecognitionResult(false, null, "No templates", "unknown", null, 0);
        }

        // Check ambiguity
        boolean ambiguous = (bestScore - secondScore) < AMBIGUITY_GAP && secondScore >= 0;

        if (bestScore >= MIN_CONFIDENCE && !ambiguous) {
            return new RecognitionResult(true, bestTemplate.id, bestTemplate.displayName,
                    bestTemplate.kind, bestTemplate.element, bestScore);
        } else {
            return new RecognitionResult(false, bestTemplate.id,
                    bestTemplate.displayName + " (" + String.format("%.0f%%", bestScore * 100) + ")",
                    bestTemplate.kind, bestTemplate.element, bestScore);
        }
    }

    // ---- Ink Rendering ----

    /**
     * Render strokes onto a INK_SIZE x INK_SIZE pixel grid with three thickness layers.
     */
    private static InkLayers renderInk(List<List<Point>> strokes, double rotationDeg) {
        int size = INK_SIZE;
        byte[] coreMask = new byte[size * size];
        byte[] softMask = new byte[size * size];
        byte[] looseMask = new byte[size * size];

        double cos = 1, sin = 0;
        if (rotationDeg != 0) {
            double rad = Math.toRadians(rotationDeg);
            cos = Math.cos(rad);
            sin = Math.sin(rad);
        }

        for (List<Point> stroke : strokes) {
            if (stroke == null || stroke.isEmpty()) continue;

            List<Point> rotated;
            if (rotationDeg != 0) {
                rotated = new ArrayList<>();
                for (Point p : stroke) {
                    double rx = (p.x - 0.5) * cos - (p.y - 0.5) * sin + 0.5;
                    double ry = (p.x - 0.5) * sin + (p.y - 0.5) * cos + 0.5;
                    rotated.add(new Point(rx, ry));
                }
            } else {
                rotated = stroke;
            }

            if (rotated.size() == 1) {
                markInk(coreMask, softMask, looseMask, size, rotated.get(0).x, rotated.get(0).y);
                continue;
            }

            for (int i = 1; i < rotated.size(); i++) {
                Point start = rotated.get(i - 1);
                Point end = rotated.get(i);
                drawSegment(coreMask, softMask, looseMask, size, start, end);
            }
        }

        return new InkLayers(coreMask, softMask, looseMask,
                countInk(coreMask), countInk(softMask), countInk(looseMask));
    }

    private static void markInk(byte[] core, byte[] soft, byte[] loose, int size, double x, double y) {
        markMask(core, size, x, y, CORE_RADIUS);
        markMask(soft, size, x, y, SOFT_RADIUS);
        markMask(loose, size, x, y, LOOSE_RADIUS);
    }

    private static void markMask(byte[] mask, int size, double x, double y, int radius) {
        int centerX = (int) Math.round(clamp(x) * (size - 1));
        int centerY = (int) Math.round(clamp(y) * (size - 1));
        int radiusSq = radius * radius;

        for (int oy = -radius; oy <= radius; oy++) {
            for (int ox = -radius; ox <= radius; ox++) {
                if (ox * ox + oy * oy > radiusSq) continue;
                int px = centerX + ox;
                int py = centerY + oy;
                if (px < 0 || px >= size || py < 0 || py >= size) continue;
                mask[py * size + px] = 1;
            }
        }
    }

    private static void drawSegment(byte[] core, byte[] soft, byte[] loose, int size, Point start, Point end) {
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(dx, dy) * size * 2));

        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            markInk(core, soft, loose, size, start.x + dx * t, start.y + dy * t);
        }
    }

    private static int countInk(byte[] mask) {
        int count = 0;
        for (byte b : mask) count += b;
        return count;
    }

    // ---- Ink Comparison ----

    private static class InkScores {
        double inkScore;
        double candidateExplainedRatio;
        double templateCoveredRatio;
        double softDiceScore;
        double unexplainedInkRatio;
        double missingInkRatio;
        double requiredCellCoverage;
        double forbiddenCellInkRatio;
    }

    private static InkScores compareInk(InkLayers candidate, InkLayers reference) {
        InkScores scores = new InkScores();

        if (candidate.coreInk == 0 || reference.coreInk == 0) {
            scores.inkScore = 0;
            scores.unexplainedInkRatio = 1;
            scores.missingInkRatio = 1;
            scores.forbiddenCellInkRatio = 1;
            return scores;
        }

        // How much of the candidate is explained by the template's loose ink
        scores.candidateExplainedRatio = clamp(
                (double) maskOverlap(candidate.coreMask, reference.looseMask) / candidate.coreInk);
        // How much of the template is covered by the candidate's loose ink
        scores.templateCoveredRatio = clamp(
                (double) maskOverlap(reference.coreMask, candidate.looseMask) / reference.coreInk);
        // Dice coefficient for soft layers
        scores.softDiceScore = diceScore(candidate.softMask, reference.softMask,
                candidate.softInk, reference.softInk);

        scores.unexplainedInkRatio = clamp(1 - scores.candidateExplainedRatio);
        scores.missingInkRatio = clamp(1 - scores.templateCoveredRatio);

        // Region grid analysis
        byte[] candidateCoreCells = occupiedCells(candidate.coreMask);
        byte[] candidateLooseCells = occupiedCells(candidate.looseMask);
        byte[] referenceCoreCells = occupiedCells(reference.coreMask);
        byte[] referenceLooseCells = occupiedCells(reference.looseMask);

        int requiredCount = 0, requiredCovered = 0;
        int candidateCount = 0, forbiddenCount = 0;

        for (int i = 0; i < referenceCoreCells.length; i++) {
            if (referenceCoreCells[i] != 0) {
                requiredCount++;
                if (candidateLooseCells[i] != 0) requiredCovered++;
            }
            if (candidateCoreCells[i] != 0) {
                candidateCount++;
                if (referenceLooseCells[i] == 0) forbiddenCount++;
            }
        }

        scores.requiredCellCoverage = requiredCount > 0 ? (double) requiredCovered / requiredCount : 0;
        scores.forbiddenCellInkRatio = candidateCount > 0 ? (double) forbiddenCount / candidateCount : 1;

        // Combined ink score (matching templateMatcher.js weights)
        scores.inkScore = clamp(
                scores.candidateExplainedRatio * 0.32 +
                scores.templateCoveredRatio * 0.32 +
                scores.softDiceScore * 0.14 +
                scores.requiredCellCoverage * 0.16 +
                (1 - scores.forbiddenCellInkRatio) * 0.06
        );

        return scores;
    }

    private static int maskOverlap(byte[] a, byte[] b) {
        int overlap = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != 0 && b[i] != 0) overlap++;
        }
        return overlap;
    }

    private static double diceScore(byte[] a, byte[] b, int aInk, int bInk) {
        if (aInk == 0 || bInk == 0) return 0;
        return clamp((double)(maskOverlap(a, b) * 2) / (aInk + bInk));
    }

    private static byte[] occupiedCells(byte[] mask) {
        int size = INK_SIZE;
        int gridSize = REGION_GRID_SIZE;
        byte[] cells = new byte[gridSize * gridSize];

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (mask[y * size + x] == 0) continue;
                int cellX = Math.min(gridSize - 1, (int) ((double) x / size * gridSize));
                int cellY = Math.min(gridSize - 1, (int) ((double) y / size * gridSize));
                cells[cellY * gridSize + cellX] = 1;
            }
        }
        return cells;
    }

    // ---- Structural Features ----

    private static TemplateFeatures extractTemplateFeatures(List<List<Point>> rawStrokes, TemplateNormalizer.NormalizedResult norm) {
        double aspect = norm.sourceAspectRatio;
        int strokeCount = rawStrokes.size();
        double[] profile = computeStrokeProfile(rawStrokes);
        return new TemplateFeatures(aspect, strokeCount, profile);
    }

    private static double[] computeStrokeProfile(List<List<Point>> strokes) {
        List<Double> lengths = new ArrayList<>();
        for (List<Point> stroke : strokes) {
            double len = pathLength(stroke);
            if (len > 0.0001) lengths.add(len);
        }
        lengths.sort((a, b) -> Double.compare(b, a)); // descending

        double total = 0;
        for (double l : lengths) total += l;
        if (total == 0) return new double[0];

        double[] profile = new double[lengths.size()];
        for (int i = 0; i < lengths.size(); i++) {
            profile[i] = lengths.get(i) / total;
        }
        return profile;
    }

    private static double aspectCompatibility(double candidateRatio, double templateRatio) {
        double distance = Math.abs(Math.log(candidateRatio / Math.max(0.001, templateRatio)));
        return clamp(1 - distance / 1.1);
    }

    private static double strokeCountCompatibility(int candidateCount, int templateCount) {
        if (candidateCount == 0 || templateCount == 0) return 0;
        return clamp(1.0 - (double) Math.abs(candidateCount - templateCount) / Math.max(candidateCount, templateCount));
    }

    private static double profileCompatibility(double[] candidateProfile, double[] templateProfile) {
        int count = Math.max(candidateProfile.length, templateProfile.length);
        if (count == 0) return 1;

        double distance = 0;
        for (int i = 0; i < count; i++) {
            double c = i < candidateProfile.length ? candidateProfile[i] : 0;
            double t = i < templateProfile.length ? templateProfile[i] : 0;
            distance += Math.abs(c - t);
        }
        return clamp(1 - distance / 1.4);
    }

    // ---- Utility ----

    private static double pathLength(List<Point> points) {
        double d = 0;
        for (int i = 1; i < points.size(); i++) {
            d += Math.hypot(points.get(i).x - points.get(i - 1).x, points.get(i).y - points.get(i - 1).y);
        }
        return d;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
