package com.maxello1.whamagic.magic;

import com.maxello1.whamagic.parser.Point;
import com.maxello1.whamagic.parser.SpellParser;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Applies a server parse result and its source strokes to spell paper data. */
public final class SpellStackUpdater {
    private SpellStackUpdater() {}

    public static void applyParseResultToStack(
            ItemStack stack,
            SpellParser.ParseResult result,
            List<List<Point>> strokes,
            DataComponentType<StoredSpell> storedSpellComponent,
            DataComponentType<List<List<Point>>> strokesComponent) {
        stack.set(strokesComponent, strokes);
        if (result.isValidSpell()) {
            stack.set(storedSpellComponent, StoredSpell.fromIr(result.ir, strokes));
        } else {
            stack.remove(storedSpellComponent);
        }
    }
}
