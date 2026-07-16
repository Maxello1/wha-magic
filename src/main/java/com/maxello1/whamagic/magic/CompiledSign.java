package com.maxello1.whamagic.magic;

import com.maxello1.whamagic.parser.BoundingBox;
import com.maxello1.whamagic.parser.Point;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Objects;

/** Immutable execution-facing form of one recognized sign. */
public record CompiledSign(
        Identifier semanticId,
        String matchedTemplateId,
        SignSemantic semantic,
        double confidence,
        RecognitionQualityMetrics qualityMetrics,
        double angleAroundRing,
        double orientationDegrees,
        double radialPosition,
        SpellLayer layer,
        Point centroid,
        BoundingBox bounds,
        List<Integer> sourceStrokeIndices,
        boolean reversed
) {
    public CompiledSign {
        semanticId = Objects.requireNonNull(semanticId, "semanticId");
        matchedTemplateId = Objects.requireNonNull(matchedTemplateId, "matchedTemplateId");
        semantic = Objects.requireNonNull(semantic, "semantic");
        qualityMetrics = qualityMetrics == null
                ? RecognitionQualityMetrics.NEUTRAL
                : qualityMetrics;
        layer = Objects.requireNonNull(layer, "layer");
        centroid = Objects.requireNonNull(centroid, "centroid");
        bounds = Objects.requireNonNull(bounds, "bounds");
        sourceStrokeIndices = sourceStrokeIndices == null
                ? List.of()
                : List.copyOf(sourceStrokeIndices);
    }

    /** Compatibility constructor for compiled data without direct quality metrics. */
    public CompiledSign(
            Identifier semanticId,
            String matchedTemplateId,
            SignSemantic semantic,
            double confidence,
            double angleAroundRing,
            double orientationDegrees,
            double radialPosition,
            SpellLayer layer,
            Point centroid,
            BoundingBox bounds,
            List<Integer> sourceStrokeIndices,
            boolean reversed) {
        this(semanticId, matchedTemplateId, semantic, confidence,
                RecognitionQualityMetrics.NEUTRAL, angleAroundRing,
                orientationDegrees, radialPosition, layer, centroid, bounds,
                sourceStrokeIndices, reversed);
    }
}
