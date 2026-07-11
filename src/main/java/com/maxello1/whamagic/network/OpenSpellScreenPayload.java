package com.maxello1.whamagic.network;

import com.maxello1.whamagic.WitchHatMod;
import com.maxello1.whamagic.parser.Point;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;

import java.util.List;

public record OpenSpellScreenPayload(
        InteractionHand hand,
        List<List<Point>> strokes
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenSpellScreenPayload> ID = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(WitchHatMod.MOD_ID, "open_spell_screen"));
    
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenSpellScreenPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.INT.map(id -> InteractionHand.values()[id], InteractionHand::ordinal), OpenSpellScreenPayload::hand,
            Point.STROKES_STREAM_CODEC, OpenSpellScreenPayload::strokes,
            OpenSpellScreenPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
