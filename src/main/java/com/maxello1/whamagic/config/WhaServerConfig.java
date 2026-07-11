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
    private static final File CONFIG_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "wha-magic-server.json");

    public static final ConfigData INSTANCE = new ConfigData();

    public static class ConfigData {
        public Casting casting = new Casting();
        public EarthMagic earthMagic = new EarthMagic();
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

        public static class Network {
            public int maxStrokes = 64;
            public int maxPointsPerStroke = 512;
            public int maxTotalPoints = 8192;
            public int parseCooldownTicks = 10;
        }

        public static class Debug {
            public boolean recognitionLogging = false;
        }
    }

    public static void load() {
        if (!CONFIG_FILE.exists()) {
            save();
            return;
        }

        try (FileReader reader = new FileReader(CONFIG_FILE)) {
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
            save(); // Try to overwrite with defaults
        }
    }

    public static void save() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(INSTANCE, writer);
        } catch (IOException e) {
            LOGGER.error("Failed to save wha-magic-server.json", e);
        }
    }
}
