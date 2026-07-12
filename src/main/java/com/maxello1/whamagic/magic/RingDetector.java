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
package com.maxello1.whamagic.magic;

import com.maxello1.whamagic.parser.Point;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RingDetector {
    public record RingGlyph(Point center, double radius, double completeness, boolean isClosed, double gapAngleDeg, double rmse) {}
    public record RingDetection(RingGlyph glyph, java.util.Set<Integer> ringStrokeIndices) {}

    public static RingDetection detectRing(List<List<Point>> strokes) {
        Set<Integer> bestStrokes = null;
        RingGlyph bestGlyph = null;
        double bestScore = -1;
        
        int n = strokes.size();
        // Test combinations up to 4 strokes to form a ring
        int maxK = Math.min(n, 4);
        
        // Pre-resample to save time
        List<List<Point>> resampledStrokes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            resampledStrokes.add(resample(strokes.get(i), 0.025));
        }
        
        for (int k = 1; k <= maxK; k++) {
            List<List<Integer>> combos = new ArrayList<>();
            generateCombinations(n, k, 0, new ArrayList<>(), combos);
            
            for (List<Integer> combo : combos) {
                List<Point> combined = new ArrayList<>();
                for (int idx : combo) {
                    combined.addAll(resampledStrokes.get(idx));
                }
                
                if (combined.size() < 10) continue;
                
                RingGlyph glyph = fitCircle(combined, 45, 0.05);
                if (glyph != null && glyph.radius > 0.08 && glyph.radius < 0.8 && glyph.completeness > 0.75) {
                    // Favor completeness, heavily penalize RMSE, penalize using extra strokes slightly
                    double score = glyph.completeness - (glyph.rmse * 5.0) - (k * 0.01);
                    if (bestGlyph == null || score > bestScore) {
                        bestGlyph = glyph;
                        bestStrokes = new HashSet<>(combo);
                        bestScore = score;
                    }
                }
            }
        }
        
        if (bestStrokes == null || bestGlyph == null) return null;
        return new RingDetection(bestGlyph, bestStrokes);
    }
    
    private static void generateCombinations(int n, int k, int start, List<Integer> current, List<List<Integer>> result) {
        if (current.size() == k) {
            result.add(new ArrayList<>(current));
            return;
        }
        for (int i = start; i < n; i++) {
            current.add(i);
            generateCombinations(n, k, i + 1, current, result);
            current.remove(current.size() - 1);
        }
    }
    
    private static RingGlyph fitCircle(List<Point> pts, double maxGapDeg, double maxRmse) {
        int n = pts.size();
        if (n < 3) return null;
        
        // Least squares algebraic circle fit (Kåsa method)
        double Mx = 0, My = 0;
        for (Point p : pts) { Mx += p.x; My += p.y; }
        Mx /= n; My /= n;
        
        double Cxx = 0, Cyy = 0, Cxy = 0, Cxz = 0, Cyz = 0, Mz = 0;
        for (Point p : pts) {
            double x = p.x - Mx;
            double y = p.y - My;
            double z = x * x + y * y;
            Cxx += x * x; Cyy += y * y; Cxy += x * y;
            Cxz += x * z; Cyz += y * z; Mz += z;
        }
        Cxx /= n; Cyy /= n; Cxy /= n; Cxz /= n; Cyz /= n; Mz /= n;
        
        double D = Cxx * Cyy - Cxy * Cxy;
        if (Math.abs(D) < 1e-10) return null; // Collinear
        
        double A = (Cxz * Cyy - Cyz * Cxy) / D;
        double B = (Cxx * Cyz - Cxy * Cxz) / D;
        double cx = A / 2.0 + Mx;
        double cy = B / 2.0 + My;
        double rSq = (A / 2.0) * (A / 2.0) + (B / 2.0) * (B / 2.0) + Mz;
        if (rSq <= 0) return null;
        double R = Math.sqrt(rSq);
        
        // Root Mean Square Error calculation
        double rmse = 0;
        for (Point p : pts) {
            double err = distance(p, new Point(cx, cy)) - R;
            rmse += err * err;
        }
        rmse = Math.sqrt(rmse / n);
        
        if (rmse > maxRmse) return null;
        
        // Calculate completeness and gap
        boolean[] bins = new boolean[360];
        for (Point p : pts) {
            double angle = Math.toDegrees(Math.atan2(p.y - cy, p.x - cx));
            if (angle < 0) angle += 360;
            int bin = (int) Math.round(angle) % 360;
            // Pad slightly for tolerance
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
            if (bins[i % 360]) {
                if (i < 360) filled++;
                if (currentGap > maxGap) maxGap = currentGap;
                currentGap = 0;
            } else {
                currentGap++;
            }
        }
        
        double completeness = filled / 360.0;
        boolean isClosed = completeness > 0.85 && maxGap < maxGapDeg;
        
        return new RingGlyph(new Point(cx, cy), R, completeness, isClosed, maxGap, rmse);
    }
    
    private static List<Point> resample(List<Point> pts, double interval) {
        if (pts.isEmpty()) return pts;
        List<Point> resampled = new ArrayList<>();
        resampled.add(pts.get(0));
        double D = 0;
        int i = 1;
        Point current = pts.get(0);
        while (i < pts.size()) {
            Point next = pts.get(i);
            double d = distance(current, next);
            if (D + d >= interval) {
                double tx = current.x + ((interval - D) / d) * (next.x - current.x);
                double ty = current.y + ((interval - D) / d) * (next.y - current.y);
                Point q = new Point(tx, ty);
                resampled.add(q);
                current = q;
                D = 0;
            } else {
                D += d;
                current = next;
                i++;
            }
        }
        return resampled;
    }

    private static double distance(Point a, Point b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }
}
