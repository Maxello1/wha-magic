package com.maxello1.whamagic.magic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import com.maxello1.whamagic.parser.Point;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

public record StoredSpell(
    int formatVersion,
    SpellState state,
    List<ElementType> elements,
    Map<Identifier, Integer> signCounts,
    String displayName,
    int strokeHash
) {
    private static final Codec<ElementType> ELEMENT_TYPE_CODEC = Codec.STRING.comapFlatMap(
        s -> {
            try {
                return DataResult.success(ElementType.valueOf(s.toUpperCase()));
            } catch (IllegalArgumentException e) {
                return DataResult.error(() -> "Unknown element: " + s);
            }
        },
        e -> e.name().toLowerCase()
    );

    private static final Codec<StoredSpell> NEW_CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.INT.optionalFieldOf("formatVersion", 1).forGetter(StoredSpell::formatVersion),
        Codec.STRING.xmap(SpellState::valueOf, SpellState::name).fieldOf("state").forGetter(StoredSpell::state),
        Codec.list(ELEMENT_TYPE_CODEC).optionalFieldOf("elements", List.of()).forGetter(StoredSpell::elements),
        Codec.unboundedMap(Identifier.CODEC, Codec.INT).optionalFieldOf("signCounts", Map.of()).forGetter(StoredSpell::signCounts),
        Codec.STRING.optionalFieldOf("displayName", "").forGetter(StoredSpell::displayName),
        Codec.INT.fieldOf("strokeHash").forGetter(StoredSpell::strokeHash)
    ).apply(instance, StoredSpell::new));

    private record OldStoredSpell(SpellState state, String element, Map<String, Integer> signCounts, String displayName, int strokeHash) {}

    private static final Codec<OldStoredSpell> OLD_CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.STRING.xmap(SpellState::valueOf, SpellState::name).fieldOf("state").forGetter(OldStoredSpell::state),
        Codec.STRING.optionalFieldOf("element", "").forGetter(OldStoredSpell::element),
        Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("signCounts", Map.of()).forGetter(OldStoredSpell::signCounts),
        Codec.STRING.optionalFieldOf("displayName", "").forGetter(OldStoredSpell::displayName),
        Codec.INT.fieldOf("strokeHash").forGetter(OldStoredSpell::strokeHash)
    ).apply(instance, OldStoredSpell::new));

    public static final Codec<StoredSpell> CODEC = Codec.withAlternative(NEW_CODEC, OLD_CODEC.xmap(
        old -> {
            List<ElementType> elems = new ArrayList<>();
            if (old.element != null && !old.element.isEmpty()) {
                try {
                    elems.add(ElementType.valueOf(old.element.toUpperCase()));
                } catch (Exception ignored) {}
            }
            Map<Identifier, Integer> newSigns = new java.util.HashMap<>();
            for (Map.Entry<String, Integer> entry : old.signCounts.entrySet()) {
                newSigns.put(Identifier.parse(entry.getKey()), entry.getValue());
            }
            return new StoredSpell(1, old.state(), elems, newSigns, old.displayName(), old.strokeHash());
        },
        spell -> new OldStoredSpell(spell.state(), spell.elements().isEmpty() ? "" : spell.elements().get(0).name().toLowerCase(), Map.of(), spell.displayName(), spell.strokeHash())
    ));

    public static final StreamCodec<RegistryFriendlyByteBuf, StoredSpell> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, StoredSpell::formatVersion,
        ByteBufCodecs.STRING_UTF8.map(SpellState::valueOf, SpellState::name), StoredSpell::state,
        ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8.map(s -> ElementType.valueOf(s.toUpperCase()), e -> e.name().toLowerCase())), StoredSpell::elements,
        ByteBufCodecs.map(java.util.LinkedHashMap::new, Identifier.STREAM_CODEC, ByteBufCodecs.VAR_INT), StoredSpell::signCounts,
        ByteBufCodecs.STRING_UTF8, StoredSpell::displayName,
        ByteBufCodecs.VAR_INT, StoredSpell::strokeHash,
        StoredSpell::new
    );

    public static StoredSpell fromIr(SpellIr ir, List<List<Point>> strokes) {
        return new StoredSpell(
            1,
            ir.state(),
            ir.elements() != null ? ir.elements() : List.of(),
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
                int qx = (int) Math.round(p.x * 1000);
                int qy = (int) Math.round(p.y * 1000);
                hash = 31 * hash + qx;
                hash = 31 * hash + qy;
            }
        }
        return hash;
    }
}
