package com.maxello1.whamagic.dev;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maxello1.whamagic.parser.Point;
import com.maxello1.whamagic.parser.ParseDetail;
import com.maxello1.whamagic.parser.PointCloudRecognizer;
import com.maxello1.whamagic.parser.SpellDictionary;
import com.maxello1.whamagic.parser.SpellParser;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Scanner;

/** Interactive and programmatic promotion of F5 recordings into fixture JSON. */
public final class SamplePromotionTool {
    public static final int FIXTURE_FORMAT_VERSION = 3;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SamplePromotionTool() {}

    public enum SampleRole {
        TRAINING_CANDIDATE("training_candidate"),
        HOLDOUT("holdout"),
        NEGATIVE_CONFUSION("negative_confusion"),
        EXPERIMENTAL("experimental");

        private final String jsonName;

        SampleRole(String jsonName) {
            this.jsonName = jsonName;
        }

        public String jsonName() {
            return jsonName;
        }

        public static SampleRole parse(String value) {
            return Arrays.stream(values())
                    .filter(role -> role.jsonName.equals(value.trim().toLowerCase(Locale.ROOT)))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown sample role: " + value));
        }
    }

    public record PromotionRequest(
            Path recordedSample,
            Path outputFixture,
            String name,
            SampleRole sampleRole,
            List<String> expectedSigils,
            List<String> expectedSigns,
            boolean expectRing,
            boolean expectValidSpell,
            String notes,
            LocalDate sourceDate,
            boolean influencedTemplateOrThreshold
    ) {
        public PromotionRequest {
            recordedSample = Objects.requireNonNull(recordedSample, "recordedSample");
            outputFixture = Objects.requireNonNull(outputFixture, "outputFixture");
            name = requireText(name, "name");
            sampleRole = Objects.requireNonNull(sampleRole, "sampleRole");
            expectedSigils = List.copyOf(expectedSigils == null ? List.of() : expectedSigils);
            expectedSigns = List.copyOf(expectedSigns == null ? List.of() : expectedSigns);
            notes = notes == null ? "" : notes;
            sourceDate = Objects.requireNonNull(sourceDate, "sourceDate");
            validateSemanticIds(expectedSigils);
            validateSemanticIds(expectedSigns);
            if (sampleRole == SampleRole.HOLDOUT && influencedTemplateOrThreshold) {
                throw new IllegalArgumentException(
                        "A holdout cannot be marked as influencing a template or threshold");
            }
        }
    }

    public record Preview(
            List<List<Point>> rawStrokes,
            SpellParser.ParseResult recognition,
            String recognizerVersion,
            String dictionaryVersion,
            String dictionaryHash,
            LocalDate sourceDate
    ) {
        public Preview {
            rawStrokes = rawStrokes.stream().map(List::copyOf).toList();
        }
    }

    record CommandLine(Path input, boolean previewOnly) {}

    private record RecordedSample(JsonArray rawStrokes, List<List<Point>> points, LocalDate sourceDate) {}

    public static Preview preview(Path recordedSample) throws IOException {
        RecordedSample sample = readRecordedSample(recordedSample);
        SpellDictionary.DictionarySnapshot dictionary = SpellDictionary.snapshot();
        return new Preview(
                sample.points(), SpellParser.parse(sample.points(), ParseDetail.FULL_DIAGNOSTICS),
                PointCloudRecognizer.RECOGNIZER_VERSION,
                dictionary.version(), dictionary.hash(), sample.sourceDate());
    }

    public static Path promote(PromotionRequest request) throws IOException {
        RecordedSample sample = readRecordedSample(request.recordedSample());
        SpellDictionary.DictionarySnapshot dictionary = SpellDictionary.snapshot();

        JsonObject fixture = new JsonObject();
        fixture.addProperty("formatVersion", FIXTURE_FORMAT_VERSION);
        fixture.addProperty("name", request.name());
        fixture.addProperty("recognizerVersion", PointCloudRecognizer.RECOGNIZER_VERSION);
        fixture.addProperty("dictionaryVersion", dictionary.version());
        fixture.addProperty("dictionaryHash", dictionary.hash());
        fixture.addProperty("sampleRole", request.sampleRole().jsonName());

        JsonObject expectedIntent = new JsonObject();
        expectedIntent.add("sigils", GSON.toJsonTree(request.expectedSigils()));
        expectedIntent.add("signs", GSON.toJsonTree(request.expectedSigns()));
        fixture.add("expectedIntent", expectedIntent);
        fixture.addProperty("expectRing", request.expectRing());
        fixture.addProperty("expectValidSpell", request.expectValidSpell());
        fixture.addProperty("notes", request.notes());
        fixture.addProperty("sourceDate", request.sourceDate().toString());
        fixture.addProperty("influencedTemplateOrThreshold", request.influencedTemplateOrThreshold());
        fixture.add("strokes", sample.rawStrokes().deepCopy());

        Path parent = request.outputFixture().toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (Writer writer = Files.newBufferedWriter(
                request.outputFixture(), StandardCharsets.UTF_8)) {
            GSON.toJson(fixture, writer);
        }
        return request.outputFixture();
    }

    /** Console entry point for development use; every promotion choice is explicit. */
    public static void main(String[] args) throws Exception {
        CommandLine commandLine = parseCommandLine(args);
        try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            Path input = commandLine.input() != null
                    ? commandLine.input()
                    : promptPath(scanner, "F5 sample path");
            Preview preview = preview(input);
            printPreview(preview);
            if (commandLine.previewOnly()) {
                return;
            }

            SampleRole role = SampleRole.parse(prompt(scanner,
                    "sampleRole (training_candidate, holdout, negative_confusion, experimental)"));
            List<String> sigils = commaSeparated(prompt(scanner, "Expected sigils (comma-separated, blank for none)"));
            List<String> signs = commaSeparated(prompt(scanner, "Expected signs (comma-separated, blank for none)"));
            boolean expectRing = yesNo(prompt(scanner, "Expect a ring? (y/n)"));
            boolean expectValid = yesNo(prompt(scanner, "Expect a valid spell? (y/n)"));
            boolean influenced = yesNo(prompt(scanner,
                    "Did this sample influence a template or threshold? (y/n)"));
            String name = prompt(scanner, "Fixture name");
            String notes = prompt(scanner, "Notes");
            Path output = promptPath(scanner, "Output fixture path");

            promote(new PromotionRequest(
                    input, output, name, role, sigils, signs,
                    expectRing, expectValid, notes, preview.sourceDate(), influenced));
            System.out.println("Fixture written to " + output.toAbsolutePath());
        }
    }

    static CommandLine parseCommandLine(String[] args) {
        Objects.requireNonNull(args, "args");
        Path input = null;
        boolean previewOnly = false;
        for (String argument : args) {
            if ("--preview-only".equals(argument)) {
                if (previewOnly) {
                    throw new IllegalArgumentException("Duplicate option: --preview-only");
                }
                previewOnly = true;
            } else if (argument.startsWith("--")) {
                throw new IllegalArgumentException("Unknown option: " + argument);
            } else if (input == null) {
                input = Path.of(argument);
            } else {
                throw new IllegalArgumentException("Unexpected argument: " + argument);
            }
        }
        return new CommandLine(input, previewOnly);
    }

    private static RecordedSample readRecordedSample(Path path) throws IOException {
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("Recorded sample root must be an object");
            }
            root = parsed.getAsJsonObject();
        }
        JsonElement raw = root.get("rawStrokes");
        if (raw == null || !raw.isJsonArray()) {
            throw new IllegalArgumentException("Recorded sample must contain rawStrokes");
        }
        JsonArray rawStrokes = raw.getAsJsonArray().deepCopy();
        List<List<Point>> points = parseStrokes(rawStrokes);
        return new RecordedSample(rawStrokes, points, sourceDate(root));
    }

    private static List<List<Point>> parseStrokes(JsonArray rawStrokes) {
        List<List<Point>> strokes = new ArrayList<>();
        for (int strokeIndex = 0; strokeIndex < rawStrokes.size(); strokeIndex++) {
            JsonElement strokeElement = rawStrokes.get(strokeIndex);
            if (!strokeElement.isJsonArray()) {
                throw new IllegalArgumentException("rawStrokes[" + strokeIndex + "] must be an array");
            }
            List<Point> stroke = new ArrayList<>();
            JsonArray points = strokeElement.getAsJsonArray();
            for (int pointIndex = 0; pointIndex < points.size(); pointIndex++) {
                JsonElement pointElement = points.get(pointIndex);
                String context = "rawStrokes[" + strokeIndex + "][" + pointIndex + "]";
                if (!pointElement.isJsonObject()) {
                    throw new IllegalArgumentException(context + " must be an object");
                }
                JsonObject point = pointElement.getAsJsonObject();
                double x = requiredFinite(point, "x", context);
                double y = requiredFinite(point, "y", context);
                stroke.add(new Point(x, y));
            }
            strokes.add(List.copyOf(stroke));
        }
        return List.copyOf(strokes);
    }

    private static double requiredFinite(JsonObject point, String field, String context) {
        JsonElement value = point.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(context + "." + field + " must be numeric");
        }
        double result = value.getAsDouble();
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException(context + "." + field + " must be finite");
        }
        return result;
    }

    private static LocalDate sourceDate(JsonObject root) {
        if (root.has("sourceDate")) {
            return LocalDate.parse(root.get("sourceDate").getAsString());
        }
        if (root.has("timestamp")) {
            String timestamp = root.get("timestamp").getAsString();
            return LocalDate.parse(timestamp.substring(0, Math.min(10, timestamp.length())));
        }
        return LocalDate.now();
    }

    private static void printPreview(Preview preview) {
        SpellParser.ParseResult result = preview.recognition();
        System.out.printf("Recognizer: %s%nDictionary: %s (%s)%n",
                preview.recognizerVersion(), preview.dictionaryVersion(), preview.dictionaryHash());
        System.out.printf("Strokes: %d, valid: %s, state: %s%n",
                preview.rawStrokes().size(), result.isValidSpell(), result.ir.state());
        System.out.println("Sigils: " + result.ast.sigils().stream().map(s -> s.id().toString()).toList());
        System.out.println("Signs: " + result.ast.signs().stream().map(s -> s.id()).toList());
        System.out.println("Unknown ink: " + result.ast.unknownInk());
        if (result.debugResult != null) {
            System.out.printf("Candidates: %d, calls: %d, ring strokes: %s, ring combinations: %d, ring fits: %d%n",
                    result.debugResult.candidateCount(), result.debugResult.recognitionCalls(),
                    result.debugResult.ringStrokeIndices(),
                    result.debugResult.ringCombinationsConsidered(), result.debugResult.ringFitsAttempted());
        }
    }

    private static String prompt(Scanner scanner, String label) {
        System.out.print(label + ": ");
        return scanner.nextLine().trim();
    }

    private static Path promptPath(Scanner scanner, String label) {
        return Path.of(prompt(scanner, label));
    }

    private static boolean yesNo(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "y", "yes", "true" -> true;
            case "n", "no", "false" -> false;
            default -> throw new IllegalArgumentException("Expected yes or no, got: " + value);
        };
    }

    private static List<String> commaSeparated(String value) {
        if (value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    private static void validateSemanticIds(List<String> ids) {
        for (String id : ids) {
            if (Identifier.tryParse(id) == null) {
                throw new IllegalArgumentException("Invalid semantic symbol ID: " + id);
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
