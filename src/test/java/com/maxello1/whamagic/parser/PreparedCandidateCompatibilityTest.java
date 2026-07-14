package com.maxello1.whamagic.parser;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maxello1.whamagic.magic.RecognitionAlternative;
import com.maxello1.whamagic.magic.SymbolRecognitionResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PreparedCandidateCompatibilityTest {
    // Rotation and resampling commute geometrically; primitive batching changes only
    // last-bit floating-point operation order, never recognition policy or ordering.
    private static final double TOLERANCE = 1e-8;

    @BeforeAll
    static void loadDictionary() {
        SpellDictionary.ensureLoaded();
    }

    @Test
    void preparedRecognitionMatchesReferenceAcrossEveryFixture() throws Exception {
        List<Path> fixtures = fixturePaths();
        for (Path fixture : fixtures) {
            List<List<Point>> strokes = loadStrokes(fixture);
            SpellParser.ParseResult prepared = SpellParser.parse(
                    strokes,
                    com.maxello1.whamagic.magic.CandidateGenerationSettings.DEFAULTS,
                    ParseDetail.FULL_DIAGNOSTICS);
            SpellParser.ParseResult reference = SpellParser.parseReference(
                    strokes,
                    com.maxello1.whamagic.magic.CandidateGenerationSettings.DEFAULTS,
                    ParseDetail.FULL_DIAGNOSTICS);
            compareParseResults(reference, prepared, fixture.toString());
        }
    }

    private static void compareParseResults(
            SpellParser.ParseResult expected,
            SpellParser.ParseResult actual,
            String fixture) {
        assertEquals(expected.ir, actual.ir, fixture + " semantic IR");
        assertNotNull(expected.debugResult, fixture + " reference diagnostics");
        assertNotNull(actual.debugResult, fixture + " prepared diagnostics");
        assertEquals(expected.debugResult.recognitionCalls(), actual.debugResult.recognitionCalls(),
                fixture + " recognition calls");
        assertEquals(expected.debugResult.recognitionBudgetExhausted(),
                actual.debugResult.recognitionBudgetExhausted(), fixture + " recognition budget");
        assertEquals(expected.debugResult.unevaluatedCandidateCount(),
                actual.debugResult.unevaluatedCandidateCount(), fixture + " unevaluated count");
        assertEquals(expected.debugResult.selectedCandidates().stream().map(candidate -> candidate.id()).toList(),
                actual.debugResult.selectedCandidates().stream().map(candidate -> candidate.id()).toList(),
                fixture + " selected candidates");

        List<SelectionEngine.EvaluatedCandidate> expectedEvaluated =
                expected.debugResult.allEvaluated();
        List<SelectionEngine.EvaluatedCandidate> actualEvaluated =
                actual.debugResult.allEvaluated();
        assertEquals(expectedEvaluated.size(), actualEvaluated.size(), fixture + " evaluated count");
        for (int index = 0; index < expectedEvaluated.size(); index++) {
            SelectionEngine.EvaluatedCandidate left = expectedEvaluated.get(index);
            SelectionEngine.EvaluatedCandidate right = actualEvaluated.get(index);
            String context = fixture + " evaluated[" + index + "]";
            assertEquals(left.cand.id(), right.cand.id(), context + " candidate order");
            assertEquals(left.sigilRoleScore, right.sigilRoleScore, TOLERANCE,
                    context + " sigil role score");
            assertEquals(left.signRoleScore, right.signRoleScore, TOLERANCE,
                    context + " sign role score");
            assertEquals(left.bestAngle, right.bestAngle, TOLERANCE, context + " angle");
            compareRecognition(left.sigilRes, right.sigilRes, context + " sigil");
            compareRecognition(left.signRes, right.signRes, context + " sign");
        }

        assertEquals(expected.ast.sigils().size(), actual.ast.sigils().size(), fixture + " sigils");
        for (int index = 0; index < expected.ast.sigils().size(); index++) {
            var left = expected.ast.sigils().get(index);
            var right = actual.ast.sigils().get(index);
            assertEquals(left.id(), right.id(), fixture + " sigil ID " + index);
            assertEquals(left.matchedTemplateId(), right.matchedTemplateId(),
                    fixture + " sigil template " + index);
            assertEquals(left.sourceStrokeIndices(), right.sourceStrokeIndices(),
                    fixture + " sigil ownership " + index);
            assertEquals(left.recognitionConfidence(), right.recognitionConfidence(), TOLERANCE,
                    fixture + " sigil confidence " + index);
        }
        assertEquals(expected.ast.signs().size(), actual.ast.signs().size(), fixture + " signs");
        for (int index = 0; index < expected.ast.signs().size(); index++) {
            var left = expected.ast.signs().get(index);
            var right = actual.ast.signs().get(index);
            assertEquals(left.id(), right.id(), fixture + " sign ID " + index);
            assertEquals(left.matchedTemplateId(), right.matchedTemplateId(),
                    fixture + " sign template " + index);
            assertEquals(left.sourceStrokeIndices(), right.sourceStrokeIndices(),
                    fixture + " sign ownership " + index);
            assertEquals(left.confidence(), right.confidence(), TOLERANCE,
                    fixture + " sign confidence " + index);
            assertEquals(left.orientationDeg(), right.orientationDeg(), TOLERANCE,
                    fixture + " sign orientation " + index);
        }
        assertEquals(expected.ast.unknownSymbols().stream()
                        .map(unknown -> List.of(
                                unknown.candidateId(),
                                unknown.sourceStrokeIndices(),
                                unknown.classification(),
                                unknown.rejectionReason()))
                        .toList(),
                actual.ast.unknownSymbols().stream()
                        .map(unknown -> List.of(
                                unknown.candidateId(),
                                unknown.sourceStrokeIndices(),
                                unknown.classification(),
                                unknown.rejectionReason()))
                        .toList(),
                fixture + " unknown ownership/classification");
    }

    private static void compareRecognition(
            SymbolRecognitionResult expected,
            SymbolRecognitionResult actual,
            String context) {
        if (expected == null || actual == null) {
            assertEquals(expected, actual, context);
            return;
        }
        assertEquals(expected.recognized(), actual.recognized(), context + " recognized");
        assertEquals(expected.id(), actual.id(), context + " semantic ID");
        assertEquals(expected.matchedTemplateId(), actual.matchedTemplateId(),
                context + " template variant");
        assertEquals(expected.rejectionReason(), actual.rejectionReason(),
                context + " rejection");
        assertEquals(expected.score(), actual.score(), TOLERANCE, context + " score");
        assertEquals(expected.confidenceGap(), actual.confidenceGap(), TOLERANCE,
                context + " confidence gap");
        assertEquals(expected.alternatives().size(), actual.alternatives().size(),
                context + " alternatives size");
        for (int index = 0; index < expected.alternatives().size(); index++) {
            RecognitionAlternative left = expected.alternatives().get(index);
            RecognitionAlternative right = actual.alternatives().get(index);
            assertEquals(left.id(), right.id(), context + " alternative ID " + index);
            assertEquals(left.rawScore(), right.rawScore(), TOLERANCE,
                    context + " alternative score " + index);
        }
    }

    private static List<Path> fixturePaths() throws Exception {
        List<Path> roots = List.of(
                Path.of("src/test/resources/fixtures/canonical"),
                Path.of("src/test/resources/fixtures/holdout"),
                Path.of("src/test/resources/fixtures/negative_confusion"));
        List<Path> fixtures = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(path -> path.toString().endsWith(".json"))
                        .forEach(fixtures::add);
            }
        }
        fixtures.sort(Comparator.comparing(Path::toString));
        return fixtures;
    }

    private static List<List<Point>> loadStrokes(Path fixture) throws Exception {
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(fixture, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        JsonElement strokesElement = root.has("strokes")
                ? root.get("strokes")
                : root.get("rawStrokes");
        List<List<Point>> strokes = new ArrayList<>();
        for (JsonElement strokeElement : strokesElement.getAsJsonArray()) {
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
