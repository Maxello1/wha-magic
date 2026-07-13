package com.maxello1.whamagic.client;

import com.maxello1.whamagic.network.OpenSpellScreenPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class WitchHatModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(
                OpenSpellScreenPayload.ID,
                (payload, context) -> context.client().execute(
                        () -> ClientUtils.openSpellScreen(payload.hand(), payload.strokes())));
    }
}
