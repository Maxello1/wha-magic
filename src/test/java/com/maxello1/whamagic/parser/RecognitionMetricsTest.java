package com.maxello1.whamagic.parser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.maxello1.whamagic.magic.RecognitionAlternative;
import com.maxello1.whamagic.magic.RecognitionRejectionReason;
import com.maxello1.whamagic.parser.SelectionEngine.EvaluatedCandidate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
        StringBuilder detailReport = new StringBuilder();

        // Per-symbol tracking: symbol name -> [total, correct, rejection reason for failures]
        Map<String, int[]> perSymbolCounts = new LinkedHashMap<>();
        Map<String, String> perSymbolRejection = new LinkedHashMap<>();

        // Rejection reason counts across all positive fixture evaluations
        EnumMap<RecognitionRejectionReason, Integer> rejectionCounts = new EnumMap<>(RecognitionRejectionReason.class);
        for (RecognitionRejectionReason r : RecognitionRejectionReason.values()) {
            rejectionCounts.put(r, 0);
        }

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
            
            // Log ring detection for debugging
            if (result.ast != null && result.ast.ring() != null) {
                var ring = result.ast.ring();
                detailReport.append(String.format("  [RING] %s: ring detected (r=%.3f, completeness=%.3f, rmse=%.4f)\n",
                        file.getName(), ring.radius(), ring.completeness(), ring.rmse()));
            }

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

                // Per-symbol tracking
                perSymbolCounts.computeIfAbsent(expectedSpell, k -> new int[]{0, 0});
                perSymbolCounts.get(expectedSpell)[0]++;
                if (top1Match) {
                    perSymbolCounts.get(expectedSpell)[1]++;
                }

                // === Per-fixture diagnostic block for POSITIVE fixtures ===
                appendFixtureDiagnostics(detailReport, file.getName(), expectedSpell,
                        recognizedIds.contains(expectedSpell), result, rejectionCounts, perSymbolRejection, expectedSpell);
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

        // Per-symbol accuracy table
        report.append("\n=== Per-Symbol Results ===\n");
        for (Map.Entry<String, int[]> entry : perSymbolCounts.entrySet()) {
            String symbol = entry.getKey();
            int total = entry.getValue()[0];
            int correct = entry.getValue()[1];
            double pct = total > 0 ? 100.0 * correct / total : 0;
            String line = String.format("  %-20s %d/%d (%.1f%%)", symbol + ":", correct, total, pct);
            if (correct < total && perSymbolRejection.containsKey(symbol)) {
                line += "  [rejected: " + perSymbolRejection.get(symbol) + "]";
            }
            report.append(line + "\n");
        }

        // Rejection reason counts
        report.append("\n=== Rejection Reason Counts ===\n");
        for (Map.Entry<RecognitionRejectionReason, Integer> entry : rejectionCounts.entrySet()) {
            report.append(String.format("  %-30s %d\n", entry.getKey() + ":", entry.getValue()));
        }

        // Append per-fixture detail diagnostics
        report.append("\n");
        report.append(detailReport);

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

    /**
     * Append detailed diagnostic information for a single positive fixture.
     * Finds the best alternative across all evaluated candidates and prints
     * recognition details for debugging.
     */
    private void appendFixtureDiagnostics(StringBuilder out, String fileName, String expectedId,
                                          boolean recognised, SpellParser.ParseResult result,
                                          EnumMap<RecognitionRejectionReason, Integer> rejectionCounts,
                                          Map<String, String> perSymbolRejection, String symbolName) {
        if (result.debugResult == null || result.debugResult.allEvaluated() == null
                || result.debugResult.allEvaluated().isEmpty()) {
            out.append(String.format("  [DETAIL] %s:\n    (no evaluated candidates)\n\n", fileName));
            return;
        }

        List<EvaluatedCandidate> allEval = result.debugResult.allEvaluated();
        int candidatesEvaluated = allEval.size();

        // Find the best alternative across all evaluated candidates (sigil + sign)
        RecognitionAlternative bestAlt = null;
        RecognitionAlternative secondBestAlt = null;
        RasterRecognizer.RecognitionResult bestRes = null;
        EvaluatedCandidate bestEvalCand = null;
        String bestKind = "UNKNOWN";

        // Collect all alternatives from all candidates for global best/second-best
        List<AlternativeWithContext> allAlts = new ArrayList<>();
        for (EvaluatedCandidate eval : allEval) {
            if (eval.sigilRes != null && eval.sigilRes.alternatives != null) {
                for (RecognitionAlternative alt : eval.sigilRes.alternatives) {
                    allAlts.add(new AlternativeWithContext(alt, "SIGIL", eval, eval.sigilRes));
                }
            }
            if (eval.signRes != null && eval.signRes.alternatives != null) {
                for (RecognitionAlternative alt : eval.signRes.alternatives) {
                    allAlts.add(new AlternativeWithContext(alt, "SIGN", eval, eval.signRes));
                }
            }
        }

        // Sort by raw score descending
        allAlts.sort((a, b) -> Double.compare(b.alt.rawScore(), a.alt.rawScore()));

        if (!allAlts.isEmpty()) {
            AlternativeWithContext bestCtx = allAlts.get(0);
            bestAlt = bestCtx.alt;
            bestRes = bestCtx.res;
            bestEvalCand = bestCtx.eval;
            bestKind = bestCtx.kind;
        }
        if (allAlts.size() > 1) {
            secondBestAlt = allAlts.get(1).alt;
        }

        String bestMatchId = bestAlt != null && bestAlt.id() != null ? bestAlt.id().getPath() : "(none)";
        double rawConf = bestAlt != null ? bestAlt.rawScore() : 0;
        double secondConf = secondBestAlt != null ? secondBestAlt.rawScore() : 0;
        String secondBestId = secondBestAlt != null && secondBestAlt.id() != null
                ? secondBestAlt.id().getPath() : "(none)";
        double gap = rawConf - secondConf;

        RecognitionRejectionReason reason = RecognitionRejectionReason.NONE;
        if (bestRes != null && bestRes.rejectionReason != null) {
            reason = bestRes.rejectionReason;
        }

        // Track rejection reason
        rejectionCounts.merge(reason, 1, Integer::sum);

        // Track per-symbol rejection reason for failed recognitions
        if (!recognised && reason != RecognitionRejectionReason.NONE) {
            perSymbolRejection.putIfAbsent(symbolName, reason.name());
        }

        double templateCoverage = bestAlt != null ? bestAlt.templateCoverage() : 0;
        double explainedRatio = bestAlt != null ? bestAlt.candidateExplainedRatio() : 0;
        double unexplainedInk = bestAlt != null ? bestAlt.unexplainedInkRatio() : 0;
        double structuralScore = bestAlt != null ? bestAlt.structuralScore() : 0;

        List<Integer> sourceStrokes = bestEvalCand != null
                ? bestEvalCand.cand.sourceStrokeIndices() : List.of();
        int candidateId = bestEvalCand != null ? bestEvalCand.cand.id() : -1;

        out.append(String.format("  [DETAIL] %s:\n", fileName));
        out.append(String.format("    Expected:          %s\n", expectedId));
        out.append(String.format("    Best match:        %s (via %s, conf=%.3f)\n", bestMatchId, bestKind, rawConf));
        out.append(String.format("    Second best:       %s (conf=%.3f)\n", secondBestId, secondConf));
        out.append(String.format("    Gap:               %.3f\n", gap));
        out.append(String.format("    Recognised:        %s\n", recognised));
        out.append(String.format("    Rejection reason:  %s\n", reason));
        out.append(String.format("    Template coverage: %.3f\n", templateCoverage));
        out.append(String.format("    Explained ratio:   %.3f\n", explainedRatio));
        out.append(String.format("    Unexplained ink:   %.3f\n", unexplainedInk));
        out.append(String.format("    Structural score:  %.3f\n", structuralScore));
        out.append(String.format("    Source strokes:    %s\n", sourceStrokes));
        out.append(String.format("    Candidate ID:      %d\n", candidateId));
        out.append(String.format("    Candidates evaluated: %d\n", candidatesEvaluated));
        out.append("\n");
    }

    /** Associates a RecognitionAlternative with its source context for diagnostic lookup. */
    private record AlternativeWithContext(
        RecognitionAlternative alt,
        String kind,
        EvaluatedCandidate eval,
        RasterRecognizer.RecognitionResult res
    ) {}
}
