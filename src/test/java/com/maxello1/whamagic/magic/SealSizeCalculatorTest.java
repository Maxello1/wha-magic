package com.maxello1.whamagic.magic;

import com.maxello1.whamagic.parser.BoundingBox;
import com.maxello1.whamagic.parser.Point;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SealSizeCalculatorTest {
    @Test
    void largerOtherwiseIdenticalRingIncreasesSizeScale() {
        MagicScalingSettings settings = MagicScalingSettings.defaults();

        SealSizeCalculator.Result small = SealSizeCalculator.calculate(geometry(0.45), settings);
        SealSizeCalculator.Result reference = SealSizeCalculator.calculate(geometry(0.75), settings);
        SealSizeCalculator.Result large = SealSizeCalculator.calculate(geometry(1.20), settings);

        assertTrue(small.scale() < reference.scale());
        assertTrue(reference.scale() < large.scale());
        assertEquals(1.0, reference.scale(), 1.0e-12);
    }

    @Test
    void ringSizeDoesNotIncreaseOrReduceDrawingPrecision() {
        CompiledSigil sigil = new CompiledSigil(
                id("fire"),
                "fire",
                "Fire",
                ElementType.FIRE,
                SigilSemantic.empty(),
                0.90,
                new RecognitionQualityMetrics(0.90, 0.90, 0.05, 0.90),
                new Point(0.50, 0.50),
                new BoundingBox(0.45, 0.45, 0.55, 0.55),
                0.0,
                List.of(1));

        SpellQuality small = SpellQualityAnalyzer.analyze(
                geometry(0.40), List.of(sigil), List.of(), List.of());
        SpellQuality standard = SpellQualityAnalyzer.analyze(
                geometry(0.75), List.of(sigil), List.of(), List.of());
        SpellQuality large = SpellQualityAnalyzer.analyze(
                geometry(1.20), List.of(sigil), List.of(), List.of());

        assertEquals(small, standard);
        assertEquals(standard, large);
    }

    @Test
    void sizeTierBoundariesAreExact() {
        assertEquals(SealSizeTier.TINY,
                SealSizeTier.fromScale(Math.nextDown(SealSizeTier.SMALL_THRESHOLD)));
        assertEquals(SealSizeTier.SMALL,
                SealSizeTier.fromScale(SealSizeTier.SMALL_THRESHOLD));
        assertEquals(SealSizeTier.SMALL,
                SealSizeTier.fromScale(Math.nextDown(SealSizeTier.STANDARD_THRESHOLD)));
        assertEquals(SealSizeTier.STANDARD,
                SealSizeTier.fromScale(SealSizeTier.STANDARD_THRESHOLD));
        assertEquals(SealSizeTier.STANDARD,
                SealSizeTier.fromScale(Math.nextDown(SealSizeTier.LARGE_THRESHOLD)));
        assertEquals(SealSizeTier.LARGE,
                SealSizeTier.fromScale(SealSizeTier.LARGE_THRESHOLD));
        assertEquals(SealSizeTier.LARGE,
                SealSizeTier.fromScale(Math.nextDown(SealSizeTier.GRAND_THRESHOLD)));
        assertEquals(SealSizeTier.GRAND,
                SealSizeTier.fromScale(SealSizeTier.GRAND_THRESHOLD));
    }

    @Test
    void configuredMinimumAndMaximumSizeCapsAreEnforced() {
        MagicScalingSettings settings = new MagicScalingSettings(
                0.75,
                0.75,
                0.60,
                1.40,
                3.0,
                3.0,
                3.0,
                4.0);

        SealSizeCalculator.Result tiny = SealSizeCalculator.calculate(geometry(0.001), settings);
        SealSizeCalculator.Result huge = SealSizeCalculator.calculate(geometry(10.0), settings);

        assertEquals(0.60, tiny.scale(), 0.0);
        assertEquals(1.40, huge.scale(), 0.0);
        assertEquals(SealSizeTier.TINY, tiny.tier());
        assertEquals(SealSizeTier.LARGE, huge.tier());
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

    private static Identifier id(String path) {
        return Identifier.tryParse("wha-magic:" + path);
    }
}
