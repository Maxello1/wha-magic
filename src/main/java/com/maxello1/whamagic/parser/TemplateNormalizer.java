package com.maxello1.whamagic.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Normalizes raw strokes for template matching.
 * Ported from wha-spell-simulator's templateNormalizer.js.
 *
 * Resamples each stroke to a fixed number of evenly-spaced points,
 * then scales and centers the result into a [0,1] x [0,1] bounding box.
 */
public class TemplateNormalizer {

    private static final int DEFAULT_SAMPLES_PER_STROKE = 40;

    public static class NormalizedResult {
        public final List<List<Point>> strokes;
        public final double sourceAspectRatio;

        public NormalizedResult(List<List<Point>> strokes, double sourceAspectRatio) {
            this.strokes = strokes;
            this.sourceAspectRatio = sourceAspectRatio;
        }
    }

    /**
     * Normalize strokes: filter empties, resample, fit to unit bounds.
     */
    public static NormalizedResult normalize(List<List<Point>> rawStrokes) {
        return normalize(rawStrokes, DEFAULT_SAMPLES_PER_STROKE);
    }

    public static NormalizedResult normalize(List<List<Point>> rawStrokes, int samplesPerStroke) {
        // Filter to non-empty strokes
        List<List<Point>> sourceStrokes = new ArrayList<>();
        for (List<Point> stroke : rawStrokes) {
            if (stroke != null && !stroke.isEmpty()) {
                List<Point> copy = new ArrayList<>();
                for (Point p : stroke) copy.add(new Point(p.x, p.y));
                sourceStrokes.add(copy);
            }
        }

        // Collect all points for bounds
        List<Point> allPoints = new ArrayList<>();
        for (List<Point> stroke : sourceStrokes) allPoints.addAll(stroke);

        if (allPoints.isEmpty()) {
            return new NormalizedResult(new ArrayList<>(), 1.0);
        }

        // Compute bounding box
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Point p : allPoints) {
            if (p.x < minX) minX = p.x;
            if (p.x > maxX) maxX = p.x;
            if (p.y < minY) minY = p.y;
            if (p.y > maxY) maxY = p.y;
        }

        double width = maxX - minX;
        double height = maxY - minY;
        double scale = Math.max(Math.max(width, height), 0.0001);
        double centerX = minX + width / 2.0;
        double centerY = minY + height / 2.0;

        double aspectRatio = width / Math.max(0.0001, height);

        // Resample and normalize each stroke
        List<List<Point>> normalized = new ArrayList<>();
        for (List<Point> stroke : sourceStrokes) {
            List<Point> resampled = resampleStroke(stroke, samplesPerStroke);
            List<Point> fitted = new ArrayList<>();
            for (Point p : resampled) {
                double nx = (p.x - centerX) / scale + 0.5;
                double ny = (p.y - centerY) / scale + 0.5;
                fitted.add(new Point(round(nx, 5), round(ny, 5)));
            }
            normalized.add(fitted);
        }

        return new NormalizedResult(normalized, Math.round(aspectRatio * 1000.0) / 1000.0);
    }

    /**
     * Resample a stroke to targetCount evenly-spaced points along its path.
     */
    public static List<Point> resampleStroke(List<Point> points, int targetCount) {
        if (points.isEmpty() || targetCount <= 0) return new ArrayList<>();
        if (points.size() == 1 || targetCount == 1) {
            List<Point> result = new ArrayList<>();
            for (int i = 0; i < targetCount; i++) {
                result.add(new Point(points.get(0).x, points.get(0).y));
            }
            return result;
        }

        // Compute cumulative distances
        double[] cumulative = new double[points.size()];
        cumulative[0] = 0;
        for (int i = 1; i < points.size(); i++) {
            cumulative[i] = cumulative[i - 1] + dist(points.get(i - 1), points.get(i));
        }

        double total = cumulative[cumulative.length - 1];
        if (total <= 0.0001) {
            List<Point> result = new ArrayList<>();
            for (int i = 0; i < targetCount; i++) {
                result.add(new Point(points.get(0).x, points.get(0).y));
            }
            return result;
        }

        List<Point> result = new ArrayList<>();
        int segmentIndex = 1;
        for (int sample = 0; sample < targetCount; sample++) {
            double target = (total * sample) / Math.max(1, targetCount - 1);
            while (segmentIndex < cumulative.length - 1 && cumulative[segmentIndex] < target) {
                segmentIndex++;
            }
            double prevDist = cumulative[segmentIndex - 1];
            double nextDist = cumulative[segmentIndex];
            double local = clamp((target - prevDist) / Math.max(0.0001, nextDist - prevDist));
            Point prev = points.get(segmentIndex - 1);
            Point next = points.get(segmentIndex);
            result.add(new Point(
                    prev.x + (next.x - prev.x) * local,
                    prev.y + (next.y - prev.y) * local
            ));
        }

        return result;
    }

    private static double dist(Point a, Point b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static double round(double value, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }
}
