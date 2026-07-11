package com.maxello1.whamagic.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

public class ClientUtils {
    public static void openSpellScreen(InteractionHand hand, java.util.List<java.util.List<com.maxello1.whamagic.parser.Point>> existingStrokes) {
        Minecraft.getInstance().setScreenAndShow(new SpellDrawingScreen(hand, existingStrokes));
    }
}
