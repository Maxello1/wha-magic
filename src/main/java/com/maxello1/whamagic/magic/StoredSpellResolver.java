package com.maxello1.whamagic.magic;

import com.maxello1.whamagic.parser.Point;
import com.maxello1.whamagic.parser.SpellParser;

import java.util.List;
import java.util.Objects;

/** Resolves a saved spell from its authoritative cache or one bounded reparse. */
public final class StoredSpellResolver {
    private StoredSpellResolver() {}

    @FunctionalInterface
    public interface Parser {
        SpellParser.ParseResult parse(List<List<Point>> strokes);
    }

    public record Resolution(SpellIr ir, StoredSpell refreshedSpell, boolean reparsed) {
        public boolean valid() {
            return ir != null && ir.valid();
        }
    }

    public static Resolution resolve(
            StoredSpell stored,
            List<List<Point>> strokes,
            Parser parser) {
        Objects.requireNonNull(parser, "parser");
        List<List<Point>> sourceStrokes = strokes == null ? List.of() : strokes;

        if (stored != null && stored.isCurrentFor(sourceStrokes)) {
            SpellIr cached = stored.toIr();
            if (cached.valid()) {
                return new Resolution(cached, null, false);
            }
        }

        SpellParser.ParseResult parsed = parser.parse(sourceStrokes);
        if (parsed == null || !parsed.isValidSpell()) {
            return new Resolution(null, null, true);
        }

        StoredSpell refreshed = StoredSpell.fromIr(parsed.ir, sourceStrokes);
        return new Resolution(parsed.ir, refreshed, true);
    }
}
