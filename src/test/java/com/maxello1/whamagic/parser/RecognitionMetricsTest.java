package com.maxello1.whamagic.parser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs all fixtures through the parser and produces recognition accuracy metrics.
 * Outputs results to build/reports/recognition-metrics.txt.
 */
public class RecognitionMetricsTest {

    private static final Gson GSON = new Gson();

    @BeforeAll
    public static void setup() {
        SpellDictionary.ensureLoaded();
    }

    @Test
    public void produceRecognitionMetrics() throws Exception {
        File dir = new File("src/test/resources/fixtures");
        assertTrue(dir.exists() && dir.isDirectory(), "Fixtures directory must exist");

        File[] fixtureFiles = dir.listFiles((d, name) -> name.endsWith(".json"));
        assertNotNull(fixtureFiles, "Fixture files must exist");
        assertTrue(fixtureFiles.length > 0, "Must have at least one fixture");

        int totalPositive = 0;
        int totalNegative = 0;
        int top1Correct = 0;
        int top3Correct = 0;
        int falsePositives = 0;
        int falseRejections = 0;
        double totalConfidenceGap = 0;
        int confidenceGapCount = 0;
        int maxCandidates = 0;
        int maxRecognitionCalls = 0;

        List<String> falsePositiveDetails = new ArrayList<>();
        List<String> falseRejectionDetails = new ArrayList<>();
        List<String> confusionPairs = new ArrayList<>();
        StringBuilder report = new StringBuilder();

        report.append("=== Recognition Metrics Report ===\n\n");

        for (File file : fixtureFiles) {
            JsonObject fixture = GSON.fromJson(new FileReader(file), JsonObject.class);
            String expectedSpell = fixture.get("expectedSpell").getAsString();
            boolean isPositive = !expectedSpell.isEmpty();

            // Parse strokes
            List<List<Point>> strokes = new ArrayList<>();
            JsonArray strokesArr = fixture.getAsJsonArray("strokes");
            for (JsonElement strokeElem : strokesArr) {
                List<Point> stroke = new ArrayList<>();
                for (JsonElement pointElem : strokeElem.getAsJsonArray()) {
                    JsonObject pt = pointElem.getAsJsonObject();
                    stroke.add(new Point(pt.get("x").getAsDouble(), pt.get("y").getAsDouble()));
                }
                strokes.add(stroke);
            }

            SpellParser.ParseResult result = SpellParser.parse(strokes);

            // Track limits
            if (result.debugResult != null) {
                maxCandidates = Math.max(maxCandidates, result.debugResult.candidateCount());
                maxRecognitionCalls = Math.max(maxRecognitionCalls, result.debugResult.recognitionCalls());
            }

            // Get actual recognized IDs
            List<String> recognizedIds = new ArrayList<>();
            if (result.ast != null && result.ast.sigils() != null) {
                for (var sigil : result.ast.sigils()) {
                    if (sigil.id() != null) {
                        recognizedIds.add(sigil.id().getPath());
                    }
                }
            }
            if (result.ast != null && result.ast.signs() != null) {
                for (var sign : result.ast.signs()) {
                    recognizedIds.add(sign.id());
                }
            }

            // Get top-3 alternative IDs from all sources
            List<String> top3Ids = new ArrayList<>();
            if (result.debugResult != null && result.debugResult.allEvaluated() != null) {
                for (var eval : result.debugResult.allEvaluated()) {
                    if (eval.sigilRes != null && eval.sigilRes.alternatives != null) {
                        for (int i = 0; i < Math.min(3, eval.sigilRes.alternatives.size()); i++) {
                            var alt = eval.sigilRes.alternatives.get(i);
                            if (alt.id() != null) {
                                top3Ids.add(alt.id().getPath());
                            }
                        }
                    }
                    if (eval.signRes != null && eval.signRes.alternatives != null) {
                        for (int i = 0; i < Math.min(3, eval.signRes.alternatives.size()); i++) {
                            var alt = eval.signRes.alternatives.get(i);
                            if (alt.id() != null) {
                                top3Ids.add(alt.id().getPath());
                            }
                        }
                    }
                }
            }

            report.append(String.format("%-35s expected=%-20s recognized=%-30s\n",
                    file.getName(), expectedSpell.isEmpty() ? "(none)" : expectedSpell,
                    recognizedIds.isEmpty() ? "(none)" : String.join(", ", recognizedIds)));

            if (isPositive) {
                totalPositive++;
                boolean top1Match = recognizedIds.contains(expectedSpell);
                boolean top3Match = top1Match || top3Ids.contains(expectedSpell);

                if (top1Match) {
                    top1Correct++;
                    // Track confidence gap for correct identifications
                    if (result.ast != null && result.ast.sigils() != null) {
                        for (var sigil : result.ast.sigils()) {
                            if (sigil.alternatives() != null && sigil.alternatives().size() >= 2) {
                                double gap = sigil.alternatives().get(0).rawScore() - sigil.alternatives().get(1).rawScore();
                                totalConfidenceGap += gap;
                                confidenceGapCount++;
                            }
                        }
                    }
                }
                if (top3Match) top3Correct++;

                if (!top1Match) {
                    String actual = recognizedIds.isEmpty() ? "(rejected)" : recognizedIds.get(0);
                    if (recognizedIds.isEmpty()) {
                        falseRejections++;
                        falseRejectionDetails.add(file.getName() + ": expected=" + expectedSpell);
                    } else {
                        confusionPairs.add(file.getName() + ": expected=" + expectedSpell + " got=" + actual);
                    }
                }
            } else {
                totalNegative++;
                if (!recognizedIds.isEmpty()) {
                    falsePositives++;
                    falsePositiveDetails.add(file.getName() + ": got=" + String.join(", ", recognizedIds));
                }
            }
        }

        // Summary
        report.append("\n=== Summary ===\n");
        report.append(String.format("Total fixtures:      %d (positive: %d, negative: %d)\n", 
                totalPositive + totalNegative, totalPositive, totalNegative));
        report.append(String.format("Top-1 accuracy:      %d/%d (%.1f%%)\n", 
                top1Correct, totalPositive, totalPositive > 0 ? 100.0 * top1Correct / totalPositive : 0));
        report.append(String.format("Top-3 accuracy:      %d/%d (%.1f%%)\n", 
                top3Correct, totalPositive, totalPositive > 0 ? 100.0 * top3Correct / totalPositive : 0));
        report.append(String.format("False positives:     %d/%d\n", falsePositives, totalNegative));
        report.append(String.format("False rejections:    %d/%d\n", falseRejections, totalPositive));
        report.append(String.format("Avg confidence gap:  %.3f\n", 
                confidenceGapCount > 0 ? totalConfidenceGap / confidenceGapCount : 0));
        report.append(String.format("Max candidates:      %d\n", maxCandidates));
        report.append(String.format("Max recognition calls: %d\n", maxRecognitionCalls));

        if (!confusionPairs.isEmpty()) {
            report.append("\n=== Confusion Pairs ===\n");
            for (String pair : confusionPairs) {
                report.append("  " + pair + "\n");
            }
        }
        if (!falsePositiveDetails.isEmpty()) {
            report.append("\n=== False Positives ===\n");
            for (String fp : falsePositiveDetails) {
                report.append("  " + fp + "\n");
            }
        }
        if (!falseRejectionDetails.isEmpty()) {
            report.append("\n=== False Rejections ===\n");
            for (String fr : falseRejectionDetails) {
                report.append("  " + fr + "\n");
            }
        }

        // Write report
        File reportsDir = new File("build/reports");
        reportsDir.mkdirs();
        try (FileWriter writer = new FileWriter(new File(reportsDir, "recognition-metrics.txt"))) {
            writer.write(report.toString());
        }

        System.out.println(report);

        // The test itself passes — it produces the report.
        // Specific accuracy thresholds are not enforced in Phase 1.
    }
}
