package com.maxello1.whamagic.network;

import com.maxello1.whamagic.parser.Point;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Compact point-stream encoding with explicit drawing bounds. */
public final class BoundedStrokeStreamCodec {
    private BoundedStrokeStreamCodec() {}

    public static List<List<Point>> decode(
            RegistryFriendlyByteBuf buffer,
            DrawingLimits limits) {
        int strokeCount = readSize(buffer, "stroke count", limits.maxStrokes());
        int totalPoints = 0;
        List<List<Point>> strokes = new ArrayList<>(strokeCount);

        for (int strokeIndex = 0; strokeIndex < strokeCount; strokeIndex++) {
            int pointCount = readSize(
                    buffer,
                    "point count for stroke " + strokeIndex,
                    limits.maxPointsPerStroke());
            if (pointCount < 2) {
                throw new IllegalArgumentException(
                        "Too few points in stroke " + strokeIndex + ": " + pointCount);
            }

            totalPoints += pointCount;
            if (totalPoints > limits.maxTotalPoints()) {
                throw new IllegalArgumentException("Too many total points: " + totalPoints);
            }

            List<Point> stroke = new ArrayList<>(pointCount);
            for (int pointIndex = 0; pointIndex < pointCount; pointIndex++) {
                Point point = Point.STREAM_CODEC.decode(buffer);
                validatePoint(point, strokeIndex, pointIndex);
                stroke.add(point);
            }
            strokes.add(List.copyOf(stroke));
        }
        return List.copyOf(strokes);
    }

    public static void encode(
            RegistryFriendlyByteBuf buffer,
            List<List<Point>> strokes,
            DrawingLimits limits) {
        List<List<Point>> validated = immutableValidatedCopy(strokes, limits);
        buffer.writeVarInt(validated.size());
        for (List<Point> stroke : validated) {
            buffer.writeVarInt(stroke.size());
            for (Point point : stroke) {
                Point.STREAM_CODEC.encode(buffer, point);
            }
        }
    }

    public static List<List<Point>> immutableValidatedCopy(
            List<List<Point>> strokes,
            DrawingLimits limits) {
        Objects.requireNonNull(strokes, "strokes");
        Objects.requireNonNull(limits, "limits");
        if (strokes.size() > limits.maxStrokes()) {
            throw new IllegalArgumentException("Too many strokes: " + strokes.size());
        }

        int totalPoints = 0;
        List<List<Point>> copy = new ArrayList<>(strokes.size());
        for (int strokeIndex = 0; strokeIndex < strokes.size(); strokeIndex++) {
            List<Point> stroke = Objects.requireNonNull(
                    strokes.get(strokeIndex), "stroke " + strokeIndex);
            if (stroke.size() < 2) {
                throw new IllegalArgumentException(
                        "Too few points in stroke " + strokeIndex + ": " + stroke.size());
            }
            if (stroke.size() > limits.maxPointsPerStroke()) {
                throw new IllegalArgumentException(
                        "Too many points in stroke " + strokeIndex + ": " + stroke.size());
            }

            totalPoints += stroke.size();
            if (totalPoints > limits.maxTotalPoints()) {
                throw new IllegalArgumentException("Too many total points: " + totalPoints);
            }

            List<Point> strokeCopy = new ArrayList<>(stroke.size());
            for (int pointIndex = 0; pointIndex < stroke.size(); pointIndex++) {
                Point point = Objects.requireNonNull(
                        stroke.get(pointIndex),
                        "point " + strokeIndex + ':' + pointIndex);
                validatePoint(point, strokeIndex, pointIndex);
                strokeCopy.add(point);
            }
            copy.add(List.copyOf(strokeCopy));
        }
        return List.copyOf(copy);
    }

    private static int readSize(
            RegistryFriendlyByteBuf buffer,
            String description,
            int maximum) {
        int size = buffer.readVarInt();
        if (size < 0) {
            throw new IllegalArgumentException("Negative " + description + ": " + size);
        }
        if (size > maximum) {
            throw new IllegalArgumentException("Too large " + description + ": " + size);
        }
        return size;
    }

    private static void validatePoint(Point point, int strokeIndex, int pointIndex) {
        if (!Double.isFinite(point.x) || !Double.isFinite(point.y)) {
            throw new IllegalArgumentException(
                    "Non-finite point at " + strokeIndex + ':' + pointIndex);
        }
        if (point.x < 0.0 || point.x > 1.0 || point.y < 0.0 || point.y > 1.0) {
            throw new IllegalArgumentException(
                    "Point outside 0.0-1.0 at " + strokeIndex + ':' + pointIndex
                            + ": " + point.x + ", " + point.y);
        }
    }
}
