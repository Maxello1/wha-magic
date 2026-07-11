package com.maxello1.whamagic.parser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Timeout;

import java.io.File;
import java.io.FileReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class SpellParserTest {

    private static final Gson GSON = new Gson();

    @BeforeAll
    public static void setup() {
        SpellDictionary.ensureLoaded();
    }

    @Test
    public void testEmptyStrokes() {
        SpellParser.ParseResult result = SpellParser.parse(new ArrayList<>());
        assertFalse(result.isValidSpell());
    }

    private static Stream<File> fixtureFiles() {
        File dir = new File("src/test/resources/fixtures");
        if (!dir.exists() || !dir.isDirectory()) {
            return Stream.empty();
        }
        
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) {
            return Stream.empty();
        }
        return Stream.of(files);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureFiles")
    @Timeout(value = 500, unit = TimeUnit.MILLISECONDS)
    public void testFixture(File file) throws Exception {
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
        String actualSpell = result.ir.displayName() != null ? result.ir.displayName() : "";
        
        assertEquals(expectedSpell, actualSpell, "Failed on fixture: " + file.getName());
    }
}
