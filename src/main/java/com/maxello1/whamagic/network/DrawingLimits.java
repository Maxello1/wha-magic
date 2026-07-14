package com.maxello1.whamagic.network;

import com.maxello1.whamagic.config.WhaServerConfig;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** Server-authoritative limits for a single spell drawing submission. */
public record DrawingLimits(
        int maxStrokes,
        int maxPointsPerStroke,
        int maxTotalPoints) {

    public static final int PROTOCOL_MAX_STROKES = 256;
    public static final int PROTOCOL_MAX_POINTS_PER_STROKE = 2048;
    public static final int PROTOCOL_MAX_TOTAL_POINTS = 32768;

    public static final StreamCodec<RegistryFriendlyByteBuf, DrawingLimits> CODEC = new StreamCodec<>() {
        @Override
        public DrawingLimits decode(RegistryFriendlyByteBuf buffer) {
            return new DrawingLimits(
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, DrawingLimits limits) {
            buffer.writeVarInt(limits.maxStrokes());
            buffer.writeVarInt(limits.maxPointsPerStroke());
            buffer.writeVarInt(limits.maxTotalPoints());
        }
    };

    public DrawingLimits {
        requireRange("maxStrokes", maxStrokes, 1, PROTOCOL_MAX_STROKES);
        requireRange("maxPointsPerStroke", maxPointsPerStroke, 2, PROTOCOL_MAX_POINTS_PER_STROKE);
        requireRange("maxTotalPoints", maxTotalPoints, 2, PROTOCOL_MAX_TOTAL_POINTS);
    }

    public static DrawingLimits fromServerConfig() {
        WhaServerConfig.ConfigData.Network network = WhaServerConfig.INSTANCE.network;
        return new DrawingLimits(
                network.maxStrokes,
                network.maxPointsPerStroke,
                network.maxTotalPoints);
    }

    public static DrawingLimits protocolMaximum() {
        return new DrawingLimits(
                PROTOCOL_MAX_STROKES,
                PROTOCOL_MAX_POINTS_PER_STROKE,
                PROTOCOL_MAX_TOTAL_POINTS);
    }

    private static void requireRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum + ": " + value);
        }
    }
}
