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
import java.util.LinkedHashSet;
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

    /** Deterministic work limits for the server-authoritative ring search. */
    public record RingSearchSettings(
            int maxEligibleStrokes,
            int maxCombinations,
            int maxResampledPointsPerStroke,
            int maxRingStrokes
    ) {
        public static final RingSearchSettings DEFAULTS = new RingSearchSettings(18, 4096, 128, 4);

        public RingSearchSettings {
            if (maxEligibleStrokes < 1) throw new IllegalArgumentException("maxEligibleStrokes must be positive");
            if (maxCombinations < 1) throw new IllegalArgumentException("maxCombinations must be positive");
            if (maxResampledPointsPerStroke < 10) throw new IllegalArgumentException("maxResampledPointsPerStroke must be at least 10");
            if (maxRingStrokes < 1) throw new IllegalArgumentException("maxRingStrokes must be positive");
        }
    }

    /** Work and filtering diagnostics for one bounded ring search. */
    public record RingSearchDiagnostics(
            int inputStrokeCount,
            int eligibleStrokeCount,
            int combinationsConsidered,
            int fitsAttempted,
            long elapsedNanos,
            boolean budgetExhausted,
            List<Integer> droppedSourceStrokeIndices
    ) {
        public RingSearchDiagnostics {
            droppedSourceStrokeIndices = List.copyOf(droppedSourceStrokeIndices);
        }
    }

    /** Search result which distinguishes "no ring" from an exhausted search. */
    public record RingSearchResult(RingDetection detection, RingSearchDiagnostics diagnostics) {}

    private record IndexedStroke(int sourceIndex, List<Point> points) {}

    private static final double RESAMPLE_INTERVAL = 0.025;
    private static final double MIN_RING_STROKE_PATH = 0.08;
    private static final double MIN_RING_STROKE_EXTENT = 0.05;
    private static final double MAX_ENDPOINT_GAP_RADIUS_RATIO = 0.25;

    /**
     * A hand-drawn circle may briefly overtrace its closure. That adds local
     * radial/path error without producing the long straight-edge tangent profile
     * of a polygon. These limits apply only to closed single-stroke candidates.
     */
    private static final double ROUGH_MAX_NORMALIZED_RMSE = 0.075;
    private static final double ROUGH_MAX_NORMALIZED_RESIDUAL = 0.12;
    private static final double ROUGH_MAX_MEDIAN_TANGENT_ALIGNMENT = 0.15;
    private static final double ROUGH_MAX_P90_TANGENT_ALIGNMENT = 0.28;
    private static final double ROUGH_MIN_CIRCULARITY = 0.90;

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
        return searchRing(strokes).detection();
    }

    public static RingDetection detectRing(List<List<Point>> strokes, RingValidationThresholds thresholds) {
        return searchRing(strokes, RingSearchSettings.DEFAULTS, thresholds).detection();
    }

    public static RingSearchResult searchRing(List<List<Point>> strokes) {
        return searchRing(strokes, RingSearchSettings.DEFAULTS);
    }

    public static RingSearchResult searchRing(List<List<Point>> strokes, RingSearchSettings settings) {
        return searchRing(strokes, settings, RingValidationThresholds.DEFAULTS);
    }

    private static RingSearchResult searchRing(List<List<Point>> strokes,
                                               RingSearchSettings settings,
                                               RingValidationThresholds thresholds) {
        long started = System.nanoTime();
        int inputStrokeCount = strokes == null ? 0 : strokes.size();
        if (strokes == null || strokes.isEmpty()) {
            return new RingSearchResult(null, new RingSearchDiagnostics(
                    inputStrokeCount, 0, 0, 0, System.nanoTime() - started, false, List.of()));
        }

        List<IndexedStroke> eligibleStrokes = new ArrayList<>();
        List<Integer> droppedSourceIndices = new ArrayList<>();
        boolean eligibleLimitExceeded = false;

        // Filter before resampling so irrelevant strokes cannot expand into large point clouds.
        for (int i = 0; i < strokes.size(); i++) {
            List<Point> stroke = strokes.get(i);
            if (!isRingLikelyStroke(stroke)) {
                droppedSourceIndices.add(i);
                continue;
            }
            if (eligibleStrokes.size() >= settings.maxEligibleStrokes()) {
                droppedSourceIndices.add(i);
                eligibleLimitExceeded = true;
                continue;
            }
            List<Point> resampled = resample(stroke, RESAMPLE_INTERVAL, settings.maxResampledPointsPerStroke());
            if (resampled.size() < 3) {
                droppedSourceIndices.add(i);
                continue;
            }
            eligibleStrokes.add(new IndexedStroke(i, resampled));
        }

        // Dropping a ring-like stroke due to the eligible-stroke cap makes the search partial.
        // Fail closed rather than authoritatively choosing from an incomplete search space.
        if (eligibleLimitExceeded) {
            RingSearchDiagnostics diagnostics = new RingSearchDiagnostics(
                    inputStrokeCount, eligibleStrokes.size(), 0, 0,
                    System.nanoTime() - started, true, droppedSourceIndices);
            LOGGER.debug("Ring detection: eligible-stroke budget exhausted ({}/{})",
                    eligibleStrokes.size(), settings.maxEligibleStrokes());
            return new RingSearchResult(null, diagnostics);
        }

        Set<Integer> bestStrokes = null;
        RingGlyph bestGlyph = null;
        SearchState searchState = new SearchState(settings.maxCombinations());
        int maxK = Math.min(eligibleStrokes.size(), settings.maxRingStrokes());
        for (int k = 1; k <= maxK && !searchState.budgetExhausted; k++) {
            enumerateCombinations(eligibleStrokes, k, 0, new ArrayList<>(), searchState, thresholds);
        }

        if (searchState.bestGlyph != null) {
            bestGlyph = searchState.bestGlyph;
            bestStrokes = searchState.bestStrokes;
        }

        RingSearchDiagnostics diagnostics = new RingSearchDiagnostics(
                inputStrokeCount, eligibleStrokes.size(), searchState.combinationsConsidered,
                searchState.fitsAttempted, System.nanoTime() - started,
                searchState.budgetExhausted, droppedSourceIndices);

        if (searchState.budgetExhausted) {
            LOGGER.debug("Ring detection: combination budget exhausted after {} combinations and {} fits",
                    searchState.combinationsConsidered, searchState.fitsAttempted);
            return new RingSearchResult(null, diagnostics);
        }

        if (bestStrokes == null || bestGlyph == null) {
            LOGGER.debug("Ring detection: no ring found (eligible={}, combinations={}, fits={})",
                    eligibleStrokes.size(), searchState.combinationsConsidered, searchState.fitsAttempted);
            return new RingSearchResult(null, diagnostics);
        }

        LOGGER.debug("Ring detection: found ring (r={}, completeness={}, rmse={}, normRmse={}, " +
                        "maxResid={}, residStd={}, medTangent={}, p90Tangent={}, circ={}, combinations={}, fits={})",
                String.format("%.3f", bestGlyph.radius),
                String.format("%.3f", bestGlyph.completeness),
                String.format("%.4f", bestGlyph.rmse),
                String.format("%.4f", bestGlyph.normalizedRmse),
                String.format("%.4f", bestGlyph.maxNormalizedResidual),
                String.format("%.4f", bestGlyph.residualStdDev),
                String.format("%.4f", bestGlyph.medianTangentAlignment),
                String.format("%.4f", bestGlyph.p90TangentAlignment),
                String.format("%.4f", bestGlyph.circularity),
                searchState.combinationsConsidered,
                searchState.fitsAttempted);
        return new RingSearchResult(new RingDetection(bestGlyph, Set.copyOf(bestStrokes)), diagnostics);
    }

    private static void enumerateCombinations(List<IndexedStroke> eligibleStrokes,
                                              int k,
                                              int start,
                                              List<Integer> current,
                                              SearchState state,
                                              RingValidationThresholds thresholds) {
        if (state.budgetExhausted) return;
        if (current.size() == k) {
            if (state.combinationsConsidered >= state.maxCombinations) {
                state.budgetExhausted = true;
                return;
            }
            state.combinationsConsidered++;

            List<List<Point>> candidateStrokes = new ArrayList<>(k);
            Set<Integer> sourceIndices = new LinkedHashSet<>();
            int totalPoints = 0;
            for (int eligibleIndex : current) {
                IndexedStroke stroke = eligibleStrokes.get(eligibleIndex);
                candidateStrokes.add(stroke.points());
                sourceIndices.add(stroke.sourceIndex());
                totalPoints += stroke.points().size();
            }
            if (totalPoints < 10) return;

            state.fitsAttempted++;
            RingGlyph glyph = fitCircle(candidateStrokes, 45, thresholds);
            if (glyph != null && glyph.radius > 0.08 && glyph.radius < 0.8
                    && glyph.completeness > 0.75 && glyph.isClosed()) {
                double score = glyph.completeness - (glyph.rmse * 5.0) - (k * 0.01);
                if (state.bestGlyph == null || score > state.bestScore) {
                    state.bestGlyph = glyph;
                    state.bestStrokes = sourceIndices;
                    state.bestScore = score;
                }
            }
            return;
        }
        int remainingNeeded = k - current.size();
        int lastStart = eligibleStrokes.size() - remainingNeeded;
        for (int i = start; i <= lastStart && !state.budgetExhausted; i++) {
            current.add(i);
            enumerateCombinations(eligibleStrokes, k, i + 1, current, state, thresholds);
            current.remove(current.size() - 1);
        }
    }

    private static final class SearchState {
        private final int maxCombinations;
        private int combinationsConsidered;
        private int fitsAttempted;
        private boolean budgetExhausted;
        private RingGlyph bestGlyph;
        private Set<Integer> bestStrokes;
        private double bestScore = -1;

        private SearchState(int maxCombinations) {
            this.maxCombinations = maxCombinations;
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

        // ---- Gate 3: Radial residual standard deviation ----
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

        boolean roughClosedStrokeProfile = candidateStrokes.size() == 1
                && normalizedRmse <= ROUGH_MAX_NORMALIZED_RMSE
                && medianTangentAlignment <= ROUGH_MAX_MEDIAN_TANGENT_ALIGNMENT
                && p90TangentAlignment <= ROUGH_MAX_P90_TANGENT_ALIGNMENT;

        // ---- Gate 4: Tangent alignment ----
        if (medianTangentAlignment > thresholds.maxMedianTangentAlignment
                && !roughClosedStrokeProfile) return null;
        if (p90TangentAlignment > thresholds.maxP90TangentAlignment
                && !roughClosedStrokeProfile) return null;

        // ---- Gate 5: Maximum radial residual ----
        if (maxResidual > thresholds.maxNormalizedResidual
                && !(roughClosedStrokeProfile
                    && maxResidual <= ROUGH_MAX_NORMALIZED_RESIDUAL)) return null;

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
                if (circularity < thresholds.minCircularity
                        && !(roughClosedStrokeProfile
                            && circularity >= ROUGH_MIN_CIRCULARITY)) return null;
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
        boolean angularlyClosed = completeness > 0.85 && maxGap < maxGapDeg;
        boolean endpointTopologyClosed = hasClosedEndpointTopology(
                candidateStrokes, R * MAX_ENDPOINT_GAP_RADIUS_RATIO);
        boolean isClosed = angularlyClosed && endpointTopologyClosed;

        return new RingGlyph(center, R, completeness, isClosed, maxGap, rmse,
                normalizedRmse, maxResidual, residualStdDev,
                medianTangentAlignment, p90TangentAlignment, circularity);
    }

    /**
     * Cheap, permissive ring-likelihood filter applied before point-cloud expansion.
     * A useful ring stroke must have visible extent and either curve away from its
     * endpoint chord or return close to its start. Polygonal loops intentionally
     * remain eligible and are rejected later by the circle-vs-polygon gates.
     */
    private static boolean isRingLikelyStroke(List<Point> pts) {
        if (pts == null || pts.size() < 3) return false;

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double pathLength = 0;

        Point previous = null;
        for (Point point : pts) {
            if (point == null || !Double.isFinite(point.x) || !Double.isFinite(point.y)) return false;
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
            if (previous != null) pathLength += distance(previous, point);
            previous = point;
        }

        double extent = Math.hypot(maxX - minX, maxY - minY);
        if (pathLength < MIN_RING_STROKE_PATH || extent < MIN_RING_STROKE_EXTENT) return false;

        double endpointChord = distance(pts.get(0), pts.get(pts.size() - 1));
        boolean returnsNearStart = endpointChord <= Math.max(0.03, extent * 0.20);
        boolean visiblyCurved = pathLength - endpointChord > 0.002
                && pathLength > endpointChord * 1.003;
        return returnsNearStart || visiblyCurved;
    }

    /**
     * A single-stroke ring must return near its start. For multiple strokes,
     * every endpoint must pair with an endpoint from another stroke and the
     * resulting stroke graph must be connected. With at most four strokes this
     * bounded perfect-matching search examines no more than 105 pairings.
     */
    private static boolean hasClosedEndpointTopology(List<List<Point>> strokes, double maxEndpointGap) {
        if (strokes.isEmpty()) return false;
        if (strokes.size() == 1) {
            List<Point> stroke = strokes.get(0);
            return !stroke.isEmpty()
                    && distance(stroke.get(0), stroke.get(stroke.size() - 1)) <= maxEndpointGap;
        }

        List<StrokeEndpoint> endpoints = new ArrayList<>(strokes.size() * 2);
        for (int strokeIndex = 0; strokeIndex < strokes.size(); strokeIndex++) {
            List<Point> stroke = strokes.get(strokeIndex);
            if (stroke.isEmpty()) return false;
            endpoints.add(new StrokeEndpoint(strokeIndex, stroke.get(0)));
            endpoints.add(new StrokeEndpoint(strokeIndex, stroke.get(stroke.size() - 1)));
        }

        return findConnectedEndpointPairing(
                endpoints, maxEndpointGap, new boolean[endpoints.size()],
                new boolean[strokes.size()][strokes.size()]);
    }

    private record StrokeEndpoint(int strokeIndex, Point point) {}

    private static boolean findConnectedEndpointPairing(List<StrokeEndpoint> endpoints,
                                                         double maxEndpointGap,
                                                         boolean[] paired,
                                                         boolean[][] strokeConnections) {
        int firstUnpaired = -1;
        for (int i = 0; i < paired.length; i++) {
            if (!paired[i]) {
                firstUnpaired = i;
                break;
            }
        }
        if (firstUnpaired < 0) return isConnectedStrokeGraph(strokeConnections);

        StrokeEndpoint first = endpoints.get(firstUnpaired);
        paired[firstUnpaired] = true;
        for (int j = firstUnpaired + 1; j < endpoints.size(); j++) {
            if (paired[j]) continue;
            StrokeEndpoint second = endpoints.get(j);
            if (first.strokeIndex() == second.strokeIndex()) continue;
            if (distance(first.point(), second.point()) > maxEndpointGap) continue;

            boolean wasConnected = strokeConnections[first.strokeIndex()][second.strokeIndex()];
            paired[j] = true;
            strokeConnections[first.strokeIndex()][second.strokeIndex()] = true;
            strokeConnections[second.strokeIndex()][first.strokeIndex()] = true;

            if (findConnectedEndpointPairing(endpoints, maxEndpointGap, paired, strokeConnections)) return true;

            paired[j] = false;
            if (!wasConnected) {
                strokeConnections[first.strokeIndex()][second.strokeIndex()] = false;
                strokeConnections[second.strokeIndex()][first.strokeIndex()] = false;
            }
        }
        paired[firstUnpaired] = false;
        return false;
    }

    private static boolean isConnectedStrokeGraph(boolean[][] connections) {
        boolean[] visited = new boolean[connections.length];
        visitStrokeGraph(0, connections, visited);
        for (boolean reached : visited) {
            if (!reached) return false;
        }
        return true;
    }

    private static void visitStrokeGraph(int stroke, boolean[][] connections, boolean[] visited) {
        if (visited[stroke]) return;
        visited[stroke] = true;
        for (int next = 0; next < connections.length; next++) {
            if (connections[stroke][next]) visitStrokeGraph(next, connections, visited);
        }
    }

    private static List<Point> resample(List<Point> pts, double interval, int maxPoints) {
        if (pts.isEmpty()) return pts;
        double pathLength = 0;
        for (int i = 1; i < pts.size(); i++) pathLength += distance(pts.get(i - 1), pts.get(i));
        if (pathLength < 1e-10) return List.of(pts.get(0));

        // Raising the interval for extremely long/jagged strokes preserves the
        // complete path while bounding the number of generated samples.
        double effectiveInterval = Math.max(interval, pathLength / (maxPoints - 1));
        List<Point> resampled = new ArrayList<>();
        resampled.add(pts.get(0));
        double dAccum = 0;
        int i = 1;
        Point current = pts.get(0);
        while (i < pts.size()) {
            Point next = pts.get(i);
            double d = distance(current, next);
            if (d < 1e-12) {
                current = next;
                i++;
                continue;
            }
            if (dAccum + d >= effectiveInterval) {
                double tx = current.x + ((effectiveInterval - dAccum) / d) * (next.x - current.x);
                double ty = current.y + ((effectiveInterval - dAccum) / d) * (next.y - current.y);
                Point q = new Point(tx, ty);
                resampled.add(q);
                if (resampled.size() >= maxPoints) break;
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
