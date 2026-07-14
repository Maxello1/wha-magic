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

        assertThrows(SpellDictionary.DictionaryLoadException.class,
                () -> SpellDictionary.reload(source(Map.of(
                        SpellDictionary.SIGILS_RESOURCE, oneTemplateArray(template("earth", "earth"))))));

        assertSame(before, SpellDictionary.snapshot());
        assertTrue(SpellDictionary.isLoaded());
        assertEquals(before.templateCount(), PointCloudRecognizer.INSTANCE.getTemplateCount());
        assertEquals(before.templateCount(), RasterRecognizer.getTemplateCount());
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
        SpellDictionary.reload(source(resources(sigils, new JsonArray())));
        customSnapshotInstalled = true;

        List<List<Point>> strokes = squareStrokes();
        var pointCloud = PointCloudRecognizer.INSTANCE.recognize(strokes, SymbolKind.SIGIL);
        var raster = RasterRecognizer.recognize(strokes, SymbolKind.SIGIL);

        assertTrue(pointCloud.recognized(), () -> pointCloud.toString());
        assertEquals("earth", pointCloud.id());
        assertEquals("earth_a", pointCloud.matchedTemplateId());
        assertEquals(List.of("earth"), pointCloud.alternatives().stream()
                .map(alternative -> alternative.id().getPath()).toList());
        assertEquals(List.of("earth"), raster.alternatives().stream()
                .map(alternative -> alternative.id().getPath()).toList());
        assertTrue(PointCloudRecognizer.computeQuality(strokes, pointCloud.matchedTemplateId()) > 0);
        assertEquals(2, SpellDictionary.snapshot().templateCount());
        assertThrows(UnsupportedOperationException.class,
                () -> SpellDictionary.snapshot().templates().clear());
    }

    @Test
    void repeatedReloadsProduceDeterministicMetadataAndRecognition() {
        SpellDictionary.reload();
        SpellDictionary.DictionarySnapshot first = SpellDictionary.snapshot();
        var firstResult = PointCloudRecognizer.INSTANCE.recognize(squareStrokes(), SymbolKind.SIGIL);

        SpellDictionary.reload();
        SpellDictionary.DictionarySnapshot second = SpellDictionary.snapshot();
        var secondResult = PointCloudRecognizer.INSTANCE.recognize(squareStrokes(), SymbolKind.SIGIL);

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

    private static JsonArray arrayOf(JsonObject template) {
        JsonArray array = new JsonArray();
        array.add(template);
        return array;
    }

    private static JsonObject template(String semanticId, String templateId) {
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
        strokeTemplate.add("strokes", GSON.toJsonTree(squareStrokes()));
        entry.add("strokeTemplate", strokeTemplate);
        return entry;
    }

    private static List<List<Point>> squareStrokes() {
        return List.of(List.of(
                new Point(0.1, 0.1), new Point(0.9, 0.1),
                new Point(0.9, 0.9), new Point(0.1, 0.9),
                new Point(0.1, 0.1)));
    }

    private static JsonObject point(double x, double y) {
        JsonObject point = new JsonObject();
        point.addProperty("x", x);
        point.addProperty("y", y);
        return point;
    }
}
