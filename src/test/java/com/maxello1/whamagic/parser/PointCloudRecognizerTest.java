package com.maxello1.whamagic.parser;

import com.maxello1.whamagic.magic.RecognitionRejectionReason;
import com.maxello1.whamagic.magic.SymbolKind;
import com.maxello1.whamagic.magic.SymbolRecognitionRules;
import com.maxello1.whamagic.magic.SymbolRecognitionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PointCloudRecognizerTest {

    @Test
    public void symbolRecognizerUsesRecognizerNeutralResult() throws Exception {
        assertEquals(SymbolRecognitionResult.class,
                SymbolRecognizer.class
                        .getMethod("recognize", List.class, SymbolKind.class)
                        .getReturnType());
        assertEquals(SymbolRecognitionResult.class,
                PointCloudRecognizer.class
                        .getMethod("recognize", List.class, SymbolKind.class)
                        .getReturnType());
        assertEquals(SymbolRecognitionResult.class,
                RasterRecognizer.class
                        .getMethod("recognize", List.class, SymbolKind.class)
                        .getReturnType());
        assertTrue(java.util.Arrays.stream(RasterRecognizer.class.getDeclaredClasses())
                .noneMatch(type -> "RecognitionResult".equals(type.getSimpleName())));
        SymbolRecognitionResult rejected = SymbolRecognitionResult.rejected(
                "Unknown", RecognitionRejectionReason.NO_STROKES, 0.20);
        assertThrows(UnsupportedOperationException.class,
                () -> rejected.alternatives().add(null));
    }

    @Test
    public void perSymbolScoreAndGapRulesControlAcceptance() {
        SymbolRecognitionRules strict = new SymbolRecognitionRules(
                0.70, 0.05, 0.20, 0.05, true, 0, 1, 4);
        SymbolRecognitionRules relaxedGap = new SymbolRecognitionRules(
                0.70, 0.02, 0.20, 0.05, true, 0, 1, 4);

        assertEquals(RecognitionRejectionReason.SCORE_BELOW_THRESHOLD,
                PointCloudRecognizer.acceptanceReason(
                        0.69, 0.20, RecognitionRejectionReason.NONE, strict));
        assertEquals(RecognitionRejectionReason.AMBIGUOUS_TOP_MATCHES,
                PointCloudRecognizer.acceptanceReason(
                        0.75, 0.72, RecognitionRejectionReason.NONE, strict));
        assertEquals(RecognitionRejectionReason.NONE,
                PointCloudRecognizer.acceptanceReason(
                        0.75, 0.72, RecognitionRejectionReason.NONE, relaxedGap));
    }

    @Test
    public void sixteenMeaningfulStrokesReceiveTwoPointsEach() {
        List<List<Point>> strokes = makeLineStrokes(16);

        PointCloudRecognizer.NormalizationAllocation allocation =
                PointCloudRecognizer.normalizationAllocation(strokes);

        assertTrue(allocation.supported());
        assertEquals(PointCloudRecognizer.N, allocation.targetPointCount());
        assertEquals(16, allocation.meaningfulStrokeCount());
        assertEquals(16, allocation.strokeAllocations().size());
        assertEquals(PointCloudRecognizer.N, allocation.strokeAllocations().stream()
                .mapToInt(PointCloudRecognizer.StrokePointAllocation::pointCount)
                .sum());
        for (int strokeId = 0; strokeId < 16; strokeId++) {
            assertEquals(new PointCloudRecognizer.StrokePointAllocation(strokeId, 2),
                    allocation.strokeAllocations().get(strokeId));
        }

        PointCloudRecognizer.CloudPoint[] normalized =
                PointCloudRecognizer.normalizeForDiagnostics(strokes);
        assertEquals(PointCloudRecognizer.N, normalized.length);
        for (int strokeId = 0; strokeId < 16; strokeId++) {
            int expectedId = strokeId;
            assertEquals(2, java.util.Arrays.stream(normalized)
                    .filter(point -> point.strokeId() == expectedId)
                    .count(), "Every meaningful stroke must survive normalization");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {17, 24, 32})
    public void excessiveMeaningfulStrokesAreRejectedBeforeNormalization(int strokeCount) {
        List<List<Point>> strokes = makeLineStrokes(strokeCount);

        PointCloudRecognizer.NormalizationAllocation allocation =
                PointCloudRecognizer.normalizationAllocation(strokes);

        assertFalse(allocation.supported());
        assertEquals(strokeCount, allocation.meaningfulStrokeCount());
        assertTrue(allocation.strokeAllocations().isEmpty(),
                "Unsupported inputs must not receive a partial or tail-truncated allocation");
        assertThrows(IllegalArgumentException.class,
                () -> PointCloudRecognizer.normalizeForDiagnostics(strokes));

        SymbolRecognitionResult first =
                PointCloudRecognizer.recognizeStatic(strokes, SymbolKind.SIGIL);
        SymbolRecognitionResult second =
                PointCloudRecognizer.recognizeStatic(strokes, SymbolKind.SIGIL);
        assertFalse(first.recognized());
        assertEquals(RecognitionRejectionReason.UNSUPPORTED_COMPLEXITY, first.rejectionReason());
        assertEquals(first.recognized(), second.recognized());
        assertEquals(first.id(), second.id());
        assertEquals(first.score(), second.score());
        assertEquals(first.rejectionReason(), second.rejectionReason(),
                "Unsupported-complexity rejection must be deterministic");
    }

    @Test
    public void linearResamplingIsUniformAndDeterministic() {
        List<List<Point>> strokes = List.of(List.of(
                new Point(0.0, 0.0),
                new Point(0.1, 0.0),
                new Point(1.0, 0.0)));

        PointCloudRecognizer.CloudPoint[] first =
                PointCloudRecognizer.normalizeForDiagnostics(strokes);
        PointCloudRecognizer.CloudPoint[] second =
                PointCloudRecognizer.normalizeForDiagnostics(strokes);

        assertArrayEquals(first, second);
        assertEquals(PointCloudRecognizer.N, first.length);
        for (int i = 0; i < first.length; i++) {
            assertEquals(-0.5 + i / (double) (PointCloudRecognizer.N - 1), first[i].x(), 1e-9);
            assertEquals(0.0, first[i].y(), 1e-9);
            assertEquals(0, first[i].strokeId());
            assertEquals(0.0, first[i].turningAngle(), 1e-9);
        }
    }

    @Test
    public void closedContoursIncludeMergedAndSplitLoops() {
        List<List<Point>> loopMergedWithStem = List.of(List.of(
                new Point(0.5, 0.0),
                new Point(0.5, 0.2),
                new Point(0.8, 0.2),
                new Point(0.8, 0.8),
                new Point(0.2, 0.8),
                new Point(0.2, 0.2),
                new Point(0.5, 0.2)));
        assertEquals(1, PointCloudRecognizer.closedContourCount(loopMergedWithStem));

        List<List<Point>> loopSplitAcrossStrokes = List.of(
                List.of(
                        new Point(0.2, 0.2),
                        new Point(0.8, 0.2),
                        new Point(0.8, 0.8),
                        new Point(0.2, 0.8)),
                List.of(
                        new Point(0.2, 0.8),
                        new Point(0.2, 0.2)));
        assertEquals(1, PointCloudRecognizer.closedContourCount(loopSplitAcrossStrokes));

        List<List<Point>> joinedLightLoops = List.of(List.of(
                new Point(0.5, 0.2),
                new Point(0.8, 0.2),
                new Point(0.8, 0.8),
                new Point(0.2, 0.8),
                new Point(0.2, 0.2),
                new Point(0.5, 0.2),
                new Point(0.8, 0.5),
                new Point(0.5, 0.8),
                new Point(0.2, 0.5),
                new Point(0.5, 0.2)));
        assertEquals(2, PointCloudRecognizer.closedContourCount(joinedLightLoops));

        List<List<Point>> columnWithPenLift = List.of(
                List.of(new Point(0.5, 0.1), new Point(0.5, 0.5)),
                List.of(new Point(0.5, 0.5), new Point(0.5, 0.8)),
                List.of(new Point(0.2, 0.8), new Point(0.8, 0.8)));
        assertEquals(0, PointCloudRecognizer.closedContourCount(columnWithPenLift));
    }

    private static List<List<Point>> makeLineStrokes(int strokeCount) {
        List<List<Point>> strokes = new ArrayList<>(strokeCount);
        for (int strokeId = 0; strokeId < strokeCount; strokeId++) {
            double y = strokeId / (double) Math.max(1, strokeCount - 1);
            strokes.add(List.of(new Point(0.1, y), new Point(0.9, y)));
        }
        return strokes;
    }
}
