package com.maxello1.whamagic.magic;

import com.maxello1.whamagic.config.WhaServerConfig;

/** Validated immutable settings used by seal-size and parameter derivation. */
public record MagicScalingSettings(
        double referenceRingDiameter,
        double sizeExponent,
        double minimumSizeScale,
        double maximumSizeScale,
        double maximumPowerMultiplier,
        double maximumRangeMultiplier,
        double maximumRadiusMultiplier,
        double maximumDurationMultiplier
) {
    public static final double DEFAULT_REFERENCE_RING_DIAMETER = 0.75;
    public static final double DEFAULT_SIZE_EXPONENT = 0.75;
    public static final double DEFAULT_MINIMUM_SIZE_SCALE = 0.50;
    public static final double DEFAULT_MAXIMUM_SIZE_SCALE = 2.25;
    public static final double DEFAULT_MAXIMUM_POWER_MULTIPLIER = 3.0;
    public static final double DEFAULT_MAXIMUM_RANGE_MULTIPLIER = 3.0;
    public static final double DEFAULT_MAXIMUM_RADIUS_MULTIPLIER = 3.0;
    public static final double DEFAULT_MAXIMUM_DURATION_MULTIPLIER = 4.0;

    public MagicScalingSettings {
        referenceRingDiameter = validRange(
                referenceRingDiameter, 0.10, 2.0, DEFAULT_REFERENCE_RING_DIAMETER);
        sizeExponent = validRange(sizeExponent, 0.10, 2.0, DEFAULT_SIZE_EXPONENT);
        minimumSizeScale = validRange(
                minimumSizeScale, 0.10, 4.0, DEFAULT_MINIMUM_SIZE_SCALE);
        maximumSizeScale = validRange(
                maximumSizeScale, 0.25, 4.0, DEFAULT_MAXIMUM_SIZE_SCALE);
        if (maximumSizeScale < minimumSizeScale) {
            minimumSizeScale = DEFAULT_MINIMUM_SIZE_SCALE;
            maximumSizeScale = DEFAULT_MAXIMUM_SIZE_SCALE;
        }
        maximumPowerMultiplier = validRange(
                maximumPowerMultiplier, 1.0, 8.0, DEFAULT_MAXIMUM_POWER_MULTIPLIER);
        maximumRangeMultiplier = validRange(
                maximumRangeMultiplier, 1.0, 8.0, DEFAULT_MAXIMUM_RANGE_MULTIPLIER);
        maximumRadiusMultiplier = validRange(
                maximumRadiusMultiplier, 1.0, 8.0, DEFAULT_MAXIMUM_RADIUS_MULTIPLIER);
        maximumDurationMultiplier = validRange(
                maximumDurationMultiplier, 1.0, 16.0, DEFAULT_MAXIMUM_DURATION_MULTIPLIER);
    }

    public static MagicScalingSettings defaults() {
        return new MagicScalingSettings(
                DEFAULT_REFERENCE_RING_DIAMETER,
                DEFAULT_SIZE_EXPONENT,
                DEFAULT_MINIMUM_SIZE_SCALE,
                DEFAULT_MAXIMUM_SIZE_SCALE,
                DEFAULT_MAXIMUM_POWER_MULTIPLIER,
                DEFAULT_MAXIMUM_RANGE_MULTIPLIER,
                DEFAULT_MAXIMUM_RADIUS_MULTIPLIER,
                DEFAULT_MAXIMUM_DURATION_MULTIPLIER);
    }

    public static MagicScalingSettings fromConfig() {
        WhaServerConfig.ConfigData.MagicScaling config =
                WhaServerConfig.INSTANCE.magicScaling;
        return new MagicScalingSettings(
                config.referenceRingDiameter,
                config.sizeExponent,
                config.minimumSizeScale,
                config.maximumSizeScale,
                config.maximumPowerMultiplier,
                config.maximumRangeMultiplier,
                config.maximumRadiusMultiplier,
                config.maximumDurationMultiplier);
    }

    /**
     * Exact deterministic cache key. A stored spell compiled under different
     * scaling limits must be refreshed before execution.
     */
    public String fingerprint() {
        return Long.toUnsignedString(Double.doubleToLongBits(referenceRingDiameter), 16)
                + ":" + Long.toUnsignedString(Double.doubleToLongBits(sizeExponent), 16)
                + ":" + Long.toUnsignedString(Double.doubleToLongBits(minimumSizeScale), 16)
                + ":" + Long.toUnsignedString(Double.doubleToLongBits(maximumSizeScale), 16)
                + ":" + Long.toUnsignedString(Double.doubleToLongBits(maximumPowerMultiplier), 16)
                + ":" + Long.toUnsignedString(Double.doubleToLongBits(maximumRangeMultiplier), 16)
                + ":" + Long.toUnsignedString(Double.doubleToLongBits(maximumRadiusMultiplier), 16)
                + ":" + Long.toUnsignedString(Double.doubleToLongBits(maximumDurationMultiplier), 16);
    }

    private static double validRange(
            double value,
            double minimum,
            double maximum,
            double fallback) {
        return Double.isFinite(value) && value >= minimum && value <= maximum
                ? value
                : fallback;
    }
}
