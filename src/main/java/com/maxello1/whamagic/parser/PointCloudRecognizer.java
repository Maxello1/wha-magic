/*
 * Hybrid $P / $P+ Point-Cloud Recognizer
 *
 * Uses $P's weighted one-to-one greedy cloud assignment combined with
 * $P+-inspired absolute turning-angle distance. This is NOT a complete
 * implementation of $P+'s one-to-many matching procedure.
 *
 * Based on:
 * - $P: Vatavu, Anthony, Wobbrock. "Gestures as Point Clouds." ICMI 2012.
 * - $P+: Vatavu. "Improving Gesture Recognition Accuracy on Touch Screens
 *   for Users with Low Vision." CHI 2017.
 * Licensed under the New BSD License. See THIRD_PARTY_NOTICES.md.
 *
 * Ported to Java and extended for WHA Magic Minecraft mod.
 *
 * Original WHA Magic additions and modifications:
 * Copyright (c) 2026 Maxello1.
 * Licensed under the WHA Magic Restricted Use License.
 */
package com.maxello1.whamagic.parser;

import com.maxello1.whamagic.magic.RecognitionAlternative;
import com.maxello1.whamagic.magic.RecognitionRejectionReason;
import com.maxello1.whamagic.magic.SigilSemantic;
import com.maxello1.whamagic.magic.SignSemantic;
import com.maxello1.whamagic.magic.SymbolKind;
import com.maxello1.whamagic.magic.SymbolRecognitionResult;
import com.maxello1.whamagic.magic.SymbolRecognitionRules;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * $P Point-Cloud gesture recognizer.
 *
 * Treats strokes as an unordered cloud of points, making the core distance
 * stroke-order and stroke-direction independent. Separate structural gates
 * retain required closed contours and guard unsupported stroke complexity.
 *
 * Two-stage system:
 * <ol>
 *   <li><b>Recognition</b> — "What did the player draw?" Pure classification.</li>
 *   <li><b>Quality</b> — "How well did they draw it?" Feeds into spell power.</li>
 * </ol>
 */
public class PointCloudRecognizer implements SymbolRecognizer {
    public static final String RECOGNIZER_VERSION = "point-cloud-p-curvature-1";

    /** Singleton instance for use via the interface. */
    public static final PointCloudRecognizer INSTANCE = new PointCloudRecognizer();

    private static final Logger LOGGER = LoggerFactory.getLogger(PointCloudRecognizer.class);

    /** Number of resample points. 32 is proven accurate for the current gesture set. */
    public static final int N = 32;

    /** Every meaningful stroke needs both an endpoint and a direction sample. */
    private static final int MIN_POINTS_PER_MEANINGFUL_STROKE = 2;

    /** Controls step size in greedy cloud match. step = floor(N^(1-ε)). */
    private static final double EPSILON = 0.50;

    /** Weight of turning angle in $P+ distance calculation. */
    private static final double ANGLE_WEIGHT = 0.15;

    /** Closed templates above this value are meaningfully asymmetric. */
    private static final double ASYMMETRIC_TEMPLATE_MIN = 0.06;

    /** Reject a closed candidate when it is far more symmetric than its template. */
    private static final double MIN_ASYMMETRY_RATIO = 0.45;

    /** Endpoint/revisit tolerance used only for topological contour preservation. */
    private static final double CONTOUR_CONNECTION_RATIO = 0.08;

    /** Ignore tiny loops that do not occupy a meaningful part of the drawing. */
    private static final double MIN_CONTOUR_AREA_RATIO = 0.015;

    // ---- Data Structures ----

    /**
     * A point in a point cloud, with stroke ID and $P+ turning angle.
     * The turning angle measures local curvature:
     * 0 = straight continuation (or endpoint/stroke boundary),
     * π = complete reversal (sharpest possible turn).
     */
    public record CloudPoint(double x, double y, int strokeId, double turningAngle) {
        /** Create a point without turning angle (will be computed later). */
        public CloudPoint(double x, double y, int strokeId) {
            this(x, y, strokeId, 0.0);
        }
    }

    /** Point allocation for one meaningful source stroke. */
    public record StrokePointAllocation(int strokeId, int pointCount) {}

    /**
     * Immutable diagnostic plan for point-cloud normalization.
     *
     * <p>An unsupported plan has no stroke allocations. Callers must reject it
     * before normalization rather than dropping source strokes to fit {@link #N}.</p>
     */
    public record NormalizationAllocation(
            int targetPointCount,
            int meaningfulStrokeCount,
            boolean supported,
            List<StrokePointAllocation> strokeAllocations
    ) {
        public NormalizationAllocation {
            strokeAllocations = List.copyOf(strokeAllocations);
        }
    }

    /** A normalized point-cloud template. */
    static final class PointCloudTemplate {
        private final String semanticId;
        private final String templateId;
        private final String displayName;
        private final SymbolKind kind;
        private final String element;
        private final CloudPoint[] points; // always length N after normalization
        private final int strokeCount;
        private final int requiredClosedContourCount;
        private final boolean closedSingleStroke;
        private final double centralSymmetryError;
        private final SigilSemantic sigilSemantic;
        private final SignSemantic signSemantic;
        private final SymbolRecognitionRules recognitionRules;

        private PointCloudTemplate(String semanticId, String templateId, String displayName,
                                  SymbolKind kind, String element,
                                  CloudPoint[] points, int strokeCount, int requiredClosedContourCount,
                                  boolean closedSingleStroke,
                                  double centralSymmetryError,
                                  SigilSemantic sigilSemantic,
                                  SignSemantic signSemantic,
                                  SymbolRecognitionRules recognitionRules) {
            this.semanticId = semanticId;
            this.templateId = templateId;
            this.displayName = displayName;
            this.kind = kind;
            this.element = element;
            this.points = points.clone();
            this.strokeCount = strokeCount;
            this.requiredClosedContourCount = requiredClosedContourCount;
            this.closedSingleStroke = closedSingleStroke;
            this.centralSymmetryError = centralSymmetryError;
            this.sigilSemantic = sigilSemantic;
            this.signSemantic = signSemantic;
            this.recognitionRules = recognitionRules;
        }
    }

    // ---- SymbolRecognizer interface ----

    @Override public String name() { return "$P + curvature"; }

    @Override public int getTemplateCount() { return SpellDictionary.pointCloudTemplates().size(); }

    @Override
    public SymbolRecognitionResult recognize(List<List<Point>> strokes, SymbolKind expectedKind) {
        return recognizeStatic(strokes, expectedKind);
    }

    static PointCloudTemplate buildTemplate(DictionaryTemplate definition) {
        List<List<Point>> strokes = definition.strokes();
        NormalizationAllocation allocation = normalizationAllocation(strokes);
        if (!allocation.supported()) {
            throw new IllegalArgumentException("Template '" + definition.templateId() + "' has "
                    + allocation.meaningfulStrokeCount() + " meaningful strokes; N=" + N
                    + " supports at most " + (N / MIN_POINTS_PER_MEANINGFUL_STROKE));
        }
        CloudPoint[] cloud = strokesToCloud(strokes);
        CloudPoint[] normalized = normalize(cloud, N);
        int requiredClosedContourCount = closedContourCount(strokes);
        boolean closedSingleStroke = isClosedSingleStroke(strokes);
        double symmetryError = closedSingleStroke ? centralSymmetryError(normalized) : 0.0;
        return new PointCloudTemplate(
                definition.semanticId(), definition.templateId(), definition.displayName(),
                definition.kind(), definition.element(), normalized, strokes.size(),
                requiredClosedContourCount,
                closedSingleStroke, symmetryError,
                definition.sigilSemantic(), definition.signSemantic(), definition.recognitionRules());
    }

    // ---- Recognition ----

    /**
     * Recognize a candidate drawing against all templates of a given kind.
     * Returns the shared recognizer-neutral result consumed by SelectionEngine.
     *
     * @param strokes  the player's drawn strokes
     * @param expectedKind  SIGIL or SIGN — only templates of this kind are tested
     * @return recognition result with match info and alternatives
     */
    public static SymbolRecognitionResult recognizeStatic(List<List<Point>> strokes,
                                                                SymbolKind expectedKind) {
        if (strokes == null || strokes.isEmpty()) {
            return SymbolRecognitionResult.rejected("Unknown",
                    RecognitionRejectionReason.NO_STROKES,
                    defaultRules(expectedKind).minimumScore());
        }

        NormalizationAllocation allocation = normalizationAllocation(strokes);
        if (!allocation.supported()) {
            return SymbolRecognitionResult.rejected("Unknown",
                    RecognitionRejectionReason.UNSUPPORTED_COMPLEXITY,
                    defaultRules(expectedKind).minimumScore());
        }

        // Convert and normalize candidate
        CloudPoint[] candidateCloud = strokesToCloud(strokes);
        if (candidateCloud.length < 3) {
            return SymbolRecognitionResult.rejected("Unknown",
                    RecognitionRejectionReason.NO_STROKES,
                    defaultRules(expectedKind).minimumScore());
        }
        CloudPoint[] candidate = normalize(candidateCloud, N);
        int candidateClosedContourCount = closedContourCount(strokes);
        boolean candidateClosedSingleStroke = isClosedSingleStroke(strokes);
        double candidateSymmetryError = candidateClosedSingleStroke ? centralSymmetryError(candidate) : 0.0;
        double candidateComplexity = candidateComplexity(strokes);
        double candidateDimensionRatio = candidateDimensionRatio(strokes);

        // Match against each template of the expected kind
        List<TemplateScore> scored = new ArrayList<>();
        for (PointCloudTemplate tmpl : SpellDictionary.pointCloudTemplates()) {
            if (tmpl.kind != expectedKind) continue;
            double distance = greedyCloudMatch(candidate, tmpl.points, N);
            double score = Math.max((2.0 - distance) / 2.0, 0.0);
            if (candidateClosedSingleStroke && tmpl.closedSingleStroke
                    && tmpl.centralSymmetryError >= ASYMMETRIC_TEMPLATE_MIN
                    && candidateSymmetryError < tmpl.centralSymmetryError * MIN_ASYMMETRY_RATIO) {
                score = 0.0;
            }
            SymbolRecognitionRules rules = tmpl.recognitionRules;
            int candidateStrokeCount = strokes.size();
            int requiredClosedContours = rules.minimumClosedContours() >= 0
                    ? rules.minimumClosedContours()
                    : tmpl.requiredClosedContourCount;
            RecognitionRejectionReason structuralRejection = RecognitionRejectionReason.NONE;

            if (candidateClosedContourCount < requiredClosedContours
                    || candidateComplexity < rules.minimumComplexity()
                    || (!rules.allowLineLike()
                        && candidateDimensionRatio < rules.minimumDimensionRatio())) {
                score = 0;
                structuralRejection = RecognitionRejectionReason.INSUFFICIENT_GEOMETRY;
            } else {
                int softMinimum = rules.softMinimumStrokeCount();
                int softMaximum = rules.softMaximumStrokeCount() == 0
                        ? tmpl.strokeCount
                        : rules.softMaximumStrokeCount();
                if (softMinimum > 0 && candidateStrokeCount < softMinimum) {
                    score *= Math.sqrt((double) candidateStrokeCount / softMinimum);
                }
                if (softMaximum > 0 && candidateStrokeCount > softMaximum) {
                    score *= Math.sqrt((double) softMaximum / candidateStrokeCount);
                }
            }
            scored.add(new TemplateScore(tmpl, score, structuralRejection));
        }

        if (scored.isEmpty()) {
            return SymbolRecognitionResult.rejected("Unknown",
                    RecognitionRejectionReason.NO_TEMPLATES,
                    defaultRules(expectedKind).minimumScore());
        }

        scored.sort((a, b) -> {
            int scoreOrder = Double.compare(b.score, a.score);
            if (scoreOrder != 0) return scoreOrder;
            int semanticOrder = a.template.semanticId.compareTo(b.template.semanticId);
            if (semanticOrder != 0) return semanticOrder;
            return a.template.templateId.compareTo(b.template.templateId);
        });

        Map<String, TemplateScore> bestVariantBySemanticId = new LinkedHashMap<>();
        for (TemplateScore score : scored) {
            bestVariantBySemanticId.putIfAbsent(score.template.semanticId, score);
        }
        List<TemplateScore> semanticScores = List.copyOf(bestVariantBySemanticId.values());
        TemplateScore best = semanticScores.get(0);
        double secondScore = semanticScores.size() > 1 ? semanticScores.get(1).score : 0;
        double gap = best.score - secondScore;

        // Alternatives expose each semantic symbol only once, using its best visual variant.
        List<RecognitionAlternative> alternatives = new ArrayList<>();
        int altCount = Math.min(5, semanticScores.size());
        for (int i = 0; i < altCount; i++) {
            TemplateScore ts = semanticScores.get(i);
            alternatives.add(new RecognitionAlternative(
                    Identifier.tryParse(ts.template.semanticId),
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
        SymbolRecognitionRules bestRules = best.template.recognitionRules;
        RecognitionRejectionReason reason = acceptanceReason(
                best.score, secondScore, best.structuralRejection, bestRules);

        boolean recognized = reason == RecognitionRejectionReason.NONE;

        String displayName = recognized ? best.template.displayName
                : best.template.displayName + " (" + String.format("%.0f%%", best.score * 100) + ")";

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("$P recognize: {} -> {} (score={}, gap={})",
                    expectedKind, best.template.semanticId,
                    String.format("%.3f", best.score), String.format("%.3f", gap));
        }

        return new SymbolRecognitionResult(
                recognized, best.template.semanticId, best.template.templateId, displayName,
                best.template.kind, best.template.element, best.score,
                best.template.sigilSemantic, best.template.signSemantic,
                alternatives, gap, bestRules.minimumScore(), reason,
                best.score, 1.0 - best.score, best.score);
    }

    static RecognitionRejectionReason acceptanceReason(
            double bestScore,
            double secondScore,
            RecognitionRejectionReason structuralRejection,
            SymbolRecognitionRules rules) {
        if (structuralRejection != RecognitionRejectionReason.NONE) {
            return structuralRejection;
        }
        if (bestScore < rules.minimumScore()) {
            return RecognitionRejectionReason.SCORE_BELOW_THRESHOLD;
        }
        if (secondScore > 0 && bestScore - secondScore < rules.minimumGap()) {
            return RecognitionRejectionReason.AMBIGUOUS_TOP_MATCHES;
        }
        return RecognitionRejectionReason.NONE;
    }

    private static SymbolRecognitionRules defaultRules(SymbolKind kind) {
        return kind == SymbolKind.SIGIL
                ? SymbolRecognitionRules.SIGIL_DEFAULTS
                : SymbolRecognitionRules.SIGN_DEFAULTS;
    }

    // ---- Quality Scoring ----

    /**
     * Compute a quality score (0-100) for how well the drawing matches the template.
     * Higher = more precise drawing. Used for spell power calculation.
     *
     * @param strokes the player's drawn strokes
     * @param templateId exact visual template variant ID returned by recognition
     * @return quality score 0-100, or 0 if template not found
     */
    public static double computeQuality(List<List<Point>> strokes, String templateId) {
        // Find the template
        PointCloudTemplate tmpl = null;
        for (PointCloudTemplate t : SpellDictionary.pointCloudTemplates()) {
            if (t.templateId.equals(templateId)) {
                tmpl = t;
                break;
            }
        }
        if (tmpl == null) return 0;
        if (!normalizationAllocation(strokes).supported()) return 0;

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
        boolean[] matched = new boolean[n];
        for (int i = 0; i < n; i += step) {
            double d1 = cloudDistance(pts, tmpl, n, i, matched);
            double d2 = cloudDistance(tmpl, pts, n, i, matched);
            minDist = Math.min(minDist, Math.min(d1, d2));
        }
        return minDist;
    }

    /**
     * Cloud distance: greedy nearest-neighbor assignment with decreasing weights.
     */
    private static double cloudDistance(CloudPoint[] pts, CloudPoint[] tmpl, int n, int start,
                                        boolean[] matched) {
        Arrays.fill(matched, false);
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

    /**
     * Report the exact point allocation normalization would use for these strokes.
     * Unsupported inputs intentionally have no per-stroke allocation.
     */
    public static NormalizationAllocation normalizationAllocation(List<List<Point>> strokes) {
        List<List<CloudPoint>> strokeGroups = new ArrayList<>();
        if (strokes != null) {
            for (int strokeId = 0; strokeId < strokes.size(); strokeId++) {
                List<CloudPoint> group = new ArrayList<>();
                List<Point> stroke = strokes.get(strokeId);
                if (stroke != null) {
                    for (Point point : stroke) {
                        group.add(new CloudPoint(point.x, point.y, strokeId));
                    }
                }
                strokeGroups.add(group);
            }
        }
        return createNormalizationAllocation(strokeGroups, N);
    }

    /** Package-private diagnostic hook used by focused normalization tests. */
    static CloudPoint[] normalizeForDiagnostics(List<List<Point>> strokes) {
        return normalize(strokesToCloud(strokes), N);
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
        computeTurningAngles(resampled);
        return resampled;
    }

    /**
     * Compute $P+ absolute turning angles for each point.
     * The turning angle at point i is the absolute angle formed by
     * points (i-1, i, i+1), computed as acos(dot product):
     *   0 = straight continuation (collinear points)
     *   π = complete reversal (180° turn)
     * Endpoints and stroke boundaries receive 0.0 (straight continuation),
     * as specified by the official $P+ pseudocode.
     */
    private static void computeTurningAngles(CloudPoint[] points) {
        for (int i = 0; i < points.length; i++) {
            double angle = 0.0; // endpoint/stroke-boundary default: straight continuation
            if (i > 0 && i < points.length - 1
                    && points[i-1].strokeId() == points[i].strokeId()
                    && points[i].strokeId() == points[i+1].strokeId()) {
                double ax = points[i].x() - points[i-1].x();
                double ay = points[i].y() - points[i-1].y();
                double bx = points[i+1].x() - points[i].x();
                double by = points[i+1].y() - points[i].y();
                double lenA = Math.sqrt(ax*ax + ay*ay);
                double lenB = Math.sqrt(bx*bx + by*by);
                if (lenA > 1e-10 && lenB > 1e-10) {
                    double dot = (ax*bx + ay*by) / (lenA * lenB);
                    dot = Math.max(-1.0, Math.min(1.0, dot)); // clamp for acos
                    angle = Math.acos(dot); // 0 = straight, π = reversal
                }
            }
            // Absolute angle (direction-invariant)
            points[i] = new CloudPoint(points[i].x(), points[i].y(), points[i].strokeId(), angle);
        }
    }

    /**
     * Resample strokes to exactly N total points, distributing proportionally by path length
     * with a minimum of 2 points per non-trivial stroke.
     *
     * This ensures every meaningful stroke receives point representation,
     * even if it is much shorter than other strokes.
     */
    private static List<List<CloudPoint>> meaningfulStrokeGroups(List<List<CloudPoint>> strokeGroups) {
        List<List<CloudPoint>> meaningful = new ArrayList<>();
        for (List<CloudPoint> group : strokeGroups) {
            if (group.size() >= 2) meaningful.add(group);
        }
        return meaningful;
    }

    private static NormalizationAllocation createNormalizationAllocation(
            List<List<CloudPoint>> strokeGroups, int n) {
        List<List<CloudPoint>> meaningful = meaningfulStrokeGroups(strokeGroups);
        if (meaningful.size() * MIN_POINTS_PER_MEANINGFUL_STROKE > n) {
            return new NormalizationAllocation(n, meaningful.size(), false, List.of());
        }
        if (meaningful.isEmpty()) {
            return new NormalizationAllocation(n, 0, true, List.of());
        }

        double[] lengths = new double[meaningful.size()];
        double totalLength = 0;
        for (int s = 0; s < meaningful.size(); s++) {
            lengths[s] = strokePathLength(meaningful.get(s));
            totalLength += lengths[s];
        }

        int[] counts = new int[meaningful.size()];
        int reserved = meaningful.size() * MIN_POINTS_PER_MEANINGFUL_STROKE;
        int remaining = n - reserved;

        for (int s = 0; s < meaningful.size(); s++) {
            int proportional = totalLength > 1e-10
                    ? (int) Math.round((double) remaining * lengths[s] / totalLength)
                    : remaining / meaningful.size();
            counts[s] = MIN_POINTS_PER_MEANINGFUL_STROKE + proportional;
        }

        int total = 0;
        for (int count : counts) total += count;
        int diff = n - total;
        while (diff != 0) {
            int bestIdx = 0;
            double bestLen = 0;
            for (int s = 0; s < meaningful.size(); s++) {
                if (diff > 0 || counts[s] > MIN_POINTS_PER_MEANINGFUL_STROKE) {
                    if (lengths[s] > bestLen) {
                        bestLen = lengths[s];
                        bestIdx = s;
                    }
                }
            }
            counts[bestIdx] += (diff > 0) ? 1 : -1;
            diff += (diff > 0) ? -1 : 1;
        }

        List<StrokePointAllocation> strokeAllocations = new ArrayList<>(meaningful.size());
        for (int s = 0; s < meaningful.size(); s++) {
            strokeAllocations.add(new StrokePointAllocation(
                    meaningful.get(s).get(0).strokeId(), counts[s]));
        }
        return new NormalizationAllocation(n, meaningful.size(), true, strokeAllocations);
    }

    private static CloudPoint[] resamplePerStroke(List<List<CloudPoint>> strokeGroups, int n) {
        List<List<CloudPoint>> meaningful = meaningfulStrokeGroups(strokeGroups);
        NormalizationAllocation allocation = createNormalizationAllocation(strokeGroups, n);
        if (!allocation.supported()) {
            throw new IllegalArgumentException("Point-cloud normalization supports at most "
                    + (n / MIN_POINTS_PER_MEANINGFUL_STROKE) + " meaningful strokes for N=" + n
                    + ", got " + allocation.meaningfulStrokeCount());
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

        // Resample each stroke independently
        List<CloudPoint> result = new ArrayList<>();
        for (int s = 0; s < meaningful.size(); s++) {
            List<CloudPoint> stroke = meaningful.get(s);
            int strokeId = stroke.get(0).strokeId;
            int count = allocation.strokeAllocations().get(s).pointCount();
            List<CloudPoint> resampled = resampleSingleStroke(stroke, count, strokeId);
            result.addAll(resampled);
        }

        if (result.size() != n) {
            throw new IllegalStateException("Normalization allocation produced " + result.size()
                    + " points, expected " + n);
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
        List<CloudPoint> newPoints = new ArrayList<>(count);
        newPoints.add(new CloudPoint(stroke.get(0).x, stroke.get(0).y, strokeId));

        double traversed = 0;
        int segmentEnd = 1;
        for (int sample = 1; sample < count - 1; sample++) {
            double targetDistance = interval * sample;

            while (segmentEnd < stroke.size()) {
                CloudPoint a = stroke.get(segmentEnd - 1);
                CloudPoint b = stroke.get(segmentEnd);
                double segmentLength = dist(a, b);
                if (segmentLength < 1e-10) {
                    segmentEnd++;
                    continue;
                }
                if (traversed + segmentLength >= targetDistance) {
                    break;
                }
                traversed += segmentLength;
                segmentEnd++;
            }

            if (segmentEnd >= stroke.size()) {
                CloudPoint last = stroke.get(stroke.size() - 1);
                newPoints.add(new CloudPoint(last.x, last.y, strokeId));
                continue;
            }

            CloudPoint a = stroke.get(segmentEnd - 1);
            CloudPoint b = stroke.get(segmentEnd);
            double segmentLength = dist(a, b);
            double t = Math.max(0, Math.min(1, (targetDistance - traversed) / segmentLength));
            newPoints.add(new CloudPoint(
                    a.x + t * (b.x - a.x),
                    a.y + t * (b.y - a.y),
                    strokeId));
        }

        CloudPoint last = stroke.get(stroke.size() - 1);
        newPoints.add(new CloudPoint(last.x, last.y, strokeId));
        return newPoints;
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

    /**
     * Count meaningful closed contours while preserving player stroke freedom.
     * A contour may close within one continued stroke or through a cycle of
     * endpoint-connected strokes.
     */
    static int closedContourCount(List<List<Point>> strokes) {
        if (strokes == null || strokes.isEmpty()) return 0;
        double drawingDiagonal = drawingDiagonal(strokes);
        if (drawingDiagonal < 1e-10) return 0;
        double connectionTolerance = drawingDiagonal * CONTOUR_CONNECTION_RATIO;

        int withinStrokeContours = 0;
        int globallyClosedStrokes = 0;
        for (List<Point> stroke : strokes) {
            if (stroke == null || stroke.size() < 2) continue;
            withinStrokeContours += countClosedSubpaths(
                    stroke, connectionTolerance, drawingDiagonal);
            if (distance(stroke.get(0), stroke.get(stroke.size() - 1)) <= connectionTolerance
                    && isMeaningfulContour(stroke, 0, stroke.size() - 1, drawingDiagonal)) {
                globallyClosedStrokes++;
            }
        }

        int endpointGraphContours = countEndpointGraphContours(
                strokes, connectionTolerance, drawingDiagonal);
        return Math.max(0, endpointGraphContours
                + withinStrokeContours - globallyClosedStrokes);
    }

    private static int countClosedSubpaths(List<Point> stroke, double tolerance,
                                           double drawingDiagonal) {
        int contours = 0;
        int lastClosureEnd = -4;
        for (int end = 3; end < stroke.size(); end++) {
            if (end - lastClosureEnd <= 3) continue;
            for (int start = end - 3; start >= 0; start--) {
                if (distance(stroke.get(start), stroke.get(end)) <= tolerance
                        && isMeaningfulContour(stroke, start, end, drawingDiagonal)) {
                    contours++;
                    lastClosureEnd = end;
                    break;
                }
            }
        }
        return contours;
    }

    private static boolean isMeaningfulContour(List<Point> stroke, int start, int end,
                                               double drawingDiagonal) {
        if (end - start < 3) return false;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double twiceSignedArea = 0;
        for (int i = start; i <= end; i++) {
            Point point = stroke.get(i);
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
            if (i < end) {
                Point next = stroke.get(i + 1);
                twiceSignedArea += point.x * next.y - next.x * point.y;
            }
        }
        Point first = stroke.get(start);
        Point last = stroke.get(end);
        twiceSignedArea += last.x * first.y - first.x * last.y;

        double width = maxX - minX;
        double height = maxY - minY;
        double area = Math.abs(twiceSignedArea) * 0.5;
        double minimumArea = drawingDiagonal * drawingDiagonal * MIN_CONTOUR_AREA_RATIO;
        return width >= drawingDiagonal * CONTOUR_CONNECTION_RATIO
                && height >= drawingDiagonal * CONTOUR_CONNECTION_RATIO
                && area >= minimumArea;
    }

    private record ContourEdge(int startNode, int endNode, List<Point> stroke) {}

    private static int countEndpointGraphContours(List<List<Point>> strokes, double tolerance,
                                                  double drawingDiagonal) {
        List<Point> nodes = new ArrayList<>();
        List<ContourEdge> edges = new ArrayList<>();
        for (List<Point> stroke : strokes) {
            if (stroke == null || stroke.size() < 2) continue;
            Point start = stroke.get(0);
            Point end = stroke.get(stroke.size() - 1);
            int startNode = findOrCreateEndpointNode(nodes, start, tolerance);
            int endNode = findOrCreateEndpointNode(nodes, end, tolerance);
            if (startNode == endNode
                    && !isMeaningfulContour(stroke, 0, stroke.size() - 1, drawingDiagonal)) {
                continue;
            }
            edges.add(new ContourEdge(startNode, endNode, stroke));
        }
        if (edges.isEmpty()) return 0;

        DisjointSet components = new DisjointSet(nodes.size());
        for (ContourEdge edge : edges) {
            components.union(edge.startNode(), edge.endNode());
        }

        int[] edgeCounts = new int[nodes.size()];
        int[] nodeCounts = new int[nodes.size()];
        boolean[] usedNodes = new boolean[nodes.size()];
        double[] minX = new double[nodes.size()];
        double[] minY = new double[nodes.size()];
        double[] maxX = new double[nodes.size()];
        double[] maxY = new double[nodes.size()];
        Arrays.fill(minX, Double.MAX_VALUE);
        Arrays.fill(minY, Double.MAX_VALUE);
        Arrays.fill(maxX, -Double.MAX_VALUE);
        Arrays.fill(maxY, -Double.MAX_VALUE);

        for (ContourEdge edge : edges) {
            int root = components.find(edge.startNode());
            edgeCounts[root]++;
            usedNodes[edge.startNode()] = true;
            usedNodes[edge.endNode()] = true;
            for (Point point : edge.stroke()) {
                minX[root] = Math.min(minX[root], point.x);
                minY[root] = Math.min(minY[root], point.y);
                maxX[root] = Math.max(maxX[root], point.x);
                maxY[root] = Math.max(maxY[root], point.y);
            }
        }
        for (int node = 0; node < nodes.size(); node++) {
            if (usedNodes[node]) nodeCounts[components.find(node)]++;
        }

        int contours = 0;
        double minimumArea = drawingDiagonal * drawingDiagonal * MIN_CONTOUR_AREA_RATIO;
        for (int root = 0; root < nodes.size(); root++) {
            if (edgeCounts[root] == 0) continue;
            int cycleRank = edgeCounts[root] - nodeCounts[root] + 1;
            double boundingArea = (maxX[root] - minX[root]) * (maxY[root] - minY[root]);
            if (cycleRank > 0 && boundingArea >= minimumArea) {
                contours += cycleRank;
            }
        }
        return contours;
    }

    private static int findOrCreateEndpointNode(List<Point> nodes, Point point, double tolerance) {
        for (int i = 0; i < nodes.size(); i++) {
            if (distance(nodes.get(i), point) <= tolerance) return i;
        }
        nodes.add(point);
        return nodes.size() - 1;
    }

    private static double drawingDiagonal(List<List<Point>> strokes) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        boolean foundPoint = false;
        for (List<Point> stroke : strokes) {
            if (stroke == null) continue;
            for (Point point : stroke) {
                foundPoint = true;
                minX = Math.min(minX, point.x);
                minY = Math.min(minY, point.y);
                maxX = Math.max(maxX, point.x);
                maxY = Math.max(maxY, point.y);
            }
        }
        return foundPoint ? Math.hypot(maxX - minX, maxY - minY) : 0.0;
    }

    /** Path length normalized by the drawing diagonal, independent of scale. */
    private static double candidateComplexity(List<List<Point>> strokes) {
        double diagonal = drawingDiagonal(strokes);
        if (diagonal < 1e-10) return 0.0;
        double pathLength = 0.0;
        for (List<Point> stroke : strokes) {
            for (int i = 1; i < stroke.size(); i++) {
                pathLength += distance(stroke.get(i - 1), stroke.get(i));
            }
        }
        return pathLength / diagonal;
    }

    /** Ratio of the shorter drawing dimension to the longer one. */
    private static double candidateDimensionRatio(List<List<Point>> strokes) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (List<Point> stroke : strokes) {
            for (Point point : stroke) {
                minX = Math.min(minX, point.x);
                minY = Math.min(minY, point.y);
                maxX = Math.max(maxX, point.x);
                maxY = Math.max(maxY, point.y);
            }
        }
        if (!Double.isFinite(minX)) return 0.0;
        double width = maxX - minX;
        double height = maxY - minY;
        double longer = Math.max(width, height);
        return longer < 1e-10 ? 0.0 : Math.min(width, height) / longer;
    }

    private static double distance(Point a, Point b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    private static final class DisjointSet {
        private final int[] parent;

        private DisjointSet(int size) {
            parent = new int[size];
            for (int i = 0; i < size; i++) parent[i] = i;
        }

        private int find(int value) {
            if (parent[value] != value) parent[value] = find(parent[value]);
            return parent[value];
        }

        private void union(int a, int b) {
            int rootA = find(a);
            int rootB = find(b);
            if (rootA != rootB) parent[rootB] = rootA;
        }
    }

    private static boolean isClosedSingleStroke(List<List<Point>> strokes) {
        if (strokes.size() != 1 || strokes.get(0).size() < 4) return false;
        List<Point> stroke = strokes.get(0);
        Point first = stroke.get(0);
        Point last = stroke.get(stroke.size() - 1);

        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Point point : stroke) {
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
        }
        double diagonal = Math.hypot(maxX - minX, maxY - minY);
        double closureGap = Math.hypot(first.x - last.x, first.y - last.y);
        return diagonal > 1e-10 && closureGap <= diagonal * 0.08;
    }

    /**
     * Mean distance from each point's reflection through the centroid to the
     * nearest cloud point, normalized by the cloud diagonal.
     */
    private static double centralSymmetryError(CloudPoint[] points) {
        if (points.length == 0) return 0.0;
        double cx = 0, cy = 0;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (CloudPoint point : points) {
            cx += point.x();
            cy += point.y();
            minX = Math.min(minX, point.x());
            minY = Math.min(minY, point.y());
            maxX = Math.max(maxX, point.x());
            maxY = Math.max(maxY, point.y());
        }
        cx /= points.length;
        cy /= points.length;
        double diagonal = Math.hypot(maxX - minX, maxY - minY);
        if (diagonal < 1e-10) return 0.0;

        double error = 0.0;
        for (CloudPoint point : points) {
            double reflectedX = 2.0 * cx - point.x();
            double reflectedY = 2.0 * cy - point.y();
            double nearest = Double.MAX_VALUE;
            for (CloudPoint other : points) {
                nearest = Math.min(nearest,
                        Math.hypot(other.x() - reflectedX, other.y() - reflectedY));
            }
            error += nearest;
        }
        return error / points.length / diagonal;
    }

    /**
     * $P+ distance: combines spatial distance with angular difference.
     * Spatial distance measures position similarity.
     * Angular difference measures curvature similarity.
     */
    private static double dist(CloudPoint a, CloudPoint b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double spatialDist = Math.sqrt(dx * dx + dy * dy);
        // Normalized angle difference: 0 when same curvature, 1 when maximally different
        double angleDiff = Math.abs(a.turningAngle() - b.turningAngle()) / Math.PI;
        return spatialDist + ANGLE_WEIGHT * angleDiff;
    }

    private record TemplateScore(
            PointCloudTemplate template,
            double score,
            RecognitionRejectionReason structuralRejection) {}
}
