package com.maxello1.whamagic.network;

import com.maxello1.whamagic.WitchHatMod;
import com.maxello1.whamagic.config.WhaServerConfig;
import com.maxello1.whamagic.parser.Point;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;

import java.util.ArrayList;
import java.util.List;

public record SaveSpellPayload(
        InteractionHand hand,
        List<List<Point>> strokes
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SaveSpellPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(
                    WitchHatMod.MOD_ID, "save_spell"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SaveSpellPayload> CODEC = new StreamCodec<>() {
        @Override
        public SaveSpellPayload decode(RegistryFriendlyByteBuf buf) {
            int handId = buf.readInt();
            if (handId < 0 || handId >= InteractionHand.values().length) {
                throw new IllegalArgumentException("Invalid hand: " + handId);
            }
            InteractionHand hand = InteractionHand.values()[handId];

            int numStrokes = buf.readInt();
            if (numStrokes > WhaServerConfig.INSTANCE.network.maxStrokes) {
                throw new IllegalArgumentException("Too many strokes: " + numStrokes);
            }
            if (numStrokes < 0) {
                throw new IllegalArgumentException("Negative strokes count: " + numStrokes);
            }

            int totalPoints = 0;
            List<List<Point>> strokes = new ArrayList<>(numStrokes);

            for (int i = 0; i < numStrokes; i++) {
                int numPoints = buf.readInt();
                if (numPoints > WhaServerConfig.INSTANCE.network.maxPointsPerStroke) {
                    throw new IllegalArgumentException("Too many points in stroke: " + numPoints);
                }
                if (numPoints < 2) {
                    throw new IllegalArgumentException("Too few points in stroke: " + numPoints);
                }

                totalPoints += numPoints;
                if (totalPoints > WhaServerConfig.INSTANCE.network.maxTotalPoints) {
                    throw new IllegalArgumentException("Too many total points: " + totalPoints);
                }

                List<Point> stroke = new ArrayList<>(numPoints);
                for (int j = 0; j < numPoints; j++) {
                    double x = buf.readDouble();
                    double y = buf.readDouble();
                    
                    if (!Double.isFinite(x) || !Double.isFinite(y)) {
                        throw new IllegalArgumentException("Invalid coordinates: " + x + ", " + y);
                    }
                    if (x < 0.0 || x > 1.0 || y < 0.0 || y > 1.0) {
                        throw new IllegalArgumentException("Coordinates out of bounds (0.0-1.0): " + x + ", " + y);
                    }
                    
                    stroke.add(new Point(x, y));
                }
                strokes.add(stroke);
            }

            return new SaveSpellPayload(hand, strokes);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, SaveSpellPayload payload) {
            buf.writeInt(payload.hand().ordinal());
            List<List<Point>> strokes = payload.strokes();
            buf.writeInt(strokes.size());
            for (List<Point> stroke : strokes) {
                buf.writeInt(stroke.size());
                for (Point p : stroke) {
                    buf.writeDouble(p.x);
                    buf.writeDouble(p.y);
                }
            }
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
