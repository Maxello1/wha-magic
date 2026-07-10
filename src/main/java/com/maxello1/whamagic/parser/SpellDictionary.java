package com.maxello1.whamagic.parser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.maxello1.whamagic.WitchHatMod;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads sigil and sign templates from JSON resource files
 * and registers them with the CloudRecognizer.
 *
 * JSON format matches the original wha-spell-simulator project:
 * - sigils.json: contains element sigils (fire, water, wind, etc.)
 * - signs.json: contains modifier signs (column, levitation, convergence)
 */
public class SpellDictionary {

    private static final Gson GSON = new Gson();
    private static boolean loaded = false;

    /**
     * Load all templates from resource JSON files.
     * Safe to call multiple times; will only load once.
     */
    public static void ensureLoaded() {
        if (loaded) return;
        loaded = true;

        RasterRecognizer.clearTemplates();

        int sigilCount = loadFromResource("/data/wha-magic/dictionary/sigils.json", "sigil");
        int signCount = loadFromResource("/data/wha-magic/dictionary/signs.json", "sign");

        WitchHatMod.LOGGER.info("SpellDictionary loaded: {} sigils, {} signs ({} total templates)",
                sigilCount, signCount, RasterRecognizer.getTemplateCount());
    }

    /**
     * Force a reload of all templates (useful for hot-reloading during development).
     */
    public static void reload() {
        loaded = false;
        ensureLoaded();
    }

    private static int loadFromResource(String resourcePath, String kind) {
        int count = 0;
        try (InputStream is = SpellDictionary.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                WitchHatMod.LOGGER.warn("Dictionary resource not found: {}", resourcePath);
                return 0;
            }

            JsonArray entries = GSON.fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), JsonArray.class);

            for (JsonElement elem : entries) {
                JsonObject entry = elem.getAsJsonObject();
                String id = entry.get("id").getAsString();
                String displayName = entry.get("displayName").getAsString();

                // Element is only present on sigils
                String element = null;
                if (entry.has("element")) {
                    element = entry.get("element").getAsString();
                }

                // Parse stroke template
                if (!entry.has("strokeTemplate")) {
                    WitchHatMod.LOGGER.warn("Skipping {} '{}': no strokeTemplate", kind, id);
                    continue;
                }

                JsonObject strokeTemplate = entry.getAsJsonObject("strokeTemplate");
                if (!strokeTemplate.has("strokes")) {
                    WitchHatMod.LOGGER.warn("Skipping {} '{}': no strokes in template", kind, id);
                    continue;
                }

                JsonArray strokesArray = strokeTemplate.getAsJsonArray("strokes");
                List<List<Point>> strokes = parseStrokes(strokesArray);

                if (strokes.isEmpty()) {
                    WitchHatMod.LOGGER.warn("Skipping {} '{}': empty strokes", kind, id);
                    continue;
                }

                RasterRecognizer.addTemplate(id, displayName, kind, element, strokes);
                count++;
            }
        } catch (Exception e) {
            WitchHatMod.LOGGER.error("Failed to load dictionary from {}", resourcePath, e);
        }
        return count;
    }

    /**
     * Parse a JSON array of strokes, where each stroke is an array of {x, y} objects.
     */
    private static List<List<Point>> parseStrokes(JsonArray strokesArray) {
        List<List<Point>> strokes = new ArrayList<>();

        for (JsonElement strokeElem : strokesArray) {
            JsonArray pointsArray = strokeElem.getAsJsonArray();
            List<Point> strokePoints = new ArrayList<>();

            for (JsonElement pointElem : pointsArray) {
                JsonObject pt = pointElem.getAsJsonObject();
                double x = pt.get("x").getAsDouble();
                double y = pt.get("y").getAsDouble();
                strokePoints.add(new Point(x, y));
            }

            // Only add strokes with actual content (filter out single-point decoration markers)
            if (strokePoints.size() >= 2) {
                strokes.add(strokePoints);
            }
        }

        return strokes;
    }
}
