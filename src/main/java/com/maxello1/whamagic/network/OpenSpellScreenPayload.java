package com.maxello1.whamagic.network;

import com.maxello1.whamagic.WitchHatMod;
import com.maxello1.whamagic.parser.Point;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;

import java.util.List;

public record OpenSpellScreenPayload(
        InteractionHand hand,
        long revision,
        int originalStrokeItemHash,
        DrawingLimits limits,
        List<List<Point>> strokes
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenSpellScreenPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(
                    WitchHatMod.MOD_ID, "open_spell_screen"));
    
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenSpellScreenPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public OpenSpellScreenPayload decode(RegistryFriendlyByteBuf buffer) {
                    InteractionHand hand = SpellPacketFields.readHand(buffer);
                    long revision = buffer.readVarLong();
                    int originalStrokeItemHash = buffer.readInt();
                    DrawingLimits limits = DrawingLimits.CODEC.decode(buffer);
                    List<List<Point>> strokes = BoundedStrokeStreamCodec.decode(buffer, limits);
                    return new OpenSpellScreenPayload(
                            hand,
                            revision,
                            originalStrokeItemHash,
                            limits,
                            strokes);
                }

                @Override
                public void encode(
                        RegistryFriendlyByteBuf buffer,
                        OpenSpellScreenPayload payload) {
                    SpellPacketFields.writeHand(buffer, payload.hand());
                    buffer.writeVarLong(payload.revision());
                    buffer.writeInt(payload.originalStrokeItemHash());
                    DrawingLimits.CODEC.encode(buffer, payload.limits());
                    BoundedStrokeStreamCodec.encode(buffer, payload.strokes(), payload.limits());
                }
            };

    public OpenSpellScreenPayload {
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
        strokes = BoundedStrokeStreamCodec.immutableValidatedCopy(strokes, limits);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
