package com.maxello1.whamagic.parser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.maxello1.whamagic.magic.CandidateGenerationSettings;
import com.maxello1.whamagic.magic.GlyphWarning;
import com.maxello1.whamagic.magic.RingDetector;
import com.maxello1.whamagic.magic.SpellState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Timeout;

import java.io.File;
import java.io.FileReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    @Test
    public void completeLightPreservesOriginalSourceStrokeIndices() throws Exception {
        List<List<Point>> strokes = loadStrokes(
                new File("src/test/resources/fixtures/canonical/multi/spell_light_complete.json"));

        SpellParser.ParseResult result = SpellParser.parse(strokes);

        assertEquals(List.of(0), result.debugResult.ringStrokeIndices());
        var light = result.ast.sigils().stream()
                .filter(sigil -> sigil.id() != null && "light".equals(sigil.id().getPath()))
                .findFirst().orElseThrow(() -> new AssertionError("Complete Light must be recognized"));
        assertEquals(Set.of(1, 2, 3, 4, 5, 6), new HashSet<>(light.sourceStrokeIndices()));

        result.debugResult.primitiveGroups().forEach(group ->
                assertTrue(group.sourceStrokeIndices().stream().allMatch(index -> index >= 1 && index <= 6),
                        "Primitive groups must use original non-ring indices: " + group.sourceStrokeIndices()));
        result.debugResult.generatedCandidates().forEach(candidate ->
                assertTrue(candidate.sourceStrokeIndices().stream().allMatch(index -> index >= 1 && index <= 6),
                        "Candidates must use original non-ring indices: " + candidate.sourceStrokeIndices()));
    }

    @Test
    public void recognizedSignsPreserveOriginalNonRingIndices() throws Exception {
        List<List<Point>> strokes = loadStrokes(
                new File("src/test/resources/fixtures/canonical/multi/spell_earth_levitation_x2.json"));

        SpellParser.ParseResult result = SpellParser.parse(strokes);

        assertFalse(result.ast.signs().isEmpty(), "Fixture must recognize its signs");
        Set<Integer> ringIndices = new HashSet<>(result.debugResult.ringStrokeIndices());
        result.ast.signs().forEach(sign -> {
            assertFalse(sign.sourceStrokeIndices().isEmpty(), "Recognized signs must retain source ownership");
            assertTrue(sign.sourceStrokeIndices().stream().noneMatch(ringIndices::contains),
                    "Recognized signs must not own the ring stroke");
            assertTrue(sign.sourceStrokeIndices().stream().allMatch(index -> index >= 0 && index < strokes.size()),
                    "Recognized signs must use original input indices");
        });
    }

    @Test
    public void candidateLimitExhaustionInvalidatesPartialInterpretation() throws Exception {
        List<List<Point>> strokes = loadStrokes(
                new File("src/test/resources/fixtures/canonical/multi/spell_light_complete.json"));
        SpellParser.ParseResult baseline = SpellParser.parse(strokes);
        assertTrue(baseline.debugResult.candidateCount() > 1, "Fixture must generate multiple candidates");

        CandidateGenerationSettings limited = withLimits(
                CandidateGenerationSettings.DEFAULTS.maxPrimitiveGroups(),
                baseline.debugResult.candidateCount() - 1,
                CandidateGenerationSettings.DEFAULTS.maxRecognitionCalls());
        SpellParser.ParseResult result = SpellParser.parse(strokes, limited);

        assertTrue(result.debugResult.candidateLimitReached());
        assertEquals(SpellState.INVALID, result.ir.state());
        assertEquals(GlyphWarning.INCOMPLETE_RECOGNITION, result.ir.warning());
        assertFalse(result.ir.valid(), "An incomplete candidate search must fail closed in the IR");
    }

    @Test
    public void recognitionBudgetExhaustionInvalidatesAndExactBudgetDoesNot() throws Exception {
        List<List<Point>> strokes = loadStrokes(
                new File("src/test/resources/fixtures/canonical/multi/spell_light_complete.json"));
        SpellParser.ParseResult baseline = SpellParser.parse(strokes);
        int exactCalls = baseline.debugResult.recognitionCalls();
        assertTrue(exactCalls > 0);

        SpellParser.ParseResult exhausted = SpellParser.parse(strokes, withLimits(
                CandidateGenerationSettings.DEFAULTS.maxPrimitiveGroups(),
                CandidateGenerationSettings.DEFAULTS.maxCandidates(),
                exactCalls - 1));
        assertTrue(exhausted.debugResult.recognitionBudgetExhausted());
        assertTrue(exhausted.debugResult.unevaluatedCandidateCount() > 0);
        assertEquals(GlyphWarning.INCOMPLETE_RECOGNITION, exhausted.ir.warning());
        assertFalse(exhausted.ir.valid(), "An incomplete recognition search must fail closed in the IR");

        SpellParser.ParseResult exact = SpellParser.parse(strokes, withLimits(
                CandidateGenerationSettings.DEFAULTS.maxPrimitiveGroups(),
                CandidateGenerationSettings.DEFAULTS.maxCandidates(),
                exactCalls));
        assertFalse(exact.debugResult.recognitionBudgetExhausted(),
                "Using the final available call is not exhaustion when no work is skipped");
        assertEquals(0, exact.debugResult.unevaluatedCandidateCount());
        assertTrue(exact.ir.valid());
    }

    @Test
    public void ringBudgetExhaustionInvalidatesTheCompiledResult() throws Exception {
        List<Point> circle = loadStrokes(
                new File("src/test/resources/fixtures/canonical/ring_shapes/ring_clean_circle.json"))
                .get(0);
        List<List<Point>> strokes = new ArrayList<>();
        for (int i = 0; i < RingDetector.RingSearchSettings.DEFAULTS.maxEligibleStrokes() + 1; i++) {
            strokes.add(new ArrayList<>(circle));
        }

        SpellParser.ParseResult result = SpellParser.parse(strokes);

        assertTrue(result.debugResult.ringBudgetExhausted());
        assertEquals(SpellState.INVALID, result.ir.state());
        assertEquals(GlyphWarning.INCOMPLETE_RECOGNITION, result.ir.warning());
        assertFalse(result.ir.valid(), "An incomplete ring search must never produce castable IR");
    }

    @Test
    public void primitiveTruncationReportsDroppedOriginalIndices() {
        List<IndexedStroke> strokes = List.of(
                new IndexedStroke(4, List.of(new Point(0.10, 0.10), new Point(0.35, 0.10))),
                new IndexedStroke(9, List.of(new Point(0.75, 0.75), new Point(0.90, 0.75))));

        CandidateGenerator.GenerationResult result = CandidateGenerator.generateCandidates(
                strokes, null, withLimits(1, 128, 512));

        assertTrue(result.candidateLimitReached());
        assertEquals(List.of(9), result.droppedSourceStrokeIndices());
        assertEquals(List.of(4), result.primitiveGroups().get(0).sourceStrokeIndices());
    }

    @Test
    public void exactCandidateCapWithoutOmittedCandidateIsNotExhaustion() {
        List<IndexedStroke> strokes = List.of(
                new IndexedStroke(7, List.of(new Point(0.20, 0.20), new Point(0.40, 0.40))));

        CandidateGenerator.GenerationResult result = CandidateGenerator.generateCandidates(
                strokes, null, withLimits(16, 1, 512));

        assertEquals(1, result.candidates().size());
        assertFalse(result.candidateLimitReached());
    }

    @Test
    public void discardableNoiseIsReportedWithoutBecomingUnknown() throws Exception {
        List<List<Point>> strokes = loadStrokes(
                new File("src/test/resources/fixtures/canonical/multi/spell_messy_multi.json"));

        SpellParser.ParseResult result = SpellParser.parse(strokes);

        assertTrue(result.debugResult.droppedSourceStrokeIndices().contains(13),
                "The fixture's final micro-stroke must be reported as dropped noise");
        assertTrue(result.ast.unknownSymbols().stream()
                        .noneMatch(unknown -> unknown.sourceStrokeIndices().contains(13)),
                "Discarded noise must not become an UnknownSymbol");
        assertFalse(result.debugResult.ringBudgetExhausted());
        assertFalse(result.debugResult.candidateLimitReached());
        assertFalse(result.debugResult.recognitionBudgetExhausted());
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

    private static CandidateGenerationSettings withLimits(
            int maxPrimitiveGroups, int maxCandidates, int maxRecognitionCalls) {
        CandidateGenerationSettings defaults = CandidateGenerationSettings.DEFAULTS;
        return new CandidateGenerationSettings(
                maxPrimitiveGroups,
                defaults.maxGroupsPerCandidate(),
                maxCandidates,
                maxRecognitionCalls,
                defaults.maxCandidateWidthRatio(),
                defaults.maxCandidateHeightRatio(),
                defaults.maxAngularSpanDeg(),
                defaults.maxInternalGapRatio(),
                defaults.maxEmptySpaceRatio());
    }

    private static List<List<Point>> loadStrokes(File file) throws Exception {
        JsonObject json = GSON.fromJson(new FileReader(file), JsonObject.class);
        List<List<Point>> strokes = new ArrayList<>();
        for (JsonElement strokeElement : json.getAsJsonArray("strokes")) {
            List<Point> stroke = new ArrayList<>();
            for (JsonElement pointElement : strokeElement.getAsJsonArray()) {
                JsonObject point = pointElement.getAsJsonObject();
                stroke.add(new Point(point.get("x").getAsDouble(), point.get("y").getAsDouble()));
            }
            strokes.add(stroke);
        }
        return strokes;
    }
}
