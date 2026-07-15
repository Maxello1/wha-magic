package com.maxello1.whamagic.parser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.maxello1.whamagic.magic.SymbolKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class SpellDictionaryTest {
    private static final Gson GSON = new Gson();
    private boolean customSnapshotInstalled;

    @BeforeAll
    static void loadDefaultDictionary() {
        SpellDictionary.ensureLoaded();
    }

    @AfterEach
    void restoreDefaultDictionary() {
        if (customSnapshotInstalled) {
            SpellDictionary.reload();
        }
    }

    @Test
    void failedReloadPreservesPreviousSnapshotAndLoadedState() {
        SpellDictionary.DictionarySnapshot before = SpellDictionary.snapshot();
        PointCloudRecognizer.PointCloudIndex beforeIndex = SpellDictionary.pointCloudIndex();
        List<PointCloudRecognizer.SemanticTemplateGroup> beforeSigilGroups =
                beforeIndex.groups(SymbolKind.SIGIL);
        PointCloudRecognizer.SemanticTemplateGroup beforeFirstGroup =
                beforeSigilGroups.getFirst();
        PointCloudRecognizer.PointCloudTemplate beforeEarthVariant =
                beforeIndex.variant("earth");

        assertThrows(SpellDictionary.DictionaryLoadException.class,
                () -> SpellDictionary.reload(source(Map.of(
                        SpellDictionary.SIGILS_RESOURCE, oneTemplateArray(template("earth", "earth"))))));

        assertSame(before, SpellDictionary.snapshot());
        assertSame(beforeIndex, SpellDictionary.pointCloudIndex());
        assertSame(beforeSigilGroups,
                SpellDictionary.pointCloudIndex().groups(SymbolKind.SIGIL));
        assertSame(beforeFirstGroup,
                SpellDictionary.pointCloudIndex().groups(SymbolKind.SIGIL).getFirst());
        assertSame(beforeEarthVariant,
                SpellDictionary.pointCloudIndex().variant("earth"));
        assertTrue(SpellDictionary.isLoaded());
        assertEquals(before.templateCount(), SpellDictionary.snapshot().templateCount());
    }

    @Test
    void duplicateTemplateIdsAreRejectedWithoutPublishingPartialState() {
        SpellDictionary.DictionarySnapshot before = SpellDictionary.snapshot();
        JsonArray sigils = new JsonArray();
        sigils.add(template("earth", "earth_variant"));
        sigils.add(template("earth", "earth_variant"));

        SpellDictionary.DictionaryLoadException exception = assertThrows(
                SpellDictionary.DictionaryLoadException.class,
                () -> SpellDictionary.reload(source(resources(sigils, new JsonArray()))));

        assertTrue(exception.getMessage().contains("Duplicate template ID"));
        assertSame(before, SpellDictionary.snapshot());
    }

    @Test
    void namespacedAliasesCannotBypassDuplicateIdValidation() {
        JsonArray sigils = new JsonArray();
        sigils.add(template("earth", "earth"));
        sigils.add(template("earth", "minecraft:earth"));

        assertThrows(SpellDictionary.DictionaryLoadException.class,
                () -> SpellDictionary.reload(source(resources(sigils, new JsonArray()))));
    }

    @Test
    void malformedAndNonFiniteCoordinatesAreRejected() {
        String nonFinite = """
                [{
                  "id":"earth","displayName":"Earth","element":"earth",
                  "semantic":{"force":0.1,"focus":0.1,"spread":0.0,"range":0.0,"lifetimeBias":0.0},
                  "strokeTemplate":{"strokes":[[{"x":1e999,"y":0.1},{"x":0.9,"y":0.9}]]}
                }]
                """;

        assertThrows(SpellDictionary.DictionaryLoadException.class,
                () -> SpellDictionary.reload(source(Map.of(
                        SpellDictionary.SIGILS_RESOURCE, nonFinite,
                        SpellDictionary.SIGNS_RESOURCE, "[]"))));
    }

    @Test
    void invalidSemanticsAreRejected() {
        JsonObject invalid = template("earth", "earth");
        invalid.getAsJsonObject("semantic").remove("force");

        assertThrows(SpellDictionary.DictionaryLoadException.class,
                () -> SpellDictionary.reload(source(resources(
                        arrayOf(invalid), new JsonArray()))));
    }

    @Test
    void missingRequiredFieldsAndResourcesAreRejected() {
        JsonObject missingDisplayName = template("earth", "earth");
        missingDisplayName.remove("displayName");

        assertThrows(SpellDictionary.DictionaryLoadException.class,
                () -> SpellDictionary.reload(source(resources(
                        arrayOf(missingDisplayName), new JsonArray()))));
        assertThrows(SpellDictionary.DictionaryLoadException.class,
                () -> SpellDictionary.reload(path -> null));
    }

    @Test
    void unsupportedTemplateComplexityIsRejected() {
        JsonObject template = template("earth", "earth");
        JsonArray strokes = new JsonArray();
        for (int index = 0; index < 17; index++) {
            JsonArray stroke = new JsonArray();
            stroke.add(point(0.1, index / 20.0));
            stroke.add(point(0.9, index / 20.0));
            strokes.add(stroke);
        }
        template.getAsJsonObject("strokeTemplate").add("strokes", strokes);

        assertThrows(SpellDictionary.DictionaryLoadException.class,
                () -> SpellDictionary.reload(source(resources(
                        arrayOf(template), new JsonArray()))));
    }

    @Test
    void visualVariantsDeduplicateToOneSemanticAlternative() {
        JsonArray sigils = new JsonArray();
        sigils.add(template("earth", "earth_a"));
        sigils.add(template("earth", "earth_b"));
        installCustomDictionary(sigils);

        List<List<Point>> strokes = squareStrokes();
        var pointCloud = PointCloudRecognizer.recognizeStatic(strokes, SymbolKind.SIGIL);

        assertTrue(pointCloud.recognized(), () -> pointCloud.toString());
        assertEquals("earth", pointCloud.id());
        assertEquals("earth_a", pointCloud.matchedTemplateId());
        assertEquals(List.of("earth"), pointCloud.alternatives().stream()
                .map(alternative -> alternative.id().getPath()).toList());
        assertTrue(PointCloudRecognizer.computeQuality(strokes, pointCloud.matchedTemplateId()) > 0);
        assertEquals(2, SpellDictionary.snapshot().templateCount());
        assertThrows(UnsupportedOperationException.class,
                () -> SpellDictionary.snapshot().templates().clear());
    }

    @Test
    void variantsOfBestSemanticDoNotShrinkConfidenceGap() {
        JsonArray sigils = new JsonArray();
        sigils.add(template("earth", "earth_a", squareStrokes()));
        sigils.add(template("earth", "earth_b", squareStrokes()));
        sigils.add(template("wind", "wind_triangle", triangleStrokes()));
        installCustomDictionary(sigils);

        var result = PointCloudRecognizer.recognizeStatic(
                squareStrokes(), SymbolKind.SIGIL);

        assertEquals("earth", result.id());
        assertEquals("earth_a", result.matchedTemplateId());
        assertEquals(List.of("earth", "wind"), result.alternatives().stream()
                .map(alternative -> alternative.id().getPath()).toList());
        assertTrue(result.confidenceGap() > 0.0,
                "An identical visual variant must not become the second semantic match");
        assertEquals(
                result.score() - result.alternatives().get(1).rawScore(),
                result.confidenceGap(),
                1.0e-12);
    }

    @Test
    void recognitionReportsTheBestVisualVariant() {
        JsonArray sigils = new JsonArray();
        sigils.add(template("earth", "earth_a_triangle", triangleStrokes()));
        sigils.add(template("earth", "earth_z_square", squareStrokes()));
        installCustomDictionary(sigils);

        var result = PointCloudRecognizer.recognizeStatic(
                squareStrokes(), SymbolKind.SIGIL);

        assertTrue(result.recognized(), () -> result.toString());
        assertEquals("earth", result.id());
        assertEquals("earth_z_square", result.matchedTemplateId());
    }

    @Test
    void alternativesContainAtMostOneEntryPerSemanticSymbol() {
        JsonArray sigils = new JsonArray();
        sigils.add(template("earth", "earth_a", squareStrokes()));
        sigils.add(template("earth", "earth_b", squareStrokes()));
        sigils.add(template("wind", "wind_a", triangleStrokes()));
        sigils.add(template("wind", "wind_b", triangleStrokes()));
        sigils.add(template("water", "water", diamondStrokes()));
        installCustomDictionary(sigils);

        var result = PointCloudRecognizer.recognizeStatic(
                squareStrokes(), SymbolKind.SIGIL);
        List<String> alternativeIds = result.alternatives().stream()
                .map(alternative -> alternative.id().getPath())
                .toList();

        assertEquals(3, alternativeIds.size());
        assertEquals(Set.of("earth", "wind", "water"), Set.copyOf(alternativeIds));
    }

    @Test
    void equalScoreSemanticTiesUseDeterministicIdentifierOrder() {
        JsonArray sigils = new JsonArray();
        sigils.add(template("zeta", "zeta_template", squareStrokes()));
        sigils.add(template("alpha", "alpha_template", squareStrokes()));
        installCustomDictionary(sigils);

        for (int attempt = 0; attempt < 3; attempt++) {
            var result = PointCloudRecognizer.recognizeStatic(
                    squareStrokes(), SymbolKind.SIGIL);

            assertEquals("alpha", result.id());
            assertEquals("alpha_template", result.matchedTemplateId());
            assertEquals(List.of("alpha", "zeta"), result.alternatives().stream()
                    .map(alternative -> alternative.id().getPath()).toList());
            assertEquals(
                    result.alternatives().get(0).rawScore(),
                    result.alternatives().get(1).rawScore(),
                    1.0e-12);
        }
    }

    @Test
    void successfulReloadReplacesEveryPointCloudIndexObjectTogether() {
        JsonArray firstSigils = new JsonArray();
        firstSigils.add(template("earth", "shared_template", squareStrokes()));
        installCustomDictionary(firstSigils);

        SpellDictionary.DictionarySnapshot beforeSnapshot = SpellDictionary.snapshot();
        PointCloudRecognizer.PointCloudIndex beforeIndex = SpellDictionary.pointCloudIndex();
        List<PointCloudRecognizer.SemanticTemplateGroup> beforeGroups =
                beforeIndex.groups(SymbolKind.SIGIL);
        PointCloudRecognizer.SemanticTemplateGroup beforeGroup = beforeGroups.getFirst();
        PointCloudRecognizer.PointCloudTemplate beforeVariant =
                beforeIndex.variant("shared_template");

        JsonArray secondSigils = new JsonArray();
        secondSigils.add(template("earth", "shared_template", triangleStrokes()));
        installCustomDictionary(secondSigils);

        SpellDictionary.DictionarySnapshot afterSnapshot = SpellDictionary.snapshot();
        PointCloudRecognizer.PointCloudIndex afterIndex = SpellDictionary.pointCloudIndex();
        List<PointCloudRecognizer.SemanticTemplateGroup> afterGroups =
                afterIndex.groups(SymbolKind.SIGIL);
        PointCloudRecognizer.SemanticTemplateGroup afterGroup = afterGroups.getFirst();
        PointCloudRecognizer.PointCloudTemplate afterVariant =
                afterIndex.variant("shared_template");

        assertNotSame(beforeSnapshot, afterSnapshot);
        assertNotEquals(beforeSnapshot.hash(), afterSnapshot.hash());
        assertNotSame(beforeIndex, afterIndex);
        assertNotSame(beforeGroups, afterGroups);
        assertNotSame(beforeGroup, afterGroup);
        assertNotSame(beforeVariant, afterVariant);
        assertSame(beforeVariant, beforeIndex.variant("shared_template"));
        assertSame(afterVariant, afterIndex.variant("shared_template"));
    }

    @Test
    void repeatedReloadsProduceDeterministicMetadataAndRecognition() {
        SpellDictionary.reload();
        SpellDictionary.DictionarySnapshot first = SpellDictionary.snapshot();
        var firstResult = PointCloudRecognizer.recognizeStatic(squareStrokes(), SymbolKind.SIGIL);

        SpellDictionary.reload();
        SpellDictionary.DictionarySnapshot second = SpellDictionary.snapshot();
        var secondResult = PointCloudRecognizer.recognizeStatic(squareStrokes(), SymbolKind.SIGIL);

        assertEquals(first, second);
        assertEquals(firstResult.id(), secondResult.id());
        assertEquals(firstResult.matchedTemplateId(), secondResult.matchedTemplateId());
        assertEquals(firstResult.alternatives(), secondResult.alternatives());
    }

    @Test
    void defaultDictionaryContainsOnlyTheApprovedPlayerTrainingVariants() {
        SpellDictionary.reload();
        SpellDictionary.DictionarySnapshot snapshot = SpellDictionary.snapshot();

        Set<String> playerVariants = snapshot.templates().stream()
                .map(SpellDictionary.TemplateIdentity::templateId)
                .filter(templateId -> templateId.contains("-player-"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        assertEquals(Set.of(
                "column-player-20260714-a",
                "earth-player-20260714-a",
                "convergence-player-20260714-a"), playerVariants);
        assertEquals("2", snapshot.version());
        assertEquals(8, snapshot.templates().stream()
                .map(SpellDictionary.TemplateIdentity::semanticId)
                .distinct()
                .count());
    }

    private static SpellDictionary.ResourceSource source(Map<String, String> resources) {
        return path -> {
            String value = resources.get(path);
            return value == null ? null : new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        };
    }

    private static Map<String, String> resources(JsonArray sigils, JsonArray signs) {
        return Map.of(
                SpellDictionary.SIGILS_RESOURCE, GSON.toJson(sigils),
                SpellDictionary.SIGNS_RESOURCE, GSON.toJson(signs));
    }

    private static String oneTemplateArray(JsonObject template) {
        return GSON.toJson(arrayOf(template));
    }

    private void installCustomDictionary(JsonArray sigils) {
        SpellDictionary.reload(source(resources(sigils, new JsonArray())));
        customSnapshotInstalled = true;
    }

    private static JsonArray arrayOf(JsonObject template) {
        JsonArray array = new JsonArray();
        array.add(template);
        return array;
    }

    private static JsonObject template(String semanticId, String templateId) {
        return template(semanticId, templateId, squareStrokes());
    }

    private static JsonObject template(
            String semanticId,
            String templateId,
            List<List<Point>> strokes) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", semanticId);
        entry.addProperty("templateId", templateId);
        entry.addProperty("displayName", "Earth");
        entry.addProperty("element", "earth");
        JsonObject semantic = new JsonObject();
        semantic.addProperty("force", 0.1);
        semantic.addProperty("focus", 0.1);
        semantic.addProperty("spread", 0.0);
        semantic.addProperty("range", 0.0);
        semantic.addProperty("lifetimeBias", 0.0);
        entry.add("semantic", semantic);
        JsonObject strokeTemplate = new JsonObject();
        strokeTemplate.add("strokes", GSON.toJsonTree(strokes));
        entry.add("strokeTemplate", strokeTemplate);
        return entry;
    }

    private static List<List<Point>> squareStrokes() {
        return List.of(List.of(
                new Point(0.1, 0.1), new Point(0.9, 0.1),
                new Point(0.9, 0.9), new Point(0.1, 0.9),
                new Point(0.1, 0.1)));
    }

    private static List<List<Point>> triangleStrokes() {
        return List.of(List.of(
                new Point(0.5, 0.1), new Point(0.9, 0.9),
                new Point(0.1, 0.9), new Point(0.5, 0.1)));
    }

    private static List<List<Point>> diamondStrokes() {
        return List.of(List.of(
                new Point(0.5, 0.05), new Point(0.95, 0.5),
                new Point(0.5, 0.95), new Point(0.05, 0.5),
                new Point(0.5, 0.05)));
    }

    private static JsonObject point(double x, double y) {
        JsonObject point = new JsonObject();
        point.addProperty("x", x);
        point.addProperty("y", y);
        return point;
    }
}
