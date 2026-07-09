package com.example.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import com.example.WitchHatMod;

public record SpellDrawnPacket(String spell) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SpellDrawnPacket> ID = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(WitchHatMod.MOD_ID, "spell_drawn"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SpellDrawnPacket> CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8, SpellDrawnPacket::spell,
        SpellDrawnPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
