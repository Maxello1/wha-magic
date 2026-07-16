package com.maxello1.whamagic.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhaServerConfigTest {
    @Test
    void exposesRequestedMagicScalingDefaultsAndKeys() {
        WhaServerConfig.ConfigData config = new WhaServerConfig.ConfigData();
        WhaServerConfig.ConfigData.MagicScaling scaling = config.magicScaling;

        assertAll(
                () -> assertEquals(0.75, scaling.referenceRingDiameter),
                () -> assertEquals(0.75, scaling.sizeExponent),
                () -> assertEquals(0.50, scaling.minimumSizeScale),
                () -> assertEquals(2.25, scaling.maximumSizeScale),
                () -> assertEquals(3.0, scaling.maximumPowerMultiplier),
                () -> assertEquals(3.0, scaling.maximumRangeMultiplier),
                () -> assertEquals(3.0, scaling.maximumRadiusMultiplier),
                () -> assertEquals(4.0, scaling.maximumDurationMultiplier));

        JsonObject json = new Gson().toJsonTree(config).getAsJsonObject()
                .getAsJsonObject("magicScaling");
        assertAll(
                () -> assertTrue(json.has("referenceRingDiameter")),
                () -> assertTrue(json.has("sizeExponent")),
                () -> assertTrue(json.has("minimumSizeScale")),
                () -> assertTrue(json.has("maximumSizeScale")),
                () -> assertTrue(json.has("maximumPowerMultiplier")),
                () -> assertTrue(json.has("maximumRangeMultiplier")),
                () -> assertTrue(json.has("maximumRadiusMultiplier")),
                () -> assertTrue(json.has("maximumDurationMultiplier")));
    }

    @Test
    void readsAndPreservesValidMagicScalingValues() {
        JsonObject root = JsonParser.parseString("""
                {
                  "magicScaling": {
                    "referenceRingDiameter": 0.9,
                    "sizeExponent": 1.1,
                    "minimumSizeScale": 0.4,
                    "maximumSizeScale": 2.8,
                    "maximumPowerMultiplier": 4.0,
                    "maximumRangeMultiplier": 5.0,
                    "maximumRadiusMultiplier": 6.0,
                    "maximumDurationMultiplier": 7.0
                  }
                }
                """).getAsJsonObject();

        WhaServerConfig.ConfigData.MagicScaling scaling =
                WhaServerConfig.readMagicScaling(root);

        assertAll(
                () -> assertEquals(0.9, scaling.referenceRingDiameter),
                () -> assertEquals(1.1, scaling.sizeExponent),
                () -> assertEquals(0.4, scaling.minimumSizeScale),
                () -> assertEquals(2.8, scaling.maximumSizeScale),
                () -> assertEquals(4.0, scaling.maximumPowerMultiplier),
                () -> assertEquals(5.0, scaling.maximumRangeMultiplier),
                () -> assertEquals(6.0, scaling.maximumRadiusMultiplier),
                () -> assertEquals(7.0, scaling.maximumDurationMultiplier));
    }

    @Test
    void restoresSafeDefaultsForNonFiniteOutOfRangeAndInvertedValues() {
        JsonObject root = JsonParser.parseString("""
                {
                  "magicScaling": {
                    "referenceRingDiameter": -1.0,
                    "sizeExponent": 3.0,
                    "minimumSizeScale": 3.0,
                    "maximumSizeScale": 2.0,
                    "maximumPowerMultiplier": 0.0,
                    "maximumRangeMultiplier": 9.0,
                    "maximumRadiusMultiplier": 1e400,
                    "maximumDurationMultiplier": -4.0
                  }
                }
                """).getAsJsonObject();

        WhaServerConfig.ConfigData.MagicScaling scaling =
                WhaServerConfig.readMagicScaling(root);

        assertAll(
                () -> assertEquals(0.75, scaling.referenceRingDiameter),
                () -> assertEquals(0.75, scaling.sizeExponent),
                () -> assertEquals(0.50, scaling.minimumSizeScale),
                () -> assertEquals(2.25, scaling.maximumSizeScale),
                () -> assertEquals(3.0, scaling.maximumPowerMultiplier),
                () -> assertEquals(3.0, scaling.maximumRangeMultiplier),
                () -> assertEquals(3.0, scaling.maximumRadiusMultiplier),
                () -> assertEquals(4.0, scaling.maximumDurationMultiplier));
    }

    @Test
    void missingMagicScalingSectionUsesDefaults() {
        WhaServerConfig.ConfigData.MagicScaling scaling =
                WhaServerConfig.readMagicScaling(new JsonObject());

        assertEquals(0.75, scaling.referenceRingDiameter);
        assertEquals(2.25, scaling.maximumSizeScale);
        assertEquals(4.0, scaling.maximumDurationMultiplier);
    }
}
