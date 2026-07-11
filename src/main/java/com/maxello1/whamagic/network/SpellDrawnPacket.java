package com.maxello1.whamagic.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import com.maxello1.whamagic.WitchHatMod;

import java.util.List;
import com.maxello1.whamagic.parser.Point;
import java.util.UUID;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.UUIDUtil;

public record SpellDrawnPacket(UUID sessionId, InteractionHand hand, int revision, List<List<Point>> strokes) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SpellDrawnPacket> ID = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(WitchHatMod.MOD_ID, "spell_drawn"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SpellDrawnPacket> CODEC = StreamCodec.composite(
        UUIDUtil.STREAM_CODEC, SpellDrawnPacket::sessionId,
        ByteBufCodecs.INT.map(id -> InteractionHand.values()[id], InteractionHand::ordinal), SpellDrawnPacket::hand,
        ByteBufCodecs.INT, SpellDrawnPacket::revision,
        Point.STROKES_STREAM_CODEC, SpellDrawnPacket::strokes,
        SpellDrawnPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
