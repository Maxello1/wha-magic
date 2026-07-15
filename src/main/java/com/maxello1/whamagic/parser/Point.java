package com.maxello1.whamagic.parser;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;
import java.util.Objects;

public class Point {
    public final double x, y;
    
    public Point(double x, double y) {
        this.x = x;
        this.y = y;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Point point)) return false;
        return Double.compare(x, point.x) == 0 && Double.compare(y, point.y) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y);
    }

    @Override
    public String toString() {
        return "Point[x=" + x + ", y=" + y + "]";
    }

    public static final Codec<Point> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.fieldOf("x").forGetter(p -> p.x),
            Codec.DOUBLE.fieldOf("y").forGetter(p -> p.y)
    ).apply(instance, Point::new));

    public static final Codec<List<List<Point>>> STROKES_CODEC = CODEC.listOf().listOf();

    public static final StreamCodec<net.minecraft.network.FriendlyByteBuf, Point> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.SHORT, p -> (short) Math.round(p.x * 32000),
            ByteBufCodecs.SHORT, p -> (short) Math.round(p.y * 32000),
            (x, y) -> new Point(x / 32000.0, y / 32000.0)
    );

    @SuppressWarnings("unchecked")
    public static final StreamCodec<RegistryFriendlyByteBuf, List<List<Point>>> STROKES_STREAM_CODEC = 
            (StreamCodec<RegistryFriendlyByteBuf, List<List<Point>>>) (Object) STREAM_CODEC.apply(ByteBufCodecs.list()).apply(ByteBufCodecs.list());
}
