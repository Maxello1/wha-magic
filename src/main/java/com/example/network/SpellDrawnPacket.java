package com.example.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import com.example.WitchHatMod;

import java.util.List;
import com.example.parser.Point;

public record SpellDrawnPacket(String spell, List<List<Point>> strokes) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SpellDrawnPacket> ID = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(WitchHatMod.MOD_ID, "spell_drawn"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SpellDrawnPacket> CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8, SpellDrawnPacket::spell,
        Point.STROKES_STREAM_CODEC, SpellDrawnPacket::strokes,
        SpellDrawnPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
