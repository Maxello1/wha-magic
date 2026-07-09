package com.example.parser;

import java.util.ArrayList;
import java.util.List;

public class SpellParser {

    public static class ParseResult {
        public final boolean hasRing;
        public final CloudRecognizer.RecognitionResult sign;
        public final String statusMessage;

        public ParseResult(boolean hasRing, CloudRecognizer.RecognitionResult sign, String statusMessage) {
            this.hasRing = hasRing;
            this.sign = sign;
            this.statusMessage = statusMessage;
        }

        public boolean isValidSpell() {
            return hasRing && sign != null && sign.recognized;
        }
    }

    /**
     * Parses the grammar of the spell strokes (Rings and enclosed Signs).
     */
    public static ParseResult parse(List<List<Point>> strokes) {
        if (strokes == null || strokes.isEmpty()) {
            return new ParseResult(false, null, "");
        }

        List<List<Point>> rings = new ArrayList<>();
        List<List<Point>> remainingStrokes = new ArrayList<>();

        // 1. Detect Rings
        for (List<Point> stroke : strokes) {
            if (isClosedRing(stroke)) {
                rings.add(stroke);
            } else {
                remainingStrokes.add(stroke);
            }
        }

        if (rings.isEmpty()) {
            // No rings detected, but try to recognize what the user is drawing for feedback
            CloudRecognizer.RecognitionResult partialSign = CloudRecognizer.recognize(strokes);
            String status = partialSign.recognized ? "Drafting: " + partialSign.displayName + " (Missing Ring)" : "Drafting: Needs Ring";
            return new ParseResult(false, partialSign, status);
        }

        // 2. We have at least one ring. Find strokes inside the largest ring.
        // For MVP, we just take the first detected ring.
        List<Point> mainRing = rings.get(0);
        BoundingBox ringBox = computeBounds(mainRing);

        List<List<Point>> internalStrokes = new ArrayList<>();
        for (List<Point> stroke : remainingStrokes) {
            if (isInside(stroke, ringBox)) {
                internalStrokes.add(stroke);
            }
        }

        if (internalStrokes.isEmpty()) {
            return new ParseResult(true, null, "Drafting: Ring empty");
        }

        // 3. Recognize the internal sign
        CloudRecognizer.RecognitionResult signResult = CloudRecognizer.recognize(internalStrokes);

        if (signResult.recognized) {
            return new ParseResult(true, signResult, "Prepared: " + signResult.displayName);
        } else {
            return new ParseResult(true, signResult, "Drafting: Unknown sign in Ring");
        }
    }

    /**
     * A stroke is considered a ring if it is sufficiently long and its start and end points are close.
     */
    private static boolean isClosedRing(List<Point> stroke) {
        if (stroke.size() < 15) return false;

        double pathLen = 0.0;
        for (int i = 1; i < stroke.size(); i++) {
            pathLen += distance(stroke.get(i - 1), stroke.get(i));
        }

        if (pathLen < 100.0) return false; // Too small

        Point start = stroke.get(0);
        Point end = stroke.get(stroke.size() - 1);
        double closureDistance = distance(start, end);

        // Allow up to 20% of the total path length as closure gap
        return closureDistance < (pathLen * 0.2);
    }

    private static boolean isInside(List<Point> stroke, BoundingBox box) {
        if (stroke.isEmpty()) return false;
        
        double cx = 0, cy = 0;
        for (Point p : stroke) {
            cx += p.x;
            cy += p.y;
        }
        cx /= stroke.size();
        cy /= stroke.size();

        return cx >= box.minX && cx <= box.maxX && cy >= box.minY && cy <= box.maxY;
    }

    private static BoundingBox computeBounds(List<Point> points) {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        for (Point p : points) {
            if (p.x < minX) minX = p.x;
            if (p.x > maxX) maxX = p.x;
            if (p.y < minY) minY = p.y;
            if (p.y > maxY) maxY = p.y;
        }
        return new BoundingBox(minX, maxX, minY, maxY);
    }

    private static double distance(Point a, Point b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    public static class BoundingBox {
        public final double minX, maxX, minY, maxY;
        public BoundingBox(double minX, double maxX, double minY, double maxY) {
            this.minX = minX; this.maxX = maxX;
            this.minY = minY; this.maxY = maxY;
        }
    }
}
