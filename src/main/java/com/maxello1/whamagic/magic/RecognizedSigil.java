package com.maxello1.whamagic.magic;
import net.minecraft.resources.Identifier;
import com.maxello1.whamagic.parser.BoundingBox;
import com.maxello1.whamagic.parser.Point;
import java.util.List;
import java.util.Objects;

public record RecognizedSigil(
    Identifier id,
    String matchedTemplateId,
    String displayName,
    ElementType element,
    SigilSemantic semantic,
    double recognitionConfidence,
    RecognitionQualityMetrics qualityMetrics,
    Point centroid,
    BoundingBox bounds,
    double orientationDeg,
    List<Integer> sourceStrokeIndices,
    List<RecognitionAlternative> alternatives,
    RecognitionRejectionReason rejectionReason
) {
    public RecognizedSigil {
        id = Objects.requireNonNull(id, "id");
        matchedTemplateId = Objects.requireNonNull(matchedTemplateId, "matchedTemplateId");
        displayName = Objects.requireNonNull(displayName, "displayName");
        semantic = Objects.requireNonNull(semantic, "semantic");
        qualityMetrics = qualityMetrics == null
                ? RecognitionQualityMetrics.NEUTRAL
                : qualityMetrics;
        sourceStrokeIndices = sourceStrokeIndices == null ? List.of() : List.copyOf(sourceStrokeIndices);
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        rejectionReason = rejectionReason == null ? RecognitionRejectionReason.NONE : rejectionReason;
    }

    /** Compatibility constructor for recognized data without direct quality metrics. */
    public RecognizedSigil(
            Identifier id,
            String matchedTemplateId,
            String displayName,
            ElementType element,
            SigilSemantic semantic,
            double recognitionConfidence,
            Point centroid,
            BoundingBox bounds,
            double orientationDeg,
            List<Integer> sourceStrokeIndices,
            List<RecognitionAlternative> alternatives,
            RecognitionRejectionReason rejectionReason) {
        this(id, matchedTemplateId, displayName, element, semantic,
                recognitionConfidence, RecognitionQualityMetrics.NEUTRAL,
                centroid, bounds, orientationDeg, sourceStrokeIndices,
                alternatives, rejectionReason);
    }
}
