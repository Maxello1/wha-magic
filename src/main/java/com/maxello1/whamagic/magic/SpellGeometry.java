package com.maxello1.whamagic.magic;

import com.maxello1.whamagic.parser.Point;

import java.util.Objects;

/** Ring and glyph-layout geometry retained by the compiled spell. */
public record SpellGeometry(
        Point ringCenter,
        double ringRadius,
        double ringArea,
        double normalizedRingDiameter,
        double ringCompleteness,
        double ringCircularity,
        double ringNormalizedRmse,
        Point directionalBias,
        double radialSymmetryScore,
        double bilateralSymmetryScore,
        double signBalanceScore
) {
    public SpellGeometry {
        ringCenter = Objects.requireNonNull(ringCenter, "ringCenter");
        directionalBias = Objects.requireNonNull(directionalBias, "directionalBias");
    }
}
