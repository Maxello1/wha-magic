package com.example.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

public class ClientUtils {
    public static void openSpellScreen(InteractionHand hand) {
        Minecraft.getInstance().setScreen(new SpellDrawingScreen(hand));
    }
}
