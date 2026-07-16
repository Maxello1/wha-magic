package com.maxello1.whamagic.magic;

import com.maxello1.whamagic.parser.BoundingBox;
import com.maxello1.whamagic.parser.Point;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpellCompilerGeometryTest {
    private static final SigilSemantic EARTH_SEMANTIC =
            new SigilSemantic(0.5, 0.2, 0.1, 0.3, 0.0);
    private static final SignSemantic COLUMN_SEMANTIC =
            new SignSemantic("column", "inward", 0.3, 0.35, -0.24, 0.18, 0.0);

    @Test
    void preservesRingGeometryAndQualityMetrics() {
        RingDetector.RingGlyph ring = ring(new Point(0.4, 0.6), 0.3);

        SpellIr ir = SpellCompiler.compile(ast(ring, List.of(sigil(
                "earth", "Earth", ElementType.EARTH, EARTH_SEMANTIC, 1)), List.of()));
        SpellGeometry geometry = ir.geometry();

        assertAll(
                () -> assertEquals(new Point(0.4, 0.6), geometry.ringCenter()),
                () -> assertEquals(0.3, geometry.ringRadius()),
                () -> assertEquals(Math.PI * 0.3 * 0.3, geometry.ringArea()),
                () -> assertEquals(0.6, geometry.normalizedRingDiameter()),
                () -> assertEquals(ring.completeness(), geometry.ringCompleteness()),
                () -> assertEquals(ring.circularity(), geometry.ringCircularity()),
                () -> assertEquals(ring.normalizedRmse(), geometry.ringNormalizedRmse()));
    }

    @Test
    void preservesEverySignAngleOrientationMultiplicityAndSourceOwnership() {
        RingDetector.RingGlyph ring = ring(new Point(0.5, 0.5), 0.4);
        RecognizedSign first = sign(ring, 0.2, 0.5, 180.25, 87.5, 2);
        RecognizedSign second = sign(ring, 0.8, 0.5, 359.75, 271.125, 5);

        SpellIr ir = SpellCompiler.compile(ast(
                ring,
                List.of(sigil("earth", "Earth", ElementType.EARTH, EARTH_SEMANTIC, 1)),
                List.of(first, second)));

        assertAll(
                () -> assertEquals(2, ir.compiledSigns().size()),
                () -> assertEquals(2, ir.signCounts().get(id("column"))),
                () -> assertEquals(180.25, ir.compiledSigns().get(0).angleAroundRing()),
                () -> assertEquals(87.5, ir.compiledSigns().get(0).orientationDegrees()),
                () -> assertEquals(List.of(2), ir.compiledSigns().get(0).sourceStrokeIndices()),
                () -> assertEquals(359.75, ir.compiledSigns().get(1).angleAroundRing()),
                () -> assertEquals(271.125, ir.compiledSigns().get(1).orientationDegrees()),
                () -> assertEquals(List.of(5), ir.compiledSigns().get(1).sourceStrokeIndices()),
                () -> assertTrue(ir.compiledSigns().stream().noneMatch(CompiledSign::reversed)),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> ir.compiledSigns().get(0).sourceStrokeIndices().add(99)));
    }

    @Test
    void leftHeavyPlacementBiasesTowardTheOppositeSide() {
        RingDetector.RingGlyph ring = ring(new Point(0.5, 0.5), 0.4);
        List<RecognizedSign> leftHeavy = List.of(
                sign(ring, 0.2, 0.45, 180.0, 90.0, 2),
                sign(ring, 0.2, 0.55, 180.0, 270.0, 3));

        SpellGeometry geometry = SpellCompiler.compile(ast(
                ring,
                List.of(sigil("earth", "Earth", ElementType.EARTH, EARTH_SEMANTIC, 1)),
                leftHeavy)).geometry();

        assertTrue(geometry.directionalBias().x > 0.6,
                "Signs on the left must bias manifestation to the right");
        assertEquals(0.0, geometry.directionalBias().y, 1.0e-12);
    }

    @Test
    void balancedPlacementAndOrientationHaveNearZeroBias() {
        RingDetector.RingGlyph ring = ring(new Point(0.5, 0.5), 0.4);
        List<RecognizedSign> balanced = List.of(
                sign(ring, 0.2, 0.5, 180.0, 180.0, 2),
                sign(ring, 0.8, 0.5, 0.0, 0.0, 3));

        Point bias = SpellCompiler.compile(ast(
                ring,
                List.of(sigil("earth", "Earth", ElementType.EARTH, EARTH_SEMANTIC, 1)),
                balanced)).geometry().directionalBias();

        assertEquals(0.0, Math.hypot(bias.x, bias.y), 1.0e-12);
    }

    @Test
    void resolvesNamedLayersAtDocumentedThresholds() {
        assertAll(
                () -> assertEquals(SpellLayer.CORE,
                        SpellLayer.fromRadialPosition(SpellLayer.CORE_MAX_RADIAL_POSITION)),
                () -> assertEquals(SpellLayer.INNER,
                        SpellLayer.fromRadialPosition(Math.nextUp(SpellLayer.CORE_MAX_RADIAL_POSITION))),
                () -> assertEquals(SpellLayer.INNER,
                        SpellLayer.fromRadialPosition(SpellLayer.INNER_MAX_RADIAL_POSITION)),
                () -> assertEquals(SpellLayer.MIDDLE,
                        SpellLayer.fromRadialPosition(Math.nextUp(SpellLayer.INNER_MAX_RADIAL_POSITION))),
                () -> assertEquals(SpellLayer.MIDDLE,
                        SpellLayer.fromRadialPosition(SpellLayer.MIDDLE_MAX_RADIAL_POSITION)),
                () -> assertEquals(SpellLayer.OUTER,
                        SpellLayer.fromRadialPosition(Math.nextUp(SpellLayer.MIDDLE_MAX_RADIAL_POSITION))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> SpellLayer.fromRadialPosition(Double.NaN)));

        RingDetector.RingGlyph ring = ring(new Point(0.5, 0.5), 0.4);
        SpellIr ir = SpellCompiler.compile(ast(
                ring,
                List.of(sigil("earth", "Earth", ElementType.EARTH, EARTH_SEMANTIC, 1)),
                List.of(
                        sign(ring, 0.58, 0.5, 0, 0, 2),
                        sign(ring, 0.66, 0.5, 0, 0, 3),
                        sign(ring, 0.74, 0.5, 0, 0, 4),
                        sign(ring, 0.84, 0.5, 0, 0, 5))));

        assertEquals(
                List.of(SpellLayer.CORE, SpellLayer.INNER, SpellLayer.MIDDLE, SpellLayer.OUTER),
                ir.compiledSigns().stream().map(CompiledSign::layer).toList());
    }

    @Test
    void keepsMultipleSigilsDistinctAndDerivesPrimaryCompatibilityView() {
        RingDetector.RingGlyph ring = ring(new Point(0.5, 0.5), 0.4);
        SigilSemantic waterSemantic = new SigilSemantic(0.1, 0.6, 0.2, 0.4, 0.3);

        SpellIr ir = SpellCompiler.compile(ast(
                ring,
                List.of(
                        sigil("earth", "Earth", ElementType.EARTH, EARTH_SEMANTIC, 1),
                        sigil("water", "Water", ElementType.WATER, waterSemantic, 4)),
                List.of()));

        assertAll(
                () -> assertEquals(2, ir.compiledSigils().size()),
                () -> assertEquals(List.of(ElementType.EARTH, ElementType.WATER), ir.elements()),
                () -> assertEquals(EARTH_SEMANTIC, ir.sigilSemantic()),
                () -> assertNotEquals(
                        ir.compiledSigils().get(0).semanticId(),
                        ir.compiledSigils().get(1).semanticId()),
                () -> assertEquals(List.of(1), ir.compiledSigils().get(0).sourceStrokeIndices()),
                () -> assertEquals(List.of(4), ir.compiledSigils().get(1).sourceStrokeIndices()));
    }

    @Test
    void compilesDirectQualityMetricsAndDerivedParameters() {
        RingDetector.RingGlyph ring = ring(new Point(0.5, 0.5), 0.4);
        RecognitionQualityMetrics sigilMetrics =
                new RecognitionQualityMetrics(0.92, 0.88, 0.08, 0.90);
        RecognitionQualityMetrics signMetrics =
                new RecognitionQualityMetrics(0.85, 0.82, 0.12, 0.87);
        RecognizedSigil sigil = new RecognizedSigil(
                id("earth"), "earth", "Earth", ElementType.EARTH,
                EARTH_SEMANTIC, 0.91, sigilMetrics,
                new Point(0.5, 0.5), new BoundingBox(0.4, 0.4, 0.6, 0.6),
                13.5, List.of(1), List.of(), RecognitionRejectionReason.NONE);
        RecognizedSign sign = new RecognizedSign(
                2, id("column").toString(), "column", 0.88, signMetrics,
                0.0, 180.0, "sign", COLUMN_SEMANTIC, List.of(2),
                new Point(0.82, 0.5), new BoundingBox(0.80, 0.48, 0.84, 0.52),
                List.of(), RecognitionRejectionReason.NONE);

        SpellIr ir = SpellCompiler.compile(ast(ring, List.of(sigil), List.of(sign)));

        assertAll(
                () -> assertEquals(sigilMetrics,
                        ir.compiledSigils().getFirst().qualityMetrics()),
                () -> assertEquals(signMetrics,
                        ir.compiledSigns().getFirst().qualityMetrics()),
                () -> assertEquals(
                        SpellQualityAnalyzer.symbolPrecision(0.91, sigilMetrics),
                        ir.quality().sigilPrecision(), 1.0e-12),
                () -> assertEquals(
                        SpellQualityAnalyzer.symbolPrecision(0.88, signMetrics),
                        ir.quality().signPrecision(), 1.0e-12),
                () -> assertTrue(ir.parameters().sizeScale() > 0.0),
                () -> assertEquals(ir.quality().stability(),
                        ir.parameters().stability()));
    }

    private static GlyphAst ast(
            RingDetector.RingGlyph ring,
            List<RecognizedSigil> sigils,
            List<RecognizedSign> signs) {
        return new GlyphAst(ring, sigils, signs, List.of());
    }

    private static RingDetector.RingGlyph ring(Point center, double radius) {
        return new RingDetector.RingGlyph(
                center, radius, 0.93, true, 12.0, 0.004,
                0.013, 0.02, 0.01, 0.04, 0.08, 0.97);
    }

    private static RecognizedSigil sigil(
            String path,
            String displayName,
            ElementType element,
            SigilSemantic semantic,
            int sourceIndex) {
        return new RecognizedSigil(
                id(path), path, displayName, element, semantic, 0.91,
                new Point(0.5, 0.5), new BoundingBox(0.4, 0.4, 0.6, 0.6),
                13.5, List.of(sourceIndex), List.of(), RecognitionRejectionReason.NONE);
    }

    private static RecognizedSign sign(
            RingDetector.RingGlyph ring,
            double x,
            double y,
            double angle,
            double orientation,
            int sourceIndex) {
        return new RecognizedSign(
                sourceIndex,
                id("column").toString(),
                "column",
                0.88,
                angle,
                orientation,
                "sign",
                COLUMN_SEMANTIC,
                List.of(sourceIndex),
                new Point(x, y),
                new BoundingBox(x - 0.02, y - 0.02, x + 0.02, y + 0.02),
                List.of(),
                RecognitionRejectionReason.NONE);
    }

    private static Identifier id(String path) {
        return Identifier.tryParse("wha-magic:" + path);
    }
}
