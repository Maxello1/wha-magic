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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RingDetector {
    private static final Logger LOGGER = LoggerFactory.getLogger(RingDetector.class);

    public record RingGlyph(Point center, double radius, double completeness, boolean isClosed,
                            double gapAngleDeg, double rmse,
                            double normalizedRmse, double maxNormalizedResidual,
                            double residualStdDev, double medianTangentAlignment,
                            double p90TangentAlignment, double circularity) {}
    public record RingDetection(RingGlyph glyph, java.util.Set<Integer> ringStrokeIndices) {}

    /**
     * Validation thresholds for circle-vs-polygon rejection.
     * Tuned against the ring_shapes fixture suite.
     */
    public record RingValidationThresholds(
            double maxAbsoluteRmse,
            double maxNormalizedRmse,
            double maxNormalizedResidual,
            double maxResidualStdDev,
            double maxMedianTangentAlignment,
            double maxP90TangentAlignment,
            double minCircularity
    ) {
        public static final RingValidationThresholds DEFAULTS = new RingValidationThresholds(
                0.05,   // maxAbsoluteRmse — absolute RMSE threshold (existing)
                0.08,   // maxNormalizedRmse — rmse / radius
                0.05,   // maxNormalizedResidual — max |dist - R| / R (rough circle ≈ 0.037, octagon ≈ 0.052)
                0.10,   // maxResidualStdDev — std(dist - R) / R
                0.25,   // maxMedianTangentAlignment — median |dot(tangent, radius)| (octagon ≈ 0.17)
                0.25,   // maxP90TangentAlignment — 90th pctl tangent (rough circle ≈ 0.14, octagon ≈ 0.30)
                0.96    // minCircularity — 4π·area/perimeter² (rough circle ≈ 0.989, octagon ≈ 0.958)
        );
    }

    public static RingDetection detectRing(List<List<Point>> strokes) {
        return detectRing(strokes, RingValidationThresholds.DEFAULTS);
    }

    public static RingDetection detectRing(List<List<Point>> strokes, RingValidationThresholds thresholds) {
        Set<Integer> bestStrokes = null;
        RingGlyph bestGlyph = null;
        double bestScore = -1;

        int n = strokes.size();
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
                // Collect per-stroke point lists (preserve stroke boundaries for tangent analysis)
                List<List<Point>> candidateStrokes = new ArrayList<>();
                int totalPoints = 0;
                for (int idx : combo) {
                    List<Point> s = resampledStrokes.get(idx);
                    candidateStrokes.add(s);
                    totalPoints += s.size();
                }

                if (totalPoints < 10) continue;

                RingGlyph glyph = fitCircle(candidateStrokes, 45, thresholds);
                if (glyph != null && glyph.radius > 0.08 && glyph.radius < 0.8 && glyph.completeness > 0.75) {
                    double score = glyph.completeness - (glyph.rmse * 5.0) - (k * 0.01);
                    if (bestGlyph == null || score > bestScore) {
                        bestGlyph = glyph;
                        bestStrokes = new HashSet<>(combo);
                        bestScore = score;
                    }
                }
            }
        }

        if (bestStrokes == null || bestGlyph == null) {
            LOGGER.debug("Ring detection: no ring found");
            return null;
        }
        LOGGER.debug("Ring detection: found ring (r={}, completeness={}, rmse={}, normRmse={}, " +
                        "maxResid={}, residStd={}, medTangent={}, p90Tangent={}, circ={})",
                String.format("%.3f", bestGlyph.radius),
                String.format("%.3f", bestGlyph.completeness),
                String.format("%.4f", bestGlyph.rmse),
                String.format("%.4f", bestGlyph.normalizedRmse),
                String.format("%.4f", bestGlyph.maxNormalizedResidual),
                String.format("%.4f", bestGlyph.residualStdDev),
                String.format("%.4f", bestGlyph.medianTangentAlignment),
                String.format("%.4f", bestGlyph.p90TangentAlignment),
                String.format("%.4f", bestGlyph.circularity));
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

    /**
     * Fit a circle to a set of candidate strokes with comprehensive circularity validation.
     *
     * <p>The algebraic Kåsa fit operates on all points combined. Local tangent-alignment
     * measurements are computed independently within each source stroke to avoid
     * creating false tangent vectors across stroke boundaries.</p>
     *
     * @param candidateStrokes per-stroke point lists (stroke boundaries preserved)
     * @param maxGapDeg maximum angular gap (degrees) for closed classification
     * @param thresholds validation thresholds for polygon rejection
     * @return RingGlyph if the candidate passes all checks, null otherwise
     */
    private static RingGlyph fitCircle(List<List<Point>> candidateStrokes,
                                       double maxGapDeg,
                                       RingValidationThresholds thresholds) {
        // Flatten for global fit
        List<Point> pts = new ArrayList<>();
        for (List<Point> stroke : candidateStrokes) {
            pts.addAll(stroke);
        }
        int n = pts.size();
        if (n < 3) return null;

        // ---- Least squares algebraic circle fit (Kåsa method) ----
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
        Point center = new Point(cx, cy);

        // ---- Radial residual analysis ----
        double sumSqErr = 0;
        double maxResidual = 0;
        double sumResidual = 0;
        double sumSqResidual = 0;
        for (Point p : pts) {
            double dist = distance(p, center);
            double err = dist - R;
            sumSqErr += err * err;
            double absNormResidual = Math.abs(err) / R;
            maxResidual = Math.max(maxResidual, absNormResidual);
            sumResidual += err / R;
            sumSqResidual += (err / R) * (err / R);
        }
        double rmse = Math.sqrt(sumSqErr / n);
        double normalizedRmse = rmse / R;
        double meanResidual = sumResidual / n;
        double residualVariance = Math.max(0.0, sumSqResidual / n - meanResidual * meanResidual);
        double residualStdDev = Math.sqrt(residualVariance);

        // ---- Gate 1: Absolute RMSE (existing check) ----
        if (rmse > thresholds.maxAbsoluteRmse) return null;

        // ---- Gate 2: Normalized RMSE ----
        if (normalizedRmse > thresholds.maxNormalizedRmse) return null;

        // ---- Gate 3: Maximum normalized radial residual ----
        if (maxResidual > thresholds.maxNormalizedResidual) return null;

        // ---- Gate 4: Radial residual standard deviation ----
        if (residualStdDev > thresholds.maxResidualStdDev) return null;

        // ---- Tangent-to-radius alignment (per-stroke, skip endpoints) ----
        List<Double> alignments = new ArrayList<>();
        for (List<Point> stroke : candidateStrokes) {
            if (stroke.size() < 3) continue;
            // Skip first and last point of each stroke
            for (int i = 1; i < stroke.size() - 1; i++) {
                Point prev = stroke.get(i - 1);
                Point curr = stroke.get(i);
                Point next = stroke.get(i + 1);

                // Local tangent vector (central difference)
                double tx = next.x - prev.x;
                double ty = next.y - prev.y;
                double tLen = Math.sqrt(tx * tx + ty * ty);
                if (tLen < 1e-10) continue;
                tx /= tLen;
                ty /= tLen;

                // Radius vector from center to current point
                double rx = curr.x - cx;
                double ry = curr.y - cy;
                double rLen = Math.sqrt(rx * rx + ry * ry);
                if (rLen < 1e-10) continue;
                rx /= rLen;
                ry /= rLen;

                // Absolute dot product: 0 = perpendicular (circle), 1 = parallel (polygon edge)
                double alignment = Math.abs(tx * rx + ty * ry);
                alignments.add(alignment);
            }
        }

        double medianTangentAlignment = 0;
        double p90TangentAlignment = 0;
        if (!alignments.isEmpty()) {
            double[] sorted = alignments.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            medianTangentAlignment = sorted[sorted.length / 2];
            int p90Index = (int) (sorted.length * 0.90);
            p90TangentAlignment = sorted[Math.min(p90Index, sorted.length - 1)];
        }

        // ---- Gate 5: Tangent alignment ----
        if (medianTangentAlignment > thresholds.maxMedianTangentAlignment) return null;
        if (p90TangentAlignment > thresholds.maxP90TangentAlignment) return null;

        // ---- Circularity for closed single-stroke candidates ----
        double circularity = 0;
        if (candidateStrokes.size() == 1) {
            List<Point> stroke = candidateStrokes.get(0);
            if (stroke.size() >= 4) {
                double perimeter = 0;
                for (int i = 0; i < stroke.size() - 1; i++) {
                    perimeter += distance(stroke.get(i), stroke.get(i + 1));
                }
                Point first = stroke.get(0);
                Point last = stroke.get(stroke.size() - 1);
                perimeter += distance(last, first);

                // Shoelace formula for area
                double area = 0;
                for (int i = 0; i < stroke.size() - 1; i++) {
                    area += stroke.get(i).x * stroke.get(i + 1).y;
                    area -= stroke.get(i + 1).x * stroke.get(i).y;
                }
                area += last.x * first.y;
                area -= first.x * last.y;
                area = Math.abs(area) / 2.0;

                if (perimeter > 1e-10) {
                    circularity = (4.0 * Math.PI * area) / (perimeter * perimeter);
                }

                // ---- Gate 6: Circularity (single-stroke only) ----
                if (circularity < thresholds.minCircularity) return null;
            }
        }

        // ---- Angular completeness and gap ----
        boolean[] bins = new boolean[360];
        for (Point p : pts) {
            double angle = Math.toDegrees(Math.atan2(p.y - cy, p.x - cx));
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

        return new RingGlyph(center, R, completeness, isClosed, maxGap, rmse,
                normalizedRmse, maxResidual, residualStdDev,
                medianTangentAlignment, p90TangentAlignment, circularity);
    }

    private static List<Point> resample(List<Point> pts, double interval) {
        if (pts.isEmpty()) return pts;
        List<Point> resampled = new ArrayList<>();
        resampled.add(pts.get(0));
        double dAccum = 0;
        int i = 1;
        Point current = pts.get(0);
        while (i < pts.size()) {
            Point next = pts.get(i);
            double d = distance(current, next);
            if (dAccum + d >= interval) {
                double tx = current.x + ((interval - dAccum) / d) * (next.x - current.x);
                double ty = current.y + ((interval - dAccum) / d) * (next.y - current.y);
                Point q = new Point(tx, ty);
                resampled.add(q);
                current = q;
                dAccum = 0;
            } else {
                dAccum += d;
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
