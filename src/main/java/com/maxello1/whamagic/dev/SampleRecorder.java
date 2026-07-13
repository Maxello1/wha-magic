package com.maxello1.whamagic.dev;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.maxello1.whamagic.magic.*;
import com.maxello1.whamagic.parser.Point;
import com.maxello1.whamagic.parser.PointCloudRecognizer;
import com.maxello1.whamagic.parser.SpellDictionary;
import com.maxello1.whamagic.parser.SpellParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Development utility to save drawings and their recognition results as JSON samples.
 * Saves under run/dev-samples/ which is gitignored.
 */
public class SampleRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(SampleRecorder.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String SAMPLES_DIR = "run/dev-samples";

    /**
     * Save the current drawing and parse result as a JSON sample file.
     * 
     * @param rawStrokes the raw drawn strokes
     * @param result the parse result (may be null if parsing failed)
     * @param notes optional notes about this sample
     * @return the saved file path, or null on failure
     */
    public static String saveSample(List<List<Point>> rawStrokes, SpellParser.ParseResult result, String notes) {
        try {
            File dir = new File(SAMPLES_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"));
            File file = new File(dir, "sample_" + timestamp + ".json");

            JsonObject root = new JsonObject();
            SpellDictionary.DictionarySnapshot dictionary = SpellDictionary.snapshot();
            root.addProperty("formatVersion", 3);
            root.addProperty("recognizerVersion", PointCloudRecognizer.RECOGNIZER_VERSION);
            root.addProperty("dictionaryVersion", dictionary.version());
            root.addProperty("dictionaryHash", dictionary.hash());
            root.addProperty("sampleRole", "experimental");
            JsonObject expectedIntent = new JsonObject();
            expectedIntent.add("sigils", new JsonArray());
            expectedIntent.add("signs", new JsonArray());
            root.add("expectedIntent", expectedIntent);
            root.addProperty("notes", notes == null ? "" : notes);
            root.addProperty("sourceDate", LocalDate.now().toString());
            root.addProperty("influencedTemplateOrThreshold", false);
            root.addProperty("timestamp", LocalDateTime.now().toString());

            // Raw strokes
            JsonArray strokesArray = new JsonArray();
            if (rawStrokes != null) {
                for (List<Point> stroke : rawStrokes) {
                    JsonArray strokeArray = new JsonArray();
                    for (Point p : stroke) {
                        JsonObject point = new JsonObject();
                        point.addProperty("x", p.x);
                        point.addProperty("y", p.y);
                        strokeArray.add(point);
                    }
                    strokesArray.add(strokeArray);
                }
            }
            root.add("rawStrokes", strokesArray);

            // Result data
            if (result != null) {
                JsonObject resultObj = new JsonObject();
                resultObj.addProperty("valid", result.isValidSpell());

                if (result.ast != null) {
                    // Sigils
                    JsonArray sigilsArray = new JsonArray();
                    if (result.ast.sigils() != null) {
                        for (RecognizedSigil sigil : result.ast.sigils()) {
                            JsonObject s = new JsonObject();
                            s.addProperty("id", sigil.id() != null ? sigil.id().toString() : "null");
                            s.addProperty("matchedTemplateId", sigil.matchedTemplateId());
                            s.addProperty("element", sigil.element() != null ? sigil.element().name() : "null");
                            s.addProperty("confidence", Math.round(sigil.recognitionConfidence() * 1000.0) / 1000.0);
                            s.addProperty("rejectionReason", sigil.rejectionReason().name());
                            s.add("sourceStrokeIndices", GSON.toJsonTree(sigil.sourceStrokeIndices()));
                            addAlternatives(s, sigil.alternatives());
                            sigilsArray.add(s);
                        }
                    }
                    resultObj.add("sigils", sigilsArray);

                    // Signs
                    JsonArray signsArray = new JsonArray();
                    if (result.ast.signs() != null) {
                        for (RecognizedSign sign : result.ast.signs()) {
                            JsonObject s = new JsonObject();
                            s.addProperty("id", sign.id());
                            s.addProperty("matchedTemplateId", sign.matchedTemplateId());
                            s.addProperty("confidence", Math.round(sign.confidence() * 1000.0) / 1000.0);
                            s.addProperty("angleAroundRing", Math.round(sign.angleAroundRing() * 100.0) / 100.0);
                            s.add("sourceStrokeIndices", GSON.toJsonTree(sign.sourceStrokeIndices()));
                            signsArray.add(s);
                        }
                    }
                    resultObj.add("signs", signsArray);

                    // Unknowns
                    JsonArray unknownsArray = new JsonArray();
                    if (result.ast.unknownSymbols() != null) {
                        for (UnknownSymbol unk : result.ast.unknownSymbols()) {
                            JsonObject u = new JsonObject();
                            u.addProperty("candidateId", unk.candidateId());
                            u.addProperty("state", unk.state().name());
                            u.addProperty("rejectionReason", unk.rejectionReason().name());
                            u.add("sourceStrokeIndices", GSON.toJsonTree(unk.sourceStrokeIndices()));
                            addAlternatives(u, unk.alternatives());
                            unknownsArray.add(u);
                        }
                    }
                    resultObj.add("unknowns", unknownsArray);
                }

                // Diagnostics
                if (result.debugResult != null) {
                    resultObj.addProperty("candidateCount", result.debugResult.candidateCount());
                    resultObj.addProperty("recognitionCalls", result.debugResult.recognitionCalls());
                    resultObj.addProperty("primitiveGroupCount", result.debugResult.primitiveGroupCount());
                    resultObj.addProperty("selectedCandidateCount", result.debugResult.selectedCandidateCount());
                    resultObj.addProperty("candidateLimitReached", result.debugResult.candidateLimitReached());
                    resultObj.addProperty("ringBudgetExhausted", result.debugResult.ringBudgetExhausted());
                    resultObj.addProperty("recognitionBudgetExhausted", result.debugResult.recognitionBudgetExhausted());
                    resultObj.addProperty("unevaluatedCandidateCount", result.debugResult.unevaluatedCandidateCount());
                    resultObj.add("droppedSourceStrokeIndices", GSON.toJsonTree(result.debugResult.droppedSourceStrokeIndices()));
                    resultObj.addProperty("ringCombinationsConsidered", result.debugResult.ringCombinationsConsidered());
                    resultObj.addProperty("ringFitsAttempted", result.debugResult.ringFitsAttempted());
                    resultObj.addProperty("ringElapsedNanos", result.debugResult.ringElapsedNanos());
                    resultObj.add("ringStrokeIndices", GSON.toJsonTree(result.debugResult.ringStrokeIndices()));
                }

                root.add("result", resultObj);
            }

            try (FileWriter writer = new FileWriter(file)) {
                GSON.toJson(root, writer);
            }

            LOGGER.info("Sample saved: {}", file.getAbsolutePath());
            return file.getAbsolutePath();

        } catch (IOException e) {
            LOGGER.error("Failed to save sample", e);
            return null;
        }
    }

    private static void addAlternatives(JsonObject parent, List<RecognitionAlternative> alternatives) {
        JsonArray altsArray = new JsonArray();
        if (alternatives != null) {
            for (RecognitionAlternative alt : alternatives) {
                JsonObject a = new JsonObject();
                a.addProperty("id", alt.id() != null ? alt.id().toString() : "null");
                a.addProperty("displayName", alt.displayName());
                a.addProperty("kind", alt.kind() != null ? alt.kind().name() : "null");
                a.addProperty("rawScore", Math.round(alt.rawScore() * 1000.0) / 1000.0);
                a.addProperty("roleScore", Math.round(alt.roleScore() * 1000.0) / 1000.0);
                a.addProperty("templateCoverage", Math.round(alt.templateCoverage() * 1000.0) / 1000.0);
                a.addProperty("candidateExplainedRatio", Math.round(alt.candidateExplainedRatio() * 1000.0) / 1000.0);
                a.addProperty("unexplainedInkRatio", Math.round(alt.unexplainedInkRatio() * 1000.0) / 1000.0);
                a.addProperty("structuralScore", Math.round(alt.structuralScore() * 1000.0) / 1000.0);
                a.addProperty("rotationDeg", Math.round(alt.rotationDeg() * 100.0) / 100.0);
                altsArray.add(a);
            }
        }
        parent.add("alternatives", altsArray);
    }
}
