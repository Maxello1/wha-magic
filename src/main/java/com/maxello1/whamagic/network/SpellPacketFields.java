package com.maxello1.whamagic.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.InteractionHand;

final class SpellPacketFields {
    private SpellPacketFields() {}

    static InteractionHand readHand(RegistryFriendlyByteBuf buffer) {
        int handId = buffer.readVarInt();
        InteractionHand[] hands = InteractionHand.values();
        if (handId < 0 || handId >= hands.length) {
            throw new IllegalArgumentException("Invalid hand: " + handId);
        }
        return hands[handId];
    }

    static void writeHand(RegistryFriendlyByteBuf buffer, InteractionHand hand) {
        buffer.writeVarInt(hand.ordinal());
    }
}
