package com.maxello1.whamagic.network;

import com.maxello1.whamagic.WitchHatMod;
import com.maxello1.whamagic.parser.Point;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;

import java.util.List;

public record SaveSpellPayload(
        InteractionHand hand,
        long revision,
        int originalStrokeItemHash,
        List<List<Point>> strokes
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SaveSpellPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(
                    WitchHatMod.MOD_ID, "save_spell"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SaveSpellPayload> CODEC = new StreamCodec<>() {
        @Override
        public SaveSpellPayload decode(RegistryFriendlyByteBuf buf) {
            InteractionHand hand = SpellPacketFields.readHand(buf);
            long revision = buf.readVarLong();
            int originalStrokeItemHash = buf.readInt();
            List<List<Point>> strokes = BoundedStrokeStreamCodec.decode(
                    buf,
                    DrawingLimits.protocolMaximum());
            return new SaveSpellPayload(hand, revision, originalStrokeItemHash, strokes);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, SaveSpellPayload payload) {
            SpellPacketFields.writeHand(buf, payload.hand());
            buf.writeVarLong(payload.revision());
            buf.writeInt(payload.originalStrokeItemHash());
            BoundedStrokeStreamCodec.encode(
                    buf,
                    payload.strokes(),
                    DrawingLimits.protocolMaximum());
        }
    };

    public SaveSpellPayload {
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
        strokes = BoundedStrokeStreamCodec.immutableValidatedCopy(
                strokes,
                DrawingLimits.protocolMaximum());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
