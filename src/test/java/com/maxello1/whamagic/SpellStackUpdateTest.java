package com.maxello1.whamagic;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.maxello1.whamagic.magic.CandidateGenerationSettings;
import com.maxello1.whamagic.magic.GlyphWarning;
import com.maxello1.whamagic.magic.SpellStackUpdater;
import com.maxello1.whamagic.magic.StoredSpell;
import com.maxello1.whamagic.parser.Point;
import com.maxello1.whamagic.parser.SpellDictionary;
import com.maxello1.whamagic.parser.SpellParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpellStackUpdateTest {
    private static final Gson GSON = new Gson();
    private static DataComponentType<StoredSpell> storedSpellComponent;
    private static DataComponentType<List<List<Point>>> rawStrokesComponent;

    @SuppressWarnings("deprecation")
    @BeforeAll
    static void loadDictionary() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Items.PAPER.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);
        storedSpellComponent = new DataComponentType.Builder<StoredSpell>()
                .persistent(StoredSpell.CODEC)
                .build();
        rawStrokesComponent = new DataComponentType.Builder<List<List<Point>>>()
                .persistent(Point.STROKES_CODEC)
                .build();
        SpellDictionary.ensureLoaded();
    }

    @Test
    void invalidOverwriteRemovesStoredSpellAndKeepsLatestRawStrokes() throws Exception {
        List<List<Point>> validStrokes = loadStrokes(
                "/fixtures/canonical/multi/spell_light_complete.json");
        List<List<Point>> invalidStrokes = loadStrokes(
                "/fixtures/canonical/negative/neg_light_incomplete.json");

        SpellParser.ParseResult validResult = SpellParser.parse(validStrokes);
        SpellParser.ParseResult invalidResult = SpellParser.parse(invalidStrokes);
        assertTrue(validResult.isValidSpell(), "Complete Light fixture must produce a valid spell");
        assertFalse(invalidResult.isValidSpell(), "Incomplete Light fixture must remain invalid");

        ItemStack stack = new ItemStack(Items.PAPER);
        SpellStackUpdater.applyParseResultToStack(
                stack, validResult, validStrokes, storedSpellComponent, rawStrokesComponent);
        assertNotNull(stack.get(storedSpellComponent));
        assertSame(validStrokes, stack.get(rawStrokesComponent));

        SpellStackUpdater.applyParseResultToStack(
                stack, invalidResult, invalidStrokes, storedSpellComponent, rawStrokesComponent);
        assertNull(stack.get(storedSpellComponent),
                "Invalid overwrite must remove the previous compiled spell");
        assertSame(invalidStrokes, stack.get(rawStrokesComponent),
                "Raw strokes must reflect the latest submitted drawing");
    }

    @Test
    void budgetExhaustedOverwriteCannotRemainStored() throws Exception {
        List<List<Point>> strokes = loadStrokes(
                "/fixtures/canonical/multi/spell_light_complete.json");
        SpellParser.ParseResult validResult = SpellParser.parse(strokes);
        int exactCalls = validResult.debugResult.recognitionCalls();
        assertTrue(validResult.ir.valid());
        assertTrue(exactCalls > 0);

        CandidateGenerationSettings defaults = CandidateGenerationSettings.DEFAULTS;
        CandidateGenerationSettings limited = new CandidateGenerationSettings(
                defaults.maxPrimitiveGroups(), defaults.maxGroupsPerCandidate(),
                defaults.maxCandidates(), exactCalls - 1,
                defaults.maxCandidateWidthRatio(), defaults.maxCandidateHeightRatio(),
                defaults.maxAngularSpanDeg(), defaults.maxInternalGapRatio(),
                defaults.maxEmptySpaceRatio());
        SpellParser.ParseResult exhaustedResult = SpellParser.parse(strokes, limited);
        assertTrue(exhaustedResult.debugResult.recognitionBudgetExhausted());
        assertEquals(GlyphWarning.INCOMPLETE_RECOGNITION, exhaustedResult.ir.warning());
        assertFalse(exhaustedResult.ir.valid());

        ItemStack stack = new ItemStack(Items.PAPER);
        SpellStackUpdater.applyParseResultToStack(
                stack, validResult, strokes, storedSpellComponent, rawStrokesComponent);
        assertNotNull(stack.get(storedSpellComponent));

        SpellStackUpdater.applyParseResultToStack(
                stack, exhaustedResult, strokes, storedSpellComponent, rawStrokesComponent);
        assertNull(stack.get(storedSpellComponent),
                "A budget-exhausted redraw must remove the previous compiled spell");
        assertSame(strokes, stack.get(rawStrokesComponent));
    }

    private static List<List<Point>> loadStrokes(String resourcePath) throws Exception {
        InputStream input = SpellStackUpdateTest.class.getResourceAsStream(resourcePath);
        assertNotNull(input, "Missing fixture: " + resourcePath);
        JsonObject fixture;
        try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            fixture = GSON.fromJson(reader, JsonObject.class);
        }
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
