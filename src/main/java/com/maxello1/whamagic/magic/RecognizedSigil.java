package com.maxello1.whamagic.magic;
import net.minecraft.resources.Identifier;
import com.maxello1.whamagic.parser.BoundingBox;
import com.maxello1.whamagic.parser.Point;
import java.util.List;

public record RecognizedSigil(
    Identifier id,
    ElementType element,
    double recognitionConfidence,
    Point centroid,
    BoundingBox bounds,
    double orientationDeg,
    List<Integer> sourceStrokeIndices,
    List<RecognitionAlternative> alternatives,
    RecognitionRejectionReason rejectionReason
) {}
