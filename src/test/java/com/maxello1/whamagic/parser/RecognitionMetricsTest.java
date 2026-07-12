package com.maxello1.whamagic.parser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.maxello1.whamagic.magic.CandidateGenerationSettings;
import com.maxello1.whamagic.magic.RecognitionAlternative;
import com.maxello1.whamagic.magic.RecognitionRejectionReason;
import com.maxello1.whamagic.parser.SelectionEngine.EvaluatedCandidate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs all fixtures through the parser and produces recognition accuracy metrics.
 * Outputs results to build/reports/recognition-metrics.txt.
 */
public class RecognitionMetricsTest {

    private static final Gson GSON = new Gson();
    private static final int MAX_RECOGNITION_CALLS = CandidateGenerationSettings.DEFAULTS.maxRecognitionCalls();

    @BeforeAll
    public static void setup() {
        SpellDictionary.ensureLoaded();
    }

    @Test
    public void produceRecognitionMetrics() throws Exception {
        File fixturesRoot = new File("src/test/resources/fixtures");
        assertTrue(fixturesRoot.exists() && fixturesRoot.isDirectory(), "Fixtures directory must exist");

        // Discover fixtures from each category (sorted by name for deterministic ordering)
        TreeMap<String, FixtureEntry> canonicalPositive = discoverFixtures(new File(fixturesRoot, "canonical/positive"));
        TreeMap<String, FixtureEntry> canonicalNegative = discoverFixtures(new File(fixturesRoot, "canonical/negative"));
        TreeMap<String, FixtureEntry> canonicalMulti = discoverFixtures(new File(fixturesRoot, "canonical/multi"));
        TreeMap<String, FixtureEntry> canonicalInvariance = discoverFixtures(new File(fixturesRoot, "canonical/invariance"));
        TreeMap<String, FixtureEntry> canonicalRingShapes = discoverFixtures(new File(fixturesRoot, "canonical/ring_shapes"));
        TreeMap<String, FixtureEntry> holdoutPositive = discoverFixtures(new File(fixturesRoot, "holdout/positive"));
        TreeMap<String, FixtureEntry> holdoutNegative = discoverFixtures(new File(fixturesRoot, "holdout/negative"));

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

        // --- Canonical positive assertions ---
        List<String> canonicalPositiveFailures = new ArrayList<>();
        List<String> deterministicFailures = new ArrayList<>();

        for (Map.Entry<String, FixtureEntry> entry : canonicalPositive.entrySet()) {
            FixtureEntry fe = entry.getValue();
            JsonObject fixture = fe.fixture;
            File file = fe.file;

            List<String> expectedSigils = getExpectedSigils(fixture);
            List<String> expectedSigns = getExpectedSigns(fixture);
            boolean isPositive = !expectedSigils.isEmpty() || !expectedSigns.isEmpty();

            List<List<Point>> strokes = parseStrokes(fixture);
            SpellParser.ParseResult result = SpellParser.parse(strokes);

            // Determinism check: run again and verify full structural fingerprint
            SpellParser.ParseResult result2 = SpellParser.parse(strokes);
            String fp1 = buildDeterminismFingerprint(result);
            String fp2 = buildDeterminismFingerprint(result2);
            if (!fp1.equals(fp2)) {
                deterministicFailures.add(file.getName() + ": fingerprint mismatch");
            }

            // Enforce expectRing and expectValidSpell metadata
            enforceFixtureMetadata(fixture, result, file.getName(), canonicalPositiveFailures);

            // Log ring detection for debugging
            if (result.ast != null && result.ast.ring() != null) {
                var ring = result.ast.ring();
                detailReport.append(String.format("  [RING] %s: ring detected (r=%.3f, completeness=%.3f, rmse=%.4f, " +
                                "normRmse=%.4f, maxResid=%.4f, residStd=%.4f, medTangent=%.4f, p90Tangent=%.4f, circ=%.4f)\n",
                        file.getName(), ring.radius(), ring.completeness(), ring.rmse(),
                        ring.normalizedRmse(), ring.maxNormalizedResidual(), ring.residualStdDev(),
                        ring.medianTangentAlignment(), ring.p90TangentAlignment(), ring.circularity()));
            }

            // Track limits
            if (result.debugResult != null) {
                maxCandidates = Math.max(maxCandidates, result.debugResult.candidateCount());
                maxRecognitionCalls = Math.max(maxRecognitionCalls, result.debugResult.recognitionCalls());
            }

            List<String> recognizedSigils = getRecognizedSigils(result);
            List<String> recognizedSigns = getRecognizedSigns(result);
            List<String> recognizedIds = getRecognizedIds(result);

            // Get top-3 alternative IDs from all sources
            List<String> top3Ids = getTop3Ids(result);

            String expectedLabel = formatExpected(expectedSigils, expectedSigns);
            report.append(String.format("%-35s expected=%-20s recognized=%-30s\n",
                    file.getName(), expectedLabel.isEmpty() ? "(none)" : expectedLabel,
                    recognizedIds.isEmpty() ? "(none)" : String.join(", ", recognizedIds)));

            if (isPositive) {
                totalPositive++;

                // Check sigils and signs separately as multisets
                boolean sigilMatch = multisetEquals(recognizedSigils, expectedSigils);
                boolean signMatch = multisetEquals(recognizedSigns, expectedSigns);
                boolean top1Match = sigilMatch && signMatch;

                // Check top-3 fallback (any expected in top-3 alternatives)
                boolean top3Match = top1Match;
                if (!top1Match) {
                    for (String s : expectedSigils) {
                        if (top3Ids.contains(s)) { top3Match = true; break; }
                    }
                    for (String s : expectedSigns) {
                        if (top3Ids.contains(s)) { top3Match = true; break; }
                    }
                }

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
                } else {
                    canonicalPositiveFailures.add(file.getName()
                            + ": expected_sigils=" + expectedSigils + " expected_signs=" + expectedSigns
                            + " got_sigils=" + recognizedSigils + " got_signs=" + recognizedSigns);
                }
                if (top3Match) top3Correct++;

                if (!top1Match) {
                    if (recognizedIds.isEmpty()) {
                        falseRejections++;
                        falseRejectionDetails.add(file.getName() + ": expected=" + expectedLabel);
                    } else {
                        String actual = recognizedIds.get(0);
                        confusionPairs.add(file.getName() + ": expected=" + expectedLabel + " got=" + actual);
                    }
                }

                // Per-symbol tracking
                String symbolName = expectedLabel;
                perSymbolCounts.computeIfAbsent(symbolName, k -> new int[]{0, 0});
                perSymbolCounts.get(symbolName)[0]++;
                if (top1Match) {
                    perSymbolCounts.get(symbolName)[1]++;
                }

                // Per-fixture diagnostic block for POSITIVE fixtures
                appendFixtureDiagnostics(detailReport, file.getName(), expectedLabel,
                        top1Match, result, rejectionCounts, perSymbolRejection, symbolName);
            } else {
                totalNegative++;
                if (!recognizedIds.isEmpty()) {
                    falsePositives++;
                    falsePositiveDetails.add(file.getName() + ": got=" + String.join(", ", recognizedIds));
                }
            }
        }

        // --- Canonical negative assertions ---
        List<String> canonicalNegativeFailures = new ArrayList<>();

        for (Map.Entry<String, FixtureEntry> entry : canonicalNegative.entrySet()) {
            FixtureEntry fe = entry.getValue();
            JsonObject fixture = fe.fixture;
            File file = fe.file;

            List<List<Point>> strokes = parseStrokes(fixture);
            SpellParser.ParseResult result = SpellParser.parse(strokes);

            // Determinism check
            SpellParser.ParseResult result2 = SpellParser.parse(strokes);
            String fp1 = buildDeterminismFingerprint(result);
            String fp2 = buildDeterminismFingerprint(result2);
            if (!fp1.equals(fp2)) {
                deterministicFailures.add(file.getName() + ": fingerprint mismatch");
            }

            // Enforce metadata
            enforceFixtureMetadata(fixture, result, file.getName(), canonicalNegativeFailures);

            // Track limits
            if (result.debugResult != null) {
                maxCandidates = Math.max(maxCandidates, result.debugResult.candidateCount());
                maxRecognitionCalls = Math.max(maxRecognitionCalls, result.debugResult.recognitionCalls());
            }

            List<String> recognizedIds = getRecognizedIds(result);

            totalNegative++;
            report.append(String.format("%-35s expected=%-20s recognized=%-30s\n",
                    file.getName(), "(none)",
                    recognizedIds.isEmpty() ? "(none)" : String.join(", ", recognizedIds)));

            if (!recognizedIds.isEmpty()) {
                falsePositives++;
                falsePositiveDetails.add(file.getName() + ": got=" + String.join(", ", recognizedIds));
                canonicalNegativeFailures.add(file.getName() + ": got=" + String.join(", ", recognizedIds));
                appendSelectionDiagnostics(detailReport, file.getName(), result);
            }
        }

        // --- Canonical multi assertions ---
        List<String> canonicalMultiFailures = new ArrayList<>();

        for (Map.Entry<String, FixtureEntry> entry : canonicalMulti.entrySet()) {
            FixtureEntry fe = entry.getValue();
            JsonObject fixture = fe.fixture;
            File file = fe.file;

            List<String> expectedSigils = getExpectedSigils(fixture);
            List<String> expectedSigns = getExpectedSigns(fixture);

            List<List<Point>> strokes = parseStrokes(fixture);
            long parseStartNanos = System.nanoTime();
            SpellParser.ParseResult result = SpellParser.parse(strokes);
            double parseDurationMs = (System.nanoTime() - parseStartNanos) / 1_000_000.0;

            // Determinism check
            SpellParser.ParseResult result2 = SpellParser.parse(strokes);
            String fp1 = buildDeterminismFingerprint(result);
            String fp2 = buildDeterminismFingerprint(result2);
            if (!fp1.equals(fp2)) {
                deterministicFailures.add(file.getName() + ": fingerprint mismatch");
            }

            // Enforce metadata
            enforceFixtureMetadata(fixture, result, file.getName(), canonicalMultiFailures);

            // Track limits
            if (result.debugResult != null) {
                maxCandidates = Math.max(maxCandidates, result.debugResult.candidateCount());
                maxRecognitionCalls = Math.max(maxRecognitionCalls, result.debugResult.recognitionCalls());
            }

            List<String> recognizedSigils = getRecognizedSigils(result);
            List<String> recognizedSigns = getRecognizedSigns(result);

            boolean sigilMatch = multisetEquals(recognizedSigils, expectedSigils);
            boolean signMatch = multisetEquals(recognizedSigns, expectedSigns);

            String expectedLabel = formatExpected(expectedSigils, expectedSigns);
            List<String> recognizedIds = getRecognizedIds(result);
            int recognitionCalls = result.debugResult != null ? result.debugResult.recognitionCalls() : 0;
            int candidateCount = result.debugResult != null ? result.debugResult.candidateCount() : 0;
            report.append(String.format("%-35s expected=%-20s recognized=%-30s calls=%-4d candidates=%-4d parseMs=%.3f\n",
                    file.getName(), expectedLabel.isEmpty() ? "(none)" : expectedLabel,
                    recognizedIds.isEmpty() ? "(none)" : String.join(", ", recognizedIds),
                    recognitionCalls, candidateCount, parseDurationMs));

            if (!sigilMatch || !signMatch) {
                canonicalMultiFailures.add(file.getName()
                        + ": expected_sigils=" + expectedSigils + " expected_signs=" + expectedSigns
                        + " got_sigils=" + recognizedSigils + " got_signs=" + recognizedSigns);
                appendSelectionDiagnostics(detailReport, file.getName(), result);
            } else {
                totalPositive++;
                top1Correct++;
                top3Correct++;
            }
        }

        // --- Canonical invariance assertions ---
        List<String> canonicalInvarianceFailures = new ArrayList<>();

        for (Map.Entry<String, FixtureEntry> entry : canonicalInvariance.entrySet()) {
            FixtureEntry fe = entry.getValue();
            JsonObject fixture = fe.fixture;
            File file = fe.file;

            List<String> expectedSigils = getExpectedSigils(fixture);
            List<String> expectedSigns = getExpectedSigns(fixture);

            List<List<Point>> strokes = parseStrokes(fixture);
            SpellParser.ParseResult result = SpellParser.parse(strokes);

            // Determinism check
            SpellParser.ParseResult result2 = SpellParser.parse(strokes);
            String fp1 = buildDeterminismFingerprint(result);
            String fp2 = buildDeterminismFingerprint(result2);
            if (!fp1.equals(fp2)) {
                deterministicFailures.add(file.getName() + ": fingerprint mismatch");
            }

            // Enforce metadata
            enforceFixtureMetadata(fixture, result, file.getName(), canonicalInvarianceFailures);

            // Track limits
            if (result.debugResult != null) {
                maxCandidates = Math.max(maxCandidates, result.debugResult.candidateCount());
                maxRecognitionCalls = Math.max(maxRecognitionCalls, result.debugResult.recognitionCalls());
            }

            List<String> recognizedSigils = getRecognizedSigils(result);
            List<String> recognizedSigns = getRecognizedSigns(result);

            boolean sigilMatch = multisetEquals(recognizedSigils, expectedSigils);
            boolean signMatch = multisetEquals(recognizedSigns, expectedSigns);

            String expectedLabel = formatExpected(expectedSigils, expectedSigns);
            List<String> recognizedIds = getRecognizedIds(result);
            report.append(String.format("%-35s expected=%-20s recognized=%-30s [invariance]\n",
                    file.getName(), expectedLabel.isEmpty() ? "(none)" : expectedLabel,
                    recognizedIds.isEmpty() ? "(none)" : String.join(", ", recognizedIds)));

            if (!sigilMatch || !signMatch) {
                canonicalInvarianceFailures.add(file.getName()
                        + ": expected_sigils=" + expectedSigils + " expected_signs=" + expectedSigns
                        + " got_sigils=" + recognizedSigils + " got_signs=" + recognizedSigns);
            }
        }

        // --- Holdout fixtures (report only, don't fail) ---
        int holdoutTotal = 0;
        int holdoutCorrect = 0;

        for (Map.Entry<String, FixtureEntry> entry : holdoutPositive.entrySet()) {
            FixtureEntry fe = entry.getValue();
            JsonObject fixture = fe.fixture;
            File file = fe.file;

            List<String> expectedSigils = getExpectedSigils(fixture);
            List<String> expectedSigns = getExpectedSigns(fixture);

            List<List<Point>> strokes = parseStrokes(fixture);
            SpellParser.ParseResult result = SpellParser.parse(strokes);

            // Track limits
            if (result.debugResult != null) {
                maxCandidates = Math.max(maxCandidates, result.debugResult.candidateCount());
                maxRecognitionCalls = Math.max(maxRecognitionCalls, result.debugResult.recognitionCalls());
            }

            List<String> recognizedSigils = getRecognizedSigils(result);
            List<String> recognizedSigns = getRecognizedSigns(result);
            List<String> recognizedIds = getRecognizedIds(result);

            holdoutTotal++;
            boolean match = multisetEquals(recognizedSigils, expectedSigils) && multisetEquals(recognizedSigns, expectedSigns);
            if (match) holdoutCorrect++;

            String expectedLabel = formatExpected(expectedSigils, expectedSigns);
            report.append(String.format("%-35s expected=%-20s recognized=%-30s [holdout]\n",
                    file.getName(), expectedLabel.isEmpty() ? "(none)" : expectedLabel,
                    recognizedIds.isEmpty() ? "(none)" : String.join(", ", recognizedIds)));
        }

        for (Map.Entry<String, FixtureEntry> entry : holdoutNegative.entrySet()) {
            FixtureEntry fe = entry.getValue();
            JsonObject fixture = fe.fixture;
            File file = fe.file;

            List<List<Point>> strokes = parseStrokes(fixture);
            SpellParser.ParseResult result = SpellParser.parse(strokes);

            // Track limits
            if (result.debugResult != null) {
                maxCandidates = Math.max(maxCandidates, result.debugResult.candidateCount());
                maxRecognitionCalls = Math.max(maxRecognitionCalls, result.debugResult.recognitionCalls());
            }

            List<String> recognizedIds = getRecognizedIds(result);

            holdoutTotal++;
            if (recognizedIds.isEmpty()) holdoutCorrect++;

            report.append(String.format("%-35s expected=%-20s recognized=%-30s [holdout]\n",
                    file.getName(), "(none)",
                    recognizedIds.isEmpty() ? "(none)" : String.join(", ", recognizedIds)));
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

        // Holdout results
        report.append("\n=== HOLDOUT RESULTS ===\n");
        if (holdoutTotal > 0) {
            report.append(String.format("holdout accuracy: %d/%d (%.0f%%)\n", holdoutCorrect, holdoutTotal,
                    100.0 * holdoutCorrect / holdoutTotal));
        } else {
            report.append("holdout accuracy: 0/0 (no holdout fixtures)\n");
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

        // Print holdout results prominently to stdout
        System.out.println("\n=== HOLDOUT RESULTS ===");
        if (holdoutTotal > 0) {
            System.out.printf("holdout accuracy: %d/%d (%.0f%%)%n", holdoutCorrect, holdoutTotal,
                    100.0 * holdoutCorrect / holdoutTotal);
        } else {
            System.out.println("holdout accuracy: 0/0 (no holdout fixtures)");
        }

        // === HARD ASSERTIONS ===

        // 0. Required categories must not be empty
        assertFalse(canonicalPositive.isEmpty(),
                "Canonical positive fixtures must not be empty");
        assertFalse(canonicalNegative.isEmpty(),
                "Canonical negative fixtures must not be empty");
        assertFalse(canonicalMulti.isEmpty(),
                "Canonical multi-symbol fixtures must not be empty");
        assertFalse(canonicalInvariance.isEmpty(),
                "Canonical invariance fixtures must not be empty");
        assertFalse(canonicalRingShapes.isEmpty(),
                "Canonical ring-shape fixtures must not be empty");
        assertTrue(canonicalMulti.containsKey("spell_earth_levitation_x2.json"),
                "Earth + Levitation x2 fixture must exist");
        assertTrue(canonicalMulti.containsKey("spell_earth_levitation_x3.json"),
                "Earth + Levitation x3 fixture must exist");
        assertTrue(canonicalMulti.containsKey("spell_messy_multi.json"),
                "Messy multi-symbol fixture must exist");

        // 1. Determinism: all results must be identical across runs
        assertTrue(deterministicFailures.isEmpty(),
                "Recognition must be deterministic. Failures:\n  " + String.join("\n  ", deterministicFailures));

        // 2. Canonical positive: 100% must be recognised correctly
        assertTrue(canonicalPositiveFailures.isEmpty(),
                "All canonical positive fixtures must be recognised correctly. Failures:\n  "
                        + String.join("\n  ", canonicalPositiveFailures));

        // 3. Canonical negative: 0 false positives
        assertTrue(canonicalNegativeFailures.isEmpty(),
                "No canonical negative fixtures should be recognised as any symbol. Failures:\n  "
                        + String.join("\n  ", canonicalNegativeFailures));

        // 4. Canonical multi: 100% match IDs and multiplicity
        assertTrue(canonicalMultiFailures.isEmpty(),
                "All canonical multi-symbol fixtures must match IDs and multiplicity. Failures:\n  "
                        + String.join("\n  ", canonicalMultiFailures));

        // 5. Canonical invariance: 100% must be recognised correctly
        assertTrue(canonicalInvarianceFailures.isEmpty(),
                "All canonical invariance fixtures must be recognised correctly. Failures:\n  "
                        + String.join("\n  ", canonicalInvarianceFailures));

        // 6. Recognition calls must not exceed configured maximum
        assertTrue(maxRecognitionCalls <= MAX_RECOGNITION_CALLS,
                "Max recognition calls " + maxRecognitionCalls + " exceeds limit of " + MAX_RECOGNITION_CALLS);
    }

    // ---- Fixture discovery ----

    private TreeMap<String, FixtureEntry> discoverFixtures(File dir) throws Exception {
        TreeMap<String, FixtureEntry> fixtures = new TreeMap<>();
        if (!dir.exists() || !dir.isDirectory()) {
            return fixtures;
        }
        Path root = dir.toPath();
        collectFixturesRecursive(dir, root, fixtures);
        return fixtures;
    }

    private void collectFixturesRecursive(File dir, Path root, TreeMap<String, FixtureEntry> fixtures) throws Exception {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                collectFixturesRecursive(child, root, fixtures);
            } else if (child.getName().endsWith(".json")) {
                JsonObject fixture = GSON.fromJson(new FileReader(child), JsonObject.class);
                // Use path relative to category root to avoid key collisions
                String key = root.relativize(child.toPath()).toString().replace('\\', '/');
                fixtures.put(key, new FixtureEntry(child, fixture));
            }
        }
    }

    // ---- Fixture schema parsing ----

    private List<String> getExpectedSigils(JsonObject fixture) {
        if (fixture.has("expectedIntent")) {
            JsonObject intent = fixture.getAsJsonObject("expectedIntent");
            List<String> sigils = new ArrayList<>();
            if (intent.has("sigils")) {
                for (JsonElement e : intent.getAsJsonArray("sigils")) {
                    sigils.add(e.getAsString());
                }
            }
            return sigils;
        }
        // Legacy fallback
        String spell = fixture.get("expectedSpell").getAsString();
        if (spell.isEmpty()) return List.of();
        return List.of(spell);
    }

    private List<String> getExpectedSigns(JsonObject fixture) {
        if (fixture.has("expectedIntent")) {
            JsonObject intent = fixture.getAsJsonObject("expectedIntent");
            List<String> signs = new ArrayList<>();
            if (intent.has("signs")) {
                for (JsonElement e : intent.getAsJsonArray("signs")) {
                    signs.add(e.getAsString());
                }
            }
            return signs;
        }
        // Legacy: no sign info available
        return List.of();
    }

    // ---- Stroke parsing ----

    private List<List<Point>> parseStrokes(JsonObject fixture) {
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
        return strokes;
    }

    // ---- Result extraction ----

    private List<String> getRecognizedSigils(SpellParser.ParseResult result) {
        List<String> ids = new ArrayList<>();
        if (result.ast != null && result.ast.sigils() != null) {
            for (var sigil : result.ast.sigils()) {
                if (sigil.id() != null) {
                    ids.add(sigil.id().getPath());
                }
            }
        }
        return ids;
    }

    private List<String> getRecognizedSigns(SpellParser.ParseResult result) {
        List<String> ids = new ArrayList<>();
        if (result.ast != null && result.ast.signs() != null) {
            for (var sign : result.ast.signs()) {
                ids.add(sign.id());
            }
        }
        return ids;
    }

    private List<String> getRecognizedIds(SpellParser.ParseResult result) {
        List<String> ids = new ArrayList<>();
        ids.addAll(getRecognizedSigils(result));
        ids.addAll(getRecognizedSigns(result));
        return ids;
    }

    private List<String> getTop3Ids(SpellParser.ParseResult result) {
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
        return top3Ids;
    }

    // ---- Multiset comparison ----

    private boolean multisetEquals(List<String> a, List<String> b) {
        List<String> sortedA = new ArrayList<>(a);
        List<String> sortedB = new ArrayList<>(b);
        Collections.sort(sortedA);
        Collections.sort(sortedB);
        return sortedA.equals(sortedB);
    }

    // ---- Determinism fingerprint ----

    /**
     * Build a structured determinism fingerprint from a parse result.
     * Covers: symbol IDs, source-stroke assignments, candidate states,
     * rejection reasons, recognition-call count, and scores (rounded to 1e-6).
     */
    private String buildDeterminismFingerprint(SpellParser.ParseResult result) {
        StringBuilder fp = new StringBuilder();
        // Sigils sorted by ID then source strokes
        if (result.ast != null && result.ast.sigils() != null) {
            List<String> sigilEntries = new ArrayList<>();
            for (var sigil : result.ast.sigils()) {
                List<Integer> sorted = new ArrayList<>(sigil.sourceStrokeIndices());
                Collections.sort(sorted);
                long roundedScore = Math.round(sigil.recognitionConfidence() * 1e6);
                sigilEntries.add("SIGIL:" + sigil.id().getPath() + ":strokes=" + sorted
                        + ":score=" + roundedScore + ":reason=" + sigil.rejectionReason());
            }
            Collections.sort(sigilEntries);
            fp.append(String.join("|", sigilEntries));
        }
        fp.append(";");
        // Signs sorted by ID
        if (result.ast != null && result.ast.signs() != null) {
            List<String> signEntries = new ArrayList<>();
            for (var sign : result.ast.signs()) {
                long roundedScore = Math.round(sign.confidence() * 1e6);
                signEntries.add("SIGN:" + sign.id() + ":score=" + roundedScore);
            }
            Collections.sort(signEntries);
            fp.append(String.join("|", signEntries));
        }
        fp.append(";");
        // Unknowns sorted by candidate ID
        if (result.ast != null && result.ast.unknownSymbols() != null) {
            List<String> unknownEntries = new ArrayList<>();
            for (var unk : result.ast.unknownSymbols()) {
                List<Integer> sorted = new ArrayList<>(unk.sourceStrokeIndices());
                Collections.sort(sorted);
                unknownEntries.add("UNK:" + unk.candidateId() + ":strokes=" + sorted
                        + ":state=" + unk.state() + ":reason=" + unk.rejectionReason());
            }
            Collections.sort(unknownEntries);
            fp.append(String.join("|", unknownEntries));
        }
        fp.append(";");
        // Recognition call count
        if (result.debugResult != null) {
            fp.append("calls=" + result.debugResult.recognitionCalls());
        }
        return fp.toString();
    }

    // ---- Fixture metadata enforcement ----

    /**
     * Enforce expectRing and expectValidSpell for both true and false values.
     */
    private void enforceFixtureMetadata(JsonObject fixture, SpellParser.ParseResult result,
                                        String fileName, List<String> failures) {
        if (fixture.has("expectRing")) {
            boolean expected = fixture.get("expectRing").getAsBoolean();
            boolean actual = result.ast != null && result.ast.ring() != null;
            if (expected != actual) {
                failures.add(fileName + ": expectRing=" + expected + " actual=" + actual);
            }
        }
        if (fixture.has("expectValidSpell")) {
            boolean expected = fixture.get("expectValidSpell").getAsBoolean();
            boolean actual = result.isValidSpell();
            if (expected != actual) {
                failures.add(fileName + ": expectValidSpell=" + expected + " actual=" + actual);
            }
        }
    }

    // ---- Formatting helpers ----

    private String formatExpected(List<String> sigils, List<String> signs) {
        List<String> all = new ArrayList<>();
        all.addAll(sigils);
        all.addAll(signs);
        return String.join(", ", all);
    }

    // ---- Data types ----

    private record FixtureEntry(File file, JsonObject fixture) {}

    private void appendSelectionDiagnostics(StringBuilder out, String fileName, SpellParser.ParseResult result) {
        out.append("  [SELECTION DETAIL] ").append(fileName).append(':').append('\n');
        if (result.debugResult == null) {
            out.append("    (no debug result)\n\n");
            return;
        }
        out.append("    Primitive groups: ");
        for (com.maxello1.whamagic.magic.PrimitiveStrokeGroup group : result.debugResult.primitiveGroups()) {
            out.append('#').append(group.id()).append(group.sourceStrokeIndices()).append(' ');
        }
        out.append('\n');
        out.append("    Selected candidates: ");
        for (com.maxello1.whamagic.magic.SymbolCandidate candidate : result.debugResult.selectedCandidates()) {
            out.append('#').append(candidate.id())
                    .append(candidate.sourceStrokeIndices())
                    .append(candidate.isSuperCandidate() ? "(super) " : " ");
        }
        out.append('\n');
        List<EvaluatedCandidate> evaluated = result.debugResult.allEvaluated();
        int limit = evaluated.size();
        for (int i = 0; i < limit; i++) {
            EvaluatedCandidate eval = evaluated.get(i);
            out.append(String.format(
                    "    #%d strokes=%s super=%s sigil=%s sign=%s role=(%.3f,%.3f)%n",
                    eval.cand.id(), eval.cand.sourceStrokeIndices(), eval.cand.isSuperCandidate(),
                    recognitionSummary(eval.sigilRes), recognitionSummary(eval.signRes),
                    eval.sigilRoleScore, eval.signRoleScore));
        }
        out.append('\n');
    }

    private String recognitionSummary(RasterRecognizer.RecognitionResult result) {
        if (result == null) return "none";
        return String.format("%s/%.3f/%s/%s", result.id, result.score,
                result.recognized, result.rejectionReason);
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

        RecognitionAlternative bestAlt = null;
        RecognitionAlternative secondBestAlt = null;
        RasterRecognizer.RecognitionResult bestRes = null;
        EvaluatedCandidate bestEvalCand = null;
        String bestKind = "UNKNOWN";

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

        // Show expected symbol's best score across all candidates
        double expectedBestScore = 0;
        String expectedBestKind = "N/A";
        for (AlternativeWithContext ctx : allAlts) {
            if (ctx.alt.id() != null && ctx.alt.id().getPath().equals(expectedId)) {
                expectedBestScore = ctx.alt.rawScore();
                expectedBestKind = ctx.kind;
                break; // already sorted descending
            }
        }
        out.append(String.format("    Expected '%s' best: %.3f (via %s)\n", expectedId, expectedBestScore, expectedBestKind));
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
