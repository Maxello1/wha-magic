package com.maxello1.whamagic.magic;

import com.maxello1.whamagic.parser.BoundingBox;
import com.maxello1.whamagic.parser.Point;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpellQualityAnalyzerTest {
    private static final RecognitionQualityMetrics CLEAN_METRICS =
            new RecognitionQualityMetrics(0.96, 0.95, 0.02, 0.94);
    private static final SignSemantic ORIENTATION_SEMANTIC =
            new SignSemantic("", "orientation", 0.0, 0.0, 0.0, 0.0, 0.0);
    private static final SignSemantic INWARD_SEMANTIC =
            new SignSemantic("column", "inward", 0.3, 0.35, -0.24, 0.18, 0.0);

    @Test
    void cleanRingScoresHigherThanRoughAcceptedRing() {
        SpellGeometry clean = geometry(0.75, 0.99, 0.99, 0.005, 1.0, 1.0, 1.0);
        SpellGeometry rough = geometry(0.75, 0.86, 0.91, 0.070, 1.0, 1.0, 1.0);

        SpellQuality cleanQuality = analyze(clean, List.of(sigil(CLEAN_METRICS, List.of(1))), List.of());
        SpellQuality roughQuality = analyze(rough, List.of(sigil(CLEAN_METRICS, List.of(1))), List.of());

        assertTrue(cleanQuality.ringPrecision() > roughQuality.ringPrecision());
        assertTrue(roughQuality.ringPrecision() > 0.0,
                "A rough ring inside accepted metric limits must retain a graded score");
        assertTrue(roughQuality.overall() > 0.0,
                "Quality analysis must not turn recognized geometry into an invalid sentinel");
    }

    @Test
    void roughRecognizedRingRemainsAnActiveSpell() {
        RingDetector.RingGlyph roughRing = new RingDetector.RingGlyph(
                new Point(0.50, 0.50),
                0.375,
                0.86,
                true,
                18.0,
                0.02625,
                0.070,
                0.10,
                0.03,
                0.12,
                0.24,
                0.91);
        RecognizedSigil recognized = new RecognizedSigil(
                id("fire"),
                "fire",
                "Fire",
                ElementType.FIRE,
                SigilSemantic.empty(),
                0.82,
                new RecognitionQualityMetrics(0.78, 0.80, 0.12, 0.76),
                new Point(0.50, 0.50),
                new BoundingBox(0.45, 0.45, 0.55, 0.55),
                0.0,
                List.of(1),
                List.of(),
                RecognitionRejectionReason.NONE);

        SpellIr compiled = SpellCompiler.compile(new GlyphAst(
                roughRing, List.of(recognized), List.of(), List.of()));

        assertEquals(SpellState.ACTIVE, compiled.state());
        assertTrue(compiled.valid());
        assertTrue(compiled.quality().ringPrecision() > 0.0);
    }

    @Test
    void betterTemplateCoverageRaisesSymbolPrecisionWithoutChangingConfidence() {
        RecognitionQualityMetrics lowCoverage =
                new RecognitionQualityMetrics(0.35, 0.85, 0.08, 0.85);
        RecognitionQualityMetrics highCoverage =
                new RecognitionQualityMetrics(0.90, 0.85, 0.08, 0.85);

        double low = SpellQualityAnalyzer.symbolPrecision(0.85, lowCoverage);
        double high = SpellQualityAnalyzer.symbolPrecision(0.85, highCoverage);

        assertTrue(high > low);
        assertEquals(
                SpellQualityAnalyzer.SYMBOL_TEMPLATE_COVERAGE_WEIGHT * (0.90 - 0.35),
                high - low,
                1.0e-12);
    }

    @Test
    void additionalPenLiftsDoNotAutomaticallyCollapsePrecision() {
        SpellGeometry geometry = geometry(0.75, 0.95, 0.97, 0.01, 1.0, 1.0, 1.0);
        CompiledSigil singleStroke = sigil(CLEAN_METRICS, List.of(1));
        CompiledSigil splitStrokes = sigil(CLEAN_METRICS, List.of(1, 2, 3));

        SpellQuality single = analyze(geometry, List.of(singleStroke), List.of());
        SpellQuality split = analyze(geometry, List.of(splitStrokes), List.of());

        assertEquals(single.sigilPrecision(), split.sigilPrecision(), 0.0,
                "Source-stroke count alone must not penalize a recognized template variant");

        RecognitionQualityMetrics mildlyDegraded =
                new RecognitionQualityMetrics(0.91, 0.90, 0.05, 0.90);
        double degraded = SpellQualityAnalyzer.symbolPrecision(0.90, mildlyDegraded);
        double clean = SpellQualityAnalyzer.symbolPrecision(0.92, CLEAN_METRICS);
        assertTrue(clean - degraded < 0.10,
                "A small metric change associated with harmless pen lifts must stay modest");
    }

    @Test
    void signFreeSpellReceivesNeutralSignPrecision() {
        SpellQuality quality = analyze(
                geometry(0.75, 0.95, 0.97, 0.01, 1.0, 1.0, 1.0),
                List.of(sigil(CLEAN_METRICS, List.of(1))),
                List.of());

        assertEquals(1.0, quality.signPrecision());
    }

    @Test
    void noiseLowersCleanlinessOnlySlightly() {
        SpellGeometry geometry = geometry(0.75, 0.95, 0.97, 0.01, 1.0, 1.0, 1.0);
        List<CompiledSigil> sigils = List.of(sigil(CLEAN_METRICS, List.of(1)));
        SpellQuality clean = SpellQualityAnalyzer.analyze(geometry, sigils, List.of(), List.of());
        SpellQuality noisy = SpellQualityAnalyzer.analyze(
                geometry,
                sigils,
                List.of(),
                List.of(new ClassifiedUnknownInk(
                        UnknownInkClassification.NOISE,
                        -1,
                        List.of(2),
                        null,
                        RecognitionRejectionReason.NOISE_DISCARDED)));

        assertEquals(1.0 - SpellQualityAnalyzer.NOISE_PENALTY_PER_STROKE,
                noisy.inkCleanliness(), 1.0e-12);
        assertTrue(noisy.inkCleanliness() < clean.inkCleanliness());
        assertTrue(clean.overall() - noisy.overall() < 0.01);
    }

    @Test
    void qualityScoresAreFiniteBoundedAndDeterministic() {
        SpellGeometry geometry = geometry(0.75, 0.88, 0.93, 0.035, 0.2, 0.4, 0.3);
        List<CompiledSigil> sigils = List.of(sigil(
                new RecognitionQualityMetrics(0.77, 0.81, 0.12, 0.74),
                List.of(1, 2)));
        List<CompiledSign> signs = List.of(sign(
                "column", INWARD_SEMANTIC, 0.80, 0.50, 180.0,
                new RecognitionQualityMetrics(0.82, 0.79, 0.10, 0.76)));
        List<ClassifiedUnknownInk> ink = List.of(new ClassifiedUnknownInk(
                UnknownInkClassification.HARMLESS_UNEXPLAINED,
                4,
                List.of(4),
                null,
                RecognitionRejectionReason.SCORE_BELOW_THRESHOLD));

        SpellQuality first = SpellQualityAnalyzer.analyze(geometry, sigils, signs, ink);
        SpellQuality second = SpellQualityAnalyzer.analyze(geometry, sigils, signs, ink);

        assertEquals(first, second);
        assertUnitScores(first);
    }

    @Test
    void inwardFacingColumnScoresHigherThanWronglyFacingColumn() {
        SpellGeometry geometry = geometry(0.75, 0.95, 0.97, 0.01, 1.0, 1.0, 1.0);
        CompiledSign inward = sign("column", INWARD_SEMANTIC, 0.80, 0.50, 180.0, CLEAN_METRICS);
        CompiledSign outward = sign("column", INWARD_SEMANTIC, 0.80, 0.50, 0.0, CLEAN_METRICS);

        double correct = SpellQualityAnalyzer.layoutPrecision(geometry, List.of(), List.of(inward));
        double wrong = SpellQualityAnalyzer.layoutPrecision(geometry, List.of(), List.of(outward));

        assertTrue(correct > wrong + 0.25);
    }

    @Test
    void orientationModeIsNeutralForIntentionalDirection() {
        SpellGeometry geometry = geometry(0.75, 0.95, 0.97, 0.01, 1.0, 1.0, 1.0);
        CompiledSign east = sign("levitation", ORIENTATION_SEMANTIC,
                0.80, 0.50, 0.0, CLEAN_METRICS);
        CompiledSign west = sign("levitation", ORIENTATION_SEMANTIC,
                0.80, 0.50, 180.0, CLEAN_METRICS);

        assertEquals(
                SpellQualityAnalyzer.layoutPrecision(geometry, List.of(), List.of(east)),
                SpellQualityAnalyzer.layoutPrecision(geometry, List.of(), List.of(west)),
                0.0);
    }

    @Test
    void directionalLeftHeavyGroupingRemainsHighQuality() {
        SpellGeometry asymmetric = geometry(0.75, 0.95, 0.97, 0.01, 0.0, 0.0, 0.0);
        List<CompiledSign> leftHeavy = List.of(
                sign("column", ORIENTATION_SEMANTIC, 0.20, 0.35, 20.0, CLEAN_METRICS),
                sign("column", ORIENTATION_SEMANTIC, 0.20, 0.50, 90.0, CLEAN_METRICS),
                sign("column", ORIENTATION_SEMANTIC, 0.20, 0.65, 160.0, CLEAN_METRICS));

        double layout = SpellQualityAnalyzer.layoutPrecision(asymmetric, List.of(), leftHeavy);

        assertTrue(layout > 0.85,
                "Symmetry and balance are only a small bonus, not a directional-layout gate");
    }

    @Test
    void middleAndOuterLayersAreMoreSuitableThanInnerAndCoreForCurrentSigns() {
        SpellGeometry geometry = geometry(0.75, 0.95, 0.97, 0.01, 1.0, 1.0, 1.0);
        CompiledSign core = sign(
                "column", ORIENTATION_SEMANTIC, 0.50, 0.50, 0.0, CLEAN_METRICS);
        CompiledSign inner = sign(
                "column", ORIENTATION_SEMANTIC, 0.64, 0.50, 0.0, CLEAN_METRICS);
        CompiledSign middle = sign(
                "column", ORIENTATION_SEMANTIC, 0.76, 0.50, 0.0, CLEAN_METRICS);
        CompiledSign outer = sign(
                "column", ORIENTATION_SEMANTIC, 0.82, 0.50, 0.0, CLEAN_METRICS);

        double coreScore = SpellQualityAnalyzer.layoutPrecision(
                geometry, List.of(), List.of(core));
        double innerScore = SpellQualityAnalyzer.layoutPrecision(
                geometry, List.of(), List.of(inner));
        double middleScore = SpellQualityAnalyzer.layoutPrecision(
                geometry, List.of(), List.of(middle));
        double outerScore = SpellQualityAnalyzer.layoutPrecision(
                geometry, List.of(), List.of(outer));

        assertTrue(innerScore > coreScore);
        assertTrue(middleScore > innerScore);
        assertTrue(outerScore > innerScore);
    }

    @Test
    void severeAccidentalOverlapLowersLayoutPrecision() {
        SpellGeometry geometry = geometry(0.75, 0.95, 0.97, 0.01, 1.0, 1.0, 1.0);
        List<CompiledSign> separated = List.of(
                sign("column", ORIENTATION_SEMANTIC, 0.78, 0.40, 0.0, CLEAN_METRICS),
                sign("column", ORIENTATION_SEMANTIC, 0.78, 0.60, 0.0, CLEAN_METRICS));
        CompiledSign samePlace = sign(
                "column", ORIENTATION_SEMANTIC, 0.80, 0.50, 0.0, CLEAN_METRICS);
        List<CompiledSign> overlapping = List.of(samePlace, samePlace);

        double separatedScore = SpellQualityAnalyzer.layoutPrecision(
                geometry, List.of(), separated);
        double overlappingScore = SpellQualityAnalyzer.layoutPrecision(
                geometry, List.of(), overlapping);

        assertTrue(separatedScore > overlappingScore + 0.15);
    }

    @Test
    void duplicateSignsRetainMultiplicityInPrecisionAverage() {
        SpellGeometry geometry = geometry(0.75, 0.95, 0.97, 0.01, 1.0, 1.0, 1.0);
        CompiledSign good = sign("column", ORIENTATION_SEMANTIC,
                0.80, 0.42, 0.0, CLEAN_METRICS);
        RecognitionQualityMetrics poorMetrics =
                new RecognitionQualityMetrics(0.30, 0.35, 0.60, 0.30);
        CompiledSign poor = sign("column", ORIENTATION_SEMANTIC,
                0.80, 0.58, 0.0, poorMetrics, 0.40);

        SpellQuality quality = analyze(geometry, List.of(sigil(CLEAN_METRICS, List.of(1))),
                List.of(good, good, poor));
        double expected = (
                2.0 * SpellQualityAnalyzer.symbolPrecision(good.confidence(), good.qualityMetrics())
                        + SpellQualityAnalyzer.symbolPrecision(
                                poor.confidence(), poor.qualityMetrics())) / 3.0;

        assertEquals(expected, quality.signPrecision(), 1.0e-12);
    }

    @Test
    void qualityTierBoundariesAreExact() {
        assertEquals(QualityTier.FLAWED,
                QualityTier.fromOverall(Math.nextDown(QualityTier.ROUGH_THRESHOLD)));
        assertEquals(QualityTier.ROUGH,
                QualityTier.fromOverall(QualityTier.ROUGH_THRESHOLD));
        assertEquals(QualityTier.SERVICEABLE,
                QualityTier.fromOverall(QualityTier.SERVICEABLE_THRESHOLD));
        assertEquals(QualityTier.REFINED,
                QualityTier.fromOverall(QualityTier.REFINED_THRESHOLD));
        assertEquals(QualityTier.MASTERWORK,
                QualityTier.fromOverall(QualityTier.MASTERWORK_THRESHOLD));
    }

    private static SpellQuality analyze(
            SpellGeometry geometry,
            List<CompiledSigil> sigils,
            List<CompiledSign> signs) {
        return SpellQualityAnalyzer.analyze(geometry, sigils, signs, List.of());
    }

    private static CompiledSigil sigil(
            RecognitionQualityMetrics metrics,
            List<Integer> sourceStrokeIndices) {
        return new CompiledSigil(
                id("fire"),
                "fire",
                "Fire",
                ElementType.FIRE,
                SigilSemantic.empty(),
                0.92,
                metrics,
                new Point(0.50, 0.50),
                new BoundingBox(0.45, 0.45, 0.55, 0.55),
                0.0,
                sourceStrokeIndices);
    }

    private static CompiledSign sign(
            String path,
            SignSemantic semantic,
            double x,
            double y,
            double orientation,
            RecognitionQualityMetrics metrics) {
        return sign(path, semantic, x, y, orientation, metrics, 0.90);
    }

    private static CompiledSign sign(
            String path,
            SignSemantic semantic,
            double x,
            double y,
            double orientation,
            RecognitionQualityMetrics metrics,
            double confidence) {
        double radialPosition = Math.hypot(x - 0.50, y - 0.50) / 0.40;
        return new CompiledSign(
                id(path),
                path,
                semantic,
                confidence,
                metrics,
                Math.toDegrees(Math.atan2(y - 0.50, x - 0.50)),
                orientation,
                radialPosition,
                SpellLayer.fromRadialPosition(radialPosition),
                new Point(x, y),
                new BoundingBox(x - 0.025, y - 0.025, x + 0.025, y + 0.025),
                List.of(2),
                false);
    }

    private static SpellGeometry geometry(
            double diameter,
            double completeness,
            double circularity,
            double normalizedRmse,
            double radialSymmetry,
            double bilateralSymmetry,
            double signBalance) {
        double radius = diameter / 2.0;
        return new SpellGeometry(
                new Point(0.50, 0.50),
                radius,
                Math.PI * radius * radius,
                diameter,
                completeness,
                circularity,
                normalizedRmse,
                new Point(0.0, 0.0),
                radialSymmetry,
                bilateralSymmetry,
                signBalance);
    }

    private static Identifier id(String path) {
        return Identifier.tryParse("wha-magic:" + path);
    }

    private static void assertUnitScores(SpellQuality quality) {
        for (double score : List.of(
                quality.overall(),
                quality.ringPrecision(),
                quality.sigilPrecision(),
                quality.signPrecision(),
                quality.layoutPrecision(),
                quality.inkCleanliness(),
                quality.stability())) {
            assertTrue(Double.isFinite(score));
            assertTrue(score >= 0.0 && score <= 1.0);
        }
    }
}
