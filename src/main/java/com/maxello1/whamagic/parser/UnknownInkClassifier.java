package com.maxello1.whamagic.parser;

import com.maxello1.whamagic.magic.RecognitionRejectionReason;
import com.maxello1.whamagic.magic.UnknownInkClassification;

import java.util.List;

/** Structural classification for source ink not owned by a recognized symbol. */
final class UnknownInkClassifier {
    private static final double NOISE_PATH_LENGTH = 0.10;
    private static final int NOISE_POINT_COUNT = 4;
    private static final double NOISE_DIMENSION = 0.07;

    // A small group of short marks can remain diagnostic-only without invalidating a spell.
    private static final double HARMLESS_PATH_LENGTH = 0.18;
    private static final double HARMLESS_DIAGONAL = 0.18;
    private static final int MAX_GROUPED_HARMLESS_STROKES = 3;

    private UnknownInkClassifier() {}

    static UnknownInkClassification classify(
            List<List<Point>> strokes,
            RecognitionRejectionReason rejectionReason) {
        Geometry geometry = geometry(strokes);
        if (isNoiseGeometry(geometry)) {
            return UnknownInkClassification.NOISE;
        }
        if (rejectionReason == RecognitionRejectionReason.AMBIGUOUS_TOP_MATCHES) {
            return UnknownInkClassification.AMBIGUOUS;
        }
        if (strokes.size() <= MAX_GROUPED_HARMLESS_STROKES
                && strokes.stream().allMatch(UnknownInkClassifier::isIndividuallyHarmless)) {
            return UnknownInkClassification.HARMLESS_UNEXPLAINED;
        }
        return UnknownInkClassification.SUBSTANTIAL_UNKNOWN;
    }

    private static boolean isIndividuallyHarmless(List<Point> stroke) {
        Geometry geometry = geometry(List.of(stroke));
        return isNoiseGeometry(geometry)
                || (geometry.pathLength <= HARMLESS_PATH_LENGTH
                    && Math.hypot(geometry.width, geometry.height) <= HARMLESS_DIAGONAL);
    }

    private static boolean isNoiseGeometry(Geometry geometry) {
        return geometry.pointCount < NOISE_POINT_COUNT
                || geometry.pathLength < NOISE_PATH_LENGTH
                || (geometry.width < NOISE_DIMENSION && geometry.height < NOISE_DIMENSION);
    }

    static boolean isNoise(List<List<Point>> strokes) {
        return classify(strokes, RecognitionRejectionReason.NONE) == UnknownInkClassification.NOISE;
    }

    static BoundingBox bounds(List<List<Point>> strokes) {
        Geometry geometry = geometry(strokes);
        return geometry.pointCount == 0 ? null
                : new BoundingBox(geometry.minX, geometry.minY, geometry.maxX, geometry.maxY);
    }

    private static Geometry geometry(List<List<Point>> strokes) {
        int pointCount = 0;
        double pathLength = 0;
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (List<Point> stroke : strokes) {
            pointCount += stroke.size();
            for (int i = 0; i < stroke.size(); i++) {
                Point point = stroke.get(i);
                minX = Math.min(minX, point.x);
                minY = Math.min(minY, point.y);
                maxX = Math.max(maxX, point.x);
                maxY = Math.max(maxY, point.y);
                if (i > 0) {
                    Point previous = stroke.get(i - 1);
                    pathLength += Math.hypot(point.x - previous.x, point.y - previous.y);
                }
            }
        }
        if (pointCount == 0) {
            return new Geometry(0, 0, 0, 0, 0, 0, 0, 0);
        }
        return new Geometry(pointCount, pathLength, minX, minY, maxX, maxY,
                Math.max(0, maxX - minX), Math.max(0, maxY - minY));
    }

    private record Geometry(
            int pointCount,
            double pathLength,
            double minX,
            double minY,
            double maxX,
            double maxY,
            double width,
            double height) {}
}
