package com.maxello1.whamagic.magic;
import net.minecraft.resources.Identifier;
import com.maxello1.whamagic.parser.BoundingBox;
import com.maxello1.whamagic.parser.Point;
import java.util.List;

public record RecognizedSigil(
    Identifier id,
    ElementType element,
    SigilSemantic semantic,
    double recognitionConfidence,
    Point centroid,
    BoundingBox bounds,
    double orientationDeg,
    List<Integer> sourceStrokeIndices,
    List<RecognitionAlternative> alternatives,
    RecognitionRejectionReason rejectionReason
) {
    public RecognizedSigil {
        sourceStrokeIndices = sourceStrokeIndices == null ? List.of() : List.copyOf(sourceStrokeIndices);
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        rejectionReason = rejectionReason == null ? RecognitionRejectionReason.NONE : rejectionReason;
    }
}
