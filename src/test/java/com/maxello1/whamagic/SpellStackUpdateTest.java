package com.maxello1.whamagic;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.maxello1.whamagic.parser.Point;
import com.maxello1.whamagic.parser.SpellDictionary;
import com.maxello1.whamagic.parser.SpellParser;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpellStackUpdateTest {
    private static final Gson GSON = new Gson();
    private static net.minecraft.core.component.DataComponentType<com.maxello1.whamagic.magic.StoredSpell> storedSpellComponent;
    private static net.minecraft.core.component.DataComponentType<List<List<Point>>> rawStrokesComponent;

    @BeforeAll
    static void loadDictionary() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.minecraft.world.item.Items.PAPER.builtInRegistryHolder()
                .bindComponents(net.minecraft.core.component.DataComponentMap.EMPTY);
        storedSpellComponent = new net.minecraft.core.component.DataComponentType.Builder<com.maxello1.whamagic.magic.StoredSpell>()
                .persistent(com.maxello1.whamagic.magic.StoredSpell.CODEC).build();
        rawStrokesComponent = new net.minecraft.core.component.DataComponentType.Builder<List<List<Point>>>()
                .persistent(Point.STROKES_CODEC).build();
        SpellDictionary.ensureLoaded();
    }

    @Test
    void invalidOverwriteRemovesStoredSpellAndKeepsLatestRawStrokes() throws Exception {
        List<List<Point>> validStrokes = loadStrokes(
                "src/test/resources/fixtures/canonical/multi/spell_light_complete.json");
        List<List<Point>> invalidStrokes = loadStrokes(
                "src/test/resources/fixtures/canonical/negative/neg_light_incomplete.json");

        SpellParser.ParseResult validResult = SpellParser.parse(validStrokes);
        SpellParser.ParseResult invalidResult = SpellParser.parse(invalidStrokes);
        assertTrue(validResult.isValidSpell(), "Complete Light fixture must produce a valid spell");
        assertFalse(invalidResult.isValidSpell(), "Incomplete Light fixture must remain invalid");

        ItemStack stack = new ItemStack(net.minecraft.world.item.Items.PAPER);
        com.maxello1.whamagic.magic.SpellStackUpdater.applyParseResultToStack(
                stack, validResult, validStrokes, storedSpellComponent, rawStrokesComponent);
        assertNotNull(stack.get(storedSpellComponent));
        assertSame(validStrokes, stack.get(rawStrokesComponent));

        com.maxello1.whamagic.magic.SpellStackUpdater.applyParseResultToStack(
                stack, invalidResult, invalidStrokes, storedSpellComponent, rawStrokesComponent);
        assertNull(stack.get(storedSpellComponent),
                "Invalid overwrite must remove the previous compiled spell");
        assertSame(invalidStrokes, stack.get(rawStrokesComponent),
                "Raw strokes must reflect the latest submitted drawing");
    }

    private static List<List<Point>> loadStrokes(String path) throws Exception {
        JsonObject fixture = GSON.fromJson(new FileReader(new File(path)), JsonObject.class);
        List<List<Point>> strokes = new ArrayList<>();
        for (JsonElement strokeElement : fixture.getAsJsonArray("strokes")) {
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
