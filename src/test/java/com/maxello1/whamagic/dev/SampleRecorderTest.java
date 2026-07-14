package com.maxello1.whamagic.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maxello1.whamagic.magic.SymbolKind;
import com.maxello1.whamagic.parser.Point;
import com.maxello1.whamagic.parser.SpellParser;
import net.minecraft.resources.Identifier;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleRecorderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesStructuredIntentAndExactRawCoordinates() throws Exception {
        LocalDateTime recordedAt = LocalDateTime.of(2026, 7, 14, 5, 23, 10);
        List<List<Point>> strokes = preciseStrokes();
        RecognitionSampleMetadata metadata = metadata(
                "Morning layout",
                List.of(
                        symbol("fire", SymbolKind.SIGIL, null),
                        symbol("column", SymbolKind.SIGN, 90.0)),
                true,
                RecognitionSampleMetadata.RingStyle.SINGLE_STROKE,
                "Preserve these coordinates exactly");

        Path sample = SampleRecorder.writeSample(
                temporaryDirectory, strokes, null, metadata, recordedAt);

        assertEquals(
                "sample_2026-07-14_05-23-10-000_morning-layout_fire_column-r090_ring__got-unknown_unparsed.json",
                sample.getFileName().toString());
        JsonObject json = read(sample);
        assertEquals(4, json.get("formatVersion").getAsInt());
        assertEquals("Morning layout", json.get("sampleName").getAsString());
        assertEquals("experimental", json.get("sampleRole").getAsString());
        assertEquals("Preserve these coordinates exactly", json.get("notes").getAsString());
        assertFalse(json.has("simplifiedStrokes"));

        JsonObject intent = json.getAsJsonObject("expectedIntent");
        assertEquals("wha-magic:fire", intent.getAsJsonArray("sigils")
                .get(0).getAsJsonObject().get("id").getAsString());
        assertTrue(intent.getAsJsonArray("sigils")
                .get(0).getAsJsonObject().get("rotationDeg").isJsonNull());
        assertEquals(90.0, intent.getAsJsonArray("signs")
                .get(0).getAsJsonObject().get("rotationDeg").getAsDouble());
        assertTrue(intent.get("ring").getAsBoolean());
        assertEquals("single_stroke", intent.get("ringStyle").getAsString());

        assertRawCoordinates(strokes, json.getAsJsonArray("rawStrokes"));
    }

    @Test
    void keepsIntendedIntentSeparateFromActualRecognition() throws Exception {
        List<List<Point>> strokes = List.of(List.of(new Point(0.1, 0.1), new Point(0.12, 0.12)));
        SpellParser.ParseResult actual = SpellParser.parse(strokes);
        RecognitionSampleMetadata metadata = metadata(
                "", List.of(symbol("fire", SymbolKind.SIGIL, null)), false,
                RecognitionSampleMetadata.RingStyle.NONE, "");

        Path sample = SampleRecorder.writeSample(
                temporaryDirectory, strokes, actual, metadata,
                LocalDateTime.of(2026, 7, 14, 6, 0));
        JsonObject json = read(sample);

        assertEquals("wha-magic:fire", json.getAsJsonObject("expectedIntent")
                .getAsJsonArray("sigils").get(0).getAsJsonObject().get("id").getAsString());
        assertTrue(json.has("result"));
        assertEquals(actual.isValidSpell(), json.getAsJsonObject("result").get("valid").getAsBoolean());
    }

    @Test
    void sanitizesAndBoundsFilenamesWithoutLosingTimestampPrefix() {
        List<RecognitionSampleMetadata.IntendedSymbol> manySymbols =
                java.util.stream.IntStream.range(0, 30)
                        .mapToObj(index -> symbol("wind-directs-air", SymbolKind.SIGIL, (double) index))
                        .toList();
        RecognitionSampleMetadata metadata = metadata(
                "../../Unsafe : Sample * Name?", manySymbols, true,
                RecognitionSampleMetadata.RingStyle.OVERTRACED, "");

        String filename = SampleRecorder.buildFilename(
                metadata, null, LocalDateTime.of(2026, 7, 14, 7, 8, 9));

        assertTrue(filename.startsWith("sample_2026-07-14_07-08-09-000_"));
        assertTrue(filename.endsWith("__got-unknown_unparsed.json"));
        assertTrue(filename.length() <= SampleRecorder.MAX_FILENAME_LENGTH);
        assertFalse(filename.contains(".."));
        assertFalse(filename.matches(".*[\\\\/:*?\"<>|].*"));
    }

    @Test
    void cancellingCaptureDoesNotWriteAFile() throws Exception {
        RecognitionSampleCapture capture = new RecognitionSampleCapture(preciseStrokes(), null);

        capture.cancel();

        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(0, files.count());
        }
        assertThrows(IllegalStateException.class, () -> capture.save(
                temporaryDirectory,
                metadata("", List.of(), false, RecognitionSampleMetadata.RingStyle.NONE, ""),
                LocalDateTime.now()));
    }

    @Test
    void successfulCaptureSavePreservesRawCoordinates() throws Exception {
        List<List<Point>> strokes = preciseStrokes();
        RecognitionSampleCapture capture = new RecognitionSampleCapture(strokes, null);

        Path sample = capture.save(
                temporaryDirectory,
                metadata("", List.of(), false, RecognitionSampleMetadata.RingStyle.NONE, ""),
                LocalDateTime.of(2026, 7, 14, 8, 0));

        assertRawCoordinates(strokes, read(sample).getAsJsonArray("rawStrokes"));
    }

    @Test
    void preservesRecognitionAndDiagnosticFields() throws Exception {
        List<List<Point>> strokes = List.of(List.of(new Point(0.10, 0.10), new Point(0.12, 0.12)));
        SpellParser.ParseResult result = SpellParser.parse(strokes);

        Path sample = SampleRecorder.writeSample(
                temporaryDirectory, strokes, result,
                metadata("", List.of(), false, RecognitionSampleMetadata.RingStyle.NONE, ""),
                LocalDateTime.of(2026, 7, 14, 9, 0));
        JsonObject recordedResult = read(sample).getAsJsonObject("result");

        assertEquals(result.isValidSpell(), recordedResult.get("valid").getAsBoolean());
        assertTrue(recordedResult.get("sigils").isJsonArray());
        assertTrue(recordedResult.get("signs").isJsonArray());
        assertTrue(recordedResult.get("unknowns").isJsonArray());
        assertEquals(result.debugResult.candidateCount(), recordedResult.get("candidateCount").getAsInt());
        assertEquals(result.debugResult.recognitionCalls(), recordedResult.get("recognitionCalls").getAsInt());
    }

    private static RecognitionSampleMetadata metadata(
            String name,
            List<RecognitionSampleMetadata.IntendedSymbol> symbols,
            boolean ring,
            RecognitionSampleMetadata.RingStyle ringStyle,
            String notes) {
        return new RecognitionSampleMetadata(
                name, symbols, ring, ringStyle,
                RecognitionSampleMetadata.SampleRole.EXPERIMENTAL,
                false, notes, false);
    }

    private static RecognitionSampleMetadata.IntendedSymbol symbol(
            String path, SymbolKind kind, Double rotation) {
        return new RecognitionSampleMetadata.IntendedSymbol(
                Identifier.fromNamespaceAndPath("wha-magic", path), kind, rotation);
    }

    private static List<List<Point>> preciseStrokes() {
        return List.of(
                List.of(
                        new Point(0.123456789012345, 0.987654321098765),
                        new Point(0.223456789012345, 0.887654321098765)),
                List.of(
                        new Point(0.333333333333333, 0.666666666666667),
                        new Point(0.777777777777777, 0.111111111111111)));
    }

    private static JsonObject read(Path sample) throws Exception {
        try (Reader reader = Files.newBufferedReader(sample, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static void assertRawCoordinates(
            List<List<Point>> expected,
            JsonArray actual) {
        assertEquals(expected.size(), actual.size());
        for (int strokeIndex = 0; strokeIndex < expected.size(); strokeIndex++) {
            JsonArray actualStroke = actual.get(strokeIndex).getAsJsonArray();
            assertEquals(expected.get(strokeIndex).size(), actualStroke.size());
            for (int pointIndex = 0; pointIndex < expected.get(strokeIndex).size(); pointIndex++) {
                Point point = expected.get(strokeIndex).get(pointIndex);
                JsonObject recorded = actualStroke.get(pointIndex).getAsJsonObject();
                assertEquals(point.x, recorded.get("x").getAsDouble());
                assertEquals(point.y, recorded.get("y").getAsDouble());
            }
        }
    }
}
