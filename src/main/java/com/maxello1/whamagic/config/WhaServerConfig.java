package com.maxello1.whamagic.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class WhaServerConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("wha-magic-config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final ConfigData INSTANCE = new ConfigData();

    public static class ConfigData {
        public Casting casting = new Casting();
        public EarthMagic earthMagic = new EarthMagic();
        public MagicScaling magicScaling = new MagicScaling();
        public Network network = new Network();
        public Debug debug = new Debug();

        public static class Casting {
            public int cooldownTicks = 20;
        }

        public static class EarthMagic {
            public boolean allowPermanentBlockChanges = false;
            public int temporaryBlockLifetimeTicks = 100;
            public int maxBlocksPerCast = 48;
            public boolean allowReplacingBlocks = false;
        }

        public static class MagicScaling {
            static final double DEFAULT_REFERENCE_RING_DIAMETER = 0.75;
            static final double DEFAULT_SIZE_EXPONENT = 0.75;
            static final double DEFAULT_MINIMUM_SIZE_SCALE = 0.50;
            static final double DEFAULT_MAXIMUM_SIZE_SCALE = 2.25;
            static final double DEFAULT_MAXIMUM_POWER_MULTIPLIER = 3.0;
            static final double DEFAULT_MAXIMUM_RANGE_MULTIPLIER = 3.0;
            static final double DEFAULT_MAXIMUM_RADIUS_MULTIPLIER = 3.0;
            static final double DEFAULT_MAXIMUM_DURATION_MULTIPLIER = 4.0;

            public double referenceRingDiameter = DEFAULT_REFERENCE_RING_DIAMETER;
            public double sizeExponent = DEFAULT_SIZE_EXPONENT;
            public double minimumSizeScale = DEFAULT_MINIMUM_SIZE_SCALE;
            public double maximumSizeScale = DEFAULT_MAXIMUM_SIZE_SCALE;
            public double maximumPowerMultiplier = DEFAULT_MAXIMUM_POWER_MULTIPLIER;
            public double maximumRangeMultiplier = DEFAULT_MAXIMUM_RANGE_MULTIPLIER;
            public double maximumRadiusMultiplier = DEFAULT_MAXIMUM_RADIUS_MULTIPLIER;
            public double maximumDurationMultiplier = DEFAULT_MAXIMUM_DURATION_MULTIPLIER;

            void sanitize() {
                referenceRingDiameter = finiteRangeOrDefault(
                        referenceRingDiameter,
                        0.10,
                        2.0,
                        DEFAULT_REFERENCE_RING_DIAMETER);
                sizeExponent = finiteRangeOrDefault(
                        sizeExponent,
                        0.10,
                        2.0,
                        DEFAULT_SIZE_EXPONENT);
                minimumSizeScale = finiteRangeOrDefault(
                        minimumSizeScale,
                        0.10,
                        4.0,
                        DEFAULT_MINIMUM_SIZE_SCALE);
                maximumSizeScale = finiteRangeOrDefault(
                        maximumSizeScale,
                        0.25,
                        4.0,
                        DEFAULT_MAXIMUM_SIZE_SCALE);
                if (maximumSizeScale < minimumSizeScale) {
                    minimumSizeScale = DEFAULT_MINIMUM_SIZE_SCALE;
                    maximumSizeScale = DEFAULT_MAXIMUM_SIZE_SCALE;
                }
                maximumPowerMultiplier = finiteRangeOrDefault(
                        maximumPowerMultiplier,
                        1.0,
                        8.0,
                        DEFAULT_MAXIMUM_POWER_MULTIPLIER);
                maximumRangeMultiplier = finiteRangeOrDefault(
                        maximumRangeMultiplier,
                        1.0,
                        8.0,
                        DEFAULT_MAXIMUM_RANGE_MULTIPLIER);
                maximumRadiusMultiplier = finiteRangeOrDefault(
                        maximumRadiusMultiplier,
                        1.0,
                        8.0,
                        DEFAULT_MAXIMUM_RADIUS_MULTIPLIER);
                maximumDurationMultiplier = finiteRangeOrDefault(
                        maximumDurationMultiplier,
                        1.0,
                        16.0,
                        DEFAULT_MAXIMUM_DURATION_MULTIPLIER);
            }

            private static double finiteRangeOrDefault(
                    double value,
                    double minimum,
                    double maximum,
                    double fallback) {
                return Double.isFinite(value) && value >= minimum && value <= maximum
                        ? value
                        : fallback;
            }
        }

        public static class Network {
            public int maxStrokes = 64;
            public int maxPointsPerStroke = 2048;
            public int maxTotalPoints = 8192;
            public int parseCooldownTicks = 10;
        }

        public static class Debug {
            public boolean recognitionLogging = false;
        }
    }

    public static void load() {
        resetDefaults();
        File configFile = configFile();
        if (!configFile.exists()) {
            save();
            return;
        }

        try (FileReader reader = new FileReader(configFile)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            
            if (json.has("casting")) {
                JsonObject casting = json.getAsJsonObject("casting");
                if (casting.has("cooldownTicks")) INSTANCE.casting.cooldownTicks = Math.max(0, casting.get("cooldownTicks").getAsInt());
            }

            if (json.has("earthMagic")) {
                JsonObject earthMagic = json.getAsJsonObject("earthMagic");
                if (earthMagic.has("allowPermanentBlockChanges")) INSTANCE.earthMagic.allowPermanentBlockChanges = earthMagic.get("allowPermanentBlockChanges").getAsBoolean();
                if (earthMagic.has("temporaryBlockLifetimeTicks")) INSTANCE.earthMagic.temporaryBlockLifetimeTicks = Math.max(1, earthMagic.get("temporaryBlockLifetimeTicks").getAsInt());
                if (earthMagic.has("maxBlocksPerCast")) INSTANCE.earthMagic.maxBlocksPerCast = Math.max(1, Math.min(256, earthMagic.get("maxBlocksPerCast").getAsInt()));
                if (earthMagic.has("allowReplacingBlocks")) INSTANCE.earthMagic.allowReplacingBlocks = earthMagic.get("allowReplacingBlocks").getAsBoolean();
            }

            INSTANCE.magicScaling = readMagicScaling(json);

            if (json.has("network")) {
                JsonObject network = json.getAsJsonObject("network");
                if (network.has("maxStrokes")) INSTANCE.network.maxStrokes = Math.max(1, Math.min(256, network.get("maxStrokes").getAsInt()));
                if (network.has("maxPointsPerStroke")) INSTANCE.network.maxPointsPerStroke = Math.max(2, Math.min(2048, network.get("maxPointsPerStroke").getAsInt()));
                if (network.has("maxTotalPoints")) INSTANCE.network.maxTotalPoints = Math.max(2, Math.min(32768, network.get("maxTotalPoints").getAsInt()));
                if (network.has("parseCooldownTicks")) INSTANCE.network.parseCooldownTicks = Math.max(0, network.get("parseCooldownTicks").getAsInt());
            }

            if (json.has("debug")) {
                JsonObject debug = json.getAsJsonObject("debug");
                if (debug.has("recognitionLogging")) INSTANCE.debug.recognitionLogging = debug.get("recognitionLogging").getAsBoolean();
            }
            
            // Save after loading to ensure any missing fields are added back to the file
            save();
            
        } catch (Exception e) {
            LOGGER.error("Failed to load wha-magic-server.json, using defaults.", e);
            resetDefaults();
            save(); // Try to overwrite with defaults
        }
    }

    public static void save() {
        if (INSTANCE.magicScaling == null) {
            INSTANCE.magicScaling = new ConfigData.MagicScaling();
        } else {
            INSTANCE.magicScaling.sanitize();
        }
        try (FileWriter writer = new FileWriter(configFile())) {
            GSON.toJson(INSTANCE, writer);
        } catch (IOException e) {
            LOGGER.error("Failed to save wha-magic-server.json", e);
        }
    }

    static ConfigData.MagicScaling readMagicScaling(JsonObject root) {
        ConfigData.MagicScaling settings = new ConfigData.MagicScaling();
        if (root == null || !root.has("magicScaling")) {
            return settings;
        }

        JsonObject json = root.getAsJsonObject("magicScaling");
        if (json.has("referenceRingDiameter")) {
            settings.referenceRingDiameter = json.get("referenceRingDiameter").getAsDouble();
        }
        if (json.has("sizeExponent")) {
            settings.sizeExponent = json.get("sizeExponent").getAsDouble();
        }
        if (json.has("minimumSizeScale")) {
            settings.minimumSizeScale = json.get("minimumSizeScale").getAsDouble();
        }
        if (json.has("maximumSizeScale")) {
            settings.maximumSizeScale = json.get("maximumSizeScale").getAsDouble();
        }
        if (json.has("maximumPowerMultiplier")) {
            settings.maximumPowerMultiplier = json.get("maximumPowerMultiplier").getAsDouble();
        }
        if (json.has("maximumRangeMultiplier")) {
            settings.maximumRangeMultiplier = json.get("maximumRangeMultiplier").getAsDouble();
        }
        if (json.has("maximumRadiusMultiplier")) {
            settings.maximumRadiusMultiplier = json.get("maximumRadiusMultiplier").getAsDouble();
        }
        if (json.has("maximumDurationMultiplier")) {
            settings.maximumDurationMultiplier = json.get("maximumDurationMultiplier").getAsDouble();
        }
        settings.sanitize();
        return settings;
    }

    private static void resetDefaults() {
        INSTANCE.casting = new ConfigData.Casting();
        INSTANCE.earthMagic = new ConfigData.EarthMagic();
        INSTANCE.magicScaling = new ConfigData.MagicScaling();
        INSTANCE.network = new ConfigData.Network();
        INSTANCE.debug = new ConfigData.Debug();
    }

    private static File configFile() {
        return new File(
                FabricLoader.getInstance().getConfigDir().toFile(),
                "wha-magic-server.json");
    }
}
