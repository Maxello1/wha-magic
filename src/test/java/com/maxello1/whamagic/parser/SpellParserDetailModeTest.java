package com.maxello1.whamagic.parser;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maxello1.whamagic.dev.RecognitionSampleCapture;
import com.maxello1.whamagic.magic.RecognizedSigil;
import com.maxello1.whamagic.magic.RecognizedSign;
import com.maxello1.whamagic.magic.UnknownSymbol;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpellParserDetailModeTest {

    private static final Path COMPLETE_LIGHT = Path.of(
            "src/test/resources/fixtures/canonical/multi/spell_light_complete.json");
    private static final Path NEGATIVE_SCRIBBLE = Path.of(
            "src/test/resources/fixtures/canonical/negative/neg_scribble.json");

    @BeforeAll
    static void loadDictionary() {
        SpellDictionary.ensureLoaded();
    }

    @Test
    void detailModesPreserveSemanticIrWhileBoundingRetainedDiagnostics() throws Exception {
        List<List<Point>> strokes = loadStrokes(COMPLETE_LIGHT);

        SpellParser.ParseResult runtime = SpellParser.parse(strokes, ParseDetail.RUNTIME);
        SpellParser.ParseResult preview = SpellParser.parse(strokes, ParseDetail.PREVIEW);
        SpellParser.ParseResult full = SpellParser.parse(strokes, ParseDetail.FULL_DIAGNOSTICS);

        assertTrue(full.ir.valid(), "Complete Light must remain a valid compatibility fixture");
        assertEquals(full.ir, runtime.ir, "Runtime parsing must preserve the semantic IR");
        assertEquals(full.ir, preview.ir, "Preview parsing must preserve the semantic IR");

        assertEquals(ParseDetail.RUNTIME, runtime.detail);
        assertNull(runtime.summary);
        assertNull(runtime.debugResult);
        assertNoCompleteRecognitionPayload(runtime);

        assertEquals(ParseDetail.PREVIEW, preview.detail);
        assertNotNull(preview.summary);
        assertTrue(preview.summary.candidateCount() > 0);
        assertTrue(preview.summary.selectedCandidateCount() > 0);
        assertTrue(preview.summary.recognitionCalls() > 0);
        assertNull(preview.debugResult);
        assertSelectedIdentityAndBounds(preview);
        assertNoCompleteRecognitionPayload(preview);

        assertEquals(ParseDetail.FULL_DIAGNOSTICS, full.detail);
        assertNotNull(full.summary);
        assertNotNull(full.debugResult);
        assertFalse(full.debugResult.generatedCandidates().isEmpty());
        assertFalse(full.debugResult.allEvaluated().isEmpty());
        assertEquals(full.summary.candidateCount(), full.debugResult.candidateCount());
        assertEquals(full.summary.recognitionCalls(), full.debugResult.recognitionCalls());
        assertTrue(hasAlternatives(full),
                "Full diagnostics must retain recognition alternatives");
        assertTrue(full.debugResult.allEvaluated().stream().anyMatch(evaluation ->
                        (evaluation.sigilRes != null && !evaluation.sigilRes.alternatives().isEmpty())
                                || (evaluation.signRes != null
                                && !evaluation.signRes.alternatives().isEmpty())),
                "Full diagnostics must retain evaluated recognition attempts");
    }

    @Test
    void nonDiagnosticModesDoNotRetainUnknownStrokeGraphs() throws Exception {
        List<List<Point>> strokes = loadStrokes(NEGATIVE_SCRIBBLE);

        SpellParser.ParseResult runtime = SpellParser.parse(strokes, ParseDetail.RUNTIME);
        SpellParser.ParseResult preview = SpellParser.parse(strokes, ParseDetail.PREVIEW);
        SpellParser.ParseResult full = SpellParser.parse(strokes, ParseDetail.FULL_DIAGNOSTICS);

        assertFalse(full.ast.unknownSymbols().isEmpty(),
                "Negative scribble must exercise unknown-symbol retention");
        assertTrue(full.ast.unknownSymbols().stream().anyMatch(unknown -> !unknown.strokes().isEmpty()));
        assertTrue(runtime.ast.unknownSymbols().stream().allMatch(unknown -> unknown.strokes().isEmpty()));
        assertTrue(preview.ast.unknownSymbols().stream().allMatch(unknown -> unknown.strokes().isEmpty()));
        assertNoCompleteRecognitionPayload(runtime);
        assertNoCompleteRecognitionPayload(preview);
    }

    @Test
    void sampleCaptureRequiresFullDiagnostics() throws Exception {
        List<List<Point>> strokes = loadStrokes(COMPLETE_LIGHT);
        SpellParser.ParseResult preview = SpellParser.parse(strokes, ParseDetail.PREVIEW);
        SpellParser.ParseResult full = SpellParser.parse(strokes, ParseDetail.FULL_DIAGNOSTICS);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new RecognitionSampleCapture(strokes, preview));
        assertTrue(failure.getMessage().contains("full parser diagnostics"));
        assertDoesNotThrow(() -> new RecognitionSampleCapture(strokes, full));
    }

    private static void assertSelectedIdentityAndBounds(SpellParser.ParseResult result) {
        List<RecognizedSigil> sigils = result.ast.sigils();
        List<RecognizedSign> signs = result.ast.signs();
        assertFalse(sigils.isEmpty() && signs.isEmpty(), "Preview must retain selected symbols");
        sigils.forEach(sigil -> {
            assertNotNull(sigil.id());
            assertNotNull(sigil.matchedTemplateId());
            assertNotNull(sigil.bounds());
        });
        signs.forEach(sign -> {
            assertNotNull(sign.id());
            assertNotNull(sign.matchedTemplateId());
            assertNotNull(sign.bounds());
        });
    }

    private static void assertNoCompleteRecognitionPayload(SpellParser.ParseResult result) {
        assertTrue(result.ast.sigils().stream().allMatch(sigil -> sigil.alternatives().isEmpty()));
        assertTrue(result.ast.signs().stream().allMatch(sign -> sign.alternatives().isEmpty()));
        assertTrue(result.ast.unknownSymbols().stream().allMatch(unknown ->
                unknown.alternatives().isEmpty() && unknown.strokes().isEmpty()));
    }

    private static boolean hasAlternatives(SpellParser.ParseResult result) {
        return result.ast.sigils().stream().anyMatch(sigil -> !sigil.alternatives().isEmpty())
                || result.ast.signs().stream().anyMatch(sign -> !sign.alternatives().isEmpty())
                || result.ast.unknownSymbols().stream().anyMatch(unknown -> !unknown.alternatives().isEmpty());
    }

    private static List<List<Point>> loadStrokes(Path fixture) throws Exception {
        JsonObject json;
        try (Reader reader = Files.newBufferedReader(fixture, StandardCharsets.UTF_8)) {
            json = JsonParser.parseReader(reader).getAsJsonObject();
        }

        List<List<Point>> strokes = new ArrayList<>();
        for (JsonElement strokeElement : json.getAsJsonArray("strokes")) {
            List<Point> stroke = new ArrayList<>();
            for (JsonElement pointElement : strokeElement.getAsJsonArray()) {
                JsonObject point = pointElement.getAsJsonObject();
                stroke.add(new Point(point.get("x").getAsDouble(), point.get("y").getAsDouble()));
            }
            strokes.add(stroke);
        }
        return strokes;
    }
}
