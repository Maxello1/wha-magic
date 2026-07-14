package com.maxello1.whamagic.network;

import com.maxello1.whamagic.parser.Point;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkPayloadTest {
    private static final double POINT_CODEC_TOLERANCE = 1.0 / 32000.0;

    @Test
    void openPayloadRoundTripPreservesServerLimitsAndSessionFields() {
        DrawingLimits limits = new DrawingLimits(12, 48, 160);
        List<List<Point>> strokes = sampleStrokes();
        OpenSpellScreenPayload expected = new OpenSpellScreenPayload(
                InteractionHand.OFF_HAND, 42L, -918273, limits, strokes);
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            OpenSpellScreenPayload.CODEC.encode(buffer, expected);
            OpenSpellScreenPayload decoded = OpenSpellScreenPayload.CODEC.decode(buffer);

            assertEquals(expected.hand(), decoded.hand());
            assertEquals(expected.revision(), decoded.revision());
            assertEquals(expected.originalStrokeItemHash(), decoded.originalStrokeItemHash());
            assertEquals(limits, decoded.limits());
            assertStrokesEqual(strokes, decoded.strokes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void savePayloadUsesCompactPointEncodingAndVarIntCollectionSizes() {
        List<List<Point>> strokes = sampleStrokes();
        SaveSpellPayload expected = new SaveSpellPayload(
                InteractionHand.MAIN_HAND, 9981L, 1234567, strokes);
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            SaveSpellPayload.CODEC.encode(buffer, expected);
            int encodedBytes = buffer.writerIndex();
            SaveSpellPayload decoded = SaveSpellPayload.CODEC.decode(buffer);

            assertTrue(encodedBytes < 48, "Expected compact payload, got " + encodedBytes + " bytes");
            assertEquals(expected.hand(), decoded.hand());
            assertEquals(expected.revision(), decoded.revision());
            assertEquals(expected.originalStrokeItemHash(), decoded.originalStrokeItemHash());
            assertStrokesEqual(strokes, decoded.strokes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void saveDecoderRejectsOversizedAndOutOfRangeInput() {
        RegistryFriendlyByteBuf oversized = buffer();
        try {
            writeSaveHeader(oversized);
            oversized.writeVarInt(DrawingLimits.PROTOCOL_MAX_STROKES + 1);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SaveSpellPayload.CODEC.decode(oversized));
        } finally {
            oversized.release();
        }

        RegistryFriendlyByteBuf outOfRange = buffer();
        try {
            writeSaveHeader(outOfRange);
            outOfRange.writeVarInt(1);
            outOfRange.writeVarInt(2);
            outOfRange.writeShort(-1);
            outOfRange.writeShort(100);
            outOfRange.writeShort(100);
            outOfRange.writeShort(100);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SaveSpellPayload.CODEC.decode(outOfRange));
        } finally {
            outOfRange.release();
        }
    }

    @Test
    void constructorsRejectMalformedCoordinatesAndStrokeShapes() {
        assertThrows(IllegalArgumentException.class, () -> new SaveSpellPayload(
                InteractionHand.MAIN_HAND,
                1L,
                0,
                List.of(List.of(new Point(0.5, 0.5)))));
        assertThrows(IllegalArgumentException.class, () -> new SaveSpellPayload(
                InteractionHand.MAIN_HAND,
                1L,
                0,
                List.of(List.of(new Point(Double.NaN, 0.5), new Point(0.6, 0.6)))));
        assertThrows(IllegalArgumentException.class, () -> new SaveSpellPayload(
                InteractionHand.MAIN_HAND,
                1L,
                0,
                List.of(List.of(new Point(-0.01, 0.5), new Point(0.6, 0.6)))));
    }

    @Test
    void serverSpecificLimitsRemainStricterThanTheProtocolEnvelope() {
        List<List<Point>> strokes = sampleStrokes();

        assertThrows(
                IllegalArgumentException.class,
                () -> BoundedStrokeStreamCodec.immutableValidatedCopy(
                        strokes,
                        new DrawingLimits(1, 48, 160)));
        assertThrows(
                IllegalArgumentException.class,
                () -> BoundedStrokeStreamCodec.immutableValidatedCopy(
                        strokes,
                        new DrawingLimits(12, 2, 160)));
        assertThrows(
                IllegalArgumentException.class,
                () -> BoundedStrokeStreamCodec.immutableValidatedCopy(
                        strokes,
                        new DrawingLimits(12, 48, 4)));
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    private static void writeSaveHeader(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(InteractionHand.MAIN_HAND.ordinal());
        buffer.writeVarLong(1L);
        buffer.writeInt(0);
    }

    private static List<List<Point>> sampleStrokes() {
        return List.of(
                List.of(new Point(0.1, 0.2), new Point(0.3, 0.4)),
                List.of(
                        new Point(0.5, 0.6),
                        new Point(0.7, 0.8),
                        new Point(0.9, 1.0)));
    }

    private static void assertStrokesEqual(
            List<List<Point>> expected,
            List<List<Point>> actual) {
        assertEquals(expected.size(), actual.size());
        for (int strokeIndex = 0; strokeIndex < expected.size(); strokeIndex++) {
            assertEquals(expected.get(strokeIndex).size(), actual.get(strokeIndex).size());
            for (int pointIndex = 0; pointIndex < expected.get(strokeIndex).size(); pointIndex++) {
                Point expectedPoint = expected.get(strokeIndex).get(pointIndex);
                Point actualPoint = actual.get(strokeIndex).get(pointIndex);
                assertEquals(expectedPoint.x, actualPoint.x, POINT_CODEC_TOLERANCE);
                assertEquals(expectedPoint.y, actualPoint.y, POINT_CODEC_TOLERANCE);
            }
        }
    }
}
