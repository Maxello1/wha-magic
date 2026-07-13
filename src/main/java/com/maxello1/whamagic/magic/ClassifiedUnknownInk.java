package com.maxello1.whamagic.magic;

import com.maxello1.whamagic.parser.BoundingBox;

import java.util.List;

/** Provenance and severity for source ink not owned by a recognized symbol. */
public record ClassifiedUnknownInk(
        UnknownInkClassification classification,
        int candidateId,
        List<Integer> sourceStrokeIndices,
        BoundingBox bounds,
        RecognitionRejectionReason rejectionReason
) {
    public ClassifiedUnknownInk {
        sourceStrokeIndices = sourceStrokeIndices == null ? List.of() : List.copyOf(sourceStrokeIndices);
        rejectionReason = rejectionReason == null ? RecognitionRejectionReason.NONE : rejectionReason;
    }
}
