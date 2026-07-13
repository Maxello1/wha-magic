package com.maxello1.whamagic.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SamplePromotionToolTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void promotionPreservesRawStrokesExactlyAndWritesMetadata() throws Exception {
        Path input = temporaryDirectory.resolve("recorded.json");
        Path output = temporaryDirectory.resolve("fixture.json");
        JsonObject recorded = recordedSample();
        writeJson(input, recorded);

        SamplePromotionTool.Preview preview = SamplePromotionTool.preview(input);
        SamplePromotionTool.promote(new SamplePromotionTool.PromotionRequest(
                input, output, "precise_sample",
                SamplePromotionTool.SampleRole.EXPERIMENTAL,
                List.of("earth"), List.of("levitation", "levitation"),
                true, true, "exact coordinates", LocalDate.of(2026, 7, 13), false));

        JsonObject fixture = readJson(output);
        assertEquals(recorded.getAsJsonArray("rawStrokes"), fixture.getAsJsonArray("strokes"));
        assertEquals(3, fixture.get("formatVersion").getAsInt());
        assertEquals("experimental", fixture.get("sampleRole").getAsString());
        assertEquals("point-cloud-p-curvature-1", fixture.get("recognizerVersion").getAsString());
        assertFalse(fixture.get("dictionaryHash").getAsString().isBlank());
        assertEquals("2026-07-13", fixture.get("sourceDate").getAsString());
        assertFalse(fixture.get("influencedTemplateOrThreshold").getAsBoolean());
        assertEquals(List.of("earth"), strings(
                fixture.getAsJsonObject("expectedIntent").getAsJsonArray("sigils")));
        assertEquals(List.of("levitation", "levitation"), strings(
                fixture.getAsJsonObject("expectedIntent").getAsJsonArray("signs")));
        assertEquals(recorded.getAsJsonArray("rawStrokes").size(), preview.rawStrokes().size());
    }

    @Test
    void holdoutsCannotBeMarkedAsTrainingInfluencedOrSilentlyReclassified() throws Exception {
        Path input = temporaryDirectory.resolve("recorded.json");
        Path output = temporaryDirectory.resolve("holdout.json");
        writeJson(input, recordedSample());

        assertThrows(IllegalArgumentException.class, () ->
                new SamplePromotionTool.PromotionRequest(
                        input, output, "holdout_sample",
                        SamplePromotionTool.SampleRole.HOLDOUT,
                        List.of("earth"), List.of(), false, false, "",
                        LocalDate.of(2026, 7, 13), true));

        SamplePromotionTool.promote(new SamplePromotionTool.PromotionRequest(
                input, output, "holdout_sample",
                SamplePromotionTool.SampleRole.HOLDOUT,
                List.of("earth"), List.of(), false, false, "",
                LocalDate.of(2026, 7, 13), false));
        assertEquals("holdout", readJson(output).get("sampleRole").getAsString());
    }

    @Test
    void previewOnlyOptionIsAcceptedBeforeOrAfterTheSamplePath() {
        Path sample = Path.of("recorded.json");

        SamplePromotionTool.CommandLine after = SamplePromotionTool.parseCommandLine(
                new String[]{sample.toString(), "--preview-only"});
        SamplePromotionTool.CommandLine before = SamplePromotionTool.parseCommandLine(
                new String[]{"--preview-only", sample.toString()});
        SamplePromotionTool.CommandLine prompted = SamplePromotionTool.parseCommandLine(
                new String[]{"--preview-only"});

        assertEquals(sample, after.input());
        assertTrue(after.previewOnly());
        assertEquals(after, before);
        assertNull(prompted.input());
        assertTrue(prompted.previewOnly());
        assertThrows(IllegalArgumentException.class, () -> SamplePromotionTool.parseCommandLine(
                new String[]{"--preview-only", "--preview-only"}));
        assertThrows(IllegalArgumentException.class, () -> SamplePromotionTool.parseCommandLine(
                new String[]{sample.toString(), "other.json"}));
    }

    @Test
    void malformedPointErrorIncludesItsStrokeAndPointIndex() throws Exception {
        Path input = temporaryDirectory.resolve("malformed.json");
        JsonObject recorded = recordedSample();
        recorded.getAsJsonArray("rawStrokes")
                .get(0).getAsJsonArray()
                .get(0).getAsJsonObject()
                .remove("y");
        writeJson(input, recorded);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> SamplePromotionTool.preview(input));

        assertEquals("rawStrokes[0][0].y must be numeric", exception.getMessage());
    }

    private static JsonObject recordedSample() {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", 3);
        JsonArray strokes = new JsonArray();
        JsonArray stroke = new JsonArray();
        stroke.add(point(0.123456789012345, 0.987654321098765));
        stroke.add(point(0.223456789012345, 0.887654321098765));
        stroke.add(point(0.323456789012345, 0.787654321098765));
        strokes.add(stroke);
        root.add("rawStrokes", strokes);
        return root;
    }

    private static JsonObject point(double x, double y) {
        JsonObject point = new JsonObject();
        point.addProperty("x", x);
        point.addProperty("y", y);
        return point;
    }

    private static void writeJson(Path path, JsonObject object) throws Exception {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write(object.toString());
        }
    }

    private static JsonObject readJson(Path path) throws Exception {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static List<String> strings(JsonArray array) {
        return array.asList().stream().map(element -> element.getAsString()).toList();
    }
}
