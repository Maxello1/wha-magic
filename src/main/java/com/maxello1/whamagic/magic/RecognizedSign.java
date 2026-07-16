package com.maxello1.whamagic.magic;

import com.maxello1.whamagic.parser.BoundingBox;
import com.maxello1.whamagic.parser.Point;

import java.util.List;
import java.util.Objects;

public record RecognizedSign(
    int candidateId,
    String id,
    String matchedTemplateId,
    double confidence,
    RecognitionQualityMetrics qualityMetrics,
    double angleAroundRing,
    double orientationDeg,
    String layer,
    SignSemantic semantic,
    List<Integer> sourceStrokeIndices,
    Point centroid,
    BoundingBox bounds,
    List<RecognitionAlternative> alternatives,
    RecognitionRejectionReason rejectionReason
) {
    public RecognizedSign {
        id = Objects.requireNonNull(id, "id");
        matchedTemplateId = Objects.requireNonNull(matchedTemplateId, "matchedTemplateId");
        semantic = Objects.requireNonNull(semantic, "semantic");
        qualityMetrics = qualityMetrics == null
                ? RecognitionQualityMetrics.NEUTRAL
                : qualityMetrics;
        sourceStrokeIndices = sourceStrokeIndices == null ? List.of() : List.copyOf(sourceStrokeIndices);
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        rejectionReason = rejectionReason == null ? RecognitionRejectionReason.NONE : rejectionReason;
    }

    /** Compatibility constructor for recognized data without direct quality metrics. */
    public RecognizedSign(
            int candidateId,
            String id,
            String matchedTemplateId,
            double confidence,
            double angleAroundRing,
            double orientationDeg,
            String layer,
            SignSemantic semantic,
            List<Integer> sourceStrokeIndices,
            Point centroid,
            BoundingBox bounds,
            List<RecognitionAlternative> alternatives,
            RecognitionRejectionReason rejectionReason) {
        this(candidateId, id, matchedTemplateId, confidence,
                RecognitionQualityMetrics.NEUTRAL, angleAroundRing,
                orientationDeg, layer, semantic, sourceStrokeIndices, centroid,
                bounds, alternatives, rejectionReason);
    }
}
