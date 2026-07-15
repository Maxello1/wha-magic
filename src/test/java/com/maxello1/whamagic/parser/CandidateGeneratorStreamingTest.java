package com.maxello1.whamagic.parser;

import com.maxello1.whamagic.magic.CandidateGenerationSettings;
import com.maxello1.whamagic.magic.PrimitiveStrokeGroup;
import com.maxello1.whamagic.magic.RingDetector;
import com.maxello1.whamagic.magic.SymbolCandidate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateGeneratorStreamingTest {

    @Test
    void disconnectedPrimitiveGroupsPreserveSingletonOrder() {
        List<IndexedStroke> strokes = indexed(List.of(
                stroke(0.05, 0.05, 0.08, 0.08),
                stroke(0.92, 0.05, 0.95, 0.08),
                stroke(0.05, 0.92, 0.08, 0.95),
                stroke(0.92, 0.92, 0.95, 0.95)));

        CandidateGenerator.GenerationResult actual = generate(
                strokes, null, CandidateGenerationSettings.DEFAULTS);

        assertEquals(4, actual.primitiveGroups().size());
        assertCandidateLayout(actual, List.of(
                List.of(0, 1, 2, 3),
                List.of(0), List.of(1), List.of(2), List.of(3)));
    }

    @Test
    void candidateCapChangesExhaustionOnlyWhenAValidCandidateIsOmitted() {
        List<IndexedStroke> strokes = indexed(List.of(
                stroke(0.10, 0.10, 0.15, 0.12),
                stroke(0.25, 0.15, 0.30, 0.18),
                stroke(0.40, 0.20, 0.45, 0.24)));

        CandidateGenerator.GenerationResult unrestricted = generate(
                strokes, null, CandidateGenerationSettings.DEFAULTS);
        List<List<Integer>> completeLayout = List.of(
                List.of(0, 1, 2),
                List.of(0), List.of(1), List.of(2),
                List.of(0, 1), List.of(0, 2), List.of(1, 2));
        assertCandidateLayout(unrestricted, completeLayout);
        assertFalse(unrestricted.candidateLimitReached());

        CandidateGenerator.GenerationResult exact = generate(
                strokes, null, withLimits(16, 7));
        assertCandidateLayout(exact, completeLayout);
        assertFalse(exact.candidateLimitReached(),
                "Consuming the last slot is not exhaustion when no valid candidate remains");

        CandidateGenerator.GenerationResult omitted = generate(
                strokes, null, withLimits(16, 6));
        assertCandidateLayout(omitted, completeLayout.subList(0, 6));
        assertTrue(omitted.candidateLimitReached());
    }

    @Test
    void primitiveGroupCapPreservesLengthPriorityAndDroppedOwnership() {
        List<IndexedStroke> strokes = List.of(
                new IndexedStroke(20, stroke(0.05, 0.05, 0.15, 0.05)),
                new IndexedStroke(5, stroke(0.75, 0.05, 0.95, 0.05)),
                new IndexedStroke(17, stroke(0.05, 0.70, 0.35, 0.70)),
                new IndexedStroke(9, stroke(0.55, 0.90, 0.95, 0.90)));

        CandidateGenerator.GenerationResult actual = generate(
                strokes, null, withLimits(2, 128));

        assertTrue(actual.candidateLimitReached());
        assertEquals(List.of(3, 2),
                actual.primitiveGroups().stream().map(PrimitiveStrokeGroup::id).toList(),
                "The two longest primitive groups must be retained in deterministic order");
        assertEquals(List.of(5, 20), actual.droppedSourceStrokeIndices());
    }

    @Test
    void maximumLegalPrimitiveCountRemainsBounded() {
        List<List<Point>> raw = new ArrayList<>();
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                double x = 0.07 + column * 0.28;
                double y = 0.07 + row * 0.28;
                raw.add(stroke(x, y, x + 0.015, y + 0.012));
            }
        }

        CandidateGenerator.GenerationResult actual = generate(
                indexed(raw), null, withLimits(16, 32));

        assertEquals(16, actual.primitiveGroups().size());
        assertEquals(16, new HashSet<>(actual.primitiveGroups().stream()
                .flatMap(group -> group.sourceStrokeIndices().stream()).toList()).size());
        assertTrue(actual.droppedSourceStrokeIndices().isEmpty());
        assertTrue(actual.candidateLimitReached());

        List<List<Integer>> expectedLayout = new ArrayList<>();
        expectedLayout.add(IntStream.range(0, 16).boxed().toList());
        for (int group = 0; group < 16; group++) {
            expectedLayout.add(List.of(group));
        }
        expectedLayout.addAll(List.of(
                List.of(0, 1), List.of(0, 4), List.of(0, 5),
                List.of(1, 2), List.of(1, 4), List.of(1, 5), List.of(1, 6),
                List.of(2, 3), List.of(2, 5), List.of(2, 6), List.of(2, 7),
                List.of(3, 6), List.of(3, 7), List.of(4, 5), List.of(4, 8)));
        assertCandidateLayout(actual, expectedLayout);
    }

    private static void assertCandidateLayout(
            CandidateGenerator.GenerationResult result,
            List<List<Integer>> expectedPrimitiveGroups) {
        List<SymbolCandidate> candidates = result.candidates();
        assertEquals(expectedPrimitiveGroups.size(), candidates.size(), "candidate count");
        assertEquals(IntStream.range(0, candidates.size()).boxed().toList(),
                candidates.stream().map(SymbolCandidate::id).toList(), "candidate IDs/order");
        for (int index = 0; index < candidates.size(); index++) {
            SymbolCandidate candidate = candidates.get(index);
            List<Integer> expected = expectedPrimitiveGroups.get(index);
            assertEquals(expected, candidate.primitiveGroupIds(),
                    "candidate " + index + " primitive groups");
            assertEquals(expected, candidate.sourceStrokeIndices(),
                    "candidate " + index + " source-stroke ownership");
            assertEquals(index == 0 && expected.size() > 1, candidate.isSuperCandidate(),
                    "candidate " + index + " super-candidate flag");
        }
    }

    private static CandidateGenerator.GenerationResult generate(
            List<IndexedStroke> strokes,
            RingDetector.RingGlyph ring,
            CandidateGenerationSettings settings) {
        return CandidateGenerator.generateCandidates(strokes, ring, settings);
    }

    private static CandidateGenerationSettings withLimits(
            int maxPrimitiveGroups, int maxCandidates) {
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

    private static List<IndexedStroke> indexed(List<List<Point>> strokes) {
        List<IndexedStroke> indexed = new ArrayList<>(strokes.size());
        for (int index = 0; index < strokes.size(); index++) {
            indexed.add(new IndexedStroke(index, strokes.get(index)));
        }
        return indexed;
    }

    private static List<Point> stroke(double x1, double y1, double x2, double y2) {
        return List.of(new Point(x1, y1), new Point(x2, y2));
    }
}
