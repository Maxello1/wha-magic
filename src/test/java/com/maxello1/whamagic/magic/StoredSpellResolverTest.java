package com.maxello1.whamagic.magic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maxello1.whamagic.parser.BoundingBox;
import com.maxello1.whamagic.parser.Point;
import com.maxello1.whamagic.parser.SpellDictionary;
import com.maxello1.whamagic.parser.SpellParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoredSpellResolverTest {
    private static final Identifier EARTH = Identifier.parse("earth");
    private static final Identifier WIND = Identifier.parse("wind-directs-air");
    private static final Identifier FIRE = Identifier.parse("fire");
    private static final Identifier LEVITATION = Identifier.parse("levitation");
    private static final Identifier CONVERGENCE = Identifier.parse("convergence");

    private static final SigilSemantic EARTH_SEMANTIC =
            new SigilSemantic(0.75, 0.60, -0.10, 0.45, 0.25);
    private static final SigilSemantic WIND_SEMANTIC =
            new SigilSemantic(0.35, 0.25, 0.40, 0.55, 0.15);
    private static final SignSemantic LEVITATION_SEMANTIC =
            new SignSemantic("levitation", "orientation", 0.25, 0.30, 0.15, 0.50, 0.10);
    private static final SignSemantic CONVERGENCE_SEMANTIC =
            new SignSemantic("convergence", "inward", 0.05, 0.70, -0.25, 0.10, 0.20);

    private static final Point RING_CENTER = new Point(0.50, 0.50);
    private static final double RING_RADIUS = 0.40;
    private static final List<List<Point>> STROKES = List.of(
            List.of(new Point(0.10, 0.20), new Point(0.30, 0.40)),
            List.of(new Point(0.50, 0.60), new Point(0.70, 0.80)));
    private static final List<List<Point>> OTHER_STROKES = List.of(
            List.of(new Point(0.10, 0.20), new Point(0.30, 0.41)),
            List.of(new Point(0.50, 0.60), new Point(0.70, 0.80)));

    @BeforeAll
    static void loadDictionary() {
        SpellDictionary.ensureLoaded();
    }

    @Test
    void currentV4CacheDoesNotInvokeParser() {
        StoredSpell cached = StoredSpell.fromIr(authoritativeIr(), STROKES);
        AtomicInteger parseCalls = new AtomicInteger();

        StoredSpellResolver.Resolution resolution = StoredSpellResolver.resolve(
                cached, STROKES, strokes -> {
                    parseCalls.incrementAndGet();
                    throw new AssertionError("A current compiled spell must not be reparsed");
                });

        assertAll(
                () -> assertEquals(StoredSpell.FORMAT_VERSION, cached.formatVersion()),
                () -> assertEquals(0, parseCalls.get()),
                () -> assertFalse(resolution.reparsed()),
                () -> assertEquals(cached.toIr(), resolution.ir()),
                () -> assertNull(resolution.refreshedSpell()));
    }

    @Test
    void realParsedSpellProducesCurrentV4Cache() throws Exception {
        JsonObject fixture = JsonParser.parseString(Files.readString(Path.of(
                "src/test/resources/fixtures/canonical/multi/spell_light_complete.json")))
                .getAsJsonObject();
        List<List<Point>> strokes = new ArrayList<>();
        fixture.getAsJsonArray("strokes").forEach(strokeElement -> {
            List<Point> stroke = new ArrayList<>();
            strokeElement.getAsJsonArray().forEach(pointElement -> {
                JsonObject point = pointElement.getAsJsonObject();
                stroke.add(new Point(point.get("x").getAsDouble(), point.get("y").getAsDouble()));
            });
            strokes.add(List.copyOf(stroke));
        });

        SpellParser.ParseResult parsed = SpellParser.parse(strokes);
        StoredSpell stored = StoredSpell.fromIr(parsed.ir, strokes);

        assertAll(
                () -> assertTrue(parsed.isValidSpell()),
                () -> assertEquals(parsed.ir.compiledSigils(), stored.compiledSigils()),
                () -> assertEquals(parsed.ir.compiledSigns(), stored.compiledSigns()),
                () -> assertEquals(parsed.ir.geometry(), stored.geometry()),
                () -> assertTrue(stored.isCurrentFor(strokes)));
    }

    @Test
    void staleStrokeHashReparsesExactlyOnceAndRefreshesComponent() {
        assertSuccessfulRefresh(StoredSpell.fromIr(authoritativeIr(), OTHER_STROKES));
    }

    @Test
    void staleDictionaryHashReparsesExactlyOnceAndRefreshesComponent() {
        assertSuccessfulRefresh(withStringField(
                StoredSpell.fromIr(authoritativeIr(), STROKES),
                "dictionaryHash", "stale-dictionary-hash"));
    }

    @Test
    void staleDictionaryVersionReparsesExactlyOnceAndRefreshesComponent() {
        assertSuccessfulRefresh(withStringField(
                StoredSpell.fromIr(authoritativeIr(), STROKES),
                "dictionaryVersion", "obsolete-dictionary-version"));
    }

    @Test
    void staleRecognizerVersionReparsesExactlyOnceAndRefreshesComponent() {
        assertSuccessfulRefresh(withStringField(
                StoredSpell.fromIr(authoritativeIr(), STROKES),
                "recognizerVersion", "obsolete-recognizer"));
    }

    @Test
    void staleScalingFingerprintReparsesExactlyOnceAndRefreshesComponent() {
        assertSuccessfulRefresh(withStringField(
                StoredSpell.fromIr(authoritativeIr(), STROKES),
                "scalingSettingsFingerprint", "obsolete-scaling-settings"));
    }

    @Test
    void v2MigrationReparsesOnceThenUsesRefreshedV4Cache() {
        JsonObject v2Json = JsonParser.parseString("""
                {
                  "formatVersion": 2,
                  "state": "active",
                  "elements": ["earth"],
                  "signCounts": {"levitation": 2},
                  "sigilSemantic": {
                    "force": 0.5,
                    "focus": 0.2,
                    "spread": 0.1,
                    "range": 0.3,
                    "lifetimeBias": 0.0
                  },
                  "signSemantics": [{
                    "manifestation": "levitation",
                    "directionMode": "orientation",
                    "force": 0.2,
                    "focus": 0.1,
                    "spread": 0.1,
                    "range": 0.2,
                    "lifetimeBias": 0.1
                  }],
                  "displayName": "legacy earth",
                  "strokeHash": 12345,
                  "dictionaryVersion": "2",
                  "dictionaryHash": "legacy",
                  "recognizerVersion": "legacy",
                  "authoritySignature": ""
                }
                """).getAsJsonObject();
        StoredSpell v2 = StoredSpell.CODEC.parse(JsonOps.INSTANCE, v2Json)
                .result().orElseThrow(() -> new AssertionError("V2 must decode safely"));
        AtomicInteger parseCalls = new AtomicInteger();

        StoredSpellResolver.Resolution migrated = StoredSpellResolver.resolve(
                v2, STROKES, strokes -> {
                    parseCalls.incrementAndGet();
                    return new SpellParser.ParseResult(null, authoritativeIr());
                });
        StoredSpell refreshed = migrated.refreshedSpell();
        StoredSpellResolver.Resolution cached = StoredSpellResolver.resolve(
                refreshed, STROKES, strokes -> {
                    parseCalls.incrementAndGet();
                    throw new AssertionError("Refreshed V4 data must not parse again");
                });

        assertAll(
                () -> assertEquals(2, v2.formatVersion()),
                () -> assertTrue(v2.compiledSigils().isEmpty()),
                () -> assertTrue(v2.compiledSigns().isEmpty()),
                () -> assertNull(v2.geometry()),
                () -> assertTrue(migrated.reparsed()),
                () -> assertNotNull(refreshed),
                () -> assertEquals(StoredSpell.FORMAT_VERSION, refreshed.formatVersion()),
                () -> assertFalse(cached.reparsed()),
                () -> assertEquals(1, parseCalls.get(), "Migration must invoke parsing exactly once"));
    }

    @Test
    void v3MigrationReparsesOnceThenUsesRefreshedV4Cache() {
        JsonObject v3Json = currentJson();
        v3Json.addProperty("formatVersion", 3);
        v3Json.remove("quality");
        v3Json.remove("parameters");
        v3Json.remove("scalingSettingsFingerprint");
        v3Json.getAsJsonArray("compiledSigils").forEach(element ->
                element.getAsJsonObject().remove("qualityMetrics"));
        v3Json.getAsJsonArray("compiledSigns").forEach(element ->
                element.getAsJsonObject().remove("qualityMetrics"));

        StoredSpell v3 = StoredSpell.CODEC.parse(JsonOps.INSTANCE, v3Json)
                .result().orElseThrow(() -> new AssertionError("V3 must decode safely"));
        StoredSpell networkV3 = streamRoundTrip(v3);
        AtomicInteger parseCalls = new AtomicInteger();

        StoredSpellResolver.Resolution migrated = StoredSpellResolver.resolve(
                v3, STROKES, strokes -> {
                    parseCalls.incrementAndGet();
                    return new SpellParser.ParseResult(null, authoritativeIr());
                });
        StoredSpellResolver.Resolution cached = StoredSpellResolver.resolve(
                migrated.refreshedSpell(), STROKES, strokes -> {
                    parseCalls.incrementAndGet();
                    throw new AssertionError("Refreshed V4 data must not parse again");
                });

        assertAll(
                () -> assertEquals(3, v3.formatVersion()),
                () -> assertEquals(v3, networkV3),
                () -> assertEquals(
                        RecognitionQualityMetrics.NEUTRAL,
                        v3.compiledSigils().getFirst().qualityMetrics()),
                () -> assertEquals(SpellQuality.UNASSESSED, v3.quality()),
                () -> assertEquals(SpellParameters.NEUTRAL, v3.parameters()),
                () -> assertTrue(migrated.reparsed()),
                () -> assertNotNull(migrated.refreshedSpell()),
                () -> assertEquals(StoredSpell.FORMAT_VERSION,
                        migrated.refreshedSpell().formatVersion()),
                () -> assertFalse(cached.reparsed()),
                () -> assertEquals(1, parseCalls.get(),
                        "V3 migration must invoke parsing exactly once"));
    }

    @Test
    void persistentAndNetworkCodecsRoundTripAllCompiledFields() {
        StoredSpell expected = StoredSpell.fromIr(authoritativeIr(), STROKES);
        StoredSpell persistent = StoredSpell.CODEC.parse(
                        JsonOps.INSTANCE,
                        StoredSpell.CODEC.encodeStart(JsonOps.INSTANCE, expected)
                                .result().orElseThrow())
                .result().orElseThrow();

        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), RegistryAccess.EMPTY);
        StoredSpell network;
        try {
            StoredSpell.STREAM_CODEC.encode(buffer, expected);
            network = StoredSpell.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }

        assertAll(
                () -> assertEquals(expected, persistent),
                () -> assertEquals(expected, network),
                () -> assertEquals(2, persistent.compiledSigils().size()),
                () -> assertEquals(4, persistent.compiledSigns().size()),
                () -> assertEquals(expected.geometry(), persistent.geometry()),
                () -> assertEquals(expected.quality(), persistent.quality()),
                () -> assertEquals(expected.parameters(), persistent.parameters()),
                () -> assertEquals(
                        expected.scalingSettingsFingerprint(),
                        persistent.scalingSettingsFingerprint()),
                () -> assertEquals(
                        expected.compiledSigils().getFirst().qualityMetrics(),
                        persistent.compiledSigils().getFirst().qualityMetrics()),
                () -> assertEquals(expected.compiledSigns().get(0).sourceStrokeIndices(),
                        persistent.compiledSigns().get(0).sourceStrokeIndices()));
    }

    @Test
    void authoritySignatureCoversGeometryAnglesLayersAndRingQuality() {
        SpellIr baseIr = authoritativeIr();
        StoredSpell base = StoredSpell.fromIr(baseIr, STROKES);

        SpellGeometry centerChanged = geometryCopy(
                baseIr.geometry(), new Point(0.51, 0.50), null, null, null, null);
        SpellGeometry completenessChanged = geometryCopy(
                baseIr.geometry(), null, 0.89, null, null, null);
        SpellGeometry circularityChanged = geometryCopy(
                baseIr.geometry(), null, null, 0.91, null, null);
        SpellGeometry rmseChanged = geometryCopy(
                baseIr.geometry(), null, null, null, 0.027, null);
        CompiledSign first = baseIr.compiledSigns().getFirst();
        CompiledSign angleChanged = signCopy(
                first, first.angleAroundRing() + 5.0, null, null, null, null);
        CompiledSign layerChanged = signCopy(first, null, SpellLayer.OUTER, null, null, null);

        StoredSpell geometryVariant = StoredSpell.fromIr(withGeometry(baseIr, centerChanged), STROKES);
        StoredSpell completenessVariant = StoredSpell.fromIr(
                withGeometry(baseIr, completenessChanged), STROKES);
        StoredSpell circularityVariant = StoredSpell.fromIr(
                withGeometry(baseIr, circularityChanged), STROKES);
        StoredSpell rmseVariant = StoredSpell.fromIr(withGeometry(baseIr, rmseChanged), STROKES);
        StoredSpell angleVariant = StoredSpell.fromIr(
                withFirstSign(baseIr, angleChanged), STROKES);
        StoredSpell layerVariant = StoredSpell.fromIr(
                withFirstSign(baseIr, layerChanged), STROKES);

        assertAll(
                () -> assertNotEquals(base.authoritySignature(), geometryVariant.authoritySignature()),
                () -> assertNotEquals(base.authoritySignature(), angleVariant.authoritySignature()),
                () -> assertNotEquals(base.authoritySignature(), layerVariant.authoritySignature()),
                () -> assertNotEquals(base.authoritySignature(), completenessVariant.authoritySignature()),
                () -> assertNotEquals(base.authoritySignature(), circularityVariant.authoritySignature()),
                () -> assertNotEquals(base.authoritySignature(), rmseVariant.authoritySignature()),
                () -> assertFalse(withAuthoritySignature(
                        completenessVariant, base.authoritySignature()).isCurrentFor(STROKES)),
                () -> assertFalse(withAuthoritySignature(
                        angleVariant, base.authoritySignature()).isCurrentFor(STROKES)),
                () -> assertFalse(withAuthoritySignature(
                        layerVariant, base.authoritySignature()).isCurrentFor(STROKES)));
    }

    @Test
    void authoritySignatureCoversEveryQualityMetricAndDerivedParameter() {
        SpellIr baseIr = authoritativeIr();
        StoredSpell base = StoredSpell.fromIr(baseIr, STROKES);
        SpellQuality quality = baseIr.quality();
        SpellParameters parameters = baseIr.parameters();

        List<SpellQuality> qualityVariants = List.of(
                new SpellQuality(0.83, quality.ringPrecision(), quality.sigilPrecision(),
                        quality.signPrecision(), quality.layoutPrecision(),
                        quality.inkCleanliness(), quality.stability(), QualityTier.REFINED),
                new SpellQuality(quality.overall(), 0.91, quality.sigilPrecision(),
                        quality.signPrecision(), quality.layoutPrecision(),
                        quality.inkCleanliness(), quality.stability(), quality.tier()),
                new SpellQuality(quality.overall(), quality.ringPrecision(), 0.85,
                        quality.signPrecision(), quality.layoutPrecision(),
                        quality.inkCleanliness(), quality.stability(), quality.tier()),
                new SpellQuality(quality.overall(), quality.ringPrecision(),
                        quality.sigilPrecision(), 0.80, quality.layoutPrecision(),
                        quality.inkCleanliness(), quality.stability(), quality.tier()),
                new SpellQuality(quality.overall(), quality.ringPrecision(),
                        quality.sigilPrecision(), quality.signPrecision(), 0.81,
                        quality.inkCleanliness(), quality.stability(), quality.tier()),
                new SpellQuality(quality.overall(), quality.ringPrecision(),
                        quality.sigilPrecision(), quality.signPrecision(),
                        quality.layoutPrecision(), 0.94, quality.stability(), quality.tier()),
                new SpellQuality(quality.overall(), quality.ringPrecision(),
                        quality.sigilPrecision(), quality.signPrecision(),
                        quality.layoutPrecision(), quality.inkCleanliness(), 0.85, quality.tier()),
                new SpellQuality(quality.overall(), quality.ringPrecision(),
                        quality.sigilPrecision(), quality.signPrecision(),
                        quality.layoutPrecision(), quality.inkCleanliness(),
                        quality.stability(), QualityTier.SERVICEABLE));

        List<SpellParameters> parameterVariants = List.of(
                parametersCopy(parameters, "sizeScale"),
                parametersCopy(parameters, "sizeTier"),
                parametersCopy(parameters, "qualityEfficiency"),
                parametersCopy(parameters, "powerMultiplier"),
                parametersCopy(parameters, "rangeMultiplier"),
                parametersCopy(parameters, "radiusMultiplier"),
                parametersCopy(parameters, "durationMultiplier"),
                parametersCopy(parameters, "speedMultiplier"),
                parametersCopy(parameters, "forceMultiplier"),
                parametersCopy(parameters, "stability"));

        for (SpellQuality variant : qualityVariants) {
            assertNotEquals(
                    base.authoritySignature(),
                    StoredSpell.fromIr(withQuality(baseIr, variant), STROKES)
                            .authoritySignature());
        }
        for (SpellParameters variant : parameterVariants) {
            assertNotEquals(
                    base.authoritySignature(),
                    StoredSpell.fromIr(withParameters(baseIr, variant), STROKES)
                            .authoritySignature());
        }

        CompiledSigil firstSigil = baseIr.compiledSigils().getFirst();
        RecognitionQualityMetrics metrics = firstSigil.qualityMetrics();
        CompiledSigil metricsChanged = new CompiledSigil(
                firstSigil.semanticId(),
                firstSigil.matchedTemplateId(),
                firstSigil.displayName(),
                firstSigil.element(),
                firstSigil.semantic(),
                firstSigil.recognitionConfidence(),
                new RecognitionQualityMetrics(
                        metrics.templateCoverage() - 0.01,
                        metrics.candidateExplainedRatio(),
                        metrics.unexplainedInkRatio(),
                        metrics.structuralScore()),
                firstSigil.centroid(),
                firstSigil.bounds(),
                firstSigil.orientationDegrees(),
                firstSigil.sourceStrokeIndices());
        StoredSpell metricsVariant = StoredSpell.fromIr(
                withFirstSigil(baseIr, metricsChanged), STROKES);

        assertAll(
                () -> assertNotEquals(
                        base.authoritySignature(), metricsVariant.authoritySignature()),
                () -> assertFalse(withAuthoritySignature(
                        metricsVariant, base.authoritySignature()).isCurrentFor(STROKES)));
    }

    @Test
    void malformedAndNonFiniteGeometryOrSemanticsAreRejected() {
        SpellIr base = authoritativeIr();
        SpellGeometry nonFiniteGeometry = new SpellGeometry(
                base.geometry().ringCenter(),
                Double.NaN,
                base.geometry().ringArea(),
                base.geometry().normalizedRingDiameter(),
                base.geometry().ringCompleteness(),
                base.geometry().ringCircularity(),
                base.geometry().ringNormalizedRmse(),
                base.geometry().directionalBias(),
                base.geometry().radialSymmetryScore(),
                base.geometry().bilateralSymmetryScore(),
                base.geometry().signBalanceScore());
        SpellGeometry nonFiniteBias = geometryCopy(
                base.geometry(), null, null, null, null,
                new Point(Double.POSITIVE_INFINITY, 0.0));

        CompiledSigil sigil = base.compiledSigils().getFirst();
        CompiledSigil invalidSemanticSigil = new CompiledSigil(
                sigil.semanticId(), sigil.matchedTemplateId(), sigil.displayName(), sigil.element(),
                new SigilSemantic(Double.NaN, 0, 0, 0, 0), sigil.recognitionConfidence(),
                sigil.centroid(), sigil.bounds(), sigil.orientationDegrees(), sigil.sourceStrokeIndices());
        SpellIr invalidSemantic = new SpellIr(
                base.state(), base.warning(),
                replaceFirst(base.compiledSigils(), invalidSemanticSigil),
                base.compiledSigns(), base.geometry(), base.quality(), base.parameters(),
                base.displayName(), base.statusMessage());

        StoredSpell invalidRadius = StoredSpell.fromIr(withGeometry(base, nonFiniteGeometry), STROKES);
        StoredSpell invalidBias = StoredSpell.fromIr(withGeometry(base, nonFiniteBias), STROKES);
        StoredSpell invalidSemanticStored = StoredSpell.fromIr(invalidSemantic, STROKES);

        JsonObject encoded = currentJson();
        JsonObject invalidRange = encoded.deepCopy();
        invalidRange.getAsJsonObject("geometry").addProperty("ringRadius", 2.0);
        JsonObject invalidLayer = encoded.deepCopy();
        invalidLayer.getAsJsonArray("compiledSigns").get(0).getAsJsonObject()
                .addProperty("layer", "sideways");
        JsonObject invalidManifestation = encoded.deepCopy();
        invalidManifestation.getAsJsonArray("compiledSigns").get(0).getAsJsonObject()
                .getAsJsonObject("semantic").addProperty("manifestation", "");

        assertAll(
                () -> assertFalse(invalidRadius.isCurrentFor(STROKES)),
                () -> assertFalse(invalidBias.isCurrentFor(STROKES)),
                () -> assertFalse(invalidSemanticStored.isCurrentFor(STROKES)),
                () -> assertTrue(StoredSpell.CODEC.encodeStart(
                        JsonOps.INSTANCE, invalidRadius).error().isPresent()),
                () -> assertTrue(StoredSpell.CODEC.parse(
                        JsonOps.INSTANCE, invalidRange).error().isPresent()),
                () -> assertTrue(StoredSpell.CODEC.parse(
                        JsonOps.INSTANCE, invalidLayer).error().isPresent()),
                () -> assertTrue(StoredSpell.CODEC.parse(
                        JsonOps.INSTANCE, invalidManifestation).error().isPresent()));
    }

    @Test
    void malformedMissingOrNonFiniteQualityAndParametersAreRejected() {
        JsonObject encoded = currentJson();
        JsonObject missingQuality = encoded.deepCopy();
        missingQuality.remove("quality");
        JsonObject missingParameters = encoded.deepCopy();
        missingParameters.remove("parameters");
        JsonObject missingFingerprint = encoded.deepCopy();
        missingFingerprint.remove("scalingSettingsFingerprint");
        JsonObject missingMetrics = encoded.deepCopy();
        missingMetrics.getAsJsonArray("compiledSigils").get(0).getAsJsonObject()
                .remove("qualityMetrics");

        JsonObject nonFiniteQuality = encoded.deepCopy();
        nonFiniteQuality.getAsJsonObject("quality")
                .addProperty("overall", Double.NaN);
        JsonObject outOfRangeQuality = encoded.deepCopy();
        outOfRangeQuality.getAsJsonObject("quality")
                .addProperty("ringPrecision", 1.01);
        JsonObject mismatchedQualityTier = encoded.deepCopy();
        mismatchedQualityTier.getAsJsonObject("quality")
                .addProperty("tier", "flawed");
        JsonObject outOfRangeMetrics = encoded.deepCopy();
        outOfRangeMetrics.getAsJsonArray("compiledSigns").get(0).getAsJsonObject()
                .getAsJsonObject("qualityMetrics")
                .addProperty("candidateExplainedRatio", -0.01);

        JsonObject negativeParameter = encoded.deepCopy();
        negativeParameter.getAsJsonObject("parameters")
                .addProperty("rangeMultiplier", -0.01);
        JsonObject nonFiniteParameter = encoded.deepCopy();
        nonFiniteParameter.getAsJsonObject("parameters")
                .addProperty("powerMultiplier", Double.NaN);
        JsonObject parameterAboveSchemaCap = encoded.deepCopy();
        parameterAboveSchemaCap.getAsJsonObject("parameters")
                .addProperty("durationMultiplier", 16.01);
        JsonObject mismatchedSizeTier = encoded.deepCopy();
        mismatchedSizeTier.getAsJsonObject("parameters")
                .addProperty("sizeTier", "grand");
        JsonObject mismatchedStability = encoded.deepCopy();
        mismatchedStability.getAsJsonObject("parameters")
                .addProperty("stability", 0.25);

        assertAll(
                () -> assertCodecRejects(missingQuality),
                () -> assertCodecRejects(missingParameters),
                () -> assertCodecRejects(missingFingerprint),
                () -> assertCodecRejects(missingMetrics),
                () -> assertCodecRejects(nonFiniteQuality),
                () -> assertCodecRejects(outOfRangeQuality),
                () -> assertCodecRejects(mismatchedQualityTier),
                () -> assertCodecRejects(outOfRangeMetrics),
                () -> assertCodecRejects(negativeParameter),
                () -> assertCodecRejects(nonFiniteParameter),
                () -> assertCodecRejects(parameterAboveSchemaCap),
                () -> assertCodecRejects(mismatchedSizeTier),
                () -> assertCodecRejects(mismatchedStability));
    }

    @Test
    void streamCodecRejectsRawOutOfRangeMetricsBeforeRecordClamping() {
        StoredSpell expected = StoredSpell.fromIr(authoritativeIr(), STROKES);
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            StoredSpell.STREAM_CODEC.encode(buffer, expected);
            long encodedMetric = Double.doubleToLongBits(
                    expected.compiledSigils().getFirst().qualityMetrics().templateCoverage());
            int metricOffset = -1;
            for (int offset = 0; offset <= buffer.writerIndex() - Long.BYTES; offset++) {
                if (buffer.getLong(offset) == encodedMetric) {
                    metricOffset = offset;
                    break;
                }
            }
            assertTrue(metricOffset >= 0, "Expected metric must exist in encoded payload");
            buffer.setLong(metricOffset, Double.doubleToLongBits(2.0));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> StoredSpell.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void streamCodecRefusesToEncodeParametersAboveAbsoluteSchemaCaps() {
        StoredSpell valid = StoredSpell.fromIr(authoritativeIr(), STROKES);
        SpellParameters source = valid.parameters();
        SpellParameters invalidParameters = new SpellParameters(
                source.sizeScale(),
                source.sizeTier(),
                source.qualityEfficiency(),
                source.powerMultiplier(),
                source.rangeMultiplier(),
                source.radiusMultiplier(),
                16.01,
                source.speedMultiplier(),
                source.forceMultiplier(),
                source.stability());
        StoredSpell invalid = new StoredSpell(
                valid.formatVersion(),
                valid.state(),
                valid.compiledSigils(),
                valid.compiledSigns(),
                valid.geometry(),
                valid.quality(),
                invalidParameters,
                valid.displayName(),
                valid.strokeHash(),
                valid.dictionaryVersion(),
                valid.dictionaryHash(),
                valid.recognizerVersion(),
                valid.scalingSettingsFingerprint(),
                valid.authoritySignature());
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> StoredSpell.STREAM_CODEC.encode(buffer, invalid));
        } finally {
            buffer.release();
        }
    }

    @Test
    void mismatchedRadialLayerIsRejectedEvenWithFreshSignature() {
        SpellIr base = authoritativeIr();
        CompiledSign first = base.compiledSigns().getFirst();
        CompiledSign mismatched = signCopy(first, null, SpellLayer.CORE, null, null, null);
        StoredSpell stored = StoredSpell.fromIr(withFirstSign(base, mismatched), STROKES);

        assertFalse(stored.isCurrentFor(STROKES));
    }

    @Test
    void invalidStaleParseResultIsRejectedAndNotCached() {
        StoredSpell stale = StoredSpell.fromIr(authoritativeIr(), OTHER_STROKES);
        AtomicInteger parseCalls = new AtomicInteger();
        SpellIr invalid = new SpellIr(
                SpellState.INVALID,
                GlyphWarning.MISSING_RING,
                List.of(),
                List.of(),
                null,
                "",
                "Missing ring");

        StoredSpellResolver.Resolution resolution = StoredSpellResolver.resolve(
                stale, STROKES, strokes -> {
                    parseCalls.incrementAndGet();
                    assertEquals(STROKES, strokes);
                    return new SpellParser.ParseResult(null, invalid);
                });

        assertAll(
                () -> assertEquals(1, parseCalls.get()),
                () -> assertTrue(resolution.reparsed()),
                () -> assertNull(resolution.ir()),
                () -> assertNull(resolution.refreshedSpell()));
    }

    @Test
    void malformedLegacySignIdsDoNotPreventSafeDecode() {
        JsonObject legacy = JsonParser.parseString("""
                {
                  "state": "active",
                  "element": "earth",
                  "signCounts": {
                    "levitation": 3,
                    "not a valid identifier!": 7
                  },
                  "displayName": "legacy earth",
                  "strokeHash": 12345
                }
                """).getAsJsonObject();

        StoredSpell migrated = StoredSpell.CODEC.parse(JsonOps.INSTANCE, legacy)
                .result().orElseThrow(() -> new AssertionError("Legacy component must decode safely"));

        assertAll(
                () -> assertTrue(migrated.formatVersion() < StoredSpell.FORMAT_VERSION),
                () -> assertEquals(SpellState.ACTIVE, migrated.state()),
                () -> assertTrue(migrated.compiledSigils().isEmpty()),
                () -> assertTrue(migrated.compiledSigns().isEmpty()),
                () -> assertEquals("legacy earth", migrated.displayName()),
                () -> assertFalse(migrated.isCurrentFor(STROKES)));
    }

    @Test
    void strokeHashIncludesStrokeBoundaries() {
        Point a = new Point(0.10, 0.20);
        Point b = new Point(0.30, 0.40);
        Point c = new Point(0.50, 0.60);
        Point d = new Point(0.70, 0.80);
        List<List<Point>> split = List.of(List.of(a, b), List.of(c, d));
        List<List<Point>> joined = List.of(List.of(a, b, c, d));

        int splitHash = StoredSpell.computeStrokeHash(split);
        int joinedHash = StoredSpell.computeStrokeHash(joined);

        assertAll(
                () -> assertNotEquals(splitHash, joinedHash),
                () -> assertEquals(splitHash, StoredSpell.computeStrokeHash(split)),
                () -> assertEquals(joinedHash, StoredSpell.computeStrokeHash(joined)));
    }

    private static void assertSuccessfulRefresh(StoredSpell stale) {
        assertFalse(stale.isCurrentFor(STROKES), "Test precondition: component must be stale");
        SpellIr reparsedIr = reparsedIr();
        AtomicInteger parseCalls = new AtomicInteger();

        StoredSpellResolver.Resolution resolution = StoredSpellResolver.resolve(
                stale, STROKES, strokes -> {
                    parseCalls.incrementAndGet();
                    assertEquals(STROKES, strokes);
                    return new SpellParser.ParseResult(null, reparsedIr);
                });

        StoredSpell refreshed = resolution.refreshedSpell();
        assertAll(
                () -> assertEquals(1, parseCalls.get()),
                () -> assertTrue(resolution.reparsed()),
                () -> assertEquals(reparsedIr, resolution.ir()),
                () -> assertNotNull(refreshed),
                () -> assertTrue(refreshed.isCurrentFor(STROKES)),
                () -> assertEquals(reparsedIr, refreshed.toIr()));
    }

    private static StoredSpell withStringField(StoredSpell spell, String field, String value) {
        JsonObject encoded = StoredSpell.CODEC.encodeStart(JsonOps.INSTANCE, spell)
                .result().orElseThrow().getAsJsonObject();
        encoded.addProperty(field, value);
        return StoredSpell.CODEC.parse(JsonOps.INSTANCE, encoded)
                .result().orElseThrow();
    }

    private static StoredSpell withAuthoritySignature(StoredSpell spell, String signature) {
        return new StoredSpell(
                spell.formatVersion(), spell.state(), spell.compiledSigils(), spell.compiledSigns(),
                spell.geometry(), spell.quality(), spell.parameters(), spell.displayName(),
                spell.strokeHash(), spell.dictionaryVersion(), spell.dictionaryHash(),
                spell.recognizerVersion(), spell.scalingSettingsFingerprint(), signature);
    }

    private static SpellIr authoritativeIr() {
        List<CompiledSigil> sigils = List.of(
                compiledSigil(EARTH, "Earth", ElementType.EARTH, EARTH_SEMANTIC, 0),
                compiledSigil(
                        WIND, "Wind (Directs Air)", ElementType.WIND, WIND_SEMANTIC, 1));
        List<CompiledSign> signs = List.of(
                compiledSign(LEVITATION, LEVITATION_SEMANTIC, 180.0, 0.70, 180.0, 0),
                compiledSign(LEVITATION, LEVITATION_SEMANTIC, 180.0, 0.70, 180.0, 1),
                compiledSign(LEVITATION, LEVITATION_SEMANTIC, 180.0, 0.70, 180.0, 0),
                compiledSign(CONVERGENCE, CONVERGENCE_SEMANTIC, 0.0, 0.70, 0.0, 1));
        SpellQuality quality = quality();
        SpellParameters parameters = SpellParameterCalculator.calculate(
                sigils, signs, geometry(), quality, MagicScalingSettings.fromConfig());
        return new SpellIr(
                SpellState.ACTIVE, null, sigils, signs, geometry(), quality, parameters,
                "Earth + Wind [levitation x3, convergence x1]",
                "Active: Earth + Wind [levitation x3, convergence x1]");
    }

    private static SpellIr reparsedIr() {
        SigilSemantic fireSemantic = new SigilSemantic(0.90, 0.55, 0.05, 0.30, 0.40);
        List<CompiledSigil> sigils = List.of(
                compiledSigil(FIRE, "Fire", ElementType.FIRE, fireSemantic, 0));
        List<CompiledSign> signs = List.of(
                compiledSign(CONVERGENCE, CONVERGENCE_SEMANTIC, 90.0, 0.82, 90.0, 1),
                compiledSign(CONVERGENCE, CONVERGENCE_SEMANTIC, 270.0, 0.82, 270.0, 1));
        SpellQuality quality = quality();
        SpellParameters parameters = SpellParameterCalculator.calculate(
                sigils, signs, geometry(), quality, MagicScalingSettings.fromConfig());
        return new SpellIr(
                SpellState.ACTIVE, null, sigils, signs, geometry(), quality, parameters,
                "Fire [convergence x2]", "Active: Fire [convergence x2]");
    }

    private static CompiledSigil compiledSigil(
            Identifier id,
            String displayName,
            ElementType element,
            SigilSemantic semantic,
            int sourceIndex) {
        return new CompiledSigil(
                id, id.getPath(), displayName, element, semantic, 0.92,
                new RecognitionQualityMetrics(0.93, 0.91, 0.03, 0.89),
                new Point(0.5, 0.5), new BoundingBox(0.42, 0.42, 0.58, 0.58),
                0.0, List.of(sourceIndex));
    }

    private static CompiledSign compiledSign(
            Identifier id,
            SignSemantic semantic,
            double angle,
            double radialPosition,
            double orientation,
            int sourceIndex) {
        double radians = Math.toRadians(angle);
        double x = RING_CENTER.x + RING_RADIUS * radialPosition * Math.cos(radians);
        double y = RING_CENTER.y + RING_RADIUS * radialPosition * Math.sin(radians);
        return new CompiledSign(
                id, id.getPath(), semantic, 0.88,
                new RecognitionQualityMetrics(0.87, 0.90, 0.04, 0.86),
                angle, orientation, radialPosition,
                SpellLayer.fromRadialPosition(radialPosition),
                new Point(x, y), new BoundingBox(x - 0.02, y - 0.02, x + 0.02, y + 0.02),
                List.of(sourceIndex), false);
    }

    private static SpellGeometry geometry() {
        return new SpellGeometry(
                RING_CENTER,
                RING_RADIUS,
                Math.PI * RING_RADIUS * RING_RADIUS,
                RING_RADIUS * 2.0,
                0.94,
                0.98,
                0.012,
                new Point(0.0, 0.0),
                0.84,
                0.91,
                1.0);
    }

    private static SpellQuality quality() {
        return new SpellQuality(
                0.82,
                0.90,
                0.84,
                0.79,
                0.80,
                0.95,
                0.84,
                QualityTier.REFINED);
    }

    private static SpellIr withGeometry(SpellIr ir, SpellGeometry geometry) {
        return new SpellIr(
                ir.state(), ir.warning(), ir.compiledSigils(), ir.compiledSigns(), geometry,
                ir.quality(), ir.parameters(), ir.displayName(), ir.statusMessage());
    }

    private static SpellIr withFirstSign(SpellIr ir, CompiledSign replacement) {
        return new SpellIr(
                ir.state(), ir.warning(), ir.compiledSigils(),
                replaceFirst(ir.compiledSigns(), replacement), ir.geometry(),
                ir.quality(), ir.parameters(), ir.displayName(), ir.statusMessage());
    }

    private static SpellIr withFirstSigil(SpellIr ir, CompiledSigil replacement) {
        return new SpellIr(
                ir.state(), ir.warning(),
                replaceFirst(ir.compiledSigils(), replacement), ir.compiledSigns(),
                ir.geometry(), ir.quality(), ir.parameters(), ir.displayName(),
                ir.statusMessage());
    }

    private static SpellIr withQuality(SpellIr ir, SpellQuality quality) {
        return new SpellIr(
                ir.state(), ir.warning(), ir.compiledSigils(), ir.compiledSigns(),
                ir.geometry(), quality, ir.parameters(), ir.displayName(), ir.statusMessage());
    }

    private static SpellIr withParameters(SpellIr ir, SpellParameters parameters) {
        return new SpellIr(
                ir.state(), ir.warning(), ir.compiledSigils(), ir.compiledSigns(),
                ir.geometry(), ir.quality(), parameters, ir.displayName(), ir.statusMessage());
    }

    private static SpellParameters parametersCopy(
            SpellParameters source,
            String changedField) {
        double sizeScale = source.sizeScale();
        SealSizeTier sizeTier = source.sizeTier();
        double qualityEfficiency = source.qualityEfficiency();
        double power = source.powerMultiplier();
        double range = source.rangeMultiplier();
        double radius = source.radiusMultiplier();
        double duration = source.durationMultiplier();
        double speed = source.speedMultiplier();
        double force = source.forceMultiplier();
        double stability = source.stability();
        switch (changedField) {
            case "sizeScale" -> {
                sizeScale += 0.01;
                sizeTier = SealSizeTier.fromScale(sizeScale);
            }
            case "sizeTier" -> sizeTier = sizeTier == SealSizeTier.TINY
                    ? SealSizeTier.SMALL
                    : SealSizeTier.TINY;
            case "qualityEfficiency" -> qualityEfficiency += 0.01;
            case "powerMultiplier" -> power += 0.01;
            case "rangeMultiplier" -> range += 0.01;
            case "radiusMultiplier" -> radius += 0.01;
            case "durationMultiplier" -> duration += 0.01;
            case "speedMultiplier" -> speed += 0.01;
            case "forceMultiplier" -> force += 0.01;
            case "stability" -> stability += 0.01;
            default -> throw new IllegalArgumentException("Unknown parameter field: " + changedField);
        }
        return new SpellParameters(
                sizeScale,
                sizeTier,
                qualityEfficiency,
                power,
                range,
                radius,
                duration,
                speed,
                force,
                stability);
    }

    private static <T> List<T> replaceFirst(List<T> values, T replacement) {
        return java.util.stream.Stream.concat(
                java.util.stream.Stream.of(replacement), values.stream().skip(1)).toList();
    }

    private static SpellGeometry geometryCopy(
            SpellGeometry source,
            Point ringCenter,
            Double completeness,
            Double circularity,
            Double normalizedRmse,
            Point directionalBias) {
        return new SpellGeometry(
                ringCenter == null ? source.ringCenter() : ringCenter,
                source.ringRadius(),
                source.ringArea(),
                source.normalizedRingDiameter(),
                completeness == null ? source.ringCompleteness() : completeness,
                circularity == null ? source.ringCircularity() : circularity,
                normalizedRmse == null ? source.ringNormalizedRmse() : normalizedRmse,
                directionalBias == null ? source.directionalBias() : directionalBias,
                source.radialSymmetryScore(),
                source.bilateralSymmetryScore(),
                source.signBalanceScore());
    }

    private static CompiledSign signCopy(
            CompiledSign source,
            Double angle,
            SpellLayer layer,
            Double radialPosition,
            Point centroid,
            SignSemantic semantic) {
        return new CompiledSign(
                source.semanticId(), source.matchedTemplateId(),
                semantic == null ? source.semantic() : semantic,
                source.confidence(),
                source.qualityMetrics(),
                angle == null ? source.angleAroundRing() : angle,
                source.orientationDegrees(),
                radialPosition == null ? source.radialPosition() : radialPosition,
                layer == null ? source.layer() : layer,
                centroid == null ? source.centroid() : centroid,
                source.bounds(), source.sourceStrokeIndices(), source.reversed());
    }

    private static JsonObject currentJson() {
        return StoredSpell.CODEC.encodeStart(
                        JsonOps.INSTANCE, StoredSpell.fromIr(authoritativeIr(), STROKES))
                .result().orElseThrow().getAsJsonObject();
    }

    private static void assertCodecRejects(JsonObject payload) {
        assertTrue(
                StoredSpell.CODEC.parse(JsonOps.INSTANCE, payload).error().isPresent(),
                () -> "Expected malformed stored spell to be rejected: " + payload);
    }

    private static StoredSpell streamRoundTrip(StoredSpell spell) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            StoredSpell.STREAM_CODEC.encode(buffer, spell);
            return StoredSpell.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }
}
