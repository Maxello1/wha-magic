package com.maxello1.whamagic.magic;

import java.util.Objects;

/** Bounded execution multipliers derived once by the compiler. */
public record SpellParameters(
        double sizeScale,
        SealSizeTier sizeTier,
        double qualityEfficiency,
        double powerMultiplier,
        double rangeMultiplier,
        double radiusMultiplier,
        double durationMultiplier,
        double speedMultiplier,
        double forceMultiplier,
        double stability
) {
    public static final SpellParameters NEUTRAL = new SpellParameters(
            1.0, SealSizeTier.STANDARD, 1.0,
            1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0);

    public SpellParameters {
        sizeScale = finiteNonNegative("sizeScale", sizeScale);
        sizeTier = Objects.requireNonNull(sizeTier, "sizeTier");
        qualityEfficiency = finiteNonNegative("qualityEfficiency", qualityEfficiency);
        powerMultiplier = finiteNonNegative("powerMultiplier", powerMultiplier);
        rangeMultiplier = finiteNonNegative("rangeMultiplier", rangeMultiplier);
        radiusMultiplier = finiteNonNegative("radiusMultiplier", radiusMultiplier);
        durationMultiplier = finiteNonNegative("durationMultiplier", durationMultiplier);
        speedMultiplier = finiteNonNegative("speedMultiplier", speedMultiplier);
        forceMultiplier = finiteNonNegative("forceMultiplier", forceMultiplier);
        stability = finiteNonNegative("stability", stability);
    }

    private static double finiteNonNegative(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(
                    name + " must be finite and non-negative: " + value);
        }
        return value;
    }
}
