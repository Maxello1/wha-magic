package com.maxello1.whamagic.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import com.maxello1.whamagic.network.OpenSpellScreenPayload;

public class WitchHatModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(OpenSpellScreenPayload.ID, (payload, context) -> {
			context.client().execute(() -> {
				ClientUtils.openSpellScreen(payload.hand(), payload.strokes());
			});
		});
	}
}
