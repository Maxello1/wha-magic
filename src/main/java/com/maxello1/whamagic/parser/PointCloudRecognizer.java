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
public class PointCloudRecognizer implements SymbolRecognizer {

    /** Singleton instance for use via the interface. */
    public static final PointCloudRecognizer INSTANCE = new PointCloudRecognizer();

    private static final Logger LOGGER = LoggerFactory.getLogger(PointCloudRecognizer.class);

    /** Number of resample points. 32 is proven accurate for the current gesture set. */
    public static final int N = 32;

    /** Controls step size in greedy cloud match. step = floor(N^(1-ε)). */
    private static final double EPSILON = 0.50;

    /** Minimum score to consider a match valid. */
    private static final double MIN_SCORE = 0.20;

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

    // ---- SymbolRecognizer interface ----

    @Override public String name() { return "$P"; }

    @Override public void clearTemplates() { templates.clear(); }

    @Override
    public void registerTemplate(String id, String displayName,
                                 com.maxello1.whamagic.magic.SymbolKind kind, String element,
                                 List<List<Point>> strokes,
                                 com.maxello1.whamagic.magic.SigilSemantic sigilSemantic,
                                 com.maxello1.whamagic.magic.SignSemantic signSemantic,
                                 com.maxello1.whamagic.magic.SymbolRecognitionRules recognitionRules) {
        registerTemplateStatic(id, displayName, kind, element, strokes, sigilSemantic, signSemantic, recognitionRules);
    }

    @Override public int getTemplateCount() { return templates.size(); }

    @Override
    public RasterRecognizer.RecognitionResult recognize(List<List<Point>> strokes, com.maxello1.whamagic.magic.SymbolKind expectedKind) {
        return recognizeStatic(strokes, expectedKind);
    }

    // ---- Template Storage ----

    private static final List<PointCloudTemplate> templates = new ArrayList<>();

    /** @deprecated Use instance methods via SymbolRecognizer interface instead. */
    public static void clearTemplatesStatic() {
        templates.clear();
    }

    /**
     * Register a template from stroke data (as stored in sigils.json/signs.json).
     * The strokes are converted to a point cloud, resampled, and normalized.
     */
    public static void registerTemplateStatic(String id, String displayName,
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

    public static int getTemplateCountStatic() {
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
    public static RasterRecognizer.RecognitionResult recognizeStatic(List<List<Point>> strokes,
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

    /** Convert stroke lists to a flat point cloud, preserving stroke IDs. */
    private static CloudPoint[] strokesToCloud(List<List<Point>> strokes) {
        List<CloudPoint> cloud = new ArrayList<>();
        for (int s = 0; s < strokes.size(); s++) {
            for (Point p : strokes.get(s)) {
                cloud.add(new CloudPoint(p.x, p.y, s));
            }
        }
        return cloud.toArray(new CloudPoint[0]);
    }

    /** Full normalization: per-stroke resample to N points, scale, translate centroid to origin. */
    private static CloudPoint[] normalize(CloudPoint[] points, int n) {
        // Group points by strokeId
        List<List<CloudPoint>> strokeGroups = new ArrayList<>();
        int currentStroke = -1;
        for (CloudPoint p : points) {
            if (p.strokeId != currentStroke) {
                strokeGroups.add(new ArrayList<>());
                currentStroke = p.strokeId;
            }
            strokeGroups.get(strokeGroups.size() - 1).add(p);
        }

        CloudPoint[] resampled = resamplePerStroke(strokeGroups, n);
        scale(resampled);
        translateToOrigin(resampled);
        return resampled;
    }

    /**
     * Resample strokes to exactly N total points, distributing proportionally by path length
     * with a minimum of 2 points per non-trivial stroke.
     *
     * This ensures every meaningful stroke receives point representation,
     * even if it is much shorter than other strokes.
     */
    private static CloudPoint[] resamplePerStroke(List<List<CloudPoint>> strokeGroups, int n) {
        // Filter out trivial strokes (0-1 points)
        List<List<CloudPoint>> meaningful = new ArrayList<>();
        for (List<CloudPoint> group : strokeGroups) {
            if (group.size() >= 2) meaningful.add(group);
        }
        if (meaningful.isEmpty()) {
            // Degenerate: no meaningful strokes, fill with first point
            CloudPoint[] result = new CloudPoint[n];
            CloudPoint p = strokeGroups.isEmpty() || strokeGroups.get(0).isEmpty()
                    ? new CloudPoint(0, 0, 0)
                    : strokeGroups.get(0).get(0);
            for (int i = 0; i < n; i++) result[i] = p;
            return result;
        }

        // Compute per-stroke path lengths
        double[] lengths = new double[meaningful.size()];
        double totalLength = 0;
        for (int s = 0; s < meaningful.size(); s++) {
            lengths[s] = strokePathLength(meaningful.get(s));
            totalLength += lengths[s];
        }

        // Assign point counts: minimum 2 per stroke, rest proportional to path length
        int minPerStroke = 2;
        int[] counts = new int[meaningful.size()];
        int reserved = meaningful.size() * minPerStroke;
        int remaining = Math.max(0, n - reserved);

        for (int s = 0; s < meaningful.size(); s++) {
            int proportional = totalLength > 1e-10
                    ? (int) Math.round((double) remaining * lengths[s] / totalLength)
                    : remaining / meaningful.size();
            counts[s] = minPerStroke + proportional;
        }

        // Adjust to hit exactly N total
        int total = 0;
        for (int c : counts) total += c;
        // Distribute surplus/deficit to the longest strokes
        int diff = n - total;
        while (diff != 0) {
            int bestIdx = 0;
            double bestLen = 0;
            for (int s = 0; s < meaningful.size(); s++) {
                if (diff > 0 || counts[s] > minPerStroke) {
                    if (lengths[s] > bestLen) {
                        bestLen = lengths[s];
                        bestIdx = s;
                    }
                }
            }
            counts[bestIdx] += (diff > 0) ? 1 : -1;
            diff += (diff > 0) ? -1 : 1;
        }

        // Resample each stroke independently
        List<CloudPoint> result = new ArrayList<>();
        for (int s = 0; s < meaningful.size(); s++) {
            List<CloudPoint> stroke = meaningful.get(s);
            int strokeId = stroke.get(0).strokeId;
            int count = Math.max(2, counts[s]);
            List<CloudPoint> resampled = resampleSingleStroke(stroke, count, strokeId);
            result.addAll(resampled);
        }

        // Ensure exactly N
        while (result.size() > n) {
            result.remove(result.size() - 1);
        }
        while (result.size() < n) {
            result.add(result.get(result.size() - 1));
        }

        return result.toArray(new CloudPoint[0]);
    }

    /**
     * Resample a single stroke to exactly count equidistant points.
     */
    private static List<CloudPoint> resampleSingleStroke(List<CloudPoint> stroke, int count, int strokeId) {
        double length = strokePathLength(stroke);
        if (length < 1e-10 || count <= 1) {
            // Degenerate stroke: return first and last point
            List<CloudPoint> pts = new ArrayList<>();
            pts.add(new CloudPoint(stroke.get(0).x, stroke.get(0).y, strokeId));
            if (count > 1) {
                pts.add(new CloudPoint(stroke.get(stroke.size() - 1).x, stroke.get(stroke.size() - 1).y, strokeId));
            }
            while (pts.size() < count) {
                pts.add(pts.get(pts.size() - 1));
            }
            return pts;
        }

        double interval = length / (count - 1);
        List<CloudPoint> newPoints = new ArrayList<>();
        newPoints.add(new CloudPoint(stroke.get(0).x, stroke.get(0).y, strokeId));

        double accDist = 0;
        int srcIdx = 1;

        while (newPoints.size() < count && srcIdx < stroke.size()) {
            double d = dist(stroke.get(srcIdx - 1), stroke.get(srcIdx));
            if (d < 1e-10) {
                srcIdx++;
                continue;
            }

            if (accDist + d >= interval) {
                double t = (interval - accDist) / d;
                double qx = stroke.get(srcIdx - 1).x + t * (stroke.get(srcIdx).x - stroke.get(srcIdx - 1).x);
                double qy = stroke.get(srcIdx - 1).y + t * (stroke.get(srcIdx).y - stroke.get(srcIdx - 1).y);
                CloudPoint q = new CloudPoint(qx, qy, strokeId);
                newPoints.add(q);
                // Insert and continue from interpolated point
                stroke = new ArrayList<>(stroke);
                stroke.add(srcIdx, q);
                srcIdx++;
                accDist = 0;
            } else {
                accDist += d;
                srcIdx++;
            }
        }

        // Pad with last point if short
        while (newPoints.size() < count) {
            newPoints.add(new CloudPoint(stroke.get(stroke.size() - 1).x, stroke.get(stroke.size() - 1).y, strokeId));
        }

        return newPoints.subList(0, count);
    }

    /** Compute path length along a single stroke's points. */
    private static double strokePathLength(List<CloudPoint> stroke) {
        double length = 0;
        for (int i = 1; i < stroke.size(); i++) {
            length += dist(stroke.get(i - 1), stroke.get(i));
        }
        return length;
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
