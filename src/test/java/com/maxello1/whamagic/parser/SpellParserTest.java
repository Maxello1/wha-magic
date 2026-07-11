package com.maxello1.whamagic.parser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.maxello1.whamagic.parser.Point;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SpellParserTest {

    private static final Gson GSON = new Gson();

    @BeforeAll
    public static void setup() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        SpellDictionary.ensureLoaded();
    }

    @Test
    public void testEmptyStrokes() {
        SpellParser.ParseResult result = SpellParser.parse(new ArrayList<>());
        assertFalse(result.isValidSpell());
    }

    @Test
    public void testFixtures() throws Exception {
        URL url = getClass().getResource("/fixtures");
        if (url == null) {
            System.out.println("No fixtures directory found, skipping fixture tests.");
            return;
        }
        File dir = new File(url.toURI());
        if (!dir.exists() || !dir.isDirectory()) {
            System.out.println("Fixtures path is not a directory, skipping.");
            return;
        }
        
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) {
            System.out.println("No json fixtures found.");
            return;
        }
        
        int passed = 0;
        for (File file : files) {
            runFixture(file);
            passed++;
        }
        System.out.println("Passed " + passed + " fixture tests.");
    }

    private void runFixture(File file) throws Exception {
        JsonObject json = GSON.fromJson(new FileReader(file), JsonObject.class);
        JsonArray strokesArray = json.getAsJsonArray("strokes");
        String expectedSpell = json.get("expectedSpell").getAsString();
        
        List<List<Point>> strokes = new ArrayList<>();
        for (JsonElement strokeElem : strokesArray) {
            JsonArray pointsArray = strokeElem.getAsJsonArray();
            List<Point> strokePoints = new ArrayList<>();
            for (JsonElement ptElem : pointsArray) {
                JsonObject pt = ptElem.getAsJsonObject();
                strokePoints.add(new Point(pt.get("x").getAsDouble(), pt.get("y").getAsDouble()));
            }
            strokes.add(strokePoints);
        }
        
        SpellParser.ParseResult result = SpellParser.parse(strokes);
        String actualSpell = result.ir.displayName();
        
        assertEquals(expectedSpell, actualSpell, "Failed on fixture: " + file.getName());
    }
}
