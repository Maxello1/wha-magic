package com.maxello1.whamagic.magic;

import java.util.Objects;

public final class SealSizeCalculator {
    private SealSizeCalculator() {}

    public record Result(double scale, SealSizeTier tier) {
        public Result {
            if (!Double.isFinite(scale) || scale < 0.0) {
                throw new IllegalArgumentException("scale must be finite and non-negative: " + scale);
            }
            tier = Objects.requireNonNull(tier, "tier");
        }
    }

    public static Result calculate(
            SpellGeometry geometry,
            MagicScalingSettings settings) {
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(settings, "settings");
        double ratio = geometry.normalizedRingDiameter()
                / settings.referenceRingDiameter();
        double rawScale = Math.pow(Math.max(0.0, ratio), settings.sizeExponent());
        double scale = clamp(
                rawScale,
                settings.minimumSizeScale(),
                settings.maximumSizeScale());
        return new Result(scale, SealSizeTier.fromScale(scale));
    }

    private static double clamp(double value, double minimum, double maximum) {
        if (!Double.isFinite(value)) return minimum;
        return Math.max(minimum, Math.min(maximum, value));
    }
}
