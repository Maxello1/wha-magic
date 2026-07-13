package com.maxello1.whamagic.client;

import com.maxello1.whamagic.parser.Point;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

import java.util.List;

public final class ClientUtils {
    private ClientUtils() {}

    public static void openSpellScreen(InteractionHand hand, List<List<Point>> existingStrokes) {
        Minecraft.getInstance().setScreenAndShow(new SpellDrawingScreen(hand, existingStrokes));
    }
}
