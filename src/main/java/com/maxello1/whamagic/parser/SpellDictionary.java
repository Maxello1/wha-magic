package com.maxello1.whamagic.parser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOGGER = LoggerFactory.getLogger(SpellDictionary.class);
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

        LOGGER.info("SpellDictionary loaded: {} sigils, {} signs ({} total templates)",
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
                LOGGER.warn("Dictionary resource not found: {}", resourcePath);
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
                    LOGGER.warn("Skipping {} '{}': no strokeTemplate", kind, id);
                    continue;
                }

                JsonObject strokeTemplate = entry.getAsJsonObject("strokeTemplate");
                if (!strokeTemplate.has("strokes")) {
                    LOGGER.warn("Skipping {} '{}': no strokes in template", kind, id);
                    continue;
                }

                JsonArray strokesArray = strokeTemplate.getAsJsonArray("strokes");
                List<List<Point>> strokes = parseStrokes(strokesArray);

                if (strokes.isEmpty()) {
                    LOGGER.warn("Skipping {} '{}': empty strokes", kind, id);
                    continue;
                }

                JsonObject semanticObj = entry.has("semantic") ? entry.getAsJsonObject("semantic") : new JsonObject();
                com.maxello1.whamagic.magic.SigilSemantic sigilSem = null;
                com.maxello1.whamagic.magic.SignSemantic signSem = null;
                
                if ("sigil".equals(kind)) {
                    sigilSem = new com.maxello1.whamagic.magic.SigilSemantic(
                        semanticObj.has("force") ? semanticObj.get("force").getAsDouble() : 0,
                        semanticObj.has("focus") ? semanticObj.get("focus").getAsDouble() : 0,
                        semanticObj.has("spread") ? semanticObj.get("spread").getAsDouble() : 0,
                        semanticObj.has("range") ? semanticObj.get("range").getAsDouble() : 0,
                        semanticObj.has("lifetimeBias") ? semanticObj.get("lifetimeBias").getAsDouble() : 0
                    );
                } else if ("sign".equals(kind)) {
                    signSem = new com.maxello1.whamagic.magic.SignSemantic(
                        semanticObj.has("manifestation") ? semanticObj.get("manifestation").getAsString() : "none",
                        semanticObj.has("directionMode") ? semanticObj.get("directionMode").getAsString() : "none",
                        semanticObj.has("force") ? semanticObj.get("force").getAsDouble() : 0,
                        semanticObj.has("focus") ? semanticObj.get("focus").getAsDouble() : 0,
                        semanticObj.has("spread") ? semanticObj.get("spread").getAsDouble() : 0,
                        semanticObj.has("range") ? semanticObj.get("range").getAsDouble() : 0,
                        semanticObj.has("lifetimeBias") ? semanticObj.get("lifetimeBias").getAsDouble() : 0
                    );
                }

                RasterRecognizer.addTemplate(id, displayName, kind, element, strokes, sigilSem, signSem);
                count++;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load dictionary from {}", resourcePath, e);
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
