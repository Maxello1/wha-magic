package com.maxello1.whamagic.magic;

import com.maxello1.whamagic.parser.Point;
import java.util.List;

public class RingDetector {
    public record RingGlyph(Point center, double radius, double completeness, boolean isClosed, double gapAngleDeg) {}
    public record RingDetection(RingGlyph glyph, java.util.Set<Integer> ringStrokeIndices) {}

    public static RingDetection detectRing(List<List<Point>> strokes) {
        int bestStrokeIdx = -1;
        double bestRadius = 0;
        Point bestCenter = null;
        
        for (int i = 0; i < strokes.size(); i++) {
            List<Point> stroke = strokes.get(i);
            if (stroke.size() < 10) continue;
            
            // Calculate center
            double cx = 0, cy = 0;
            for (Point p : stroke) { cx += p.x; cy += p.y; }
            cx /= stroke.size();
            cy /= stroke.size();
            Point center = new Point(cx, cy);
            
            // Calculate radius
            double avgR = 0;
            for (Point p : stroke) {
                avgR += distance(p, center);
            }
            avgR /= stroke.size();
            
            // Normalized radius threshold = 0.08
            if (avgR > 0.08 && avgR > bestRadius) {
                bestStrokeIdx = i;
                bestRadius = avgR;
                bestCenter = center;
            }
        }
        
        if (bestStrokeIdx == -1) return null;
        List<Point> bestStroke = strokes.get(bestStrokeIdx);
        
        // Measure coverage
        boolean[] bins = new boolean[360];
        for (Point p : bestStroke) {
            double angle = Math.toDegrees(Math.atan2(p.y - bestCenter.y, p.x - bestCenter.x));
            if (angle < 0) angle += 360;
            int bin = (int) Math.round(angle) % 360;
            bins[bin] = true;
            bins[(bin + 1) % 360] = true;
            bins[(bin + 359) % 360] = true;
            bins[(bin + 2) % 360] = true;
            bins[(bin + 358) % 360] = true;
        }
        
        int filled = 0;
        int maxGap = 0;
        int currentGap = 0;
        
        for (int i = 0; i < 720; i++) {
            int idx = i % 360;
            if (bins[idx]) {
                if (i < 360) filled++;
                if (currentGap > maxGap) maxGap = currentGap;
                currentGap = 0;
            } else {
                currentGap++;
            }
        }
        
        double completeness = filled / 360.0;
        boolean isClosed = completeness > 0.85 && maxGap < 45;
        
        RingGlyph ring = new RingGlyph(bestCenter, bestRadius, completeness, isClosed, maxGap);
        return new RingDetection(ring, java.util.Set.of(bestStrokeIdx));
    }
    
    private static double distance(Point a, Point b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }
}
