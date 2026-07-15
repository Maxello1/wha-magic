package com.maxello1.whamagic.parser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.maxello1.whamagic.magic.SigilSemantic;
import com.maxello1.whamagic.magic.SignSemantic;
import com.maxello1.whamagic.magic.SymbolKind;
import com.maxello1.whamagic.magic.SymbolRecognitionRules;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Atomically loads, validates, and publishes the active recognition dictionary. */
public final class SpellDictionary {
    public static final String DICTIONARY_VERSION = "2";
    public static final String SIGILS_RESOURCE = "/data/wha-magic/dictionary/sigils.json";
    public static final String SIGNS_RESOURCE = "/data/wha-magic/dictionary/signs.json";

    private static final Logger LOGGER = LoggerFactory.getLogger(SpellDictionary.class);
    private static final Gson GSON = new Gson();
    private static final Object LOAD_LOCK = new Object();
    private static final ResourceSource CLASSPATH_RESOURCES =
            path -> SpellDictionary.class.getResourceAsStream(path);

    private static volatile ActiveState active = ActiveState.unloaded();

    private SpellDictionary() {}

    public record TemplateIdentity(
            String semanticId,
            String templateId,
            String displayName,
            SymbolKind kind) {}

    public record DictionarySnapshot(
            String version,
            String hash,
            List<TemplateIdentity> templates
    ) {
        public DictionarySnapshot {
            templates = List.copyOf(templates);
        }

        public int templateCount() {
            return templates.size();
        }
    }

    @FunctionalInterface
    interface ResourceSource {
        InputStream open(String path) throws IOException;
    }

    public static final class DictionaryLoadException extends IllegalArgumentException {
        public DictionaryLoadException(String message) {
            super(message);
        }

        public DictionaryLoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record ActiveState(
            boolean loaded,
            DictionarySnapshot snapshot,
            PointCloudRecognizer.PointCloudIndex pointCloudIndex
    ) {
        private static ActiveState unloaded() {
            return new ActiveState(
                    false,
                    new DictionarySnapshot(DICTIONARY_VERSION, "unloaded", List.of()),
                    PointCloudRecognizer.PointCloudIndex.empty());
        }
    }

    private record SemanticDefinition(
            String declaredId,
            SymbolKind kind,
            String displayName,
            String element,
            SigilSemantic sigilSemantic,
            SignSemantic signSemantic,
            SymbolRecognitionRules rules
    ) {}

    /** Load once. The active snapshot changes only after complete validation succeeds. */
    public static void ensureLoaded() {
        if (active.loaded()) return;
        synchronized (LOAD_LOCK) {
            if (active.loaded()) return;
            ActiveState candidate = buildSnapshot(CLASSPATH_RESOURCES);
            active = candidate;
            logLoaded(candidate);
        }
    }

    /** Reload atomically, retaining the previous working snapshot on any failure. */
    public static void reload() {
        reload(CLASSPATH_RESOURCES);
    }

    static void reload(ResourceSource source) {
        Objects.requireNonNull(source, "source");
        synchronized (LOAD_LOCK) {
            ActiveState candidate = buildSnapshot(source);
            active = candidate;
            logLoaded(candidate);
        }
    }

    public static boolean isLoaded() {
        return active.loaded();
    }

    public static DictionarySnapshot snapshot() {
        ensureLoaded();
        return active.snapshot();
    }

    static PointCloudRecognizer.PointCloudIndex pointCloudIndex() {
        ensureLoaded();
        return active.pointCloudIndex();
    }

    private static ActiveState buildSnapshot(ResourceSource source) {
        try {
            byte[] sigilBytes = readRequired(source, SIGILS_RESOURCE);
            byte[] signBytes = readRequired(source, SIGNS_RESOURCE);

            List<DictionaryTemplate> definitions = new ArrayList<>();
            Set<String> templateIds = new LinkedHashSet<>();
            Map<String, SemanticDefinition> semanticDefinitions = new LinkedHashMap<>();
            parseResource(sigilBytes, SIGILS_RESOURCE, SymbolKind.SIGIL,
                    definitions, templateIds, semanticDefinitions);
            parseResource(signBytes, SIGNS_RESOURCE, SymbolKind.SIGN,
                    definitions, templateIds, semanticDefinitions);
            if (definitions.isEmpty()) {
                throw new DictionaryLoadException("Dictionary contains no templates");
            }

            List<PointCloudRecognizer.PointCloudTemplate> pointCloud = new ArrayList<>();
            List<TemplateIdentity> identities = new ArrayList<>();
            for (DictionaryTemplate definition : definitions) {
                try {
                    pointCloud.add(PointCloudRecognizer.buildTemplate(definition));
                } catch (RuntimeException exception) {
                    throw new DictionaryLoadException(
                            "Unsupported template complexity or geometry for '"
                                    + definition.templateId() + "'", exception);
                }
                identities.add(new TemplateIdentity(
                        definition.semanticId(), definition.templateId(),
                        definition.displayName(), definition.kind()));
            }

            DictionarySnapshot metadata = new DictionarySnapshot(
                    DICTIONARY_VERSION,
                    dictionaryHash(sigilBytes, signBytes),
                    identities);
            PointCloudRecognizer.PointCloudIndex pointCloudIndex =
                    PointCloudRecognizer.buildIndex(pointCloud);
            return new ActiveState(true, metadata, pointCloudIndex);
        } catch (DictionaryLoadException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DictionaryLoadException("Failed to build dictionary snapshot", exception);
        }
    }

    private static byte[] readRequired(ResourceSource source, String path) throws IOException {
        try (InputStream input = source.open(path)) {
            if (input == null) {
                throw new DictionaryLoadException("Missing dictionary resource: " + path);
            }
            return input.readAllBytes();
        }
    }

    private static void parseResource(
            byte[] bytes,
            String path,
            SymbolKind kind,
            List<DictionaryTemplate> definitions,
            Set<String> templateIds,
            Map<String, SemanticDefinition> semanticDefinitions) {
        JsonElement root;
        try {
            root = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonElement.class);
        } catch (JsonParseException exception) {
            throw new DictionaryLoadException("Malformed JSON in " + path, exception);
        }
        if (root == null || !root.isJsonArray()) {
            throw new DictionaryLoadException("Dictionary resource must contain an array: " + path);
        }

        JsonArray entries = root.getAsJsonArray();
        for (int index = 0; index < entries.size(); index++) {
            if (!entries.get(index).isJsonObject()) {
                throw new DictionaryLoadException(path + " entry " + index + " must be an object");
            }
            JsonObject entry = entries.get(index).getAsJsonObject();
            String context = path + " entry " + index;
            String semanticId = requiredString(entry, "id", context);
            String templateId = optionalString(entry, "templateId", semanticId, context);
            String displayName = requiredString(entry, "displayName", context);
            String semanticKey = identifierKey(semanticId, "semantic", context);
            String templateKey = identifierKey(templateId, "template", context);
            if (!templateIds.add(templateKey)) {
                throw new DictionaryLoadException("Duplicate template ID: " + templateId);
            }

            String element = kind == SymbolKind.SIGIL
                    ? requiredString(entry, "element", context)
                    : null;
            JsonObject semantic = requiredObject(entry, "semantic", context);
            SigilSemantic sigilSemantic = kind == SymbolKind.SIGIL
                    ? parseSigilSemantic(semantic, context)
                    : null;
            SignSemantic signSemantic = kind == SymbolKind.SIGN
                    ? parseSignSemantic(semantic, context)
                    : null;
            SymbolRecognitionRules rules = parseRules(entry, kind, context);
            List<List<Point>> strokes = parseStrokes(entry, context);

            SemanticDefinition semanticDefinition = new SemanticDefinition(
                    semanticId, kind, displayName, element, sigilSemantic, signSemantic, rules);
            SemanticDefinition previous = semanticDefinitions.putIfAbsent(semanticKey, semanticDefinition);
            if (previous != null && !previous.equals(semanticDefinition)) {
                throw new DictionaryLoadException(
                        "Visual variants for semantic ID '" + semanticId
                                + "' must share kind, display name, semantics, element, and rules");
            }

            definitions.add(new DictionaryTemplate(
                    semanticId, templateId, displayName, kind, element, strokes,
                    sigilSemantic, signSemantic, rules));
        }
    }

    private static List<List<Point>> parseStrokes(JsonObject entry, String context) {
        JsonObject strokeTemplate = requiredObject(entry, "strokeTemplate", context);
        JsonElement strokesElement = strokeTemplate.get("strokes");
        if (strokesElement == null || !strokesElement.isJsonArray()) {
            throw new DictionaryLoadException("Missing or invalid strokes in " + context);
        }
        JsonArray strokesArray = strokesElement.getAsJsonArray();
        if (strokesArray.isEmpty()) {
            throw new DictionaryLoadException("Empty strokes in " + context);
        }

        List<List<Point>> strokes = new ArrayList<>();
        int meaningfulStrokes = 0;
        for (int strokeIndex = 0; strokeIndex < strokesArray.size(); strokeIndex++) {
            JsonElement strokeElement = strokesArray.get(strokeIndex);
            if (!strokeElement.isJsonArray()) {
                throw new DictionaryLoadException("Stroke " + strokeIndex + " is not an array in " + context);
            }
            JsonArray pointsArray = strokeElement.getAsJsonArray();
            if (pointsArray.isEmpty()) {
                throw new DictionaryLoadException("Stroke " + strokeIndex + " is empty in " + context);
            }
            List<Point> stroke = new ArrayList<>();
            for (int pointIndex = 0; pointIndex < pointsArray.size(); pointIndex++) {
                JsonElement pointElement = pointsArray.get(pointIndex);
                if (!pointElement.isJsonObject()) {
                    throw new DictionaryLoadException("Point " + pointIndex + " is not an object in " + context);
                }
                JsonObject point = pointElement.getAsJsonObject();
                double x = requiredFiniteDouble(point, "x", context);
                double y = requiredFiniteDouble(point, "y", context);
                stroke.add(new Point(x, y));
            }
            if (stroke.size() >= 2) meaningfulStrokes++;
            strokes.add(List.copyOf(stroke));
        }
        if (meaningfulStrokes == 0) {
            throw new DictionaryLoadException("Template has no meaningful strokes in " + context);
        }
        PointCloudRecognizer.NormalizationAllocation allocation =
                PointCloudRecognizer.normalizationAllocation(strokes);
        if (!allocation.supported()) {
            throw new DictionaryLoadException(
                    "Unsupported template complexity in " + context + ": "
                            + allocation.meaningfulStrokeCount() + " meaningful strokes");
        }
        return List.copyOf(strokes);
    }

    private static SigilSemantic parseSigilSemantic(JsonObject semantic, String context) {
        return new SigilSemantic(
                requiredFiniteDouble(semantic, "force", context),
                requiredFiniteDouble(semantic, "focus", context),
                requiredFiniteDouble(semantic, "spread", context),
                requiredFiniteDouble(semantic, "range", context),
                requiredFiniteDouble(semantic, "lifetimeBias", context));
    }

    private static SignSemantic parseSignSemantic(JsonObject semantic, String context) {
        return new SignSemantic(
                requiredString(semantic, "manifestation", context),
                requiredString(semantic, "directionMode", context),
                requiredFiniteDouble(semantic, "force", context),
                requiredFiniteDouble(semantic, "focus", context),
                requiredFiniteDouble(semantic, "spread", context),
                requiredFiniteDouble(semantic, "range", context),
                optionalFiniteDouble(semantic, "lifetimeBias", 0.0, context));
    }

    private static SymbolRecognitionRules parseRules(
            JsonObject entry, SymbolKind kind, String context) {
        SymbolRecognitionRules defaults = kind == SymbolKind.SIGIL
                ? SymbolRecognitionRules.SIGIL_DEFAULTS
                : SymbolRecognitionRules.SIGN_DEFAULTS;
        if (!entry.has("recognitionRules")) return defaults;
        JsonObject rules = requiredObject(entry, "recognitionRules", context);
        try {
            return new SymbolRecognitionRules(
                    optionalFiniteDouble(rules, "minimumScore", defaults.minimumScore(), context),
                    optionalFiniteDouble(rules, "minimumGap", defaults.minimumGap(), context),
                    optionalFiniteDouble(rules, "minimumComplexity", defaults.minimumComplexity(), context),
                    optionalFiniteDouble(rules, "minimumDimensionRatio", defaults.minimumDimensionRatio(), context),
                    optionalBoolean(rules, "allowLineLike", defaults.allowLineLike(), context),
                    optionalInt(rules, "minimumClosedContours", defaults.minimumClosedContours(), context),
                    optionalInt(rules, "softMinimumStrokeCount", defaults.softMinimumStrokeCount(), context),
                    optionalInt(rules, "softMaximumStrokeCount", defaults.softMaximumStrokeCount(), context));
        } catch (IllegalArgumentException exception) {
            throw new DictionaryLoadException("Invalid recognition rules in " + context, exception);
        }
    }

    private static JsonObject requiredObject(JsonObject object, String field, String context) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonObject()) {
            throw new DictionaryLoadException("Missing or invalid '" + field + "' in " + context);
        }
        return value.getAsJsonObject();
    }

    private static String requiredString(JsonObject object, String field, String context) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new DictionaryLoadException("Missing or invalid '" + field + "' in " + context);
        }
        String result = value.getAsString().trim();
        if (result.isEmpty()) {
            throw new DictionaryLoadException("Blank '" + field + "' in " + context);
        }
        return result;
    }

    private static String optionalString(
            JsonObject object, String field, String defaultValue, String context) {
        return object.has(field) ? requiredString(object, field, context) : defaultValue;
    }

    private static double requiredFiniteDouble(JsonObject object, String field, String context) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new DictionaryLoadException("Missing or invalid numeric '" + field + "' in " + context);
        }
        double result = value.getAsDouble();
        if (!Double.isFinite(result)) {
            throw new DictionaryLoadException("Non-finite '" + field + "' in " + context);
        }
        return result;
    }

    private static double optionalFiniteDouble(
            JsonObject object, String field, double defaultValue, String context) {
        return object.has(field) ? requiredFiniteDouble(object, field, context) : defaultValue;
    }

    private static boolean optionalBoolean(
            JsonObject object, String field, boolean defaultValue, String context) {
        if (!object.has(field)) return defaultValue;
        JsonElement value = object.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new DictionaryLoadException("Invalid boolean '" + field + "' in " + context);
        }
        return value.getAsBoolean();
    }

    private static int optionalInt(
            JsonObject object, String field, int defaultValue, String context) {
        if (!object.has(field)) return defaultValue;
        JsonElement value = object.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new DictionaryLoadException("Invalid integer '" + field + "' in " + context);
        }
        double number = value.getAsDouble();
        int result = value.getAsInt();
        if (!Double.isFinite(number) || number != result) {
            throw new DictionaryLoadException("Invalid integer '" + field + "' in " + context);
        }
        return result;
    }

    private static String identifierKey(String value, String label, String context) {
        Identifier identifier = Identifier.tryParse(value);
        if (identifier == null) {
            throw new DictionaryLoadException("Invalid " + label + " ID '" + value + "' in " + context);
        }
        return identifier.toString();
    }

    private static String dictionaryHash(byte[] sigils, byte[] signs) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DICTIONARY_VERSION.getBytes(StandardCharsets.UTF_8));
            digest.update(SIGILS_RESOURCE.getBytes(StandardCharsets.UTF_8));
            digest.update(sigils);
            digest.update(SIGNS_RESOURCE.getBytes(StandardCharsets.UTF_8));
            digest.update(signs);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void logLoaded(ActiveState state) {
        long sigils = state.snapshot().templates().stream()
                .filter(template -> template.kind() == SymbolKind.SIGIL).count();
        long signs = state.snapshot().templates().size() - sigils;
        LOGGER.info(
                "SpellDictionary loaded atomically: {} sigil templates, {} sign templates "
                        + "({} semantic symbols, hash={})",
                sigils, signs,
                state.snapshot().templates().stream().map(TemplateIdentity::semanticId).distinct().count(),
                state.snapshot().hash());
    }
}
