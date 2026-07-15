package com.maxello1.whamagic.magic;

import com.maxello1.whamagic.parser.BoundingBox;
import com.maxello1.whamagic.parser.Point;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Objects;

/** Immutable execution-facing form of one recognized sigil. */
public record CompiledSigil(
        Identifier semanticId,
        String matchedTemplateId,
        String displayName,
        ElementType element,
        SigilSemantic semantic,
        double recognitionConfidence,
        Point centroid,
        BoundingBox bounds,
        double orientationDegrees,
        List<Integer> sourceStrokeIndices
) {
    public CompiledSigil {
        semanticId = Objects.requireNonNull(semanticId, "semanticId");
        matchedTemplateId = Objects.requireNonNull(matchedTemplateId, "matchedTemplateId");
        displayName = Objects.requireNonNull(displayName, "displayName");
        element = Objects.requireNonNull(element, "element");
        semantic = Objects.requireNonNull(semantic, "semantic");
        centroid = Objects.requireNonNull(centroid, "centroid");
        bounds = Objects.requireNonNull(bounds, "bounds");
        sourceStrokeIndices = sourceStrokeIndices == null
                ? List.of()
                : List.copyOf(sourceStrokeIndices);
    }
}
