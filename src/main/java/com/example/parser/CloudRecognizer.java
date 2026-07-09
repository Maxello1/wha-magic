package com.example.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * $P Point-Cloud Recognizer for multi-stroke gesture recognition.
 *
 * Based on the $P algorithm by Vatavu, Anthony, and Wobbrock.
 * Treats gestures as unordered point clouds, making recognition
 * invariant to stroke count, stroke order, and drawing direction.
 */
public class CloudRecognizer {

    private static final int NUM_POINTS = 32;
    private static final List<Template> templates = new ArrayList<>();

    public static class Template {
        public final String id;
        public final String displayName;
        public final String kind; // "sigil" or "sign"
        public final String element; // e.g. "fire", "water", "wind" - only for sigils
        public final List<Point> points;

        public Template(String id, String displayName, String kind, String element, List<List<Point>> strokes) {
            this.id = id;
            this.displayName = displayName;
            this.kind = kind;
            this.element = element;
            this.points = normalize(flattenStrokes(strokes));
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

    /**
     * Register a template from stroke data (lists of point lists).
     */
    public static void addTemplate(String id, String displayName, String kind, String element, List<List<Point>> strokes) {
        templates.add(new Template(id, displayName, kind, element, strokes));
    }

    /**
     * Clear all registered templates.
     */
    public static void clearTemplates() {
        templates.clear();
    }

    /**
     * Get the number of registered templates.
     */
    public static int getTemplateCount() {
        return templates.size();
    }

    /**
     * Recognize drawn strokes against all registered templates.
     */
    public static RecognitionResult recognize(List<List<Point>> strokes) {
        if (strokes == null || strokes.isEmpty()) {
            return new RecognitionResult(false, null, "No strokes", "unknown", null, 0);
        }

        List<Point> flat = flattenStrokes(strokes);
        if (flat.size() < 3) {
            return new RecognitionResult(false, null, "Too few points", "unknown", null, 0);
        }

        List<Point> candidate = normalize(flat);

        double bestScore = Double.MAX_VALUE;
        Template bestTemplate = null;

        for (Template t : templates) {
            double d = greedyCloudMatch(candidate, t.points);
            if (d < bestScore) {
                bestScore = d;
                bestTemplate = t;
            }
        }

        if (bestTemplate == null) {
            return new RecognitionResult(false, null, "No templates", "unknown", null, 0);
        }

        // Normalize score: lower distance = better match
        // The score is a normalized confidence value from 0 to 1
        double maxD = Math.sqrt(2.0) * 0.5; // half-diagonal of unit square
        double confidence = Math.max(0, (maxD - bestScore) / maxD);

        // Threshold for acceptance
        if (confidence >= 0.35) {
            return new RecognitionResult(true, bestTemplate.id, bestTemplate.displayName,
                    bestTemplate.kind, bestTemplate.element, confidence);
        } else {
            return new RecognitionResult(false, bestTemplate.id,
                    bestTemplate.displayName + " (" + String.format("%.0f%%", confidence * 100) + ")",
                    bestTemplate.kind, bestTemplate.element, confidence);
        }
    }

    // ---- $P Core Algorithm ----

    /**
     * Flatten multiple strokes into a single point list.
     */
    private static List<Point> flattenStrokes(List<List<Point>> strokes) {
        List<Point> all = new ArrayList<>();
        for (List<Point> stroke : strokes) {
            if (stroke != null) {
                all.addAll(stroke);
            }
        }
        return all;
    }

    /**
     * Normalize a point cloud: resample, scale, translate to origin.
     */
    private static List<Point> normalize(List<Point> points) {
        List<Point> resampled = resample(points, NUM_POINTS);
        resampled = scale(resampled);
        resampled = translateToOrigin(resampled);
        return resampled;
    }

    /**
     * Resample point cloud to n evenly-spaced points.
     */
    private static List<Point> resample(List<Point> points, int n) {
        if (points.isEmpty()) return points;

        double totalLen = pathLength(points);
        double interval = totalLen / (n - 1);
        double D = 0.0;

        List<Point> newPoints = new ArrayList<>();
        newPoints.add(new Point(points.get(0).x, points.get(0).y));

        List<Point> src = new ArrayList<>(points);

        for (int i = 1; i < src.size(); i++) {
            Point prev = src.get(i - 1);
            Point curr = src.get(i);
            double d = distance(prev, curr);

            if ((D + d) >= interval) {
                double qx = prev.x + ((interval - D) / d) * (curr.x - prev.x);
                double qy = prev.y + ((interval - D) / d) * (curr.y - prev.y);
                Point q = new Point(qx, qy);
                newPoints.add(q);
                src.add(i, q);
                D = 0.0;
            } else {
                D += d;
            }
        }

        // Pad if we didn't get enough points due to rounding
        while (newPoints.size() < n) {
            newPoints.add(new Point(
                    newPoints.get(newPoints.size() - 1).x,
                    newPoints.get(newPoints.size() - 1).y));
        }

        // Trim if we got too many
        if (newPoints.size() > n) {
            newPoints = newPoints.subList(0, n);
        }

        return newPoints;
    }

    /**
     * Scale points to fit within [0,1] x [0,1].
     */
    private static List<Point> scale(List<Point> points) {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        for (Point p : points) {
            if (p.x < minX) minX = p.x;
            if (p.x > maxX) maxX = p.x;
            if (p.y < minY) minY = p.y;
            if (p.y > maxY) maxY = p.y;
        }

        double rangeX = Math.max(maxX - minX, 0.001);
        double rangeY = Math.max(maxY - minY, 0.001);
        double scale = Math.max(rangeX, rangeY);

        List<Point> scaled = new ArrayList<>();
        for (Point p : points) {
            scaled.add(new Point(
                    (p.x - minX) / scale,
                    (p.y - minY) / scale
            ));
        }
        return scaled;
    }

    /**
     * Translate point cloud so its centroid is at the origin.
     */
    private static List<Point> translateToOrigin(List<Point> points) {
        double cx = 0, cy = 0;
        for (Point p : points) {
            cx += p.x;
            cy += p.y;
        }
        cx /= points.size();
        cy /= points.size();

        List<Point> translated = new ArrayList<>();
        for (Point p : points) {
            translated.add(new Point(p.x - cx, p.y - cy));
        }
        return translated;
    }

    /**
     * Greedy cloud matching: finds the minimum-cost assignment between
     * two point clouds using a greedy nearest-neighbor approach.
     * Tests both the natural ordering and the reversed ordering.
     */
    private static double greedyCloudMatch(List<Point> pts1, List<Point> pts2) {
        int n = pts1.size();
        double score1 = cloudDistance(pts1, pts2, n);
        double score2 = cloudDistance(pts2, pts1, n);
        return Math.min(score1, score2);
    }

    /**
     * Compute the average nearest-neighbor distance from pts1 to pts2.
     */
    private static double cloudDistance(List<Point> pts1, List<Point> pts2, int n) {
        boolean[] matched = new boolean[n];
        double sum = 0;

        for (int i = 0; i < n; i++) {
            int index = -1;
            double minDist = Double.MAX_VALUE;

            for (int j = 0; j < n; j++) {
                if (!matched[j]) {
                    double d = distance(pts1.get(i), pts2.get(j));
                    if (d < minDist) {
                        minDist = d;
                        index = j;
                    }
                }
            }

            if (index >= 0) {
                matched[index] = true;
            }
            sum += minDist;
        }

        return sum / n;
    }

    private static double pathLength(List<Point> points) {
        double d = 0;
        for (int i = 1; i < points.size(); i++) {
            d += distance(points.get(i - 1), points.get(i));
        }
        return d;
    }

    private static double distance(Point a, Point b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }
}
