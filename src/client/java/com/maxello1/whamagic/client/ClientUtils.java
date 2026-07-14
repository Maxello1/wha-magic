package com.maxello1.whamagic.client;

import com.maxello1.whamagic.network.OpenSpellScreenPayload;
import com.maxello1.whamagic.network.SpellEditResultPayload;
import net.minecraft.client.Minecraft;

public final class ClientUtils {
    private static SpellDrawingScreen activeSpellScreen;

    private ClientUtils() {}

    public static void openSpellScreen(OpenSpellScreenPayload payload) {
        SpellDrawingScreen screen = new SpellDrawingScreen(
                payload.hand(),
                payload.revision(),
                payload.originalStrokeItemHash(),
                payload.limits(),
                payload.strokes());
        showSpellScreen(screen);
    }

    public static void handleEditResult(SpellEditResultPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (activeSpellScreen != null) {
            activeSpellScreen.handleEditResult(payload);
        } else if (!payload.accepted() && minecraft.player != null) {
            minecraft.player.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal(payload.message()));
        }
    }

    static void clearSpellScreen(SpellDrawingScreen screen) {
        if (activeSpellScreen == screen) {
            activeSpellScreen = null;
        }
    }

    static void showSpellScreen(SpellDrawingScreen screen) {
        activeSpellScreen = screen;
        Minecraft.getInstance().setScreenAndShow(screen);
    }
}
