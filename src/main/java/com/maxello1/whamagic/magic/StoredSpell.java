package com.maxello1.whamagic.magic;

import com.maxello1.whamagic.parser.Point;
import com.maxello1.whamagic.parser.PointCloudRecognizer;
import com.maxello1.whamagic.parser.SpellDictionary;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Persistent, server-authoritative compiled representation of a saved spell. */
public record StoredSpell(
        int formatVersion,
        SpellState state,
        List<ElementType> elements,
        Map<Identifier, Integer> signCounts,
        SigilSemantic sigilSemantic,
        List<SignSemantic> signSemantics,
        String displayName,
        int strokeHash,
        String dictionaryVersion,
        String dictionaryHash,
        String recognizerVersion
) {
    public static final int FORMAT_VERSION = 2;

    private static final Codec<SpellState> SPELL_STATE_CODEC = Codec.STRING.comapFlatMap(
            value -> {
                try {
                    return DataResult.success(SpellState.valueOf(value.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException exception) {
                    return DataResult.error(() -> "Unknown spell state: " + value);
                }
            },
            state -> state.name().toLowerCase(Locale.ROOT));

    private static final Codec<ElementType> ELEMENT_TYPE_CODEC = Codec.STRING.comapFlatMap(
            value -> {
                try {
                    return DataResult.success(ElementType.valueOf(value.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException exception) {
                    return DataResult.error(() -> "Unknown element: " + value);
                }
            },
            element -> element.name().toLowerCase(Locale.ROOT));

    private static final Codec<SigilSemantic> SIGIL_SEMANTIC_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.DOUBLE.fieldOf("force").forGetter(SigilSemantic::force),
                    Codec.DOUBLE.fieldOf("focus").forGetter(SigilSemantic::focus),
                    Codec.DOUBLE.fieldOf("spread").forGetter(SigilSemantic::spread),
                    Codec.DOUBLE.fieldOf("range").forGetter(SigilSemantic::range),
                    Codec.DOUBLE.fieldOf("lifetimeBias").forGetter(SigilSemantic::lifetimeBias)
            ).apply(instance, SigilSemantic::new));

    private static final Codec<SignSemantic> SIGN_SEMANTIC_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.optionalFieldOf("manifestation", "").forGetter(
                            semantic -> safeString(semantic.manifestation())),
                    Codec.STRING.optionalFieldOf("directionMode", "").forGetter(
                            semantic -> safeString(semantic.directionMode())),
                    Codec.DOUBLE.fieldOf("force").forGetter(SignSemantic::force),
                    Codec.DOUBLE.fieldOf("focus").forGetter(SignSemantic::focus),
                    Codec.DOUBLE.fieldOf("spread").forGetter(SignSemantic::spread),
                    Codec.DOUBLE.fieldOf("range").forGetter(SignSemantic::range),
                    Codec.DOUBLE.optionalFieldOf("lifetimeBias", 0.0).forGetter(SignSemantic::lifetimeBias)
            ).apply(instance, SignSemantic::new));

    /**
     * One tolerant storage shape decodes both historical component layouts and v2.
     * Legacy sign keys remain strings until migration so malformed IDs can be ignored safely.
     */
    private record PersistedSpell(
            int formatVersion,
            SpellState state,
            List<ElementType> elements,
            String legacyElement,
            Map<String, Integer> signCounts,
            Optional<SigilSemantic> sigilSemantic,
            List<SignSemantic> signSemantics,
            String displayName,
            int strokeHash,
            String dictionaryVersion,
            String dictionaryHash,
            String recognizerVersion
    ) {}

    private static final Codec<PersistedSpell> PERSISTED_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.INT.optionalFieldOf("formatVersion", 0).forGetter(PersistedSpell::formatVersion),
                    SPELL_STATE_CODEC.fieldOf("state").forGetter(PersistedSpell::state),
                    Codec.list(ELEMENT_TYPE_CODEC).optionalFieldOf("elements", List.of())
                            .forGetter(PersistedSpell::elements),
                    Codec.STRING.optionalFieldOf("element", "").forGetter(PersistedSpell::legacyElement),
                    Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("signCounts", Map.of())
                            .forGetter(PersistedSpell::signCounts),
                    SIGIL_SEMANTIC_CODEC.optionalFieldOf("sigilSemantic")
                            .forGetter(PersistedSpell::sigilSemantic),
                    Codec.list(SIGN_SEMANTIC_CODEC).optionalFieldOf("signSemantics", List.of())
                            .forGetter(PersistedSpell::signSemantics),
                    Codec.STRING.optionalFieldOf("displayName", "").forGetter(PersistedSpell::displayName),
                    Codec.INT.fieldOf("strokeHash").forGetter(PersistedSpell::strokeHash),
                    Codec.STRING.optionalFieldOf("dictionaryVersion", "").forGetter(PersistedSpell::dictionaryVersion),
                    Codec.STRING.optionalFieldOf("dictionaryHash", "").forGetter(PersistedSpell::dictionaryHash),
                    Codec.STRING.optionalFieldOf("recognizerVersion", "").forGetter(PersistedSpell::recognizerVersion)
            ).apply(instance, PersistedSpell::new));

    public static final Codec<StoredSpell> CODEC = PERSISTED_CODEC.xmap(
            StoredSpell::fromPersisted,
            StoredSpell::toPersisted);

    private static final StreamCodec<RegistryFriendlyByteBuf, SigilSemantic> SIGIL_SEMANTIC_STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public SigilSemantic decode(RegistryFriendlyByteBuf buffer) {
                    return new SigilSemantic(
                            ByteBufCodecs.DOUBLE.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, SigilSemantic semantic) {
                    ByteBufCodecs.DOUBLE.encode(buffer, semantic.force());
                    ByteBufCodecs.DOUBLE.encode(buffer, semantic.focus());
                    ByteBufCodecs.DOUBLE.encode(buffer, semantic.spread());
                    ByteBufCodecs.DOUBLE.encode(buffer, semantic.range());
                    ByteBufCodecs.DOUBLE.encode(buffer, semantic.lifetimeBias());
                }
            };

    private static final StreamCodec<RegistryFriendlyByteBuf, SignSemantic> SIGN_SEMANTIC_STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public SignSemantic decode(RegistryFriendlyByteBuf buffer) {
                    return new SignSemantic(
                            ByteBufCodecs.STRING_UTF8.decode(buffer),
                            ByteBufCodecs.STRING_UTF8.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, SignSemantic semantic) {
                    ByteBufCodecs.STRING_UTF8.encode(buffer, safeString(semantic.manifestation()));
                    ByteBufCodecs.STRING_UTF8.encode(buffer, safeString(semantic.directionMode()));
                    ByteBufCodecs.DOUBLE.encode(buffer, semantic.force());
                    ByteBufCodecs.DOUBLE.encode(buffer, semantic.focus());
                    ByteBufCodecs.DOUBLE.encode(buffer, semantic.spread());
                    ByteBufCodecs.DOUBLE.encode(buffer, semantic.range());
                    ByteBufCodecs.DOUBLE.encode(buffer, semantic.lifetimeBias());
                }
            };

    public static final StreamCodec<RegistryFriendlyByteBuf, StoredSpell> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public StoredSpell decode(RegistryFriendlyByteBuf buffer) {
                    int formatVersion = ByteBufCodecs.VAR_INT.decode(buffer);
                    SpellState state = SpellState.valueOf(
                            ByteBufCodecs.STRING_UTF8.decode(buffer).toUpperCase(Locale.ROOT));
                    List<ElementType> elements = ByteBufCodecs.collection(
                            ArrayList::new,
                            ByteBufCodecs.STRING_UTF8.map(
                                    value -> ElementType.valueOf(value.toUpperCase(Locale.ROOT)),
                                    element -> element.name().toLowerCase(Locale.ROOT)))
                            .decode(buffer);
                    Map<Identifier, Integer> signCounts = ByteBufCodecs.map(
                            LinkedHashMap::new, Identifier.STREAM_CODEC, ByteBufCodecs.VAR_INT)
                            .decode(buffer);
                    SigilSemantic sigilSemantic = ByteBufCodecs.BOOL.decode(buffer)
                            ? SIGIL_SEMANTIC_STREAM_CODEC.decode(buffer)
                            : null;
                    List<SignSemantic> signSemantics = ByteBufCodecs.collection(
                            ArrayList::new, SIGN_SEMANTIC_STREAM_CODEC).decode(buffer);
                    String displayName = ByteBufCodecs.STRING_UTF8.decode(buffer);
                    int strokeHash = ByteBufCodecs.VAR_INT.decode(buffer);
                    String dictionaryVersion = ByteBufCodecs.STRING_UTF8.decode(buffer);
                    String dictionaryHash = ByteBufCodecs.STRING_UTF8.decode(buffer);
                    String recognizerVersion = ByteBufCodecs.STRING_UTF8.decode(buffer);
                    return new StoredSpell(
                            formatVersion, state, elements, signCounts, sigilSemantic,
                            signSemantics, displayName, strokeHash, dictionaryVersion,
                            dictionaryHash, recognizerVersion);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, StoredSpell spell) {
                    ByteBufCodecs.VAR_INT.encode(buffer, spell.formatVersion());
                    ByteBufCodecs.STRING_UTF8.encode(
                            buffer, spell.state().name().toLowerCase(Locale.ROOT));
                    ByteBufCodecs.collection(
                            ArrayList::new,
                            ByteBufCodecs.STRING_UTF8.map(
                                    value -> ElementType.valueOf(value.toUpperCase(Locale.ROOT)),
                                    element -> element.name().toLowerCase(Locale.ROOT)))
                            .encode(buffer, new ArrayList<>(spell.elements()));
                    ByteBufCodecs.map(
                            LinkedHashMap::new, Identifier.STREAM_CODEC, ByteBufCodecs.VAR_INT)
                            .encode(buffer, new LinkedHashMap<>(spell.signCounts()));
                    ByteBufCodecs.BOOL.encode(buffer, spell.sigilSemantic() != null);
                    if (spell.sigilSemantic() != null) {
                        SIGIL_SEMANTIC_STREAM_CODEC.encode(buffer, spell.sigilSemantic());
                    }
                    ByteBufCodecs.collection(ArrayList::new, SIGN_SEMANTIC_STREAM_CODEC)
                            .encode(buffer, new ArrayList<>(spell.signSemantics()));
                    ByteBufCodecs.STRING_UTF8.encode(buffer, spell.displayName());
                    ByteBufCodecs.VAR_INT.encode(buffer, spell.strokeHash());
                    ByteBufCodecs.STRING_UTF8.encode(buffer, spell.dictionaryVersion());
                    ByteBufCodecs.STRING_UTF8.encode(buffer, spell.dictionaryHash());
                    ByteBufCodecs.STRING_UTF8.encode(buffer, spell.recognizerVersion());
                }
            };

    public StoredSpell {
        state = state == null ? SpellState.INVALID : state;
        elements = elements == null ? List.of() : List.copyOf(elements);
        signCounts = signCounts == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(signCounts));
        signSemantics = signSemantics == null ? List.of() : List.copyOf(signSemantics);
        displayName = safeString(displayName);
        dictionaryVersion = safeString(dictionaryVersion);
        dictionaryHash = safeString(dictionaryHash);
        recognizerVersion = safeString(recognizerVersion);
    }

    /** Build a current authoritative component from a successful server parse. */
    public static StoredSpell fromIr(SpellIr ir, List<List<Point>> strokes) {
        SpellDictionary.DictionarySnapshot dictionary = SpellDictionary.snapshot();
        return new StoredSpell(
                FORMAT_VERSION,
                ir.state(),
                ir.elements(),
                ir.signCounts(),
                ir.sigilSemantic(),
                ir.signSemantics(),
                ir.displayName(),
                computeStrokeHash(strokes),
                dictionary.version(),
                dictionary.hash(),
                PointCloudRecognizer.RECOGNIZER_VERSION);
    }

    /** True only when every input that governs execution still matches. */
    public boolean isCurrentFor(List<List<Point>> strokes) {
        SpellDictionary.DictionarySnapshot dictionary = SpellDictionary.snapshot();
        return formatVersion == FORMAT_VERSION
                && state != null
                && (state == SpellState.PREPARED || state == SpellState.ACTIVE)
                && sigilSemantic != null
                && strokeHash == computeStrokeHash(strokes)
                && dictionary.version().equals(dictionaryVersion)
                && dictionary.hash().equals(dictionaryHash)
                && PointCloudRecognizer.RECOGNIZER_VERSION.equals(recognizerVersion);
    }

    /** Reconstruct the execution IR directly, without recognition or parsing. */
    public SpellIr toIr() {
        String prefix = switch (state) {
            case ACTIVE -> "Active: ";
            case PREPARED -> "Prepared: ";
            case DRAFT -> "Drafting: ";
            case INVALID -> "Invalid: ";
        };
        return new SpellIr(
                state,
                null,
                elements,
                signCounts,
                sigilSemantic,
                signSemantics,
                displayName,
                prefix + displayName);
    }

    /**
     * Hash raw drawing geometry while retaining pen-lift boundaries. Quantization
     * matches the historical hash, but stroke and point counts are now explicit.
     */
    public static int computeStrokeHash(List<List<Point>> strokes) {
        if (strokes == null || strokes.isEmpty()) return 0;
        int hash = 1;
        hash = 31 * hash + strokes.size();
        for (List<Point> stroke : strokes) {
            int pointCount = stroke == null ? -1 : stroke.size();
            hash = 31 * hash + pointCount;
            if (stroke == null) continue;
            for (Point point : stroke) {
                if (point == null) {
                    hash = 31 * hash;
                    hash = 31 * hash;
                    continue;
                }
                int qx = (int) Math.round(point.x * 1000);
                int qy = (int) Math.round(point.y * 1000);
                hash = 31 * hash + qx;
                hash = 31 * hash + qy;
            }
        }
        return hash;
    }

    private static StoredSpell fromPersisted(PersistedSpell persisted) {
        List<ElementType> elements = new ArrayList<>(persisted.elements());
        if (elements.isEmpty() && !persisted.legacyElement().isBlank()) {
            try {
                elements.add(ElementType.valueOf(
                        persisted.legacyElement().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Unknown legacy elements make the cache stale; raw strokes remain loadable.
            }
        }

        Map<Identifier, Integer> signCounts = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : persisted.signCounts().entrySet()) {
            Identifier id = Identifier.tryParse(entry.getKey());
            Integer count = entry.getValue();
            if (id != null && count != null && count > 0) {
                signCounts.merge(id, count, Integer::sum);
            }
        }

        return new StoredSpell(
                persisted.formatVersion(),
                persisted.state(),
                elements,
                signCounts,
                persisted.sigilSemantic().orElse(null),
                persisted.signSemantics(),
                persisted.displayName(),
                persisted.strokeHash(),
                persisted.dictionaryVersion(),
                persisted.dictionaryHash(),
                persisted.recognizerVersion());
    }

    private PersistedSpell toPersisted() {
        Map<String, Integer> stringSignCounts = new LinkedHashMap<>();
        signCounts.forEach((id, count) -> stringSignCounts.put(id.toString(), count));
        return new PersistedSpell(
                formatVersion,
                state,
                elements,
                "",
                stringSignCounts,
                Optional.ofNullable(sigilSemantic),
                signSemantics,
                displayName,
                strokeHash,
                dictionaryVersion,
                dictionaryHash,
                recognizerVersion);
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }
}
