package com.example.parser;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

public class Point {
    public final double x, y;
    
    public Point(double x, double y) {
        this.x = x;
        this.y = y;
    }

    public static final Codec<Point> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.fieldOf("x").forGetter(p -> p.x),
            Codec.DOUBLE.fieldOf("y").forGetter(p -> p.y)
    ).apply(instance, Point::new));

    public static final Codec<List<List<Point>>> STROKES_CODEC = CODEC.listOf().listOf();

    public static final StreamCodec<net.minecraft.network.FriendlyByteBuf, Point> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, p -> p.x,
            ByteBufCodecs.DOUBLE, p -> p.y,
            Point::new
    );

    @SuppressWarnings("unchecked")
    public static final StreamCodec<RegistryFriendlyByteBuf, List<List<Point>>> STROKES_STREAM_CODEC = 
            (StreamCodec<RegistryFriendlyByteBuf, List<List<Point>>>) (Object) STREAM_CODEC.apply(ByteBufCodecs.list()).apply(ByteBufCodecs.list());
}
