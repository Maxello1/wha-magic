package com.maxello1.whamagic.magic;
import com.maxello1.whamagic.parser.BoundingBox;
import com.maxello1.whamagic.parser.Point;
import java.util.List;

public record UnknownSymbol(
    int candidateId,
    List<Integer> sourceStrokeIndices,
    List<List<Point>> strokes,
    BoundingBox bounds,
    CandidateState state,
    UnknownInkClassification classification,
    List<RecognitionAlternative> alternatives,
    RecognitionRejectionReason rejectionReason
) {
    public UnknownSymbol {
        sourceStrokeIndices = sourceStrokeIndices == null ? List.of() : List.copyOf(sourceStrokeIndices);
        strokes = strokes == null ? List.of() : strokes.stream().map(List::copyOf).toList();
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        classification = classification == null
                ? UnknownInkClassification.SUBSTANTIAL_UNKNOWN
                : classification;
        rejectionReason = rejectionReason == null ? RecognitionRejectionReason.NONE : rejectionReason;
    }
}
