package com.example.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Hand;

public class ClientUtils {
    public static void openSpellScreen(Hand hand) {
        MinecraftClient.getInstance().setScreen(new SpellDrawingScreen(hand));
    }
}
