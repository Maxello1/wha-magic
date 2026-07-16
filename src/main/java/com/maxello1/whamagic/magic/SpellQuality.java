package com.maxello1.whamagic.magic;

import java.util.Objects;

/** Deterministic drawing and layout quality for a compiled spell. */
public record SpellQuality(
        double overall,
        double ringPrecision,
        double sigilPrecision,
        double signPrecision,
        double layoutPrecision,
        double inkCleanliness,
        double stability,
        QualityTier tier
) {
    public static final SpellQuality UNASSESSED = new SpellQuality(
            0.70, 0.70, 0.70, 1.0, 0.70, 1.0, 0.70,
            QualityTier.SERVICEABLE);

    public SpellQuality {
        overall = finiteUnit("overall", overall);
        ringPrecision = finiteUnit("ringPrecision", ringPrecision);
        sigilPrecision = finiteUnit("sigilPrecision", sigilPrecision);
        signPrecision = finiteUnit("signPrecision", signPrecision);
        layoutPrecision = finiteUnit("layoutPrecision", layoutPrecision);
        inkCleanliness = finiteUnit("inkCleanliness", inkCleanliness);
        stability = finiteUnit("stability", stability);
        tier = Objects.requireNonNull(tier, "tier");
    }

    public static SpellQuality assessed(
            double overall,
            double ringPrecision,
            double sigilPrecision,
            double signPrecision,
            double layoutPrecision,
            double inkCleanliness,
            double stability) {
        double clampedOverall = finiteUnit("overall", overall);
        return new SpellQuality(
                clampedOverall,
                ringPrecision,
                sigilPrecision,
                signPrecision,
                layoutPrecision,
                inkCleanliness,
                stability,
                QualityTier.fromOverall(clampedOverall));
    }

    private static double finiteUnit(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
