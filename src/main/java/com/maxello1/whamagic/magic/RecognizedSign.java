package com.maxello1.whamagic.magic;

import com.maxello1.whamagic.parser.BoundingBox;
import com.maxello1.whamagic.parser.Point;

import java.util.List;

public record RecognizedSign(
    int candidateId,
    String id,
    double confidence,
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
        sourceStrokeIndices = sourceStrokeIndices == null ? List.of() : List.copyOf(sourceStrokeIndices);
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        rejectionReason = rejectionReason == null ? RecognitionRejectionReason.NONE : rejectionReason;
    }
}
