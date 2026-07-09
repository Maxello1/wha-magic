package com.example.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

public class ClientUtils {
    public static void openSpellScreen(InteractionHand hand, java.util.List<java.util.List<com.example.parser.Point>> existingStrokes) {
        Minecraft.getInstance().setScreenAndShow(new SpellDrawingScreen(hand, existingStrokes));
    }
}
