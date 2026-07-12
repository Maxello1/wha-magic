/*
 * $P Point-Cloud Recognizer for WHA Magic
 *
 * Based on the $P recognizer by Vatavu, Anthony, and Wobbrock (ICMI 2012).
 * https://depts.washington.edu/madlab/proj/dollar/pdollar.html
 *
 * Ported to Java for WHA Magic Minecraft mod.
 *
 * Original WHA Magic additions and modifications:
 * Copyright (c) 2026 Maxello1.
 * Licensed under the WHA Magic Restricted Use License.
 */
package com.maxello1.whamagic.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * $P Point-Cloud gesture recognizer.
 *
 * Treats all strokes as an unordered cloud of points, making it inherently
 * stroke-order, stroke-direction, and stroke-count independent.
 *
 * Two-stage system:
 * <ol>
 *   <li><b>Recognition</b> — "What did the player draw?" Pure classification.</li>
 *   <li><b>Quality</b> — "How well did they draw it?" Feeds into spell power.</li>
 * </ol>
 */
public class PointCloudRecognizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(PointCloudRecognizer.class);

    /** Number of resample points. 32 is the sweet spot for accuracy vs performance. */
    public static final int N = 32;

    /** Controls step size in greedy cloud match. step = floor(N^(1-ε)). */
    private static final double EPSILON = 0.50;

    /** Minimum score to consider a match valid. */
    private static final double MIN_SCORE = 0.15;

    /** Minimum gap between best and second-best to avoid ambiguity. */
    private static final double MIN_GAP = 0.02;

    // ---- Data Structures ----

    /** A point in a point cloud, with a stroke ID for resampling. */
    public record CloudPoint(double x, double y, int strokeId) {}

    /** A normalized point-cloud template. */
    public static class PointCloudTemplate {
        public final String id;
        public final String displayName;
        public final com.maxello1.whamagic.magic.SymbolKind kind;
        public final String element;
        public final CloudPoint[] points; // always length N after normalization
        public final com.maxello1.whamagic.magic.SigilSemantic sigilSemantic;
        public final com.maxello1.whamagic.magic.SignSemantic signSemantic;
        public final com.maxello1.whamagic.magic.SymbolRecognitionRules recognitionRules;

        public PointCloudTemplate(String id, String displayName,
                                  com.maxello1.whamagic.magic.SymbolKind kind, String element,
                                  CloudPoint[] points,
                                  com.maxello1.whamagic.magic.SigilSemantic sigilSemantic,
                                  com.maxello1.whamagic.magic.SignSemantic signSemantic,
                                  com.maxello1.whamagic.magic.SymbolRecognitionRules recognitionRules) {
            this.id = id;
            this.displayName = displayName;
            this.kind = kind;
            this.element = element;
            this.points = points;
            this.sigilSemantic = sigilSemantic;
            this.signSemantic = signSemantic;
            this.recognitionRules = recognitionRules;
        }
    }

    // ---- Template Storage ----

    private static final List<PointCloudTemplate> templates = new ArrayList<>();

    public static void clearTemplates() {
        templates.clear();
    }

    /**
     * Register a template from stroke data (as stored in sigils.json/signs.json).
     * The strokes are converted to a point cloud, resampled, and normalized.
     */
    public static void registerTemplate(String id, String displayName,
                                        com.maxello1.whamagic.magic.SymbolKind kind, String element,
                                        List<List<Point>> strokes,
                                        com.maxello1.whamagic.magic.SigilSemantic sigilSemantic,
                                        com.maxello1.whamagic.magic.SignSemantic signSemantic,
                                        com.maxello1.whamagic.magic.SymbolRecognitionRules recognitionRules) {
        CloudPoint[] cloud = strokesToCloud(strokes);
        CloudPoint[] normalized = normalize(cloud, N);
        templates.add(new PointCloudTemplate(id, displayName, kind, element, normalized,
                sigilSemantic, signSemantic, recognitionRules));
        LOGGER.debug("Registered $P template '{}' ({} strokes -> {} points)", id, strokes.size(), N);
    }

    public static int getTemplateCount() {
        return templates.size();
    }

    // ---- Recognition (Stage 1) ----

    /**
     * Recognize a candidate drawing against all templates of a given kind.
     * Returns a RasterRecognizer.RecognitionResult for compatibility with SelectionEngine.
     *
     * @param strokes  the player's drawn strokes
     * @param expectedKind  SIGIL or SIGN — only templates of this kind are tested
     * @return recognition result with match info and alternatives
     */
    public static RasterRecognizer.RecognitionResult recognize(List<List<Point>> strokes,
                                                               com.maxello1.whamagic.magic.SymbolKind expectedKind) {
        if (strokes == null || strokes.isEmpty()) {
            return new RasterRecognizer.RecognitionResult(false, null, "Unknown", null, null, 0,
                    null, null, com.maxello1.whamagic.magic.RecognitionRejectionReason.NO_STROKES);
        }

        // Convert and normalize candidate
        CloudPoint[] candidateCloud = strokesToCloud(strokes);
        if (candidateCloud.length < 3) {
            return new RasterRecognizer.RecognitionResult(false, null, "Unknown", null, null, 0,
                    null, null, com.maxello1.whamagic.magic.RecognitionRejectionReason.NO_STROKES);
        }
        CloudPoint[] candidate = normalize(candidateCloud, N);

        // Match against each template of the expected kind
        List<TemplateScore> scored = new ArrayList<>();
        for (PointCloudTemplate tmpl : templates) {
            if (tmpl.kind != expectedKind) continue;
            double distance = greedyCloudMatch(candidate, tmpl.points, N);
            double score = Math.max((2.0 - distance) / 2.0, 0.0);
            scored.add(new TemplateScore(tmpl, score, distance));
        }

        if (scored.isEmpty()) {
            return new RasterRecognizer.RecognitionResult(false, null, "Unknown", null, null, 0,
                    null, null, com.maxello1.whamagic.magic.RecognitionRejectionReason.NO_TEMPLATES);
        }

        // Sort by score descending
        scored.sort((a, b) -> Double.compare(b.score, a.score));

        TemplateScore best = scored.get(0);
        double secondScore = scored.size() > 1 ? scored.get(1).score : 0;
        double gap = best.score - secondScore;

        // Build alternatives list
        List<com.maxello1.whamagic.magic.RecognitionAlternative> alternatives = new ArrayList<>();
        int altCount = Math.min(5, scored.size());
        for (int i = 0; i < altCount; i++) {
            TemplateScore ts = scored.get(i);
            alternatives.add(new com.maxello1.whamagic.magic.RecognitionAlternative(
                    net.minecraft.resources.Identifier.tryParse(ts.template.id),
                    ts.template.displayName,
                    ts.template.kind,
                    ts.score,
                    0, // roleScore set by SelectionEngine
                    ts.score, // templateCoverage ~ $P score
                    ts.score, // candidateExplainedRatio ~ $P score
                    1.0 - ts.score, // unexplainedInkRatio
                    ts.score, // structuralScore = $P score
                    0  // rotationDeg set by SelectionEngine
            ));
        }

        // Apply gates
        com.maxello1.whamagic.magic.RecognitionRejectionReason reason =
                com.maxello1.whamagic.magic.RecognitionRejectionReason.NONE;

        if (best.score < MIN_SCORE) {
            reason = com.maxello1.whamagic.magic.RecognitionRejectionReason.SCORE_BELOW_THRESHOLD;
        } else if (gap < MIN_GAP && secondScore > 0) {
            reason = com.maxello1.whamagic.magic.RecognitionRejectionReason.AMBIGUOUS_TOP_MATCHES;
        }

        boolean recognized = reason == com.maxello1.whamagic.magic.RecognitionRejectionReason.NONE;

        String displayName = recognized ? best.template.displayName
                : best.template.displayName + " (" + String.format("%.0f%%", best.score * 100) + ")";

        LOGGER.debug("$P recognize: {} -> {} (score={}, gap={})",
                expectedKind, best.template.id,
                String.format("%.3f", best.score), String.format("%.3f", gap));

        return new RasterRecognizer.RecognitionResult(recognized, best.template.id, displayName,
                best.template.kind, best.template.element, best.score,
                best.template.sigilSemantic, best.template.signSemantic,
                alternatives, gap, MIN_SCORE, reason,
                best.score, 1.0 - best.score, best.score);
    }

    // ---- Quality Scoring (Stage 2) ----

    /**
     * Compute a quality score (0-100) for how well the drawing matches the template.
     * Higher = more precise drawing. Used for spell power calculation.
     *
     * @param strokes the player's drawn strokes
     * @param templateId the recognized template ID to score against
     * @return quality score 0-100, or 0 if template not found
     */
    public static double computeQuality(List<List<Point>> strokes, String templateId) {
        // Find the template
        PointCloudTemplate tmpl = null;
        for (PointCloudTemplate t : templates) {
            if (t.id.equals(templateId)) {
                tmpl = t;
                break;
            }
        }
        if (tmpl == null) return 0;

        CloudPoint[] candidateCloud = strokesToCloud(strokes);
        if (candidateCloud.length < 3) return 0;
        CloudPoint[] candidate = normalize(candidateCloud, N);

        double distance = greedyCloudMatch(candidate, tmpl.points, N);
        return computeQualityFromDistance(candidate, tmpl.points, distance);
    }

    /**
     * Compute quality from pre-computed match data.
     */
    private static double computeQualityFromDistance(CloudPoint[] candidate, CloudPoint[] template, double distance) {
        // Base quality from match distance (inverse relationship)
        // distance of 0 = perfect match = 100 points
        // distance of 1.0 = mediocre match = ~50 points
        // distance of 2.0 = poor match = 0 points
        double baseQuality = Math.max(0, (2.0 - distance) / 2.0) * 100.0;

        // Penalize for deviation in individual point placements
        double maxDeviation = 0;
        double sumDeviation = 0;
        boolean[] matched = new boolean[N];
        for (int i = 0; i < N; i++) {
            double minDist = Double.MAX_VALUE;
            int bestJ = 0;
            for (int j = 0; j < N; j++) {
                if (!matched[j]) {
                    double d = dist(candidate[i], template[j]);
                    if (d < minDist) {
                        minDist = d;
                        bestJ = j;
                    }
                }
            }
            matched[bestJ] = true;
            maxDeviation = Math.max(maxDeviation, minDist);
            sumDeviation += minDist;
        }

        // Large deviations (sloppy drawing) reduce quality
        double avgDeviation = sumDeviation / N;
        double deviationPenalty = Math.min(20, maxDeviation * 40 + avgDeviation * 20);

        return Math.max(0, Math.min(100, baseQuality - deviationPenalty));
    }

    // ---- Core $P Algorithm ----

    /**
     * Greedy cloud matching: tries multiple starting alignments and both directions.
     */
    private static double greedyCloudMatch(CloudPoint[] pts, CloudPoint[] tmpl, int n) {
        int step = (int) Math.floor(Math.pow(n, 1.0 - EPSILON));
        if (step < 1) step = 1;
        double minDist = Double.MAX_VALUE;
        for (int i = 0; i < n; i += step) {
            double d1 = cloudDistance(pts, tmpl, n, i);
            double d2 = cloudDistance(tmpl, pts, n, i);
            minDist = Math.min(minDist, Math.min(d1, d2));
        }
        return minDist;
    }

    /**
     * Cloud distance: greedy nearest-neighbor assignment with decreasing weights.
     */
    private static double cloudDistance(CloudPoint[] pts, CloudPoint[] tmpl, int n, int start) {
        boolean[] matched = new boolean[n];
        double sum = 0;
        int i = start;
        do {
            double minDist = Double.MAX_VALUE;
            int index = -1;
            for (int j = 0; j < n; j++) {
                if (!matched[j]) {
                    double d = dist(pts[i], tmpl[j]);
                    if (d < minDist) {
                        minDist = d;
                        index = j;
                    }
                }
            }
            if (index >= 0) {
                matched[index] = true;
            }
            double weight = 1.0 - ((i - start + n) % n) / (double) n;
            sum += weight * minDist;
            i = (i + 1) % n;
        } while (i != start);
        return sum;
    }

    // ---- Preprocessing ----

    /** Convert stroke lists (from game input or JSON templates) to a flat point cloud. */
    private static CloudPoint[] strokesToCloud(List<List<Point>> strokes) {
        List<CloudPoint> cloud = new ArrayList<>();
        for (int s = 0; s < strokes.size(); s++) {
            for (Point p : strokes.get(s)) {
                cloud.add(new CloudPoint(p.x, p.y, s));
            }
        }
        return cloud.toArray(new CloudPoint[0]);
    }

    /** Full normalization: resample to N points, scale to unit square, translate centroid to origin. */
    private static CloudPoint[] normalize(CloudPoint[] points, int n) {
        CloudPoint[] resampled = resample(points, n);
        scale(resampled);
        translateToOrigin(resampled);
        return resampled;
    }

    /**
     * Resample points to exactly N equidistant points.
     * Respects stroke boundaries (no interpolation across strokes).
     */
    private static CloudPoint[] resample(CloudPoint[] points, int n) {
        double totalLength = pathLength(points);
        if (totalLength < 1e-10) {
            // Degenerate case: all points at same location
            CloudPoint[] result = new CloudPoint[n];
            for (int i = 0; i < n; i++) result[i] = points[0];
            return result;
        }
        double interval = totalLength / (n - 1);

        List<CloudPoint> newPoints = new ArrayList<>();
        newPoints.add(points[0]);

        double accumulatedDist = 0;

        // Work with a mutable copy to allow insertion
        List<CloudPoint> ptsList = new ArrayList<>(List.of(points));

        for (int i = 1; i < ptsList.size() && newPoints.size() < n; i++) {
            CloudPoint prev = ptsList.get(i - 1);
            CloudPoint curr = ptsList.get(i);

            if (curr.strokeId == prev.strokeId) {
                double d = dist(prev, curr);
                while (accumulatedDist + d >= interval && newPoints.size() < n) {
                    double t = (interval - accumulatedDist) / d;
                    double qx = prev.x + t * (curr.x - prev.x);
                    double qy = prev.y + t * (curr.y - prev.y);
                    CloudPoint q = new CloudPoint(qx, qy, curr.strokeId);
                    newPoints.add(q);

                    // Insert interpolated point and continue from it
                    ptsList.add(i, q);
                    i++;
                    prev = q;
                    curr = ptsList.get(i);
                    accumulatedDist = 0;
                    d = dist(prev, curr);
                }
                accumulatedDist += d;
            } else {
                accumulatedDist = 0; // reset at stroke boundary
            }
        }

        // If rounding errors leave us short, duplicate last point
        while (newPoints.size() < n) {
            newPoints.add(newPoints.get(newPoints.size() - 1));
        }

        return newPoints.subList(0, n).toArray(new CloudPoint[0]);
    }

    /** Scale points to fit within a unit square, preserving aspect ratio. */
    private static void scale(CloudPoint[] points) {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (CloudPoint p : points) {
            minX = Math.min(minX, p.x);
            maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y);
            maxY = Math.max(maxY, p.y);
        }
        double width = maxX - minX;
        double height = maxY - minY;
        double size = Math.max(width, height);
        if (size < 1e-10) size = 1.0; // avoid division by zero for dots

        for (int i = 0; i < points.length; i++) {
            points[i] = new CloudPoint(
                    (points[i].x - minX) / size,
                    (points[i].y - minY) / size,
                    points[i].strokeId
            );
        }
    }

    /** Translate all points so that their centroid is at the origin (0, 0). */
    private static void translateToOrigin(CloudPoint[] points) {
        double cx = 0, cy = 0;
        for (CloudPoint p : points) {
            cx += p.x;
            cy += p.y;
        }
        cx /= points.length;
        cy /= points.length;
        for (int i = 0; i < points.length; i++) {
            points[i] = new CloudPoint(
                    points[i].x - cx,
                    points[i].y - cy,
                    points[i].strokeId
            );
        }
    }

    // ---- Utilities ----

    private static double dist(CloudPoint a, CloudPoint b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static double pathLength(CloudPoint[] points) {
        double length = 0;
        for (int i = 1; i < points.length; i++) {
            if (points[i].strokeId == points[i - 1].strokeId) {
                length += dist(points[i - 1], points[i]);
            }
        }
        return length;
    }

    private record TemplateScore(PointCloudTemplate template, double score, double distance) {}
}
