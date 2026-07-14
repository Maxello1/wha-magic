package com.maxello1.whamagic.network;

import com.maxello1.whamagic.WitchHatMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Objects;

public record SpellEditResultPayload(
        long revision,
        Status status,
        String message) implements CustomPacketPayload {

    public enum Status {
        SAVED,
        STALE_SESSION,
        INVALID_ITEM,
        INVALID_DRAWING,
        MISSING_WAND,
        RATE_LIMITED
    }

    private static final int MAX_MESSAGE_LENGTH = 256;

    public static final CustomPacketPayload.Type<SpellEditResultPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(
                    WitchHatMod.MOD_ID, "spell_edit_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SpellEditResultPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public SpellEditResultPayload decode(RegistryFriendlyByteBuf buffer) {
                    int statusId = buffer.readVarInt();
                    Status[] statuses = Status.values();
                    if (statusId < 0 || statusId >= statuses.length) {
                        throw new IllegalArgumentException("Invalid edit result status: " + statusId);
                    }
                    return new SpellEditResultPayload(
                            buffer.readVarLong(),
                            statuses[statusId],
                            buffer.readUtf(MAX_MESSAGE_LENGTH));
                }

                @Override
                public void encode(
                        RegistryFriendlyByteBuf buffer,
                        SpellEditResultPayload payload) {
                    buffer.writeVarInt(payload.status().ordinal());
                    buffer.writeVarLong(payload.revision());
                    buffer.writeUtf(payload.message(), MAX_MESSAGE_LENGTH);
                }
            };

    public SpellEditResultPayload {
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(message, "message");
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("message is too long");
        }
    }

    public boolean accepted() {
        return status == Status.SAVED;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
