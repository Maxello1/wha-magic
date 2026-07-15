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
public final class PointCloudRecognizer {
    public static final String RECOGNIZER_VERSION = "point-cloud-p-curvature-1";

    private static final Logger LOGGER = LoggerFactory.getLogger(PointCloudRecognizer.class);

    private PointCloudRecognizer() {}

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

    /** Candidate geometry prepared once and reused for every role and rotation probe. */
    static final class PreparedCandidate {
        private final RecognitionRejectionReason preparationFailure;
        private final int strokeCount;
        private final double[] normalizedX;
        private final double[] normalizedY;
        private final int[] sampleStrokeIds;
        private final double[] sampleTurningAngles;
        private final int closedContourCount;
        private final double complexity;
        private final double dimensionRatio;
        private final boolean closedSingleStroke;
        private final double centralSymmetryError;
        private final double[] rawX;
        private final double[] rawY;
        private final int[] strokeOffsets;
        private final double originX;
        private final double originY;
        private final double rawPathLength;

        private PreparedCandidate(
                RecognitionRejectionReason preparationFailure,
                int strokeCount,
                double[] normalizedX,
                double[] normalizedY,
                int[] sampleStrokeIds,
                double[] sampleTurningAngles,
                int closedContourCount,
                double complexity,
                double dimensionRatio,
                boolean closedSingleStroke,
                double centralSymmetryError,
                double[] rawX,
                double[] rawY,
                int[] strokeOffsets,
                double originX,
                double originY,
                double rawPathLength) {
            this.preparationFailure = preparationFailure;
            this.strokeCount = strokeCount;
            this.normalizedX = normalizedX;
            this.normalizedY = normalizedY;
            this.sampleStrokeIds = sampleStrokeIds;
            this.sampleTurningAngles = sampleTurningAngles;
            this.closedContourCount = closedContourCount;
            this.complexity = complexity;
            this.dimensionRatio = dimensionRatio;
            this.closedSingleStroke = closedSingleStroke;
            this.centralSymmetryError = centralSymmetryError;
            this.rawX = rawX;
            this.rawY = rawY;
            this.strokeOffsets = strokeOffsets;
            this.originX = originX;
            this.originY = originY;
            this.rawPathLength = rawPathLength;
        }

        RotationWorkspace newWorkspace() {
            return new RotationWorkspace(rawX.length, strokeCount);
        }
    }

    /** Reusable primitive storage for all rotations of one prepared candidate. */
    static final class RotationWorkspace {
        private final double[] normalizedX = new double[N];
        private final double[] normalizedY = new double[N];
        private final double[] turningAngles = new double[N];
        private final double[] rotatedRawX;
        private final double[] rotatedRawY;
        private final boolean[] matched = new boolean[N];
        private final double[] nodeX;
        private final double[] nodeY;
        private final int[] edgeStart;
        private final int[] edgeEnd;
        private final int[] edgeStroke;
        private final int[] parent;
        private final int[] edgeCounts;
        private final int[] nodeCounts;
        private final boolean[] usedNodes;
        private final double[] componentMinX;
        private final double[] componentMinY;
        private final double[] componentMaxX;
        private final double[] componentMaxY;

        private double drawingDiagonal;
        private double dimensionRatio;
        private double complexity;
        private int closedContourCount;
        private boolean closedSingleStroke;
        private double centralSymmetryError;

        private RotationWorkspace(int rawPointCount, int strokeCount) {
            rotatedRawX = new double[rawPointCount];
            rotatedRawY = new double[rawPointCount];
            int endpointCapacity = Math.max(1, strokeCount * 2);
            nodeX = new double[endpointCapacity];
            nodeY = new double[endpointCapacity];
            edgeStart = new int[Math.max(1, strokeCount)];
            edgeEnd = new int[Math.max(1, strokeCount)];
            edgeStroke = new int[Math.max(1, strokeCount)];
            parent = new int[endpointCapacity];
            edgeCounts = new int[endpointCapacity];
            nodeCounts = new int[endpointCapacity];
            usedNodes = new boolean[endpointCapacity];
            componentMinX = new double[endpointCapacity];
            componentMinY = new double[endpointCapacity];
            componentMaxX = new double[endpointCapacity];
            componentMaxY = new double[endpointCapacity];
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

    static PreparedCandidate prepareCandidate(List<List<Point>> strokes) {
        if (strokes == null || strokes.isEmpty()) {
            return failedPreparation(RecognitionRejectionReason.NO_STROKES, 0);
        }
        NormalizationAllocation allocation = normalizationAllocation(strokes);
        if (!allocation.supported()) {
            return failedPreparation(
                    RecognitionRejectionReason.UNSUPPORTED_COMPLEXITY, strokes.size());
        }

        CloudPoint[] candidateCloud = strokesToCloud(strokes);
        if (candidateCloud.length < 3) {
            return failedPreparation(RecognitionRejectionReason.NO_STROKES, strokes.size());
        }
        CloudPoint[] normalized = normalize(candidateCloud, N);
        double[] normalizedX = new double[N];
        double[] normalizedY = new double[N];
        int[] sampleStrokeIds = new int[N];
        double[] sampleTurningAngles = new double[N];
        for (int i = 0; i < N; i++) {
            normalizedX[i] = normalized[i].x();
            normalizedY[i] = normalized[i].y();
            sampleStrokeIds[i] = normalized[i].strokeId();
            sampleTurningAngles[i] = normalized[i].turningAngle();
        }
        int closedContourCount = closedContourCount(strokes);
        double complexity = candidateComplexity(strokes);
        double dimensionRatio = candidateDimensionRatio(strokes);
        boolean closedSingleStroke = isClosedSingleStroke(strokes);
        double symmetryError = closedSingleStroke
                ? centralSymmetryError(normalized)
                : 0.0;

        int rawPointCount = 0;
        for (List<Point> stroke : strokes) {
            if (stroke != null) rawPointCount += stroke.size();
        }
        double[] rawX = new double[rawPointCount];
        double[] rawY = new double[rawPointCount];
        int[] strokeOffsets = new int[strokes.size() + 1];
        int rawIndex = 0;
        double originX = 0.0;
        double originY = 0.0;
        double rawPathLength = 0.0;
        for (int strokeIndex = 0; strokeIndex < strokes.size(); strokeIndex++) {
            strokeOffsets[strokeIndex] = rawIndex;
            List<Point> stroke = strokes.get(strokeIndex);
            if (stroke == null) continue;
            for (int pointIndex = 0; pointIndex < stroke.size(); pointIndex++) {
                Point point = stroke.get(pointIndex);
                rawX[rawIndex] = point.x;
                rawY[rawIndex] = point.y;
                originX += point.x;
                originY += point.y;
                if (pointIndex > 0) {
                    Point previous = stroke.get(pointIndex - 1);
                    rawPathLength += Math.hypot(
                            point.x - previous.x,
                            point.y - previous.y);
                }
                rawIndex++;
            }
        }
        strokeOffsets[strokes.size()] = rawIndex;
        return new PreparedCandidate(
                null,
                strokes.size(),
                normalizedX,
                normalizedY,
                sampleStrokeIds,
                sampleTurningAngles,
                closedContourCount,
                complexity,
                dimensionRatio,
                closedSingleStroke,
                symmetryError,
                rawX,
                rawY,
                strokeOffsets,
                originX / rawPointCount,
                originY / rawPointCount,
                rawPathLength);
    }

    private static PreparedCandidate failedPreparation(
            RecognitionRejectionReason reason, int strokeCount) {
        return new PreparedCandidate(
                reason,
                strokeCount,
                new double[0],
                new double[0],
                new int[0],
                new double[0],
                0,
                0.0,
                0.0,
                false,
                0.0,
                new double[0],
                new double[0],
                new int[Math.max(1, strokeCount + 1)],
                0.0,
                0.0,
                0.0);
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
        return recognizeStatic(strokes, expectedKind, ParseDetail.FULL_DIAGNOSTICS);
    }

    static SymbolRecognitionResult recognizeStatic(
            List<List<Point>> strokes,
            SymbolKind expectedKind,
            ParseDetail detail) {
        PreparedCandidate prepared = prepareCandidate(strokes);
        return recognizePrepared(
                prepared, 0.0, expectedKind, detail, prepared.newWorkspace());
    }

    static SymbolRecognitionResult recognizePrepared(
            PreparedCandidate candidate,
            double rotationDeg,
            SymbolKind expectedKind,
            ParseDetail detail,
            RotationWorkspace workspace) {
        if (candidate.preparationFailure != null) {
            return SymbolRecognitionResult.rejected(
                    "Unknown",
                    candidate.preparationFailure,
                    defaultRules(expectedKind).minimumScore());
        }
        prepareRotation(candidate, rotationDeg, workspace);

        List<SemanticTemplateGroup> groups = SpellDictionary.pointCloudIndex().groups(expectedKind);
        if (groups.isEmpty()) {
            return SymbolRecognitionResult.rejected(
                    "Unknown",
                    RecognitionRejectionReason.NO_TEMPLATES,
                    defaultRules(expectedKind).minimumScore());
        }

        SemanticMatch[] topMatches = new SemanticMatch[5];
        int topMatchCount = 0;
        for (SemanticTemplateGroup group : groups) {
            TemplateScore bestVariant = null;
            for (PointCloudTemplate variant : group.variants) {
                TemplateScore score = scorePreparedVariant(candidate, workspace, group, variant);
                if (bestVariant == null
                        || score.score > bestVariant.score
                        || (score.score == bestVariant.score
                                && score.template.templateId.compareTo(
                                        bestVariant.template.templateId) < 0)) {
                    bestVariant = score;
                }
            }
            topMatchCount = insertTopMatch(
                    topMatches,
                    topMatchCount,
                    new SemanticMatch(
                            group,
                            bestVariant.template,
                            bestVariant.score,
                            bestVariant.structuralRejection));
        }

        SemanticMatch best = topMatches[0];
        double secondScore = topMatchCount > 1 ? topMatches[1].score : 0.0;
        double gap = best.score - secondScore;

        List<RecognitionAlternative> alternatives = new ArrayList<>();
        int alternativeCount = detail.retainsAlternatives()
                ? topMatchCount
                : 0;
        for (int i = 0; i < alternativeCount; i++) {
            SemanticMatch score = topMatches[i];
            alternatives.add(new RecognitionAlternative(
                    score.group.id,
                    score.group.displayName,
                    score.group.kind,
                    score.score,
                    0,
                    score.score,
                    score.score,
                    1.0 - score.score,
                    score.score,
                    0));
        }

        SymbolRecognitionRules bestRules = best.group.recognitionRules;
        RecognitionRejectionReason reason = acceptanceReason(
                best.score, secondScore, best.structuralRejection, bestRules);
        boolean recognized = reason == RecognitionRejectionReason.NONE;
        String displayName = recognized
                ? best.group.displayName
                : best.group.displayName + " ("
                        + String.format("%.0f%%", best.score * 100) + ")";
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("$P recognize prepared: {} -> {} (score={}, gap={}, rotation={})",
                    expectedKind,
                    best.group.semanticId,
                    String.format("%.3f", best.score),
                    String.format("%.3f", gap),
                    rotationDeg);
        }
        return new SymbolRecognitionResult(
                recognized,
                best.group.semanticId,
                best.template.templateId,
                displayName,
                best.group.kind,
                best.group.element,
                best.score,
                best.group.sigilSemantic,
                best.group.signSemantic,
                alternatives,
                gap,
                bestRules.minimumScore(),
                reason,
                best.score,
                1.0 - best.score,
                best.score);
    }

    private static TemplateScore scorePreparedVariant(
            PreparedCandidate candidate,
            RotationWorkspace workspace,
            SemanticTemplateGroup group,
            PointCloudTemplate variant) {
        double distance = greedyCloudMatch(workspace, variant.points, N);
        double score = Math.max((2.0 - distance) / 2.0, 0.0);
        if (workspace.closedSingleStroke && variant.closedSingleStroke
                && variant.centralSymmetryError >= ASYMMETRIC_TEMPLATE_MIN
                && workspace.centralSymmetryError
                        < variant.centralSymmetryError * MIN_ASYMMETRY_RATIO) {
            score = 0.0;
        }

        SymbolRecognitionRules rules = group.recognitionRules;
        int requiredClosedContours = rules.minimumClosedContours() >= 0
                ? rules.minimumClosedContours()
                : variant.requiredClosedContourCount;
        RecognitionRejectionReason structuralRejection = RecognitionRejectionReason.NONE;
        if (workspace.closedContourCount < requiredClosedContours
                || workspace.complexity < rules.minimumComplexity()
                || (!rules.allowLineLike()
                        && workspace.dimensionRatio < rules.minimumDimensionRatio())) {
            score = 0.0;
            structuralRejection = RecognitionRejectionReason.INSUFFICIENT_GEOMETRY;
        } else {
            int softMinimum = rules.softMinimumStrokeCount();
            int softMaximum = rules.softMaximumStrokeCount() == 0
                    ? variant.strokeCount
                    : rules.softMaximumStrokeCount();
            if (softMinimum > 0 && candidate.strokeCount < softMinimum) {
                score *= Math.sqrt((double) candidate.strokeCount / softMinimum);
            }
            if (softMaximum > 0 && candidate.strokeCount > softMaximum) {
                score *= Math.sqrt((double) softMaximum / candidate.strokeCount);
            }
        }
        return new TemplateScore(variant, score, structuralRejection);
    }

    private static int insertTopMatch(
            SemanticMatch[] topMatches, int currentSize, SemanticMatch candidate) {
        int insertionIndex = 0;
        while (insertionIndex < currentSize
                && compareSemanticMatches(topMatches[insertionIndex], candidate) <= 0) {
            insertionIndex++;
        }
        if (insertionIndex >= topMatches.length) return currentSize;

        int newSize = Math.min(topMatches.length, currentSize + 1);
        for (int index = newSize - 1; index > insertionIndex; index--) {
            topMatches[index] = topMatches[index - 1];
        }
        topMatches[insertionIndex] = candidate;
        return newSize;
    }

    private static int compareSemanticMatches(SemanticMatch left, SemanticMatch right) {
        int scoreOrder = Double.compare(right.score, left.score);
        if (scoreOrder != 0) return scoreOrder;
        int semanticOrder = left.group.semanticId.compareTo(right.group.semanticId);
        if (semanticOrder != 0) return semanticOrder;
        return left.template.templateId.compareTo(right.template.templateId);
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
        PointCloudTemplate tmpl = SpellDictionary.pointCloudIndex().variant(templateId);
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

    private static void prepareRotation(
            PreparedCandidate candidate,
            double rotationDeg,
            RotationWorkspace workspace) {
        double radians = Math.toRadians(rotationDeg);
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);

        if (rotationDeg == 0.0) {
            System.arraycopy(candidate.normalizedX, 0, workspace.normalizedX, 0, N);
            System.arraycopy(candidate.normalizedY, 0, workspace.normalizedY, 0, N);
            System.arraycopy(
                    candidate.sampleTurningAngles, 0, workspace.turningAngles, 0, N);
            workspace.closedContourCount = candidate.closedContourCount;
            workspace.complexity = candidate.complexity;
            workspace.dimensionRatio = candidate.dimensionRatio;
            workspace.closedSingleStroke = candidate.closedSingleStroke;
            workspace.centralSymmetryError = candidate.centralSymmetryError;
            return;
        } else {
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            for (int i = 0; i < N; i++) {
                double x = candidate.normalizedX[i];
                double y = candidate.normalizedY[i];
                double rotatedX = x * cosine - y * sine;
                double rotatedY = x * sine + y * cosine;
                workspace.normalizedX[i] = rotatedX;
                workspace.normalizedY[i] = rotatedY;
                minX = Math.min(minX, rotatedX);
                minY = Math.min(minY, rotatedY);
                maxX = Math.max(maxX, rotatedX);
                maxY = Math.max(maxY, rotatedY);
            }
            double size = Math.max(maxX - minX, maxY - minY);
            if (size < 1e-10) size = 1.0;
            double centroidX = 0.0;
            double centroidY = 0.0;
            for (int i = 0; i < N; i++) {
                workspace.normalizedX[i] = (workspace.normalizedX[i] - minX) / size;
                workspace.normalizedY[i] = (workspace.normalizedY[i] - minY) / size;
                centroidX += workspace.normalizedX[i];
                centroidY += workspace.normalizedY[i];
            }
            centroidX /= N;
            centroidY /= N;
            for (int i = 0; i < N; i++) {
                workspace.normalizedX[i] -= centroidX;
                workspace.normalizedY[i] -= centroidY;
            }
            computeTurningAngles(candidate.sampleStrokeIds, workspace);
        }

        double rawMinX = Double.MAX_VALUE;
        double rawMinY = Double.MAX_VALUE;
        double rawMaxX = -Double.MAX_VALUE;
        double rawMaxY = -Double.MAX_VALUE;
        for (int i = 0; i < candidate.rawX.length; i++) {
            double relativeX = candidate.rawX[i] - candidate.originX;
            double relativeY = candidate.rawY[i] - candidate.originY;
            double rotatedX = relativeX * cosine - relativeY * sine + candidate.originX;
            double rotatedY = relativeX * sine + relativeY * cosine + candidate.originY;
            workspace.rotatedRawX[i] = rotatedX;
            workspace.rotatedRawY[i] = rotatedY;
            rawMinX = Math.min(rawMinX, rotatedX);
            rawMinY = Math.min(rawMinY, rotatedY);
            rawMaxX = Math.max(rawMaxX, rotatedX);
            rawMaxY = Math.max(rawMaxY, rotatedY);
        }
        double width = rawMaxX - rawMinX;
        double height = rawMaxY - rawMinY;
        workspace.drawingDiagonal = Math.hypot(width, height);
        double longerDimension = Math.max(width, height);
        workspace.dimensionRatio = longerDimension < 1e-10
                ? 0.0
                : Math.min(width, height) / longerDimension;
        workspace.complexity = workspace.drawingDiagonal < 1e-10
                ? 0.0
                : candidate.rawPathLength / workspace.drawingDiagonal;
        workspace.closedContourCount = countClosedContours(candidate, workspace);
        workspace.closedSingleStroke = isClosedSingleStroke(candidate, workspace);
        workspace.centralSymmetryError = workspace.closedSingleStroke
                ? centralSymmetryError(workspace)
                : 0.0;
    }

    private static void computeTurningAngles(
            int[] strokeIds, RotationWorkspace workspace) {
        for (int i = 0; i < N; i++) {
            double angle = 0.0;
            if (i > 0 && i < N - 1
                    && strokeIds[i - 1] == strokeIds[i]
                    && strokeIds[i] == strokeIds[i + 1]) {
                double ax = workspace.normalizedX[i] - workspace.normalizedX[i - 1];
                double ay = workspace.normalizedY[i] - workspace.normalizedY[i - 1];
                double bx = workspace.normalizedX[i + 1] - workspace.normalizedX[i];
                double by = workspace.normalizedY[i + 1] - workspace.normalizedY[i];
                double lengthA = Math.sqrt(ax * ax + ay * ay);
                double lengthB = Math.sqrt(bx * bx + by * by);
                if (lengthA > 1e-10 && lengthB > 1e-10) {
                    double dot = (ax * bx + ay * by) / (lengthA * lengthB);
                    dot = Math.max(-1.0, Math.min(1.0, dot));
                    angle = Math.acos(dot);
                }
            }
            workspace.turningAngles[i] = angle;
        }
    }

    /** Shared semantic metadata plus its immutable visual variants. */
    static final class SemanticTemplateGroup {
        private final Identifier id;
        private final String semanticId;
        private final String displayName;
        private final SymbolKind kind;
        private final String element;
        private final SigilSemantic sigilSemantic;
        private final SignSemantic signSemantic;
        private final SymbolRecognitionRules recognitionRules;
        private final List<PointCloudTemplate> variants;

        private SemanticTemplateGroup(
                Identifier id,
                PointCloudTemplate representative,
                List<PointCloudTemplate> variants) {
            this.id = id;
            this.semanticId = representative.semanticId;
            this.displayName = representative.displayName;
            this.kind = representative.kind;
            this.element = representative.element;
            this.sigilSemantic = representative.sigilSemantic;
            this.signSemantic = representative.signSemantic;
            this.recognitionRules = representative.recognitionRules;
            this.variants = List.copyOf(variants);
        }
    }

    /** All point-cloud lookup structures published as one immutable dictionary state. */
    static final class PointCloudIndex {
        private final List<SemanticTemplateGroup> sigils;
        private final List<SemanticTemplateGroup> signs;
        private final Map<String, PointCloudTemplate> variantsByTemplateId;

        private PointCloudIndex(
                List<SemanticTemplateGroup> sigils,
                List<SemanticTemplateGroup> signs,
                Map<String, PointCloudTemplate> variantsByTemplateId) {
            this.sigils = List.copyOf(sigils);
            this.signs = List.copyOf(signs);
            this.variantsByTemplateId = Map.copyOf(variantsByTemplateId);
        }

        static PointCloudIndex empty() {
            return new PointCloudIndex(List.of(), List.of(), Map.of());
        }

        List<SemanticTemplateGroup> groups(SymbolKind kind) {
            return kind == SymbolKind.SIGIL ? sigils : signs;
        }

        PointCloudTemplate variant(String templateId) {
            return variantsByTemplateId.get(templateId);
        }
    }

    static PointCloudIndex buildIndex(List<PointCloudTemplate> templates) {
        Map<Identifier, List<PointCloudTemplate>> grouped = new LinkedHashMap<>();
        Map<String, PointCloudTemplate> byTemplateId = new LinkedHashMap<>();
        for (PointCloudTemplate template : templates) {
            Identifier semanticId = Identifier.tryParse(template.semanticId);
            if (semanticId == null) {
                throw new IllegalArgumentException(
                        "Invalid semantic identifier in point-cloud template: "
                                + template.semanticId);
            }
            grouped.computeIfAbsent(semanticId, ignored -> new ArrayList<>()).add(template);
            byTemplateId.put(template.templateId, template);
        }

        List<SemanticTemplateGroup> sigils = new ArrayList<>();
        List<SemanticTemplateGroup> signs = new ArrayList<>();
        for (Map.Entry<Identifier, List<PointCloudTemplate>> entry : grouped.entrySet()) {
            List<PointCloudTemplate> variants = entry.getValue();
            SemanticTemplateGroup group = new SemanticTemplateGroup(
                    entry.getKey(), variants.get(0), variants);
            (group.kind == SymbolKind.SIGIL ? sigils : signs).add(group);
        }
        return new PointCloudIndex(sigils, signs, byTemplateId);
    }

    private static int countClosedContours(
            PreparedCandidate candidate, RotationWorkspace workspace) {
        if (candidate.strokeCount == 0 || workspace.drawingDiagonal < 1e-10) return 0;
        double tolerance = workspace.drawingDiagonal * CONTOUR_CONNECTION_RATIO;
        int withinStrokeContours = 0;
        int globallyClosedStrokes = 0;
        for (int stroke = 0; stroke < candidate.strokeCount; stroke++) {
            int start = candidate.strokeOffsets[stroke];
            int endExclusive = candidate.strokeOffsets[stroke + 1];
            if (endExclusive - start < 2) continue;
            withinStrokeContours += countClosedSubpaths(
                    workspace, start, endExclusive, tolerance, workspace.drawingDiagonal);
            int end = endExclusive - 1;
            if (rawDistance(workspace, start, end) <= tolerance
                    && isMeaningfulContour(
                            workspace, start, end, workspace.drawingDiagonal)) {
                globallyClosedStrokes++;
            }
        }
        int endpointGraphContours = countEndpointGraphContours(
                candidate, workspace, tolerance, workspace.drawingDiagonal);
        return Math.max(
                0,
                endpointGraphContours + withinStrokeContours - globallyClosedStrokes);
    }

    private static int countClosedSubpaths(
            RotationWorkspace workspace,
            int strokeStart,
            int strokeEndExclusive,
            double tolerance,
            double drawingDiagonal) {
        int contours = 0;
        int lastClosureEnd = strokeStart - 4;
        for (int end = strokeStart + 3; end < strokeEndExclusive; end++) {
            if (end - lastClosureEnd <= 3) continue;
            for (int start = end - 3; start >= strokeStart; start--) {
                if (rawDistance(workspace, start, end) <= tolerance
                        && isMeaningfulContour(workspace, start, end, drawingDiagonal)) {
                    contours++;
                    lastClosureEnd = end;
                    break;
                }
            }
        }
        return contours;
    }

    private static boolean isMeaningfulContour(
            RotationWorkspace workspace, int start, int end, double drawingDiagonal) {
        if (end - start < 3) return false;
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double twiceSignedArea = 0.0;
        for (int i = start; i <= end; i++) {
            double x = workspace.rotatedRawX[i];
            double y = workspace.rotatedRawY[i];
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            if (i < end) {
                twiceSignedArea += x * workspace.rotatedRawY[i + 1]
                        - workspace.rotatedRawX[i + 1] * y;
            }
        }
        twiceSignedArea += workspace.rotatedRawX[end] * workspace.rotatedRawY[start]
                - workspace.rotatedRawX[start] * workspace.rotatedRawY[end];
        double area = Math.abs(twiceSignedArea) * 0.5;
        double minimumArea = drawingDiagonal * drawingDiagonal * MIN_CONTOUR_AREA_RATIO;
        return maxX - minX >= drawingDiagonal * CONTOUR_CONNECTION_RATIO
                && maxY - minY >= drawingDiagonal * CONTOUR_CONNECTION_RATIO
                && area >= minimumArea;
    }

    private static int countEndpointGraphContours(
            PreparedCandidate candidate,
            RotationWorkspace workspace,
            double tolerance,
            double drawingDiagonal) {
        int nodeCount = 0;
        int edgeCount = 0;
        for (int stroke = 0; stroke < candidate.strokeCount; stroke++) {
            int start = candidate.strokeOffsets[stroke];
            int endExclusive = candidate.strokeOffsets[stroke + 1];
            if (endExclusive - start < 2) continue;
            int end = endExclusive - 1;
            int startNode = findOrCreateEndpointNode(workspace, nodeCount, start, tolerance);
            if (startNode == nodeCount) nodeCount++;
            int endNode = findOrCreateEndpointNode(workspace, nodeCount, end, tolerance);
            if (endNode == nodeCount) nodeCount++;
            if (startNode == endNode
                    && !isMeaningfulContour(workspace, start, end, drawingDiagonal)) {
                continue;
            }
            workspace.edgeStart[edgeCount] = startNode;
            workspace.edgeEnd[edgeCount] = endNode;
            workspace.edgeStroke[edgeCount] = stroke;
            edgeCount++;
        }
        if (edgeCount == 0) return 0;

        for (int node = 0; node < nodeCount; node++) workspace.parent[node] = node;
        for (int edge = 0; edge < edgeCount; edge++) {
            union(
                    workspace.parent,
                    workspace.edgeStart[edge],
                    workspace.edgeEnd[edge]);
        }
        Arrays.fill(workspace.edgeCounts, 0);
        Arrays.fill(workspace.nodeCounts, 0);
        Arrays.fill(workspace.usedNodes, false);
        Arrays.fill(workspace.componentMinX, Double.MAX_VALUE);
        Arrays.fill(workspace.componentMinY, Double.MAX_VALUE);
        Arrays.fill(workspace.componentMaxX, -Double.MAX_VALUE);
        Arrays.fill(workspace.componentMaxY, -Double.MAX_VALUE);

        for (int edge = 0; edge < edgeCount; edge++) {
            int root = find(workspace.parent, workspace.edgeStart[edge]);
            workspace.edgeCounts[root]++;
            workspace.usedNodes[workspace.edgeStart[edge]] = true;
            workspace.usedNodes[workspace.edgeEnd[edge]] = true;
            int stroke = workspace.edgeStroke[edge];
            int start = candidate.strokeOffsets[stroke];
            int end = candidate.strokeOffsets[stroke + 1];
            for (int point = start; point < end; point++) {
                workspace.componentMinX[root] = Math.min(
                        workspace.componentMinX[root], workspace.rotatedRawX[point]);
                workspace.componentMinY[root] = Math.min(
                        workspace.componentMinY[root], workspace.rotatedRawY[point]);
                workspace.componentMaxX[root] = Math.max(
                        workspace.componentMaxX[root], workspace.rotatedRawX[point]);
                workspace.componentMaxY[root] = Math.max(
                        workspace.componentMaxY[root], workspace.rotatedRawY[point]);
            }
        }
        for (int node = 0; node < nodeCount; node++) {
            if (workspace.usedNodes[node]) {
                workspace.nodeCounts[find(workspace.parent, node)]++;
            }
        }

        int contours = 0;
        double minimumArea = drawingDiagonal * drawingDiagonal * MIN_CONTOUR_AREA_RATIO;
        for (int root = 0; root < nodeCount; root++) {
            if (workspace.edgeCounts[root] == 0) continue;
            int cycleRank = workspace.edgeCounts[root] - workspace.nodeCounts[root] + 1;
            double boundingArea = (workspace.componentMaxX[root] - workspace.componentMinX[root])
                    * (workspace.componentMaxY[root] - workspace.componentMinY[root]);
            if (cycleRank > 0 && boundingArea >= minimumArea) contours += cycleRank;
        }
        return contours;
    }

    private static int findOrCreateEndpointNode(
            RotationWorkspace workspace,
            int nodeCount,
            int pointIndex,
            double tolerance) {
        double x = workspace.rotatedRawX[pointIndex];
        double y = workspace.rotatedRawY[pointIndex];
        for (int node = 0; node < nodeCount; node++) {
            if (Math.hypot(workspace.nodeX[node] - x, workspace.nodeY[node] - y)
                    <= tolerance) {
                return node;
            }
        }
        workspace.nodeX[nodeCount] = x;
        workspace.nodeY[nodeCount] = y;
        return nodeCount;
    }

    private static int find(int[] parent, int value) {
        if (parent[value] != value) parent[value] = find(parent, parent[value]);
        return parent[value];
    }

    private static void union(int[] parent, int a, int b) {
        int rootA = find(parent, a);
        int rootB = find(parent, b);
        if (rootA != rootB) parent[rootB] = rootA;
    }

    private static boolean isClosedSingleStroke(
            PreparedCandidate candidate, RotationWorkspace workspace) {
        if (candidate.strokeCount != 1
                || candidate.strokeOffsets[1] - candidate.strokeOffsets[0] < 4) {
            return false;
        }
        int start = candidate.strokeOffsets[0];
        int end = candidate.strokeOffsets[1] - 1;
        return workspace.drawingDiagonal > 1e-10
                && rawDistance(workspace, start, end)
                        <= workspace.drawingDiagonal * CONTOUR_CONNECTION_RATIO;
    }

    private static double centralSymmetryError(RotationWorkspace workspace) {
        double centroidX = 0.0;
        double centroidY = 0.0;
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (int i = 0; i < N; i++) {
            centroidX += workspace.normalizedX[i];
            centroidY += workspace.normalizedY[i];
            minX = Math.min(minX, workspace.normalizedX[i]);
            minY = Math.min(minY, workspace.normalizedY[i]);
            maxX = Math.max(maxX, workspace.normalizedX[i]);
            maxY = Math.max(maxY, workspace.normalizedY[i]);
        }
        centroidX /= N;
        centroidY /= N;
        double diagonal = Math.hypot(maxX - minX, maxY - minY);
        if (diagonal < 1e-10) return 0.0;

        double error = 0.0;
        for (int i = 0; i < N; i++) {
            double reflectedX = 2.0 * centroidX - workspace.normalizedX[i];
            double reflectedY = 2.0 * centroidY - workspace.normalizedY[i];
            double nearest = Double.MAX_VALUE;
            for (int other = 0; other < N; other++) {
                nearest = Math.min(
                        nearest,
                        Math.hypot(
                                workspace.normalizedX[other] - reflectedX,
                                workspace.normalizedY[other] - reflectedY));
            }
            error += nearest;
        }
        return error / N / diagonal;
    }

    private static double rawDistance(
            RotationWorkspace workspace, int first, int second) {
        return Math.hypot(
                workspace.rotatedRawX[first] - workspace.rotatedRawX[second],
                workspace.rotatedRawY[first] - workspace.rotatedRawY[second]);
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

    private static double greedyCloudMatch(
            RotationWorkspace candidate, CloudPoint[] template, int n) {
        int step = (int) Math.floor(Math.pow(n, 1.0 - EPSILON));
        if (step < 1) step = 1;
        double minDistance = Double.MAX_VALUE;
        for (int start = 0; start < n; start += step) {
            double candidateToTemplate = cloudDistance(
                    candidate, template, n, start, false);
            double templateToCandidate = cloudDistance(
                    candidate, template, n, start, true);
            minDistance = Math.min(
                    minDistance, Math.min(candidateToTemplate, templateToCandidate));
        }
        return minDistance;
    }

    private static double cloudDistance(
            RotationWorkspace candidate,
            CloudPoint[] template,
            int n,
            int start,
            boolean templateFirst) {
        Arrays.fill(candidate.matched, false);
        double sum = 0.0;
        int sourceIndex = start;
        do {
            double minimumDistance = Double.MAX_VALUE;
            int matchedIndex = -1;
            for (int targetIndex = 0; targetIndex < n; targetIndex++) {
                if (candidate.matched[targetIndex]) continue;
                double distance = templateFirst
                        ? preparedDistance(candidate, targetIndex, template[sourceIndex])
                        : preparedDistance(candidate, sourceIndex, template[targetIndex]);
                if (distance < minimumDistance) {
                    minimumDistance = distance;
                    matchedIndex = targetIndex;
                }
            }
            if (matchedIndex >= 0) candidate.matched[matchedIndex] = true;
            double weight = 1.0 - ((sourceIndex - start + n) % n) / (double) n;
            sum += weight * minimumDistance;
            sourceIndex = (sourceIndex + 1) % n;
        } while (sourceIndex != start);
        return sum;
    }

    private static double preparedDistance(
            RotationWorkspace candidate, int candidateIndex, CloudPoint templatePoint) {
        double dx = candidate.normalizedX[candidateIndex] - templatePoint.x();
        double dy = candidate.normalizedY[candidateIndex] - templatePoint.y();
        double spatialDistance = Math.sqrt(dx * dx + dy * dy);
        double angleDifference = Math.abs(
                candidate.turningAngles[candidateIndex] - templatePoint.turningAngle()) / Math.PI;
        return spatialDistance + ANGLE_WEIGHT * angleDifference;
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

    private record SemanticMatch(
            SemanticTemplateGroup group,
            PointCloudTemplate template,
            double score,
            RecognitionRejectionReason structuralRejection) {}
}
