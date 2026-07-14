package com.maxello1.whamagic.network;

import com.maxello1.whamagic.WitchHatMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;

public record CancelSpellEditPayload(
        InteractionHand hand,
        long revision,
        int originalStrokeItemHash) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CancelSpellEditPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(
                    WitchHatMod.MOD_ID, "cancel_spell_edit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CancelSpellEditPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public CancelSpellEditPayload decode(RegistryFriendlyByteBuf buffer) {
                    return new CancelSpellEditPayload(
                            SpellPacketFields.readHand(buffer),
                            buffer.readVarLong(),
                            buffer.readInt());
                }

                @Override
                public void encode(
                        RegistryFriendlyByteBuf buffer,
                        CancelSpellEditPayload payload) {
                    SpellPacketFields.writeHand(buffer, payload.hand());
                    buffer.writeVarLong(payload.revision());
                    buffer.writeInt(payload.originalStrokeItemHash());
                }
            };

    public CancelSpellEditPayload {
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
