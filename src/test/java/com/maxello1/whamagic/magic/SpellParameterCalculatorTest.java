package com.maxello1.whamagic.magic;

import com.maxello1.whamagic.parser.BoundingBox;
import com.maxello1.whamagic.parser.Point;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpellParameterCalculatorTest {
    private static final MagicScalingSettings DEFAULTS = MagicScalingSettings.defaults();

    @Test
    void higherQualityStronglyImprovesDuration() {
        SpellParameters rough = calculate(
                geometry(0.75), quality(0.30), SigilSemantic.empty(), List.of(), DEFAULTS);
        SpellParameters masterwork = calculate(
                geometry(0.75), quality(0.90), SigilSemantic.empty(), List.of(), DEFAULTS);

        assertTrue(masterwork.durationMultiplier() > rough.durationMultiplier() * 2.0);
        assertEquals(rough.sizeScale(), masterwork.sizeScale(), 0.0,
                "Drawing quality and seal size must remain independent");
    }

    @Test
    void sizeAffectsPowerMoreThanQualityDoesAcrossRepresentativeRanges() {
        SpellQuality serviceable = quality(0.65);
        SpellParameters small = calculate(
                geometry(0.375), serviceable, SigilSemantic.empty(), List.of(), DEFAULTS);
        SpellParameters large = calculate(
                geometry(1.50), serviceable, SigilSemantic.empty(), List.of(), DEFAULTS);

        SpellParameters rough = calculate(
                geometry(0.75), quality(0.40), SigilSemantic.empty(), List.of(), DEFAULTS);
        SpellParameters refined = calculate(
                geometry(0.75), quality(0.90), SigilSemantic.empty(), List.of(), DEFAULTS);

        double sizeEffect = large.powerMultiplier() - small.powerMultiplier();
        double qualityEffect = refined.powerMultiplier() - rough.powerMultiplier();
        assertTrue(sizeEffect > qualityEffect * 3.0,
                "Ring size should be the stronger direct power input");
    }

    @Test
    void focusRaisesSpeedAndConcentrationWithoutNegativeRadius() {
        SpellParameters neutral = calculate(
                geometry(0.75), quality(0.75), SigilSemantic.empty(), List.of(), DEFAULTS);
        SpellParameters focused = calculate(
                geometry(0.75),
                quality(0.75),
                SigilSemantic.empty(),
                List.of(sign("focus", new SignSemantic(
                        "", "orientation", 0.0, 1.0, 0.0, 0.0, 0.0))),
                DEFAULTS);

        assertTrue(focused.speedMultiplier() > neutral.speedMultiplier());
        assertTrue(focused.radiusMultiplier() < neutral.radiusMultiplier());
        assertTrue(focused.radiusMultiplier() >= SpellParameterCalculator.MINIMUM_SPATIAL_MULTIPLIER);
    }

    @Test
    void spreadRaisesRadiusWithoutBypassingCap() {
        SpellParameters neutral = calculate(
                geometry(0.75), quality(0.75), SigilSemantic.empty(), List.of(), DEFAULTS);
        SpellParameters spread = calculate(
                geometry(0.75),
                quality(0.75),
                SigilSemantic.empty(),
                List.of(sign("spread", new SignSemantic(
                        "", "none", 0.0, 0.0, 100.0, 0.0, 0.0))),
                DEFAULTS);

        assertTrue(spread.radiusMultiplier() > neutral.radiusMultiplier());
        assertTrue(spread.radiusMultiplier() <= DEFAULTS.maximumRadiusMultiplier());
    }

    @Test
    void rangeSemanticRaisesRangeWithoutBypassingCap() {
        SpellParameters neutral = calculate(
                geometry(1.50), quality(0.75), SigilSemantic.empty(), List.of(), DEFAULTS);
        SpellParameters ranged = calculate(
                geometry(1.50),
                quality(0.75),
                SigilSemantic.empty(),
                List.of(sign("range", new SignSemantic(
                        "", "orientation", 0.0, 0.0, 0.0, 100.0, 0.0))),
                DEFAULTS);

        assertTrue(ranged.rangeMultiplier() > neutral.rangeMultiplier());
        assertEquals(DEFAULTS.maximumRangeMultiplier(), ranged.rangeMultiplier(), 0.0);
    }

    @Test
    void repeatedIdenticalSignsUseDiminishingReturnsAndPreserveMultiplicity() {
        CompiledSign force = sign("force", new SignSemantic(
                "", "orientation", 1.0, 0.0, 0.0, 0.0, 0.0));

        SpellParameterCalculator.SemanticAggregate one = aggregateCopies(force, 1);
        SpellParameterCalculator.SemanticAggregate two = aggregateCopies(force, 2);
        SpellParameterCalculator.SemanticAggregate three = aggregateCopies(force, 3);
        SpellParameterCalculator.SemanticAggregate four = aggregateCopies(force, 4);

        assertEquals(1.0, one.force(), 1.0e-12);
        assertEquals(1.0 + 0.70, two.force(), 1.0e-12);
        assertEquals(1.0 + 0.70 + 0.49, three.force(), 1.0e-12);
        assertEquals(1.0 + 0.70 + 0.49 + 0.343, four.force(), 1.0e-12);
        assertTrue(two.force() - one.force() > three.force() - two.force());
        assertTrue(three.force() - two.force() > four.force() - three.force());
    }

    @Test
    void allParametersRemainFiniteNonnegativeAndWithinConfiguredCaps() {
        MagicScalingSettings tightCaps = new MagicScalingSettings(
                0.75,
                0.75,
                0.50,
                1.10,
                1.20,
                1.30,
                1.40,
                1.50);
        SignSemantic extreme = new SignSemantic(
                "", "orientation", 100.0, 100.0, 100.0, 100.0, 100.0);
        List<CompiledSign> signs = List.of(
                sign("extreme", extreme),
                sign("extreme", extreme),
                sign("extreme", extreme),
                sign("extreme", extreme));

        SpellParameters parameters = calculate(
                geometry(10.0),
                quality(1.0),
                new SigilSemantic(100.0, 100.0, 100.0, 100.0, 100.0),
                signs,
                tightCaps);

        assertFiniteNonnegative(parameters);
        assertTrue(parameters.sizeScale() <= tightCaps.maximumSizeScale());
        assertTrue(parameters.powerMultiplier() <= tightCaps.maximumPowerMultiplier());
        assertTrue(parameters.forceMultiplier() <= tightCaps.maximumPowerMultiplier());
        assertTrue(parameters.rangeMultiplier() <= tightCaps.maximumRangeMultiplier());
        assertTrue(parameters.speedMultiplier() <= tightCaps.maximumRangeMultiplier());
        assertTrue(parameters.radiusMultiplier() <= tightCaps.maximumRadiusMultiplier());
        assertTrue(parameters.durationMultiplier() <= tightCaps.maximumDurationMultiplier());
    }

    @Test
    void negativeSemanticsCanReduceButNeverNegateParameters() {
        SignSemantic negative = new SignSemantic(
                "", "orientation", -100.0, -100.0, -100.0, -100.0, -100.0);

        SpellParameters parameters = calculate(
                geometry(0.20),
                quality(0.0),
                new SigilSemantic(-100.0, -100.0, -100.0, -100.0, -100.0),
                List.of(sign("negative", negative)),
                DEFAULTS);

        assertFiniteNonnegative(parameters);
        assertTrue(parameters.powerMultiplier() >= SpellParameterCalculator.MINIMUM_MULTIPLIER);
        assertTrue(parameters.forceMultiplier() >= SpellParameterCalculator.MINIMUM_MULTIPLIER);
        assertTrue(parameters.rangeMultiplier() >= SpellParameterCalculator.MINIMUM_SPATIAL_MULTIPLIER);
        assertTrue(parameters.radiusMultiplier() >= SpellParameterCalculator.MINIMUM_SPATIAL_MULTIPLIER);
        assertTrue(parameters.speedMultiplier() >= SpellParameterCalculator.MINIMUM_SPATIAL_MULTIPLIER);
        assertTrue(parameters.durationMultiplier() >= SpellParameterCalculator.MINIMUM_MULTIPLIER);
    }

    @Test
    void parameterCalculationIsDeterministic() {
        List<CompiledSign> signs = List.of(
                sign("focus", new SignSemantic(
                        "", "orientation", 0.1, 0.8, -0.1, 0.2, 0.3)),
                sign("focus", new SignSemantic(
                        "", "orientation", 0.1, 0.8, -0.1, 0.2, 0.3)));

        SpellParameters first = calculate(
                geometry(0.93), quality(0.82), SigilSemantic.empty(), signs, DEFAULTS);
        SpellParameters second = calculate(
                geometry(0.93), quality(0.82), SigilSemantic.empty(), signs, DEFAULTS);

        assertEquals(first, second);
    }

    private static SpellParameterCalculator.SemanticAggregate aggregateCopies(
            CompiledSign sign,
            int count) {
        List<CompiledSign> signs = new ArrayList<>();
        for (int index = 0; index < count; index++) signs.add(sign);
        return SpellParameterCalculator.aggregateSemantics(List.of(), signs);
    }

    private static SpellParameters calculate(
            SpellGeometry geometry,
            SpellQuality quality,
            SigilSemantic sigilSemantic,
            List<CompiledSign> signs,
            MagicScalingSettings settings) {
        return SpellParameterCalculator.calculate(
                List.of(sigil(sigilSemantic)), signs, geometry, quality, settings);
    }

    private static CompiledSigil sigil(SigilSemantic semantic) {
        return new CompiledSigil(
                id("fire"),
                "fire",
                "Fire",
                ElementType.FIRE,
                semantic,
                1.0,
                RecognitionQualityMetrics.NEUTRAL,
                new Point(0.50, 0.50),
                new BoundingBox(0.45, 0.45, 0.55, 0.55),
                0.0,
                List.of(1));
    }

    private static CompiledSign sign(String path, SignSemantic semantic) {
        return new CompiledSign(
                id(path),
                path,
                semantic,
                1.0,
                RecognitionQualityMetrics.NEUTRAL,
                0.0,
                0.0,
                0.80,
                SpellLayer.OUTER,
                new Point(0.82, 0.50),
                new BoundingBox(0.80, 0.48, 0.84, 0.52),
                List.of(2),
                false);
    }

    private static SpellGeometry geometry(double diameter) {
        double radius = diameter / 2.0;
        return new SpellGeometry(
                new Point(0.50, 0.50),
                radius,
                Math.PI * radius * radius,
                diameter,
                0.95,
                0.97,
                0.01,
                new Point(0.0, 0.0),
                1.0,
                1.0,
                1.0);
    }

    private static SpellQuality quality(double overall) {
        return SpellQuality.assessed(
                overall,
                overall,
                overall,
                overall,
                overall,
                overall,
                overall);
    }

    private static Identifier id(String path) {
        return Identifier.tryParse("wha-magic:" + path);
    }

    private static void assertFiniteNonnegative(SpellParameters parameters) {
        for (double value : List.of(
                parameters.sizeScale(),
                parameters.qualityEfficiency(),
                parameters.powerMultiplier(),
                parameters.rangeMultiplier(),
                parameters.radiusMultiplier(),
                parameters.durationMultiplier(),
                parameters.speedMultiplier(),
                parameters.forceMultiplier(),
                parameters.stability())) {
            assertTrue(Double.isFinite(value));
            assertTrue(value >= 0.0);
        }
    }
}
