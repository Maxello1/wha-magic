package com.maxello1.whamagic.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maxello1.whamagic.parser.Point;
import com.maxello1.whamagic.parser.SpellParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleRecorderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesExactRawStrokesAndConsistentUtf8MetadataWithoutAResult() throws Exception {
        LocalDateTime recordedAt = LocalDateTime.of(2026, 7, 13, 4, 50, 32, 641_123_456);
        List<List<Point>> strokes = List.of(
                List.of(
                        new Point(0.123456789012345, 0.987654321098765),
                        new Point(0.223456789012345, 0.887654321098765)),
                List.of(
                        new Point(0.333333333333333, 0.666666666666667),
                        new Point(0.444444444444444, 0.555555555555556),
                        new Point(0.777777777777777, 0.111111111111111)));
        String notes = "Grüße aus dem Atelier – 魔法";

        Path sample = SampleRecorder.writeSample(
                temporaryDirectory, strokes, null, notes, recordedAt);

        assertEquals("sample_2026-07-13_04-50-32-641.json", sample.getFileName().toString());
        JsonObject json;
        try (Reader reader = Files.newBufferedReader(sample, StandardCharsets.UTF_8)) {
            json = JsonParser.parseReader(reader).getAsJsonObject();
        }

        assertEquals(notes, json.get("notes").getAsString());
        assertEquals("2026-07-13", json.get("sourceDate").getAsString());
        assertEquals(recordedAt.toString(), json.get("timestamp").getAsString());
        assertFalse(json.has("result"));

        JsonArray recordedStrokes = json.getAsJsonArray("rawStrokes");
        assertEquals(strokes.size(), recordedStrokes.size());
        for (int strokeIndex = 0; strokeIndex < strokes.size(); strokeIndex++) {
            List<Point> expectedStroke = strokes.get(strokeIndex);
            JsonArray recordedStroke = recordedStrokes.get(strokeIndex).getAsJsonArray();
            assertEquals(expectedStroke.size(), recordedStroke.size());
            for (int pointIndex = 0; pointIndex < expectedStroke.size(); pointIndex++) {
                Point expectedPoint = expectedStroke.get(pointIndex);
                JsonObject recordedPoint = recordedStroke.get(pointIndex).getAsJsonObject();
                assertEquals(expectedPoint.x, recordedPoint.get("x").getAsDouble());
                assertEquals(expectedPoint.y, recordedPoint.get("y").getAsDouble());
            }
        }
    }

    @Test
    void preservesRecognitionAndDiagnosticFields() throws Exception {
        LocalDateTime recordedAt = LocalDateTime.of(2026, 7, 13, 5, 0);
        List<List<Point>> strokes = List.of(List.of(
                new Point(0.10, 0.10),
                new Point(0.12, 0.12)));
        SpellParser.ParseResult result = SpellParser.parse(strokes);

        Path sample = SampleRecorder.writeSample(
                temporaryDirectory, strokes, result, "", recordedAt);
        JsonObject json;
        try (Reader reader = Files.newBufferedReader(sample, StandardCharsets.UTF_8)) {
            json = JsonParser.parseReader(reader).getAsJsonObject();
        }

        JsonObject recordedResult = json.getAsJsonObject("result");
        assertEquals(result.isValidSpell(), recordedResult.get("valid").getAsBoolean());
        assertTrue(recordedResult.get("sigils").isJsonArray());
        assertTrue(recordedResult.get("signs").isJsonArray());
        assertTrue(recordedResult.get("unknowns").isJsonArray());
        assertEquals(
                result.debugResult.candidateCount(),
                recordedResult.get("candidateCount").getAsInt());
        assertEquals(
                result.debugResult.recognitionCalls(),
                recordedResult.get("recognitionCalls").getAsInt());
        JsonArray recordedRingStrokes = recordedResult.getAsJsonArray("ringStrokeIndices");
        assertEquals(result.debugResult.ringStrokeIndices().size(), recordedRingStrokes.size());
        for (int index = 0; index < recordedRingStrokes.size(); index++) {
            assertEquals(
                    result.debugResult.ringStrokeIndices().get(index),
                    recordedRingStrokes.get(index).getAsInt());
        }
    }
}
