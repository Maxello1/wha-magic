package com.maxello1.whamagic.parser;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maxello1.whamagic.magic.CandidateGenerationSettings;
import com.maxello1.whamagic.magic.PrimitiveStrokeGroup;
import com.maxello1.whamagic.magic.RingDetector;
import com.maxello1.whamagic.magic.SymbolCandidate;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateGeneratorStreamingTest {

    @Test
    void streamingMatchesReferenceForCanonicalSymbolsAndIntegrationDrawings() throws Exception {
        for (Path fixture : canonicalAndIntegrationFixtures()) {
            List<List<Point>> strokes = loadStrokes(fixture);
            RingDetector.RingSearchResult ringSearch = RingDetector.searchRing(strokes);
            RingDetector.RingDetection detection = ringSearch.detection();
            RingDetector.RingGlyph ring = detection == null ? null : detection.glyph();
            Set<Integer> ringIndices = detection == null
                    ? Set.of()
                    : detection.ringStrokeIndices();
            List<IndexedStroke> nonRingStrokes = indexedExcluding(strokes, ringIndices);

            assertEquivalent(nonRingStrokes, ring, CandidateGenerationSettings.DEFAULTS,
                    fixture.toString());
        }
    }

    @Test
    void disconnectedPrimitiveGroupsPreserveSingletonOrder() {
        List<IndexedStroke> strokes = indexed(List.of(
                stroke(0.05, 0.05, 0.08, 0.08),
                stroke(0.92, 0.05, 0.95, 0.08),
                stroke(0.05, 0.92, 0.08, 0.95),
                stroke(0.92, 0.92, 0.95, 0.95)));

        CandidateGenerator.GenerationResult actual = assertEquivalent(
                strokes, null, CandidateGenerationSettings.DEFAULTS, "disconnected groups");

        assertEquals(4, actual.primitiveGroups().size());
        assertEquals(5, actual.candidates().size(),
                "Only the super-candidate and four singletons should be connected");
        assertTrue(actual.candidates().get(0).isSuperCandidate());
        assertEquals(List.of(0, 1, 2, 3, 4),
                actual.candidates().stream().map(SymbolCandidate::id).toList());
    }

    @Test
    void candidateCapChangesExhaustionOnlyWhenAValidCandidateIsOmitted() {
        List<IndexedStroke> strokes = indexed(List.of(
                stroke(0.10, 0.10, 0.15, 0.12),
                stroke(0.25, 0.15, 0.30, 0.18),
                stroke(0.40, 0.20, 0.45, 0.24)));
        CandidateGenerator.GenerationResult unrestricted = assertEquivalent(
                strokes, null, CandidateGenerationSettings.DEFAULTS, "candidate cap baseline");
        int exactCandidateCount = unrestricted.candidates().size();
        assertTrue(exactCandidateCount > 1, "The cap fixture must generate multiple candidates");

        CandidateGenerator.GenerationResult exact = assertEquivalent(
                strokes, null, withLimits(16, exactCandidateCount), "exact candidate cap");
        assertEquals(exactCandidateCount, exact.candidates().size());
        assertFalse(exact.candidateLimitReached(),
                "Consuming the last slot is not exhaustion when no valid candidate remains");

        CandidateGenerator.GenerationResult omitted = assertEquivalent(
                strokes, null, withLimits(16, exactCandidateCount - 1), "candidate omitted at cap");
        assertEquals(exactCandidateCount - 1, omitted.candidates().size());
        assertTrue(omitted.candidateLimitReached());
    }

    @Test
    void primitiveGroupCapPreservesLengthPriorityAndDroppedOwnership() {
        List<IndexedStroke> strokes = List.of(
                new IndexedStroke(20, stroke(0.05, 0.05, 0.15, 0.05)),
                new IndexedStroke(5, stroke(0.75, 0.05, 0.95, 0.05)),
                new IndexedStroke(17, stroke(0.05, 0.70, 0.35, 0.70)),
                new IndexedStroke(9, stroke(0.55, 0.90, 0.95, 0.90)));

        CandidateGenerator.GenerationResult actual = assertEquivalent(
                strokes, null, withLimits(2, 128), "primitive group cap");

        assertTrue(actual.candidateLimitReached());
        assertEquals(List.of(3, 2),
                actual.primitiveGroups().stream().map(PrimitiveStrokeGroup::id).toList(),
                "The two longest primitive groups must be retained in deterministic order");
        assertEquals(List.of(5, 20), actual.droppedSourceStrokeIndices());
    }

    @Test
    void maximumLegalSixteenPrimitiveGroupsMatchReference() {
        List<List<Point>> raw = new ArrayList<>();
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                double x = 0.07 + column * 0.28;
                double y = 0.07 + row * 0.28;
                raw.add(stroke(x, y, x + 0.015, y + 0.012));
            }
        }

        CandidateGenerator.GenerationResult actual = assertEquivalent(
                indexed(raw), null, withLimits(16, 32), "maximum legal primitive count");

        assertEquals(16, actual.primitiveGroups().size());
        assertEquals(16, new HashSet<>(actual.primitiveGroups().stream()
                .flatMap(group -> group.sourceStrokeIndices().stream()).toList()).size());
        assertTrue(actual.droppedSourceStrokeIndices().isEmpty());
    }

    private static CandidateGenerator.GenerationResult assertEquivalent(
            List<IndexedStroke> strokes,
            RingDetector.RingGlyph ring,
            CandidateGenerationSettings settings,
            String context) {
        CandidateGenerator.GenerationResult expected =
                CandidateGenerator.generateCandidatesReference(strokes, ring, settings);
        CandidateGenerator.GenerationResult actual =
                CandidateGenerator.generateCandidates(strokes, ring, settings);

        assertEquals(expected.candidateLimitReached(), actual.candidateLimitReached(),
                context + " candidate-limit flag");
        assertEquals(expected.droppedSourceStrokeIndices(), actual.droppedSourceStrokeIndices(),
                context + " dropped source strokes");
        comparePrimitiveGroups(expected.primitiveGroups(), actual.primitiveGroups(), context);
        compareCandidates(expected.candidates(), actual.candidates(), context);
        return actual;
    }

    private static void comparePrimitiveGroups(
            List<PrimitiveStrokeGroup> expected,
            List<PrimitiveStrokeGroup> actual,
            String context) {
        assertEquals(expected.size(), actual.size(), context + " primitive count");
        for (int index = 0; index < expected.size(); index++) {
            PrimitiveStrokeGroup left = expected.get(index);
            PrimitiveStrokeGroup right = actual.get(index);
            String item = context + " primitive[" + index + "]";
            assertEquals(left.id(), right.id(), item + " ID/order");
            assertEquals(left.sourceStrokeIndices(), right.sourceStrokeIndices(), item + " ownership");
            compareStrokes(left.strokes(), right.strokes(), item + " strokes");
            assertEquals(left.bounds(), right.bounds(), item + " bounds");
            assertPoint(left.centroid(), right.centroid(), item + " centroid");
            assertEquals(left.pathLength(), right.pathLength(), item + " path length");
            assertEquals(left.radialPosition(), right.radialPosition(), item + " radial position");
            assertEquals(left.angularPosition(), right.angularPosition(), item + " angular position");
        }
    }

    private static void compareCandidates(
            List<SymbolCandidate> expected,
            List<SymbolCandidate> actual,
            String context) {
        assertEquals(expected.size(), actual.size(), context + " candidate count");
        for (int index = 0; index < expected.size(); index++) {
            SymbolCandidate left = expected.get(index);
            SymbolCandidate right = actual.get(index);
            String item = context + " candidate[" + index + "]";
            assertEquals(left.id(), right.id(), item + " ID/order");
            assertEquals(left.primitiveGroupIds(), right.primitiveGroupIds(), item + " primitive IDs");
            assertEquals(left.sourceStrokeIndices(), right.sourceStrokeIndices(), item + " ownership");
            compareStrokes(left.strokes(), right.strokes(), item + " strokes");
            assertEquals(left.bounds(), right.bounds(), item + " bounds");
            assertPoint(left.centroid(), right.centroid(), item + " centroid");
            assertEquals(left.radialPosition(), right.radialPosition(), item + " radial position");
            assertEquals(left.angularPosition(), right.angularPosition(), item + " angular position");
            assertEquals(left.angularSpan(), right.angularSpan(), item + " angular span");
            assertEquals(left.isSuperCandidate(), right.isSuperCandidate(), item + " super flag");
        }
    }

    private static void compareStrokes(
            List<List<Point>> expected,
            List<List<Point>> actual,
            String context) {
        assertEquals(expected.size(), actual.size(), context + " stroke count");
        for (int strokeIndex = 0; strokeIndex < expected.size(); strokeIndex++) {
            List<Point> leftStroke = expected.get(strokeIndex);
            List<Point> rightStroke = actual.get(strokeIndex);
            assertEquals(leftStroke.size(), rightStroke.size(), context + " point count " + strokeIndex);
            for (int pointIndex = 0; pointIndex < leftStroke.size(); pointIndex++) {
                assertPoint(leftStroke.get(pointIndex), rightStroke.get(pointIndex),
                        context + " point[" + strokeIndex + "][" + pointIndex + "]");
            }
        }
    }

    private static void assertPoint(Point expected, Point actual, String context) {
        assertEquals(expected.x, actual.x, context + " x");
        assertEquals(expected.y, actual.y, context + " y");
    }

    private static CandidateGenerationSettings withLimits(int maxPrimitiveGroups, int maxCandidates) {
        CandidateGenerationSettings defaults = CandidateGenerationSettings.DEFAULTS;
        return new CandidateGenerationSettings(
                maxPrimitiveGroups,
                defaults.maxGroupsPerCandidate(),
                maxCandidates,
                defaults.maxRecognitionCalls(),
                defaults.maxCandidateWidthRatio(),
                defaults.maxCandidateHeightRatio(),
                defaults.maxAngularSpanDeg(),
                defaults.maxInternalGapRatio(),
                defaults.maxEmptySpaceRatio());
    }

    private static List<Path> canonicalAndIntegrationFixtures() throws Exception {
        List<Path> fixtures = new ArrayList<>();
        for (Path root : List.of(
                Path.of("src/test/resources/fixtures/canonical/positive"),
                Path.of("src/test/resources/fixtures/canonical/multi"))) {
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
        JsonElement strokesElement = root.has("strokes") ? root.get("strokes") : root.get("rawStrokes");
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

    private static List<IndexedStroke> indexed(List<List<Point>> strokes) {
        return indexedExcluding(strokes, Set.of());
    }

    private static List<IndexedStroke> indexedExcluding(
            List<List<Point>> strokes,
            Set<Integer> excludedIndices) {
        List<IndexedStroke> indexed = new ArrayList<>();
        for (int index = 0; index < strokes.size(); index++) {
            if (!excludedIndices.contains(index)) {
                indexed.add(new IndexedStroke(index, strokes.get(index)));
            }
        }
        return indexed;
    }

    private static List<Point> stroke(double x1, double y1, double x2, double y2) {
        return List.of(new Point(x1, y1), new Point(x2, y2));
    }
}
