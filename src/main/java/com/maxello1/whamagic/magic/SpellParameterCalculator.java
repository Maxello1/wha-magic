package com.maxello1.whamagic.magic;

import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Central derivation of all execution multipliers from size, quality, and semantics. */
public final class SpellParameterCalculator {
    /** Each repeated semantic instance contributes 70% of the previous one. */
    public static final double REPEATED_SIGN_DECAY = 0.70;
    /** Absolute lower guards keep negative semantics from producing zero output. */
    public static final double MINIMUM_MULTIPLIER = 0.10;
    public static final double MINIMUM_SPATIAL_MULTIPLIER = 0.25;

    public static final double QUALITY_EFFICIENCY_FLOOR = 0.65;
    public static final double QUALITY_EFFICIENCY_SHARE = 0.35;
    public static final double DURATION_FLOOR = 0.35;
    public static final double DURATION_QUALITY_SHARE = 1.65;

    private SpellParameterCalculator() {}

    public record SemanticAggregate(
            double force,
            double focus,
            double spread,
            double range,
            double lifetimeBias
    ) {}

    public static SpellParameters calculate(
            List<CompiledSigil> sigils,
            List<CompiledSign> signs,
            SpellGeometry geometry,
            SpellQuality quality,
            MagicScalingSettings settings) {
        Objects.requireNonNull(sigils, "sigils");
        Objects.requireNonNull(signs, "signs");
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(settings, "settings");
        if (geometry == null) return SpellParameters.NEUTRAL;

        SealSizeCalculator.Result size = SealSizeCalculator.calculate(geometry, settings);
        SemanticAggregate semantic = aggregateSemantics(sigils, signs);
        double qualityEfficiency = QUALITY_EFFICIENCY_FLOOR
                + QUALITY_EFFICIENCY_SHARE * quality.overall();

        double semanticForceFactor = clamp(
                1.0 + 0.55 * semantic.force() + 0.15 * semantic.focus()
                        - 0.10 * Math.max(0.0, semantic.spread()),
                0.35, settings.maximumPowerMultiplier());
        double rangeSemanticFactor = clamp(
                1.0 + 0.45 * semantic.range() + 0.10 * semantic.focus(),
                0.35, settings.maximumRangeMultiplier());
        double spreadSemanticFactor = clamp(
                1.0 + 0.55 * semantic.spread(),
                0.25, settings.maximumRadiusMultiplier());
        double focusReductionFactor = 1.0 /
                Math.max(0.35, 1.0 + 0.20 * Math.max(0.0, semantic.focus()));
        double lifetimeSemanticFactor = clamp(
                1.0 + 0.50 * semantic.lifetimeBias(),
                0.25, settings.maximumDurationMultiplier());

        double power = clamp(
                size.scale() * qualityEfficiency * semanticForceFactor,
                MINIMUM_MULTIPLIER, settings.maximumPowerMultiplier());
        double range = clamp(
                Math.sqrt(size.scale()) * rangeSemanticFactor
                        * (0.85 + 0.15 * quality.overall()),
                MINIMUM_SPATIAL_MULTIPLIER, settings.maximumRangeMultiplier());
        double radius = clamp(
                Math.sqrt(size.scale()) * spreadSemanticFactor * focusReductionFactor,
                MINIMUM_SPATIAL_MULTIPLIER, settings.maximumRadiusMultiplier());
        double duration = clamp(
                (DURATION_FLOOR
                        + DURATION_QUALITY_SHARE
                                * quality.overall() * quality.overall())
                        * lifetimeSemanticFactor,
                MINIMUM_MULTIPLIER, settings.maximumDurationMultiplier());
        // Speed shares the configured range cap; force shares the power cap.
        double speed = clamp(
                (0.75 + 0.25 * quality.overall())
                        * (1.0 + 0.40 * semantic.focus()),
                MINIMUM_SPATIAL_MULTIPLIER, settings.maximumRangeMultiplier());
        double force = clamp(
                power * Math.max(0.25, 1.0 + 0.35 * semantic.force()),
                MINIMUM_MULTIPLIER, settings.maximumPowerMultiplier());

        return new SpellParameters(
                size.scale(),
                size.tier(),
                qualityEfficiency,
                power,
                range,
                radius,
                duration,
                speed,
                force,
                quality.stability());
    }

    static SemanticAggregate aggregateSemantics(
            List<CompiledSigil> sigils,
            List<CompiledSign> signs) {
        double force = 0.0;
        double focus = 0.0;
        double spread = 0.0;
        double range = 0.0;
        double lifetime = 0.0;
        if (!sigils.isEmpty()) {
            for (CompiledSigil sigil : sigils) {
                force += sigil.semantic().force();
                focus += sigil.semantic().focus();
                spread += sigil.semantic().spread();
                range += sigil.semantic().range();
                lifetime += sigil.semantic().lifetimeBias();
            }
            force /= sigils.size();
            focus /= sigils.size();
            spread /= sigils.size();
            range /= sigils.size();
            lifetime /= sigils.size();
        }

        Map<Identifier, Integer> occurrences = new HashMap<>();
        for (CompiledSign sign : signs) {
            int occurrence = occurrences.merge(sign.semanticId(), 1, Integer::sum) - 1;
            double weight = stackWeight(occurrence);
            force += sign.semantic().force() * weight;
            focus += sign.semantic().focus() * weight;
            spread += sign.semantic().spread() * weight;
            range += sign.semantic().range() * weight;
            lifetime += sign.semantic().lifetimeBias() * weight;
        }
        return new SemanticAggregate(force, focus, spread, range, lifetime);
    }

    public static double stackWeight(int zeroBasedOccurrence) {
        if (zeroBasedOccurrence < 0) {
            throw new IllegalArgumentException("occurrence must be non-negative");
        }
        return Math.pow(REPEATED_SIGN_DECAY, zeroBasedOccurrence);
    }

    private static double clamp(double value, double minimum, double maximum) {
        if (!Double.isFinite(value)) return minimum;
        return Math.max(minimum, Math.min(maximum, value));
    }
}
