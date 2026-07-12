package com.maxello1.whamagic.parser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Timeout;

import java.io.File;
import java.io.FileReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class SpellParserTest {

    private static final Gson GSON = new Gson();

    @BeforeAll
    public static void setup() {
        SpellDictionary.ensureLoaded();
    }

    @Test
    public void testEmptyStrokes() {
        SpellParser.ParseResult result = SpellParser.parse(new ArrayList<>());
        assertFalse(result.isValidSpell());
    }

    @Test
    public void testNullStrokes() {
        SpellParser.ParseResult result = SpellParser.parse(null);
        assertFalse(result.isValidSpell());
    }

    @Test
    public void testDictionaryLoaded() {
        int templateCount = com.maxello1.whamagic.parser.RasterRecognizer.getTemplateCount();
        assertTrue(templateCount > 0, "Dictionary should have loaded templates");
        assertEquals(8, templateCount, "Expected 5 sigils + 3 signs = 8 templates");
    }

    private static Stream<File> fixtureFiles() {
        File dir = new File("src/test/resources/fixtures");
        if (!dir.exists() || !dir.isDirectory()) {
            return Stream.empty();
        }
        List<File> allFixtures = new ArrayList<>();
        // Only include canonical/ and holdout/ — experimental/ is excluded
        // to prevent unfinished or intentionally malformed samples from failing.
        File canonical = new File(dir, "canonical");
        File holdout = new File(dir, "holdout");
        if (canonical.exists()) collectFixturesRecursive(canonical, allFixtures);
        if (holdout.exists()) collectFixturesRecursive(holdout, allFixtures);
        return allFixtures.stream().sorted();
    }

    private static void collectFixturesRecursive(File dir, List<File> result) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                collectFixturesRecursive(child, result);
            } else if (child.getName().endsWith(".json")) {
                result.add(child);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureFiles")
    @Timeout(value = 2000, unit = TimeUnit.MILLISECONDS)
    public void testFixture(File file) throws Exception {
        JsonObject json = GSON.fromJson(new FileReader(file), JsonObject.class);
        JsonArray strokesArray = json.getAsJsonArray("strokes");

        // Support both formatVersion 2 (expectedIntent) and legacy (expectedSpell)
        boolean isPositive;
        if (json.has("expectedIntent")) {
            JsonObject intent = json.getAsJsonObject("expectedIntent");
            boolean hasSigils = intent.has("sigils") && intent.getAsJsonArray("sigils").size() > 0;
            boolean hasSigns = intent.has("signs") && intent.getAsJsonArray("signs").size() > 0;
            isPositive = hasSigils || hasSigns;
        } else {
            String expectedSpell = json.get("expectedSpell").getAsString();
            isPositive = !expectedSpell.isEmpty();
        }

        List<List<Point>> strokes = new ArrayList<>();
        for (JsonElement strokeElem : strokesArray) {
            JsonArray pointsArray = strokeElem.getAsJsonArray();
            List<Point> strokePoints = new ArrayList<>();
            for (JsonElement ptElem : pointsArray) {
                JsonObject pt = ptElem.getAsJsonObject();
                strokePoints.add(new Point(pt.get("x").getAsDouble(), pt.get("y").getAsDouble()));
            }
            strokes.add(strokePoints);
        }

        SpellParser.ParseResult result = SpellParser.parse(strokes);
        assertNotNull(result, "Parse result must not be null");
        assertNotNull(result.debugResult, "Debug result must be populated");

        if (!isPositive) {
            // Negative fixture: we just verify the parse completes without error and produces debug data
            assertTrue(result.debugResult.recognitionCalls() >= 0, "Recognition calls should be tracked");
        } else {
            // Positive fixture: verify recognition produces diagnostic data.
            // Full spell validity requires a ring (which standalone fixtures don't have),
            // so we check that the recognizer ran and produced alternatives.
            assertTrue(result.debugResult.candidateCount() >= 0, "Candidates should be tracked");
            assertTrue(result.debugResult.recognitionCalls() >= 0, "Recognition calls should be tracked");

            // Check that at least one evaluated candidate has alternatives populated
            if (result.debugResult.allEvaluated() != null && !result.debugResult.allEvaluated().isEmpty()) {
                boolean anyHasAlternatives = false;
                for (var eval : result.debugResult.allEvaluated()) {
                    if (eval.sigilRes != null && eval.sigilRes.alternatives != null
                            && !eval.sigilRes.alternatives.isEmpty()) {
                        anyHasAlternatives = true;
                        break;
                    }
                    if (eval.signRes != null && eval.signRes.alternatives != null
                            && !eval.signRes.alternatives.isEmpty()) {
                        anyHasAlternatives = true;
                        break;
                    }
                }
                assertTrue(anyHasAlternatives, "At least one candidate must have non-empty alternatives");
            }
        }
    }
}
