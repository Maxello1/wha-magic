package com.maxello1.whamagic.magic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import com.maxello1.whamagic.parser.Point;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

public record StoredSpell(
    SpellState state,
    String element,
    Map<String, Integer> signCounts,
    String displayName,
    int strokeHash
) {
    public static final Codec<StoredSpell> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.STRING.xmap(SpellState::valueOf, SpellState::name).fieldOf("state").forGetter(StoredSpell::state),
        Codec.STRING.optionalFieldOf("element", "").forGetter(StoredSpell::element),
        Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("signCounts", Map.of()).forGetter(StoredSpell::signCounts),
        Codec.STRING.optionalFieldOf("displayName", "").forGetter(StoredSpell::displayName),
        Codec.INT.fieldOf("strokeHash").forGetter(StoredSpell::strokeHash)
    ).apply(instance, StoredSpell::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, StoredSpell> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8.map(SpellState::valueOf, SpellState::name), StoredSpell::state,
        ByteBufCodecs.STRING_UTF8, StoredSpell::element,
        ByteBufCodecs.map(java.util.LinkedHashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_INT), StoredSpell::signCounts,
        ByteBufCodecs.STRING_UTF8, StoredSpell::displayName,
        ByteBufCodecs.VAR_INT, StoredSpell::strokeHash,
        StoredSpell::new
    );

    public static StoredSpell fromIr(SpellIr ir, List<List<Point>> strokes) {
        return new StoredSpell(
            ir.state(),
            ir.element() != null ? ir.element() : "",
            ir.signCounts() != null ? ir.signCounts() : Map.of(),
            ir.displayName() != null ? ir.displayName() : "",
            computeStrokeHash(strokes)
        );
    }
    
    public static int computeStrokeHash(List<List<Point>> strokes) {
        if (strokes == null || strokes.isEmpty()) return 0;
        int hash = 1;
        for (List<Point> stroke : strokes) {
            for (Point p : stroke) {
                // Quantize to 1000x1000 grid for stable hashing
                int qx = (int) Math.round(p.x * 1000);
                int qy = (int) Math.round(p.y * 1000);
                hash = 31 * hash + qx;
                hash = 31 * hash + qy;
            }
        }
        return hash;
    }
}
