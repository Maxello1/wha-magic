package com.maxello1.whamagic.dev;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.maxello1.whamagic.magic.RecognitionAlternative;
import com.maxello1.whamagic.magic.RecognizedSigil;
import com.maxello1.whamagic.magic.RecognizedSign;
import com.maxello1.whamagic.magic.UnknownSymbol;
import com.maxello1.whamagic.parser.Point;
import com.maxello1.whamagic.parser.PointCloudRecognizer;
import com.maxello1.whamagic.parser.SegmentationDebugResult;
import com.maxello1.whamagic.parser.SpellDictionary;
import com.maxello1.whamagic.parser.SpellParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeMap;

/** Saves development drawings and their recognition results as JSON samples. */
public final class SampleRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(SampleRecorder.class);
    private static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static final Path SAMPLES_DIRECTORY = Path.of("run", "dev-samples");
    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");
    static final int MAX_FILENAME_LENGTH = 180;

    private SampleRecorder() {}

    /**
     * Save the current drawing and parse result as a JSON sample file.
     *
     * @param rawStrokes the raw drawn strokes
     * @param result the parse result (may be null if parsing failed)
     * @param notes optional notes about this sample
     * @return the saved file path, or null on failure
     */
    public static String saveSample(
            List<List<Point>> rawStrokes,
            SpellParser.ParseResult result,
            RecognitionSampleMetadata metadata) {
        LocalDateTime recordedAt = LocalDateTime.now();
        try {
            Path file = writeSample(SAMPLES_DIRECTORY, rawStrokes, result, metadata, recordedAt);
            LOGGER.info("Sample saved: {}", file);
            return file.toString();
        } catch (IOException exception) {
            LOGGER.error("Failed to save sample", exception);
            return null;
        }
    }

    static Path writeSample(
            Path samplesDirectory,
            List<List<Point>> rawStrokes,
            SpellParser.ParseResult result,
            RecognitionSampleMetadata metadata,
            LocalDateTime recordedAt) throws IOException {
        Objects.requireNonNull(samplesDirectory, "samplesDirectory");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(recordedAt, "recordedAt");

        Files.createDirectories(samplesDirectory);
        Path file = samplesDirectory.resolve(buildFilename(metadata, result, recordedAt)).toAbsolutePath();
        JsonObject sample = createSample(rawStrokes, result, metadata, recordedAt);
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(sample, writer);
        }
        return file;
    }

    static String buildFilename(
            RecognitionSampleMetadata metadata,
            SpellParser.ParseResult result,
            LocalDateTime recordedAt) {
        String timestamp = "sample_" + recordedAt.format(FILE_TIMESTAMP) + '_';
        String name = metadata.sampleName().isEmpty() ? "" : fileSafe(metadata.sampleName()) + '_';
        String intended = intendedLayoutLabel(metadata);
        String actual = truncateLabel(actualSymbolsLabel(result), 64);
        String validity = result == null ? "unparsed" : result.isValidSpell() ? "valid" : "invalid";
        String suffix = "__got-" + actual + '_' + validity + ".json";
        String middle = name + intended;
        int allowedMiddleLength = Math.max(1, MAX_FILENAME_LENGTH - timestamp.length() - suffix.length());
        if (middle.length() > allowedMiddleLength) {
            middle = trimDashes(middle.substring(0, allowedMiddleLength));
        }
        return timestamp + (middle.isEmpty() ? "intent-unspecified" : middle) + suffix;
    }

    static String layoutLabel(SpellParser.ParseResult result) {
        if (result == null) {
            return "unparsed";
        }

        TreeMap<String, Integer> symbols = new TreeMap<>();
        if (result.ast != null) {
            for (RecognizedSigil sigil : result.ast.sigils()) {
                if (sigil.id() != null) {
                    symbols.merge(sigil.id().getPath(), 1, Integer::sum);
                }
            }
            for (RecognizedSign sign : result.ast.signs()) {
                if (sign.id() != null && !sign.id().isBlank()) {
                    symbols.merge(sign.id(), 1, Integer::sum);
                }
            }
        }

        String status = result.isValidSpell() ? "valid" : "invalid";
        if (symbols.isEmpty()) {
            return status + "_unknown";
        }

        StringBuilder label = new StringBuilder(status);
        symbols.forEach((symbol, count) -> {
            label.append('_').append(fileSafe(symbol));
            if (count > 1) {
                label.append("-x").append(count);
            }
        });
        return label.length() <= 96 ? label.toString() : label.substring(0, 96);
    }

    static String fileSafe(String value) {
        String safe = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("^-+|-+$", "");
        return safe.isEmpty() ? "unknown" : safe;
    }

    private static String intendedLayoutLabel(RecognitionSampleMetadata metadata) {
        StringBuilder label = new StringBuilder();
        for (RecognitionSampleMetadata.IntendedSymbol symbol : metadata.intendedSymbols()) {
            if (!label.isEmpty()) label.append('_');
            label.append(fileSafe(symbol.id().getPath()));
            if (symbol.rotationDeg() != null) {
                label.append("-r").append(rotationLabel(symbol.rotationDeg()));
            }
        }
        if (metadata.includesCircle()) {
            if (!label.isEmpty()) label.append('_');
            label.append("ring");
        }
        return label.isEmpty() ? "intent-unspecified" : label.toString();
    }

    private static String actualSymbolsLabel(SpellParser.ParseResult result) {
        if (result == null || result.ast == null) return "unknown";
        TreeMap<String, Integer> symbols = new TreeMap<>();
        for (RecognizedSigil sigil : result.ast.sigils()) {
            if (sigil.id() != null) symbols.merge(fileSafe(sigil.id().getPath()), 1, Integer::sum);
        }
        for (RecognizedSign sign : result.ast.signs()) {
            if (sign.id() != null && !sign.id().isBlank()) {
                String id = sign.id();
                int colon = id.indexOf(':');
                symbols.merge(fileSafe(colon >= 0 ? id.substring(colon + 1) : id), 1, Integer::sum);
            }
        }
        if (symbols.isEmpty()) return "unknown";
        StringBuilder label = new StringBuilder();
        symbols.forEach((symbol, count) -> {
            if (!label.isEmpty()) label.append('-');
            label.append(symbol);
            if (count > 1) label.append("-x").append(count);
        });
        return label.toString();
    }

    private static String rotationLabel(double rotation) {
        if (rotation == Math.rint(rotation)) {
            return String.format(Locale.ROOT, "%03d", (int) rotation);
        }
        String text = Double.toString(rotation).replace('.', 'p');
        return rotation < 100.0 ? "0" + (rotation < 10.0 ? "0" : "") + text : text;
    }

    private static String trimDashes(String value) {
        return value.replaceAll("[-_]+$", "");
    }

    private static String truncateLabel(String value, int maximumLength) {
        return value.length() <= maximumLength
                ? value
                : trimDashes(value.substring(0, maximumLength));
    }

    private static JsonObject createSample(
            List<List<Point>> rawStrokes,
            SpellParser.ParseResult result,
            RecognitionSampleMetadata metadata,
            LocalDateTime recordedAt) {
        SpellDictionary.DictionarySnapshot dictionary = SpellDictionary.snapshot();
        JsonObject sample = new JsonObject();
        sample.addProperty("formatVersion", 4);
        sample.addProperty("recognizerVersion", PointCloudRecognizer.RECOGNIZER_VERSION);
        sample.addProperty("dictionaryVersion", dictionary.version());
        sample.addProperty("dictionaryHash", dictionary.hash());
        sample.addProperty("sampleName", metadata.sampleName());
        sample.addProperty("sampleRole", metadata.sampleRole().jsonName());
        sample.add("expectedIntent", expectedIntentJson(metadata));
        sample.addProperty("expectedValid", metadata.expectedValid());
        sample.addProperty("notes", metadata.notes());
        sample.addProperty("sourceDate", recordedAt.toLocalDate().toString());
        sample.addProperty(
                "influencedTemplateOrThreshold", metadata.influencedTemplateOrThreshold());
        sample.addProperty("timestamp", recordedAt.toString());
        sample.add("rawStrokes", rawStrokesJson(rawStrokes));
        if (result != null) {
            sample.add("result", resultJson(result));
        }
        return sample;
    }

    private static JsonObject expectedIntentJson(RecognitionSampleMetadata metadata) {
        JsonObject expectedIntent = new JsonObject();
        expectedIntent.add("sigils", intendedSymbolsJson(metadata.sigils()));
        expectedIntent.add("signs", intendedSymbolsJson(metadata.signs()));
        expectedIntent.addProperty("ring", metadata.includesCircle());
        expectedIntent.addProperty("ringStyle", metadata.ringStyle().jsonName());
        return expectedIntent;
    }

    private static JsonArray intendedSymbolsJson(
            List<RecognitionSampleMetadata.IntendedSymbol> symbols) {
        JsonArray json = new JsonArray();
        for (RecognitionSampleMetadata.IntendedSymbol symbol : symbols) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", symbol.id().toString());
            if (symbol.rotationDeg() == null) entry.add("rotationDeg", JsonNull.INSTANCE);
            else entry.addProperty("rotationDeg", symbol.rotationDeg());
            json.add(entry);
        }
        return json;
    }

    private static JsonArray rawStrokesJson(List<List<Point>> rawStrokes) {
        JsonArray strokesJson = new JsonArray();
        if (rawStrokes == null) {
            return strokesJson;
        }
        for (List<Point> stroke : rawStrokes) {
            JsonArray strokeJson = new JsonArray();
            for (Point point : stroke) {
                JsonObject pointJson = new JsonObject();
                pointJson.addProperty("x", point.x);
                pointJson.addProperty("y", point.y);
                strokeJson.add(pointJson);
            }
            strokesJson.add(strokeJson);
        }
        return strokesJson;
    }

    private static JsonObject resultJson(SpellParser.ParseResult result) {
        JsonObject resultJson = new JsonObject();
        resultJson.addProperty("valid", result.isValidSpell());
        if (result.ast != null) {
            resultJson.add("sigils", sigilsJson(result.ast.sigils()));
            resultJson.add("signs", signsJson(result.ast.signs()));
            resultJson.add("unknowns", unknownsJson(result.ast.unknownSymbols()));
        }
        if (result.debugResult != null) {
            addDiagnostics(resultJson, result.debugResult);
        }
        return resultJson;
    }

    private static JsonArray sigilsJson(List<RecognizedSigil> sigils) {
        JsonArray sigilsJson = new JsonArray();
        if (sigils == null) {
            return sigilsJson;
        }
        for (RecognizedSigil sigil : sigils) {
            JsonObject sigilJson = new JsonObject();
            sigilJson.addProperty("id", sigil.id() != null ? sigil.id().toString() : "null");
            sigilJson.addProperty("matchedTemplateId", sigil.matchedTemplateId());
            sigilJson.addProperty("element", sigil.element() != null ? sigil.element().name() : "null");
            sigilJson.addProperty("confidence", round(sigil.recognitionConfidence(), 1000.0));
            sigilJson.addProperty("rejectionReason", sigil.rejectionReason().name());
            sigilJson.add("sourceStrokeIndices", GSON.toJsonTree(sigil.sourceStrokeIndices()));
            addAlternatives(sigilJson, sigil.alternatives());
            sigilsJson.add(sigilJson);
        }
        return sigilsJson;
    }

    private static JsonArray signsJson(List<RecognizedSign> signs) {
        JsonArray signsJson = new JsonArray();
        if (signs == null) {
            return signsJson;
        }
        for (RecognizedSign sign : signs) {
            JsonObject signJson = new JsonObject();
            signJson.addProperty("id", sign.id());
            signJson.addProperty("matchedTemplateId", sign.matchedTemplateId());
            signJson.addProperty("confidence", round(sign.confidence(), 1000.0));
            signJson.addProperty("angleAroundRing", round(sign.angleAroundRing(), 100.0));
            signJson.add("sourceStrokeIndices", GSON.toJsonTree(sign.sourceStrokeIndices()));
            signsJson.add(signJson);
        }
        return signsJson;
    }

    private static JsonArray unknownsJson(List<UnknownSymbol> unknowns) {
        JsonArray unknownsJson = new JsonArray();
        if (unknowns == null) {
            return unknownsJson;
        }
        for (UnknownSymbol unknown : unknowns) {
            JsonObject unknownJson = new JsonObject();
            unknownJson.addProperty("candidateId", unknown.candidateId());
            unknownJson.addProperty("state", unknown.state().name());
            unknownJson.addProperty("rejectionReason", unknown.rejectionReason().name());
            unknownJson.add("sourceStrokeIndices", GSON.toJsonTree(unknown.sourceStrokeIndices()));
            addAlternatives(unknownJson, unknown.alternatives());
            unknownsJson.add(unknownJson);
        }
        return unknownsJson;
    }

    private static void addDiagnostics(
            JsonObject resultJson,
            SegmentationDebugResult diagnostics) {
        resultJson.addProperty("candidateCount", diagnostics.candidateCount());
        resultJson.addProperty("recognitionCalls", diagnostics.recognitionCalls());
        resultJson.addProperty("primitiveGroupCount", diagnostics.primitiveGroupCount());
        resultJson.addProperty("selectedCandidateCount", diagnostics.selectedCandidateCount());
        resultJson.addProperty("candidateLimitReached", diagnostics.candidateLimitReached());
        resultJson.addProperty("ringBudgetExhausted", diagnostics.ringBudgetExhausted());
        resultJson.addProperty("recognitionBudgetExhausted", diagnostics.recognitionBudgetExhausted());
        resultJson.addProperty("unevaluatedCandidateCount", diagnostics.unevaluatedCandidateCount());
        resultJson.add("droppedSourceStrokeIndices", GSON.toJsonTree(diagnostics.droppedSourceStrokeIndices()));
        resultJson.addProperty("ringCombinationsConsidered", diagnostics.ringCombinationsConsidered());
        resultJson.addProperty("ringFitsAttempted", diagnostics.ringFitsAttempted());
        resultJson.addProperty("ringElapsedNanos", diagnostics.ringElapsedNanos());
        resultJson.add("ringStrokeIndices", GSON.toJsonTree(diagnostics.ringStrokeIndices()));
    }

    private static void addAlternatives(
            JsonObject parent,
            List<RecognitionAlternative> alternatives) {
        JsonArray alternativesJson = new JsonArray();
        if (alternatives != null) {
            for (RecognitionAlternative alternative : alternatives) {
                JsonObject alternativeJson = new JsonObject();
                alternativeJson.addProperty(
                        "id", alternative.id() != null ? alternative.id().toString() : "null");
                alternativeJson.addProperty("displayName", alternative.displayName());
                alternativeJson.addProperty(
                        "kind", alternative.kind() != null ? alternative.kind().name() : "null");
                alternativeJson.addProperty("rawScore", round(alternative.rawScore(), 1000.0));
                alternativeJson.addProperty("roleScore", round(alternative.roleScore(), 1000.0));
                alternativeJson.addProperty(
                        "templateCoverage", round(alternative.templateCoverage(), 1000.0));
                alternativeJson.addProperty(
                        "candidateExplainedRatio", round(alternative.candidateExplainedRatio(), 1000.0));
                alternativeJson.addProperty(
                        "unexplainedInkRatio", round(alternative.unexplainedInkRatio(), 1000.0));
                alternativeJson.addProperty(
                        "structuralScore", round(alternative.structuralScore(), 1000.0));
                alternativeJson.addProperty("rotationDeg", round(alternative.rotationDeg(), 100.0));
                alternativesJson.add(alternativeJson);
            }
        }
        parent.add("alternatives", alternativesJson);
    }

    private static double round(double value, double scale) {
        return Math.round(value * scale) / scale;
    }
}
