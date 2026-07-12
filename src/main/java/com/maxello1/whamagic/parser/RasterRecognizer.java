/*
 * Portions of this file are ported or adapted from WHA Spell Simulator:
 * https://github.com/ytnrvdf/wha-spell-simulator
 *
 * Copyright (c) 2026 Nervadof
 * Licensed under the MIT License.
 * See THIRD_PARTY_NOTICES.md.
 *
 * Original WHA Magic additions and modifications:
 * Copyright (c) 2026 Maxello1.
 * Licensed under the WHA Magic Restricted Use License.
 */
package com.maxello1.whamagic.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(RasterRecognizer.class);

    private static final int INK_SIZE = 40;
    private static final int CORE_RADIUS = 1;
    private static final int SOFT_RADIUS = 2;
    private static final int LOOSE_RADIUS = 4;
    private static final int SAMPLES_PER_STROKE = 40;
    private static final int REGION_GRID_SIZE = 10;
    private static final double MIN_CONFIDENCE = 0.48;
    private static final double AMBIGUITY_GAP = 0.035;
    // Phase 2 acceptance gates
    private static final double MIN_TEMPLATE_COVERAGE_SIGIL = 0.35;
    private static final double MIN_TEMPLATE_COVERAGE_SIGN = 0.25;
    private static final double MAX_UNEXPLAINED_INK_SIGIL = 0.55;
    private static final double MAX_UNEXPLAINED_INK_SIGN = 0.65;
    private static final double MIN_COMPLEXITY_COMPAT = 0.15;
    private static final double MIN_DIMENSION_RATIO_SIGIL = 0.12;
    private static final double MIN_DIMENSION_RATIO_SIGN = 0.05;

    private static final List<RasterTemplate> templates = new ArrayList<>();

    // ---- Data Structures ----

    public static class RasterTemplate {
        public final String id;
        public final String displayName;
        public final com.maxello1.whamagic.magic.SymbolKind kind;
        public final String element;
        public final com.maxello1.whamagic.magic.SigilSemantic sigilSemantic;
        public final com.maxello1.whamagic.magic.SignSemantic signSemantic;
        public final List<List<Point>> rawStrokes;
        public final InkLayers ink; // pre-rendered at load time
        public final TemplateFeatures features;
        public final com.maxello1.whamagic.magic.TemplateComplexity complexity;
        public final com.maxello1.whamagic.magic.SymbolRecognitionRules recognitionRules;

        public RasterTemplate(String id, String displayName, com.maxello1.whamagic.magic.SymbolKind kind, String element, List<List<Point>> strokes,
                              com.maxello1.whamagic.magic.SigilSemantic sigilSem, com.maxello1.whamagic.magic.SignSemantic signSem,
                              com.maxello1.whamagic.magic.SymbolRecognitionRules rules) {
            this.id = id;
            this.displayName = displayName;
            this.kind = kind;
            this.element = element;
            this.sigilSemantic = sigilSem;
            this.signSemantic = signSem;
            this.recognitionRules = rules != null ? rules :
                    (kind == com.maxello1.whamagic.magic.SymbolKind.SIGIL
                            ? com.maxello1.whamagic.magic.SymbolRecognitionRules.SIGIL_DEFAULTS
                            : com.maxello1.whamagic.magic.SymbolRecognitionRules.SIGN_DEFAULTS);
            
            // Filter out 1-point decoration markers
            List<List<Point>> filteredStrokes = new ArrayList<>();
            for (List<Point> stroke : strokes) {
                if (stroke != null && stroke.size() >= 2) {
                    filteredStrokes.add(stroke);
                }
            }
            
            // Re-merge contiguous fragments left by SVG tracer
            this.rawStrokes = mergeFragmentedStrokes(filteredStrokes);

            TemplateNormalizer.NormalizedResult norm = TemplateNormalizer.normalize(this.rawStrokes, SAMPLES_PER_STROKE);
            this.ink = renderInk(norm.strokes);
            this.features = extractTemplateFeatures(this.rawStrokes, norm);
            this.complexity = computeTemplateComplexity(norm, this.ink, this.rawStrokes);
            
            LOGGER.debug("Loaded raster template '{}': {} strokes, aspect={}, coreInk={}, complexity.pathLen={}",
                    id, this.rawStrokes.size(), norm.sourceAspectRatio, this.ink.coreInk,
                    String.format("%.3f", this.complexity.pathLength()));
        }

        private static List<List<Point>> mergeFragmentedStrokes(List<List<Point>> strokes) {
            if (strokes == null || strokes.isEmpty()) return strokes;

            List<List<Point>> merged = new ArrayList<>();
            for (List<Point> s : strokes) {
                merged.add(new ArrayList<>(s));
            }

            boolean changed;
            do {
                changed = false;
                for (int i = 0; i < merged.size(); i++) {
                    for (int j = i + 1; j < merged.size(); j++) {
                        List<Point> s1 = merged.get(i);
                        List<Point> s2 = merged.get(j);

                        Point s1End = s1.get(s1.size() - 1);
                        Point s2Start = s2.get(0);
                        Point s1Start = s1.get(0);
                        Point s2End = s2.get(s2.size() - 1);

                        double dist1 = Math.hypot(s2Start.x - s1End.x, s2Start.y - s1End.y);
                        double dist2 = Math.hypot(s1Start.x - s2End.x, s1Start.y - s2End.y);
                        double dist3 = Math.hypot(s2Start.x - s1Start.x, s2Start.y - s1Start.y);
                        double dist4 = Math.hypot(s2End.x - s1End.x, s2End.y - s1End.y);

                        double minDist = Math.min(Math.min(dist1, dist2), Math.min(dist3, dist4));

                        if (minDist < 0.05) {
                            if (minDist == dist1) {
                                s1.addAll(s2);
                            } else if (minDist == dist2) {
                                s2.addAll(s1);
                                merged.set(i, s2);
                            } else if (minDist == dist3) {
                                Collections.reverse(s1);
                                s1.addAll(s2);
                            } else {
                                Collections.reverse(s2);
                                s1.addAll(s2);
                            }
                            merged.remove(j);
                            changed = true;
                            break;
                        }
                    }
                    if (changed) break;
                }
            } while (changed);

            return merged;
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
        public final double[] directionHistogram;
        public final double[] regionalDistribution;

        public TemplateFeatures(double aspectRatio, int strokeCount, double[] strokeProfile,
                                double[] directionHistogram, double[] regionalDistribution) {
            this.aspectRatio = aspectRatio;
            this.strokeCount = strokeCount;
            this.strokeProfile = strokeProfile;
            this.directionHistogram = directionHistogram;
            this.regionalDistribution = regionalDistribution;
        }
    }

    public static class RecognitionResult {
        public final boolean recognized;
        public final String id;
        public final String displayName;
        public final com.maxello1.whamagic.magic.SymbolKind kind;
        public final String element;
        public final double score;
        public final com.maxello1.whamagic.magic.SigilSemantic sigilSemantic;
        public final com.maxello1.whamagic.magic.SignSemantic signSemantic;
        // Diagnostic fields
        public final List<com.maxello1.whamagic.magic.RecognitionAlternative> alternatives;
        public final double confidenceGap;
        public final double thresholdUsed;
        public final com.maxello1.whamagic.magic.RecognitionRejectionReason rejectionReason;
        public final double templateCoverage;
        public final double unexplainedInkRatio;
        public final double structuralScore;

        public RecognitionResult(boolean recognized, String id, String displayName,
                                 com.maxello1.whamagic.magic.SymbolKind kind, String element, double score,
                                 com.maxello1.whamagic.magic.SigilSemantic sigilSem, com.maxello1.whamagic.magic.SignSemantic signSem,
                                 List<com.maxello1.whamagic.magic.RecognitionAlternative> alternatives,
                                 double confidenceGap, double thresholdUsed,
                                 com.maxello1.whamagic.magic.RecognitionRejectionReason rejectionReason,
                                 double templateCoverage, double unexplainedInkRatio, double structuralScore) {
            this.recognized = recognized;
            this.id = id;
            this.displayName = displayName;
            this.kind = kind;
            this.element = element;
            this.score = score;
            this.sigilSemantic = sigilSem;
            this.signSemantic = signSem;
            this.alternatives = alternatives != null ? alternatives : new ArrayList<>();
            this.confidenceGap = confidenceGap;
            this.thresholdUsed = thresholdUsed;
            this.rejectionReason = rejectionReason;
            this.templateCoverage = templateCoverage;
            this.unexplainedInkRatio = unexplainedInkRatio;
            this.structuralScore = structuralScore;
        }

        /** Convenience constructor for early-exit failures with no match data. */
        public RecognitionResult(boolean recognized, String id, String displayName,
                                 com.maxello1.whamagic.magic.SymbolKind kind, String element, double score,
                                 com.maxello1.whamagic.magic.SigilSemantic sigilSem, com.maxello1.whamagic.magic.SignSemantic signSem,
                                 com.maxello1.whamagic.magic.RecognitionRejectionReason rejectionReason) {
            this(recognized, id, displayName, kind, element, score, sigilSem, signSem,
                 new ArrayList<>(), 0, MIN_CONFIDENCE, rejectionReason, 0, 0, 0);
        }
    }

    // ---- Public API ----

    public static void addTemplate(String id, String displayName, com.maxello1.whamagic.magic.SymbolKind kind, String element, List<List<Point>> strokes,
                                   com.maxello1.whamagic.magic.SigilSemantic sigilSem, com.maxello1.whamagic.magic.SignSemantic signSem) {
        addTemplate(id, displayName, kind, element, strokes, sigilSem, signSem, null);
    }

    public static void addTemplate(String id, String displayName, com.maxello1.whamagic.magic.SymbolKind kind, String element, List<List<Point>> strokes,
                                   com.maxello1.whamagic.magic.SigilSemantic sigilSem, com.maxello1.whamagic.magic.SignSemantic signSem,
                                   com.maxello1.whamagic.magic.SymbolRecognitionRules rules) {
        templates.add(new RasterTemplate(id, displayName, kind, element, strokes, sigilSem, signSem, rules));
    }

    public static void clearTemplates() {
        templates.clear();
    }

    public static int getTemplateCount() {
        return templates.size();
    }

    /**
     * Recognize drawn strokes against all registered templates of a specific kind.
     * Uses the raster ink overlay + structural feature scoring.
     *
     * Returns a RecognitionResult with the top 5 alternatives and full diagnostic data.
     */
    public static RecognitionResult recognize(List<List<Point>> strokes, com.maxello1.whamagic.magic.SymbolKind expectedKind) {
        if (strokes == null || strokes.isEmpty()) {
            return new RecognitionResult(false, null, "No strokes", null, null, 0, null, null,
                    com.maxello1.whamagic.magic.RecognitionRejectionReason.NO_STROKES);
        }

        // Normalize candidate strokes
        TemplateNormalizer.NormalizedResult candidateNorm = TemplateNormalizer.normalize(strokes, SAMPLES_PER_STROKE);
        if (candidateNorm.strokes.isEmpty()) {
            return new RecognitionResult(false, null, "No valid strokes", null, null, 0, null, null,
                    com.maxello1.whamagic.magic.RecognitionRejectionReason.NO_STROKES);
        }

        InkLayers candidateInk = renderInk(candidateNorm.strokes);

        // Extract candidate structural features
        double[] candidateProfile = computeStrokeProfile(strokes);
        double candidateAspectRatio = candidateNorm.sourceAspectRatio;
        int candidateStrokeCount = strokes.size();
        double[] candidateDirectionHist = computeDirectionHistogram(candidateNorm.strokes);
        double[] candidateRegionalDist = computeRegionalDistribution(candidateInk.coreMask);

        // Collect all scored alternatives
        List<ScoredAlternative> scored = new ArrayList<>();

        for (RasterTemplate template : templates) {
            if (expectedKind != null && template.kind != expectedKind) {
                continue;
            }
            
            // 1) Ink overlap score
            InkScores inkScores = compareInk(candidateInk, template.ink);

            // 2) Structural compatibility score
            double aspectScore = aspectCompatibility(candidateAspectRatio, template.features.aspectRatio);
            double countScore = strokeCountCompatibility(candidateStrokeCount, template.features.strokeCount);
            double profileScore = profileCompatibility(candidateProfile, template.features.strokeProfile);

            // Phase 4 supplementary features (used for disambiguation, not primary score)
            double directionScore = directionHistogramCompatibility(candidateDirectionHist, template.features.directionHistogram);
            double regionalScore = regionalDistributionCompatibility(candidateRegionalDist, template.features.regionalDistribution);

            // Structural score with shape-aware weighting
            double structuralScore = clamp(
                    aspectScore * 0.20
                    + profileScore * 0.10
                    + countScore * 0.10
                    + directionScore * 0.25
                    + regionalScore * 0.25
                    + (aspectScore > 0.5 && directionScore > 0.5 ? 0.10 : 0.0));

            // Combined contextual score — ink and shape both contribute meaningfully
            double contextual = inkScores.inkScore * 0.50 + structuralScore * 0.30 + 0.20;
            double confidence = clamp(Math.min(contextual, inkScores.inkScore + 0.035));

            // Apply contamination cap
            if (inkScores.unexplainedInkRatio > 0.36 && inkScores.templateCoveredRatio < 0.82) {
                double cap = clamp(0.62 - (inkScores.unexplainedInkRatio - 0.36) * 0.8, 0.2, 1.0);
                confidence = Math.min(confidence, cap);
            }

            LOGGER.debug("  vs '{}': ink={} explained={} covered={} dice={} struct={} (aspect={} count={} profile={}) -> conf={}",
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

            scored.add(new ScoredAlternative(template, confidence, structuralScore,
                    inkScores.templateCoveredRatio, inkScores.candidateExplainedRatio,
                    inkScores.unexplainedInkRatio));
        }

        if (scored.isEmpty()) {
            return new RecognitionResult(false, null, "No templates", null, null, 0, null, null,
                    com.maxello1.whamagic.magic.RecognitionRejectionReason.NO_TEMPLATES);
        }

        // Sort by confidence descending
        scored.sort((a, b) -> Double.compare(b.confidence, a.confidence));

        ScoredAlternative best = scored.get(0);
        double secondScore = scored.size() > 1 ? scored.get(1).confidence : -1;
        double gap = secondScore >= 0 ? best.confidence - secondScore : best.confidence;

        // Build top 5 alternatives
        List<com.maxello1.whamagic.magic.RecognitionAlternative> alternatives = new ArrayList<>();
        int altCount = Math.min(5, scored.size());
        for (int i = 0; i < altCount; i++) {
            ScoredAlternative alt = scored.get(i);
            alternatives.add(new com.maxello1.whamagic.magic.RecognitionAlternative(
                    net.minecraft.resources.Identifier.tryParse(alt.template.id),
                    alt.template.displayName,
                    alt.template.kind,
                    alt.confidence,
                    0, // roleScore is set by SelectionEngine
                    alt.templateCoverage,
                    alt.candidateExplainedRatio,
                    alt.unexplainedInkRatio,
                    alt.structuralScore,
                    0  // rotationDeg is set by SelectionEngine
            ));
        }

        // Phase 2: Multi-gate acceptance
        boolean ambiguous = gap < AMBIGUITY_GAP && secondScore >= 0;
        boolean isSigil = expectedKind == com.maxello1.whamagic.magic.SymbolKind.SIGIL;
        com.maxello1.whamagic.magic.SymbolRecognitionRules rules = best.template.recognitionRules;

        // Gate 1: Minimum confidence
        com.maxello1.whamagic.magic.RecognitionRejectionReason reason = com.maxello1.whamagic.magic.RecognitionRejectionReason.NONE;
        if (best.confidence < MIN_CONFIDENCE) {
            reason = com.maxello1.whamagic.magic.RecognitionRejectionReason.SCORE_BELOW_THRESHOLD;
        }
        // Gate 2: Ambiguity gap — but allow structural score to resolve ties
        else if (ambiguous) {
            // Check if structural score can disambiguate
            boolean structurallyResolved = false;
            if (scored.size() > 1) {
                double bestStructural = best.structuralScore;
                double secondStructural = scored.get(1).structuralScore;
                // If the best match has significantly better structural fit,
                // trust it despite the close raster scores
                if (bestStructural - secondStructural >= 0.10) {
                    structurallyResolved = true;
                }
            }
            if (!structurallyResolved) {
                reason = com.maxello1.whamagic.magic.RecognitionRejectionReason.AMBIGUOUS_TOP_MATCHES;
            }
        }
        // Gate 3: Template coverage — candidate must cover enough of the template
        else if (best.templateCoverage < (isSigil ? MIN_TEMPLATE_COVERAGE_SIGIL : MIN_TEMPLATE_COVERAGE_SIGN)) {
            reason = com.maxello1.whamagic.magic.RecognitionRejectionReason.LOW_TEMPLATE_COVERAGE;
        }
        // Gate 4: Unexplained ink — candidate must not have too much extra ink
        else if (best.unexplainedInkRatio > (isSigil ? MAX_UNEXPLAINED_INK_SIGIL : MAX_UNEXPLAINED_INK_SIGN)) {
            reason = com.maxello1.whamagic.magic.RecognitionRejectionReason.EXCESS_UNEXPLAINED_INK;
        }
        // Gate 5: Dimensionality — reject line-like candidates for templates that don't allow it
        else if (!rules.allowLineLike()) {
            double candDimensionality = computeCandidateDimensionality(strokes);
            if (candDimensionality < rules.minimumDimensionRatio()) {
                reason = com.maxello1.whamagic.magic.RecognitionRejectionReason.INSUFFICIENT_GEOMETRY;
            }
        }
        // Gate 6: Structural score — candidate topology must loosely match template
        if (reason == com.maxello1.whamagic.magic.RecognitionRejectionReason.NONE && best.structuralScore < 0.45) {
            reason = com.maxello1.whamagic.magic.RecognitionRejectionReason.INSUFFICIENT_GEOMETRY;
        }

        boolean recognized = reason == com.maxello1.whamagic.magic.RecognitionRejectionReason.NONE;
        String displayName = recognized ? best.template.displayName
                : best.template.displayName + " (" + String.format("%.0f%%", best.confidence * 100) + ")";

        return new RecognitionResult(recognized, best.template.id, displayName,
                best.template.kind, best.template.element, best.confidence,
                best.template.sigilSemantic, best.template.signSemantic,
                alternatives, gap, MIN_CONFIDENCE, reason,
                best.templateCoverage, best.unexplainedInkRatio, best.structuralScore);
    }

    /** Internal holder for per-template scoring during recognition. */
    private static class ScoredAlternative {
        final RasterTemplate template;
        final double confidence;
        final double structuralScore;
        final double templateCoverage;
        final double candidateExplainedRatio;
        final double unexplainedInkRatio;

        ScoredAlternative(RasterTemplate template, double confidence, double structuralScore,
                          double templateCoverage, double candidateExplainedRatio, double unexplainedInkRatio) {
            this.template = template;
            this.confidence = confidence;
            this.structuralScore = structuralScore;
            this.templateCoverage = templateCoverage;
            this.candidateExplainedRatio = candidateExplainedRatio;
            this.unexplainedInkRatio = unexplainedInkRatio;
        }
    }

    // ---- Ink Rendering ----

    /**
     * Render strokes onto a INK_SIZE x INK_SIZE pixel grid with three thickness layers.
     */
    private static InkLayers renderInk(List<List<Point>> strokes) {
        int size = INK_SIZE;
        byte[] coreMask = new byte[size * size];
        byte[] softMask = new byte[size * size];
        byte[] looseMask = new byte[size * size];

        for (List<Point> stroke : strokes) {
            if (stroke == null || stroke.isEmpty()) continue;

            List<Point> rotated = stroke;

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
        double[] directionHist = computeDirectionHistogram(norm.strokes);
        // Regional distribution computed from template ink at load time
        InkLayers tempInk = renderInk(norm.strokes);
        double[] regionalDist = computeRegionalDistribution(tempInk.coreMask);
        return new TemplateFeatures(aspect, strokeCount, profile, directionHist, regionalDist);
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
        double diff = Math.abs(candidateCount - templateCount);
        double max = Math.max(candidateCount, templateCount);
        // Make stroke count penalty much weaker. Connect lines (less strokes) is less penalized.
        double penalty = diff / max;
        // Instead of linear 1 - penalty, we use a much softer curve, e.g. 1 - penalty * 0.4
        return clamp(1.0 - penalty * 0.4);
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

    /**
     * Compute a direction histogram with 8 bins (N, NE, E, SE, S, SW, W, NW).
     * Measures the distribution of stroke segment directions, independent of stroke count.
     */
    private static double[] computeDirectionHistogram(List<List<Point>> strokes) {
        double[] hist = new double[8];
        double totalLength = 0;

        for (List<Point> stroke : strokes) {
            for (int i = 1; i < stroke.size(); i++) {
                double dx = stroke.get(i).x - stroke.get(i - 1).x;
                double dy = stroke.get(i).y - stroke.get(i - 1).y;
                double len = Math.hypot(dx, dy);
                if (len < 0.0001) continue;

                double angle = Math.atan2(dy, dx);
                if (angle < 0) angle += 2 * Math.PI;

                int bin = (int) Math.round(angle / (Math.PI / 4)) % 8;
                hist[bin] += len;
                totalLength += len;
            }
        }

        // Normalize
        if (totalLength > 0) {
            for (int i = 0; i < hist.length; i++) {
                hist[i] /= totalLength;
            }
        }
        return hist;
    }

    /** Compare direction histograms using normalized histogram intersection. */
    private static double directionHistogramCompatibility(double[] candidateHist, double[] templateHist) {
        if (candidateHist == null || templateHist == null) return 0.5;
        double intersection = 0;
        for (int i = 0; i < Math.min(candidateHist.length, templateHist.length); i++) {
            intersection += Math.min(candidateHist[i], templateHist[i]);
        }
        return clamp(intersection);
    }

    /**
     * Compute regional ink distribution from a raster mask.
     * Uses a 5x5 grid (25 regions) for coarser but more robust comparison.
     */
    private static double[] computeRegionalDistribution(byte[] coreMask) {
        int regionSize = 5;
        double[] dist = new double[regionSize * regionSize];
        int totalInk = 0;

        int size = INK_SIZE;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (coreMask[y * size + x] != 0) {
                    int rx = Math.min(regionSize - 1, x * regionSize / size);
                    int ry = Math.min(regionSize - 1, y * regionSize / size);
                    dist[ry * regionSize + rx]++;
                    totalInk++;
                }
            }
        }

        // Normalize
        if (totalInk > 0) {
            for (int i = 0; i < dist.length; i++) {
                dist[i] /= totalInk;
            }
        }
        return dist;
    }

    /** Compare regional distributions using cosine similarity. */
    private static double regionalDistributionCompatibility(double[] candidateDist, double[] templateDist) {
        if (candidateDist == null || templateDist == null) return 0.5;
        int n = Math.min(candidateDist.length, templateDist.length);
        double dot = 0, magA = 0, magB = 0;
        for (int i = 0; i < n; i++) {
            dot += candidateDist[i] * templateDist[i];
            magA += candidateDist[i] * candidateDist[i];
            magB += templateDist[i] * templateDist[i];
        }
        double denom = Math.sqrt(magA) * Math.sqrt(magB);
        return denom > 0 ? clamp(dot / denom) : 0;
    }

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

    /** Compute template complexity profile at registration time. */
    private static com.maxello1.whamagic.magic.TemplateComplexity computeTemplateComplexity(
            TemplateNormalizer.NormalizedResult norm, InkLayers ink, List<List<Point>> rawStrokes) {
        // Total path length of normalized strokes
        double totalPath = 0;
        for (List<Point> stroke : norm.strokes) {
            totalPath += pathLength(stroke);
        }

        // Ink coverage: fraction of the 40x40 grid covered
        double inkCoverage = (double) ink.softInk / (INK_SIZE * INK_SIZE);

        // Dimensionality: min(w,h) / max(w,h) of the bounding box
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (List<Point> stroke : rawStrokes) {
            for (Point p : stroke) {
                minX = Math.min(minX, p.x);
                maxX = Math.max(maxX, p.x);
                minY = Math.min(minY, p.y);
                maxY = Math.max(maxY, p.y);
            }
        }
        double w = maxX - minX;
        double h = maxY - minY;
        double dimensionality = (w > 0 && h > 0) ? Math.min(w, h) / Math.max(w, h) : 0;

        // Endpoint estimate: 2 endpoints per stroke
        int endpointEstimate = rawStrokes.size() * 2;

        // Component estimate: number of distinct stroke groups
        int componentEstimate = rawStrokes.size();

        return new com.maxello1.whamagic.magic.TemplateComplexity(
                totalPath, inkCoverage, dimensionality, endpointEstimate, componentEstimate);
    }

    /** Compute dimensionality (min/max dimension ratio) of candidate strokes. */
    private static double computeCandidateDimensionality(List<List<Point>> strokes) {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (List<Point> stroke : strokes) {
            for (Point p : stroke) {
                minX = Math.min(minX, p.x);
                maxX = Math.max(maxX, p.x);
                minY = Math.min(minY, p.y);
                maxY = Math.max(maxY, p.y);
            }
        }
        double w = maxX - minX;
        double h = maxY - minY;
        if (w <= 0 || h <= 0) return 0;
        return Math.min(w, h) / Math.max(w, h);
    }
}
