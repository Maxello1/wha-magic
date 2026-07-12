package com.maxello1.whamagic.parser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.maxello1.whamagic.magic.RingDetector;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileReader;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dedicated unit test for RingDetector circle-vs-polygon rejection.
 *
 * <p>Tests the detector directly — "Was this geometry detected as a ring?" —
 * separately from RecognitionMetricsTest which answers "Was the entire spell
 * interpreted correctly?"</p>
 */
public class RingDetectorTest {

    private static final Gson GSON = new Gson();

    @Test
    public void testRingShapeFixtures() throws Exception {
        File ringShapesDir = new File("src/test/resources/fixtures/canonical/ring_shapes");
        assertTrue(ringShapesDir.exists() && ringShapesDir.isDirectory(),
                "Ring shapes fixture directory must exist: " + ringShapesDir.getAbsolutePath());

        File[] fixtures = ringShapesDir.listFiles((dir, name) -> name.endsWith(".json"));
        assertNotNull(fixtures);
        assertFalse(fixtures.length == 0, "Ring shape fixtures must not be empty");

        List<String> failures = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        report.append("=== RingDetector Shape Test Report ===\n\n");

        Arrays.sort(fixtures, Comparator.comparing(File::getName));

        for (File f : fixtures) {
            JsonObject json = GSON.fromJson(new FileReader(f), JsonObject.class);
            boolean expectRing = json.has("expectRing") && json.get("expectRing").getAsBoolean();
            List<List<Point>> strokes = parseStrokes(json.getAsJsonArray("strokes"));

            RingDetector.RingDetection detection = RingDetector.detectRing(strokes);
            boolean ringDetected = detection != null;

            report.append(String.format("%-35s expect=%-5s detected=%-5s",
                    f.getName(), expectRing, ringDetected));

            if (detection != null) {
                var g = detection.glyph();
                report.append(String.format(" r=%.3f compl=%.3f rmse=%.4f normRmse=%.4f " +
                                "maxResid=%.4f residStd=%.4f medTang=%.4f p90Tang=%.4f circ=%.4f strokes=%s",
                        g.radius(), g.completeness(), g.rmse(),
                        g.normalizedRmse(), g.maxNormalizedResidual(), g.residualStdDev(),
                        g.medianTangentAlignment(), g.p90TangentAlignment(), g.circularity(),
                        detection.ringStrokeIndices()));
            }
            report.append("\n");

            if (expectRing != ringDetected) {
                failures.add(f.getName() + ": expectRing=" + expectRing + " detected=" + ringDetected);
            }
        }

        // Write report
        new File("build/reports").mkdirs();
        try (var writer = new java.io.FileWriter("build/reports/ring-detector-test.txt")) {
            writer.write(report.toString());
        }

        assertTrue(failures.isEmpty(),
                "Ring detection mismatches:\n  " + String.join("\n  ", failures));
    }

    /**
     * Test that spell_light_complete.json detects the actual circular stroke (index 0)
     * as the ring, not Light's outer square.
     */
    @Test
    public void testLightCompleteRingStrokeIndex() throws Exception {
        File f = new File("src/test/resources/fixtures/canonical/multi/spell_light_complete.json");
        if (!f.exists()) {
            fail("spell_light_complete.json must exist");
        }

        JsonObject json = GSON.fromJson(new FileReader(f), JsonObject.class);
        List<List<Point>> strokes = parseStrokes(json.getAsJsonArray("strokes"));

        // The fixture should have expectedRingStrokeIndices = [0]
        JsonArray expectedIndices = json.has("expectedRingStrokeIndices")
                ? json.getAsJsonArray("expectedRingStrokeIndices") : null;
        assertNotNull(expectedIndices, "spell_light_complete.json must have expectedRingStrokeIndices");

        Set<Integer> expected = new HashSet<>();
        for (JsonElement e : expectedIndices) {
            expected.add(e.getAsInt());
        }

        RingDetector.RingDetection detection = RingDetector.detectRing(strokes);
        assertNotNull(detection, "Ring must be detected in spell_light_complete.json");

        assertEquals(expected, detection.ringStrokeIndices(),
                "Ring must be detected on the circular stroke (index 0), not Light's outer square. " +
                        "Detected ring strokes: " + detection.ringStrokeIndices());
    }

    /**
     * Test that the standalone Light sigil (with outer square but no circle)
     * does NOT have a ring detected.
     */
    @Test
    public void testStandaloneLightNoRing() throws Exception {
        File f = new File("src/test/resources/fixtures/canonical/positive/sigil_light.json");
        if (!f.exists()) {
            fail("sigil_light.json must exist");
        }

        JsonObject json = GSON.fromJson(new FileReader(f), JsonObject.class);
        List<List<Point>> strokes = parseStrokes(json.getAsJsonArray("strokes"));

        RingDetector.RingDetection detection = RingDetector.detectRing(strokes);
        assertNull(detection,
                "Standalone Light's outer square must NOT be detected as a ring. " +
                        (detection != null ? "Detected ring: r=" +
                                String.format("%.3f", detection.glyph().radius()) +
                                " strokes=" + detection.ringStrokeIndices() : ""));
    }

    private static List<List<Point>> parseStrokes(JsonArray strokesArr) {
        List<List<Point>> strokes = new ArrayList<>();
        for (JsonElement s : strokesArr) {
            List<Point> stroke = new ArrayList<>();
            for (JsonElement p : s.getAsJsonArray()) {
                JsonObject pt = p.getAsJsonObject();
                stroke.add(new Point(pt.get("x").getAsDouble(), pt.get("y").getAsDouble()));
            }
            strokes.add(stroke);
        }
        return strokes;
    }
}
