package com.maxello1.whamagic.parser;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maxello1.whamagic.magic.CandidateGenerationSettings;
import com.maxello1.whamagic.magic.ClassifiedUnknownInk;
import com.maxello1.whamagic.magic.PrimitiveStrokeGroup;
import com.maxello1.whamagic.magic.RecognitionAlternative;
import com.maxello1.whamagic.magic.RecognizedSigil;
import com.maxello1.whamagic.magic.RecognizedSign;
import com.maxello1.whamagic.magic.RingDetector;
import com.maxello1.whamagic.magic.SigilSemantic;
import com.maxello1.whamagic.magic.SignSemantic;
import com.maxello1.whamagic.magic.SpellIr;
import com.maxello1.whamagic.magic.SymbolCandidate;
import com.maxello1.whamagic.magic.SymbolRecognitionResult;
import com.maxello1.whamagic.magic.UnknownSymbol;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecognitionCompatibilityGoldenTest {
    private static final Path FIXTURE_ROOT = Path.of("src/test/resources/fixtures");
    private static final Path CANONICAL_ROOT = FIXTURE_ROOT.resolve("canonical");
    private static final Path GOLDEN_PATH =
            FIXTURE_ROOT.resolve("recognition-compatibility-golden.tsv");

    @BeforeAll
    static void loadDictionary() {
        SpellDictionary.ensureLoaded();
    }

    @Test
    void parserOutputMatchesGoldenFixtures() throws Exception {
        List<Fixture> canonical = fixturesUnder(CANONICAL_ROOT);
        assertEquals(51, canonical.size(), "Expected the complete canonical fixture set");
        Map<String, String> expected = goldenHashes("parser");
        assertEquals(canonical.size(), expected.size(), "Parser golden fixture count");
        Map<String, String> actual = new LinkedHashMap<>();

        for (Fixture fixture : canonical) {
            SpellParser.ParseResult result = SpellParser.parse(
                    fixture.strokes(),
                    CandidateGenerationSettings.DEFAULTS,
                    ParseDetail.FULL_DIAGNOSTICS);
            String snapshot = parserSnapshot(fixture.relativePath(), result);
            String actualHash = sha256(snapshot);
            actual.put(fixture.relativePath(), actualHash);
        }
        writeCurrentHashes("parser", actual);
        assertEquals(expected, actual, "Parser recognition golden output");
    }

    @Test
    void candidateOutputMatchesGoldenFixtures() throws Exception {
        List<Fixture> canonical = fixturesUnder(CANONICAL_ROOT);
        List<Fixture> candidateFixtures = canonical.stream()
                .filter(fixture -> fixture.relativePath().startsWith("canonical/positive/")
                        || fixture.relativePath().startsWith("canonical/multi/"))
                .toList();
        assertEquals(15, candidateFixtures.size(), "Expected all positive and multi fixtures");
        Map<String, String> expected = goldenHashes("candidate");
        assertEquals(candidateFixtures.size(), expected.size(), "Candidate golden fixture count");
        Map<String, String> actual = new LinkedHashMap<>();

        for (Fixture fixture : candidateFixtures) {
            CandidateCase candidateCase = generateCandidates(fixture.strokes());
            String snapshot = candidateSnapshot(
                    fixture.relativePath(), candidateCase.ring(), candidateCase.ringStrokeIndices(),
                    candidateCase.result());
            String actualHash = sha256(snapshot);
            actual.put(fixture.relativePath(), actualHash);
        }
        writeCurrentHashes("candidate", actual);
        assertEquals(expected, actual, "Candidate generation golden output");
    }

    private static Map<String, String> goldenHashes(String kind) throws Exception {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String line : Files.readAllLines(GOLDEN_PATH, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] fields = line.split("\\t", -1);
            if (fields.length != 3) {
                throw new IllegalStateException("Malformed recognition golden line: " + line);
            }
            if (kind.equals(fields[0]) && hashes.put(fields[1], fields[2]) != null) {
                throw new IllegalStateException("Duplicate recognition golden: " + fields[1]);
            }
        }
        return Map.copyOf(hashes);
    }

    private static void writeCurrentHashes(String kind, Map<String, String> hashes)
            throws Exception {
        Path reportDirectory = Path.of("build/reports");
        Files.createDirectories(reportDirectory);
        List<String> lines = hashes.entrySet().stream()
                .map(entry -> kind + "\t" + entry.getKey() + "\t" + entry.getValue())
                .toList();
        Files.write(
                reportDirectory.resolve(kind + "-compatibility-current.tsv"),
                lines,
                StandardCharsets.UTF_8);
    }

    private static List<Fixture> fixturesUnder(Path root) throws Exception {
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(root)) {
            paths = stream.filter(path -> path.toString().endsWith(".json"))
                    .sorted(Comparator.comparing(RecognitionCompatibilityGoldenTest::relativePath))
                    .toList();
        }

        List<Fixture> fixtures = new ArrayList<>(paths.size());
        for (Path path : paths) {
            fixtures.add(new Fixture(relativePath(path), loadStrokes(path)));
        }
        return List.copyOf(fixtures);
    }

    private static String relativePath(Path path) {
        return FIXTURE_ROOT.relativize(path).toString().replace('\\', '/');
    }

    private static List<List<Point>> loadStrokes(Path path) throws Exception {
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        JsonElement strokeData = root.has("strokes") ? root.get("strokes") : root.get("rawStrokes");
        List<List<Point>> strokes = new ArrayList<>();
        for (JsonElement strokeElement : strokeData.getAsJsonArray()) {
            List<Point> stroke = new ArrayList<>();
            for (JsonElement pointElement : strokeElement.getAsJsonArray()) {
                JsonObject point = pointElement.getAsJsonObject();
                stroke.add(new Point(point.get("x").getAsDouble(), point.get("y").getAsDouble()));
            }
            strokes.add(List.copyOf(stroke));
        }
        return List.copyOf(strokes);
    }

    private static CandidateCase generateCandidates(List<List<Point>> strokes) {
        RingDetector.RingSearchResult ringSearch = RingDetector.searchRing(strokes);
        RingDetector.RingDetection detection = ringSearch.detection();
        RingDetector.RingGlyph ring = detection == null ? null : detection.glyph();
        Set<Integer> ringIndices = detection == null
                ? Set.of()
                : detection.ringStrokeIndices();

        List<IndexedStroke> nonRingStrokes = new ArrayList<>();
        for (int index = 0; index < strokes.size(); index++) {
            if (!ringIndices.contains(index)) {
                nonRingStrokes.add(new IndexedStroke(index, strokes.get(index)));
            }
        }
        CandidateGenerator.GenerationResult result = CandidateGenerator.generateCandidates(
                nonRingStrokes, ring, CandidateGenerationSettings.DEFAULTS);
        return new CandidateCase(ring, ringIndices.stream().sorted().toList(), result);
    }

    private static String parserSnapshot(String path, SpellParser.ParseResult result) {
        StringBuilder out = new StringBuilder(32_768);
        token(out, "path", path);
        token(out, "detail", result.detail);
        appendIr(out, result.ir);
        appendRing(out, result.ast.ring());
        appendSigils(out, "ast.sigils", result.ast.sigils());
        appendSigns(out, "ast.signs", result.ast.signs());
        appendUnknowns(out, "ast.unknowns", result.ast.unknownSymbols());
        token(out, "ast.unknownInk.count", result.ast.unknownInk().size());
        for (int index = 0; index < result.ast.unknownInk().size(); index++) {
            appendUnknownInk(out, "ast.unknownInk." + index, result.ast.unknownInk().get(index));
        }

        ParseSummary summary = result.summary;
        token(out, "summary", summary);
        SegmentationDebugResult debug = result.debugResult;
        token(out, "debug.recognitionCalls", debug.recognitionCalls());
        token(out, "debug.candidateLimitReached", debug.candidateLimitReached());
        token(out, "debug.ringBudgetExhausted", debug.ringBudgetExhausted());
        token(out, "debug.recognitionBudgetExhausted", debug.recognitionBudgetExhausted());
        token(out, "debug.dropped", debug.droppedSourceStrokeIndices());
        token(out, "debug.unevaluated", debug.unevaluatedCandidateCount());
        token(out, "debug.ringCombinations", debug.ringCombinationsConsidered());
        token(out, "debug.ringFits", debug.ringFitsAttempted());
        token(out, "debug.ringStrokes", debug.ringStrokeIndices().stream().sorted().toList());
        appendPrimitiveGroups(out, "debug.primitives", debug.primitiveGroups());
        appendCandidates(out, "debug.generated", debug.generatedCandidates());
        appendCandidates(out, "debug.selected", debug.selectedCandidates());
        token(out, "debug.evaluated.count", debug.allEvaluated().size());
        for (int index = 0; index < debug.allEvaluated().size(); index++) {
            appendEvaluated(out, "debug.evaluated." + index, debug.allEvaluated().get(index));
        }
        return out.toString();
    }

    private static String candidateSnapshot(
            String path,
            RingDetector.RingGlyph ring,
            List<Integer> ringStrokeIndices,
            CandidateGenerator.GenerationResult result) {
        StringBuilder out = new StringBuilder(16_384);
        token(out, "path", path);
        appendRing(out, ring);
        token(out, "ringStrokeIndices", ringStrokeIndices);
        token(out, "candidateLimitReached", result.candidateLimitReached());
        token(out, "dropped", result.droppedSourceStrokeIndices());
        appendPrimitiveGroups(out, "primitives", result.primitiveGroups());
        appendCandidates(out, "candidates", result.candidates());
        return out.toString();
    }

    private static void appendIr(StringBuilder out, SpellIr ir) {
        token(out, "ir.state", ir.state());
        token(out, "ir.warning", ir.warning());
        token(out, "ir.valid", ir.valid());
        token(out, "ir.elements", ir.elements());
        token(out, "ir.signCounts.count", ir.signCounts().size());
        int index = 0;
        for (var entry : ir.signCounts().entrySet()) {
            token(out, "ir.signCounts." + index + ".id", entry.getKey());
            token(out, "ir.signCounts." + index + ".count", entry.getValue());
            index++;
        }
        appendSigilSemantic(out, "ir.sigilSemantic", ir.sigilSemantic());
        token(out, "ir.signSemantics.count", ir.signSemantics().size());
        for (int semanticIndex = 0; semanticIndex < ir.signSemantics().size(); semanticIndex++) {
            appendSignSemantic(
                    out, "ir.signSemantics." + semanticIndex, ir.signSemantics().get(semanticIndex));
        }
        token(out, "ir.displayName", ir.displayName());
        token(out, "ir.statusMessage", ir.statusMessage());
    }

    private static void appendRing(StringBuilder out, RingDetector.RingGlyph ring) {
        token(out, "ring.present", ring != null);
        if (ring == null) return;
        appendPoint(out, "ring.center", ring.center());
        number(out, "ring.radius", ring.radius());
        number(out, "ring.completeness", ring.completeness());
        token(out, "ring.closed", ring.isClosed());
        number(out, "ring.gapAngle", ring.gapAngleDeg());
        number(out, "ring.rmse", ring.rmse());
        number(out, "ring.normalizedRmse", ring.normalizedRmse());
        number(out, "ring.maxNormalizedResidual", ring.maxNormalizedResidual());
        number(out, "ring.residualStdDev", ring.residualStdDev());
        number(out, "ring.medianTangentAlignment", ring.medianTangentAlignment());
        number(out, "ring.p90TangentAlignment", ring.p90TangentAlignment());
        number(out, "ring.circularity", ring.circularity());
    }

    private static void appendSigils(
            StringBuilder out, String prefix, List<RecognizedSigil> sigils) {
        token(out, prefix + ".count", sigils.size());
        for (int index = 0; index < sigils.size(); index++) {
            RecognizedSigil sigil = sigils.get(index);
            String item = prefix + "." + index;
            token(out, item + ".id", sigil.id());
            token(out, item + ".template", sigil.matchedTemplateId());
            token(out, item + ".display", sigil.displayName());
            token(out, item + ".element", sigil.element());
            appendSigilSemantic(out, item + ".semantic", sigil.semantic());
            number(out, item + ".confidence", sigil.recognitionConfidence());
            appendPoint(out, item + ".centroid", sigil.centroid());
            appendBounds(out, item + ".bounds", sigil.bounds());
            number(out, item + ".rotation", sigil.orientationDeg());
            token(out, item + ".sources", sigil.sourceStrokeIndices());
            appendAlternatives(out, item + ".alternatives", sigil.alternatives());
            token(out, item + ".rejection", sigil.rejectionReason());
        }
    }

    private static void appendSigns(
            StringBuilder out, String prefix, List<RecognizedSign> signs) {
        token(out, prefix + ".count", signs.size());
        for (int index = 0; index < signs.size(); index++) {
            RecognizedSign sign = signs.get(index);
            String item = prefix + "." + index;
            token(out, item + ".candidate", sign.candidateId());
            token(out, item + ".id", sign.id());
            token(out, item + ".template", sign.matchedTemplateId());
            number(out, item + ".confidence", sign.confidence());
            number(out, item + ".angleAroundRing", sign.angleAroundRing());
            number(out, item + ".rotation", sign.orientationDeg());
            token(out, item + ".layer", sign.layer());
            appendSignSemantic(out, item + ".semantic", sign.semantic());
            token(out, item + ".sources", sign.sourceStrokeIndices());
            appendPoint(out, item + ".centroid", sign.centroid());
            appendBounds(out, item + ".bounds", sign.bounds());
            appendAlternatives(out, item + ".alternatives", sign.alternatives());
            token(out, item + ".rejection", sign.rejectionReason());
        }
    }

    private static void appendUnknowns(
            StringBuilder out, String prefix, List<UnknownSymbol> unknowns) {
        token(out, prefix + ".count", unknowns.size());
        for (int index = 0; index < unknowns.size(); index++) {
            UnknownSymbol unknown = unknowns.get(index);
            String item = prefix + "." + index;
            token(out, item + ".candidate", unknown.candidateId());
            token(out, item + ".sources", unknown.sourceStrokeIndices());
            appendStrokes(out, item + ".strokes", unknown.strokes());
            appendBounds(out, item + ".bounds", unknown.bounds());
            token(out, item + ".state", unknown.state());
            token(out, item + ".classification", unknown.classification());
            appendAlternatives(out, item + ".alternatives", unknown.alternatives());
            token(out, item + ".rejection", unknown.rejectionReason());
        }
    }

    private static void appendUnknownInk(
            StringBuilder out, String prefix, ClassifiedUnknownInk unknown) {
        token(out, prefix + ".classification", unknown.classification());
        token(out, prefix + ".candidate", unknown.candidateId());
        token(out, prefix + ".sources", unknown.sourceStrokeIndices());
        appendBounds(out, prefix + ".bounds", unknown.bounds());
        token(out, prefix + ".rejection", unknown.rejectionReason());
    }

    private static void appendEvaluated(
            StringBuilder out, String prefix, SelectionEngine.EvaluatedCandidate evaluated) {
        appendCandidate(out, prefix + ".candidate", evaluated.cand);
        appendRecognition(out, prefix + ".sigil", evaluated.sigilRes);
        number(out, prefix + ".sigilRoleScore", evaluated.sigilRoleScore);
        appendRecognition(out, prefix + ".sign", evaluated.signRes);
        number(out, prefix + ".signRoleScore", evaluated.signRoleScore);
        number(out, prefix + ".rotation", evaluated.bestAngle);
    }

    private static void appendRecognition(
            StringBuilder out, String prefix, SymbolRecognitionResult result) {
        token(out, prefix + ".present", result != null);
        if (result == null) return;
        token(out, prefix + ".recognized", result.recognized());
        token(out, prefix + ".id", result.id());
        token(out, prefix + ".template", result.matchedTemplateId());
        token(out, prefix + ".display", result.displayName());
        token(out, prefix + ".kind", result.kind());
        token(out, prefix + ".element", result.element());
        number(out, prefix + ".score", result.score());
        appendSigilSemantic(out, prefix + ".sigilSemantic", result.sigilSemantic());
        appendSignSemantic(out, prefix + ".signSemantic", result.signSemantic());
        appendAlternatives(out, prefix + ".alternatives", result.alternatives());
        number(out, prefix + ".gap", result.confidenceGap());
        number(out, prefix + ".threshold", result.thresholdUsed());
        token(out, prefix + ".rejection", result.rejectionReason());
        number(out, prefix + ".templateCoverage", result.templateCoverage());
        number(out, prefix + ".unexplainedInk", result.unexplainedInkRatio());
        number(out, prefix + ".structuralScore", result.structuralScore());
    }

    private static void appendAlternatives(
            StringBuilder out, String prefix, List<RecognitionAlternative> alternatives) {
        token(out, prefix + ".count", alternatives.size());
        for (int index = 0; index < alternatives.size(); index++) {
            RecognitionAlternative alternative = alternatives.get(index);
            String item = prefix + "." + index;
            token(out, item + ".id", alternative.id());
            token(out, item + ".display", alternative.displayName());
            token(out, item + ".kind", alternative.kind());
            number(out, item + ".rawScore", alternative.rawScore());
            number(out, item + ".roleScore", alternative.roleScore());
            number(out, item + ".templateCoverage", alternative.templateCoverage());
            number(out, item + ".candidateExplained", alternative.candidateExplainedRatio());
            number(out, item + ".unexplainedInk", alternative.unexplainedInkRatio());
            number(out, item + ".structuralScore", alternative.structuralScore());
            number(out, item + ".rotation", alternative.rotationDeg());
        }
    }

    private static void appendPrimitiveGroups(
            StringBuilder out, String prefix, List<PrimitiveStrokeGroup> groups) {
        token(out, prefix + ".count", groups.size());
        for (int index = 0; index < groups.size(); index++) {
            PrimitiveStrokeGroup group = groups.get(index);
            String item = prefix + "." + index;
            token(out, item + ".id", group.id());
            token(out, item + ".sources", group.sourceStrokeIndices());
            appendStrokes(out, item + ".strokes", group.strokes());
            appendBounds(out, item + ".bounds", group.bounds());
            appendPoint(out, item + ".centroid", group.centroid());
            number(out, item + ".pathLength", group.pathLength());
            number(out, item + ".radial", group.radialPosition());
            number(out, item + ".angular", group.angularPosition());
        }
    }

    private static void appendCandidates(
            StringBuilder out, String prefix, List<SymbolCandidate> candidates) {
        token(out, prefix + ".count", candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            appendCandidate(out, prefix + "." + index, candidates.get(index));
        }
    }

    private static void appendCandidate(
            StringBuilder out, String prefix, SymbolCandidate candidate) {
        token(out, prefix + ".id", candidate.id());
        token(out, prefix + ".primitiveIds", candidate.primitiveGroupIds());
        token(out, prefix + ".sources", candidate.sourceStrokeIndices());
        appendStrokes(out, prefix + ".strokes", candidate.strokes());
        appendBounds(out, prefix + ".bounds", candidate.bounds());
        appendPoint(out, prefix + ".centroid", candidate.centroid());
        number(out, prefix + ".radial", candidate.radialPosition());
        number(out, prefix + ".angular", candidate.angularPosition());
        number(out, prefix + ".angularSpan", candidate.angularSpan());
        token(out, prefix + ".super", candidate.isSuperCandidate());
    }

    private static void appendStrokes(
            StringBuilder out, String prefix, List<List<Point>> strokes) {
        token(out, prefix + ".count", strokes.size());
        for (int strokeIndex = 0; strokeIndex < strokes.size(); strokeIndex++) {
            List<Point> stroke = strokes.get(strokeIndex);
            token(out, prefix + "." + strokeIndex + ".count", stroke.size());
            for (int pointIndex = 0; pointIndex < stroke.size(); pointIndex++) {
                appendPoint(
                        out, prefix + "." + strokeIndex + "." + pointIndex,
                        stroke.get(pointIndex));
            }
        }
    }

    private static void appendPoint(StringBuilder out, String prefix, Point point) {
        token(out, prefix + ".present", point != null);
        if (point == null) return;
        number(out, prefix + ".x", point.x);
        number(out, prefix + ".y", point.y);
    }

    private static void appendBounds(StringBuilder out, String prefix, BoundingBox bounds) {
        token(out, prefix + ".present", bounds != null);
        if (bounds == null) return;
        number(out, prefix + ".minX", bounds.minX());
        number(out, prefix + ".minY", bounds.minY());
        number(out, prefix + ".maxX", bounds.maxX());
        number(out, prefix + ".maxY", bounds.maxY());
    }

    private static void appendSigilSemantic(
            StringBuilder out, String prefix, SigilSemantic semantic) {
        token(out, prefix + ".present", semantic != null);
        if (semantic == null) return;
        number(out, prefix + ".force", semantic.force());
        number(out, prefix + ".focus", semantic.focus());
        number(out, prefix + ".spread", semantic.spread());
        number(out, prefix + ".range", semantic.range());
        number(out, prefix + ".lifetimeBias", semantic.lifetimeBias());
    }

    private static void appendSignSemantic(
            StringBuilder out, String prefix, SignSemantic semantic) {
        token(out, prefix + ".present", semantic != null);
        if (semantic == null) return;
        token(out, prefix + ".manifestation", semantic.manifestation());
        token(out, prefix + ".directionMode", semantic.directionMode());
        number(out, prefix + ".force", semantic.force());
        number(out, prefix + ".focus", semantic.focus());
        number(out, prefix + ".spread", semantic.spread());
        number(out, prefix + ".range", semantic.range());
        number(out, prefix + ".lifetimeBias", semantic.lifetimeBias());
    }

    private static void number(StringBuilder out, String name, double value) {
        // The removed compatibility oracle allowed a 1e-8 numeric tolerance.
        // Keep the golden portable across JVM math intrinsics at the same precision.
        double normalized = Math.abs(value) < 0.5e-8 ? 0.0 : value;
        token(out, name, String.format(Locale.ROOT, "%.8f", normalized));
    }

    private static void token(StringBuilder out, String name, Object value) {
        String text = String.valueOf(value);
        out.append(name).append('=').append(text.length()).append(':').append(text).append(';');
    }

    private static String sha256(String snapshot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(snapshot.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Fixture(String relativePath, List<List<Point>> strokes) {}

    private record CandidateCase(
            RingDetector.RingGlyph ring,
            List<Integer> ringStrokeIndices,
            CandidateGenerator.GenerationResult result) {}
}
