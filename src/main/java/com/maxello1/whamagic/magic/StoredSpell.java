package com.maxello1.whamagic.magic;

import com.maxello1.whamagic.parser.BoundingBox;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Persistent, server-authoritative compiled representation of a saved spell. */
public record StoredSpell(
        int formatVersion,
        SpellState state,
        List<CompiledSigil> compiledSigils,
        List<CompiledSign> compiledSigns,
        SpellGeometry geometry,
        String displayName,
        int strokeHash,
        String dictionaryVersion,
        String dictionaryHash,
        String recognizerVersion,
        String authoritySignature
) {
    public static final int FORMAT_VERSION = 3;

    private static final int MAX_COMPILED_SYMBOLS = CandidateGenerationSettings.DEFAULTS.maxCandidates();
    private static final int MAX_SOURCE_STROKES = 256;
    private static final double MIN_NORMALIZED_COORDINATE = -0.25;
    private static final double MAX_NORMALIZED_COORDINATE = 1.25;
    private static final double MIN_RING_RADIUS = 0.08;
    private static final double MAX_RING_RADIUS = 0.80;
    private static final double MAX_NORMALIZED_RADIAL_POSITION = 32.0;
    private static final double MAX_DIRECTIONAL_BIAS_MAGNITUDE = 1.0;
    private static final double DERIVED_VALUE_TOLERANCE = 1.0e-9;

    private static final String AUTHORITY_ALGORITHM = "HmacSHA256";
    private static final byte[] AUTHORITY_KEY = createAuthorityKey();
    private static final ThreadLocal<Mac> AUTHORITY_MAC = ThreadLocal.withInitial(() -> {
        try {
            Mac mac = Mac.getInstance(AUTHORITY_ALGORITHM);
            mac.init(new SecretKeySpec(AUTHORITY_KEY, AUTHORITY_ALGORITHM));
            return mac;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot initialize stored-spell authority", exception);
        }
    });

    private static final Codec<SpellState> SPELL_STATE_CODEC = Codec.STRING.comapFlatMap(
            value -> enumValue(SpellState.class, value, "spell state"),
            state -> state.name().toLowerCase(Locale.ROOT));

    private static final Codec<ElementType> ELEMENT_TYPE_CODEC = Codec.STRING.comapFlatMap(
            value -> enumValue(ElementType.class, value, "element"),
            element -> element.name().toLowerCase(Locale.ROOT));

    private static final Codec<SpellLayer> SPELL_LAYER_CODEC = Codec.STRING.comapFlatMap(
            value -> enumValue(SpellLayer.class, value, "spell layer"),
            layer -> layer.name().toLowerCase(Locale.ROOT));

    private static final Codec<Identifier> IDENTIFIER_CODEC = Codec.STRING.comapFlatMap(
            value -> {
                Identifier identifier = Identifier.tryParse(value);
                return identifier == null
                        ? DataResult.error(() -> "Invalid identifier: " + value)
                        : DataResult.success(identifier);
            },
            Identifier::toString);

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

    private static final Codec<BoundingBox> BOUNDING_BOX_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.DOUBLE.fieldOf("minX").forGetter(BoundingBox::minX),
                    Codec.DOUBLE.fieldOf("minY").forGetter(BoundingBox::minY),
                    Codec.DOUBLE.fieldOf("maxX").forGetter(BoundingBox::maxX),
                    Codec.DOUBLE.fieldOf("maxY").forGetter(BoundingBox::maxY)
            ).apply(instance, BoundingBox::new));

    private static final Codec<CompiledSigil> COMPILED_SIGIL_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    IDENTIFIER_CODEC.fieldOf("semanticId").forGetter(CompiledSigil::semanticId),
                    Codec.STRING.fieldOf("matchedTemplateId").forGetter(CompiledSigil::matchedTemplateId),
                    Codec.STRING.fieldOf("displayName").forGetter(CompiledSigil::displayName),
                    ELEMENT_TYPE_CODEC.fieldOf("element").forGetter(CompiledSigil::element),
                    SIGIL_SEMANTIC_CODEC.fieldOf("semantic").forGetter(CompiledSigil::semantic),
                    Codec.DOUBLE.fieldOf("recognitionConfidence").forGetter(CompiledSigil::recognitionConfidence),
                    Point.CODEC.fieldOf("centroid").forGetter(CompiledSigil::centroid),
                    BOUNDING_BOX_CODEC.fieldOf("bounds").forGetter(CompiledSigil::bounds),
                    Codec.DOUBLE.fieldOf("orientationDegrees").forGetter(CompiledSigil::orientationDegrees),
                    Codec.list(Codec.INT).fieldOf("sourceStrokeIndices").forGetter(CompiledSigil::sourceStrokeIndices)
            ).apply(instance, CompiledSigil::new));

    private static final Codec<CompiledSign> COMPILED_SIGN_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    IDENTIFIER_CODEC.fieldOf("semanticId").forGetter(CompiledSign::semanticId),
                    Codec.STRING.fieldOf("matchedTemplateId").forGetter(CompiledSign::matchedTemplateId),
                    SIGN_SEMANTIC_CODEC.fieldOf("semantic").forGetter(CompiledSign::semantic),
                    Codec.DOUBLE.fieldOf("confidence").forGetter(CompiledSign::confidence),
                    Codec.DOUBLE.fieldOf("angleAroundRing").forGetter(CompiledSign::angleAroundRing),
                    Codec.DOUBLE.fieldOf("orientationDegrees").forGetter(CompiledSign::orientationDegrees),
                    Codec.DOUBLE.fieldOf("radialPosition").forGetter(CompiledSign::radialPosition),
                    SPELL_LAYER_CODEC.fieldOf("layer").forGetter(CompiledSign::layer),
                    Point.CODEC.fieldOf("centroid").forGetter(CompiledSign::centroid),
                    BOUNDING_BOX_CODEC.fieldOf("bounds").forGetter(CompiledSign::bounds),
                    Codec.list(Codec.INT).fieldOf("sourceStrokeIndices").forGetter(CompiledSign::sourceStrokeIndices),
                    Codec.BOOL.fieldOf("reversed").forGetter(CompiledSign::reversed)
            ).apply(instance, CompiledSign::new));

    private static final Codec<SpellGeometry> SPELL_GEOMETRY_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Point.CODEC.fieldOf("ringCenter").forGetter(SpellGeometry::ringCenter),
                    Codec.DOUBLE.fieldOf("ringRadius").forGetter(SpellGeometry::ringRadius),
                    Codec.DOUBLE.fieldOf("ringArea").forGetter(SpellGeometry::ringArea),
                    Codec.DOUBLE.fieldOf("normalizedRingDiameter").forGetter(SpellGeometry::normalizedRingDiameter),
                    Codec.DOUBLE.fieldOf("ringCompleteness").forGetter(SpellGeometry::ringCompleteness),
                    Codec.DOUBLE.fieldOf("ringCircularity").forGetter(SpellGeometry::ringCircularity),
                    Codec.DOUBLE.fieldOf("ringNormalizedRmse").forGetter(SpellGeometry::ringNormalizedRmse),
                    Point.CODEC.fieldOf("directionalBias").forGetter(SpellGeometry::directionalBias),
                    Codec.DOUBLE.fieldOf("radialSymmetryScore").forGetter(SpellGeometry::radialSymmetryScore),
                    Codec.DOUBLE.fieldOf("bilateralSymmetryScore").forGetter(SpellGeometry::bilateralSymmetryScore),
                    Codec.DOUBLE.fieldOf("signBalanceScore").forGetter(SpellGeometry::signBalanceScore)
            ).apply(instance, SpellGeometry::new));

    /**
     * The legacy aggregate fields exist only to keep historical components decodable.
     * They are intentionally discarded instead of being promoted into incomplete v3 geometry.
     */
    private record PersistedSpell(
            int formatVersion,
            SpellState state,
            List<CompiledSigil> compiledSigils,
            List<CompiledSign> compiledSigns,
            Optional<SpellGeometry> geometry,
            String displayName,
            int strokeHash,
            String dictionaryVersion,
            String dictionaryHash,
            String recognizerVersion,
            String authoritySignature,
            List<ElementType> legacyElements,
            String legacyElement,
            Map<String, Integer> legacySignCounts,
            Optional<SigilSemantic> legacySigilSemantic,
            List<SignSemantic> legacySignSemantics
    ) {}

    private static final Codec<PersistedSpell> PERSISTED_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.INT.optionalFieldOf("formatVersion", 0).forGetter(PersistedSpell::formatVersion),
                    SPELL_STATE_CODEC.fieldOf("state").forGetter(PersistedSpell::state),
                    Codec.list(COMPILED_SIGIL_CODEC).optionalFieldOf("compiledSigils", List.of())
                            .forGetter(PersistedSpell::compiledSigils),
                    Codec.list(COMPILED_SIGN_CODEC).optionalFieldOf("compiledSigns", List.of())
                            .forGetter(PersistedSpell::compiledSigns),
                    SPELL_GEOMETRY_CODEC.optionalFieldOf("geometry").forGetter(PersistedSpell::geometry),
                    Codec.STRING.optionalFieldOf("displayName", "").forGetter(PersistedSpell::displayName),
                    Codec.INT.fieldOf("strokeHash").forGetter(PersistedSpell::strokeHash),
                    Codec.STRING.optionalFieldOf("dictionaryVersion", "")
                            .forGetter(PersistedSpell::dictionaryVersion),
                    Codec.STRING.optionalFieldOf("dictionaryHash", "").forGetter(PersistedSpell::dictionaryHash),
                    Codec.STRING.optionalFieldOf("recognizerVersion", "")
                            .forGetter(PersistedSpell::recognizerVersion),
                    Codec.STRING.optionalFieldOf("authoritySignature", "")
                            .forGetter(PersistedSpell::authoritySignature),
                    Codec.list(ELEMENT_TYPE_CODEC).optionalFieldOf("elements", List.of())
                            .forGetter(PersistedSpell::legacyElements),
                    Codec.STRING.optionalFieldOf("element", "").forGetter(PersistedSpell::legacyElement),
                    Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("signCounts", Map.of())
                            .forGetter(PersistedSpell::legacySignCounts),
                    SIGIL_SEMANTIC_CODEC.optionalFieldOf("sigilSemantic")
                            .forGetter(PersistedSpell::legacySigilSemantic),
                    Codec.list(SIGN_SEMANTIC_CODEC).optionalFieldOf("signSemantics", List.of())
                            .forGetter(PersistedSpell::legacySignSemantics)
            ).apply(instance, PersistedSpell::new));

    public static final Codec<StoredSpell> CODEC = PERSISTED_CODEC.flatXmap(
            StoredSpell::decodePersisted,
            StoredSpell::encodePersisted);

    private static final StreamCodec<RegistryFriendlyByteBuf, Point> EXACT_POINT_STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public Point decode(RegistryFriendlyByteBuf buffer) {
                    return new Point(
                            ByteBufCodecs.DOUBLE.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, Point point) {
                    ByteBufCodecs.DOUBLE.encode(buffer, point.x);
                    ByteBufCodecs.DOUBLE.encode(buffer, point.y);
                }
            };

    private static final StreamCodec<RegistryFriendlyByteBuf, BoundingBox> BOUNDING_BOX_STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public BoundingBox decode(RegistryFriendlyByteBuf buffer) {
                    return new BoundingBox(
                            ByteBufCodecs.DOUBLE.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, BoundingBox bounds) {
                    ByteBufCodecs.DOUBLE.encode(buffer, bounds.minX());
                    ByteBufCodecs.DOUBLE.encode(buffer, bounds.minY());
                    ByteBufCodecs.DOUBLE.encode(buffer, bounds.maxX());
                    ByteBufCodecs.DOUBLE.encode(buffer, bounds.maxY());
                }
            };

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

    private static final StreamCodec<RegistryFriendlyByteBuf, CompiledSigil> COMPILED_SIGIL_STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public CompiledSigil decode(RegistryFriendlyByteBuf buffer) {
                    return new CompiledSigil(
                            Identifier.STREAM_CODEC.decode(buffer),
                            ByteBufCodecs.STRING_UTF8.decode(buffer),
                            ByteBufCodecs.STRING_UTF8.decode(buffer),
                            decodeEnum(buffer, ElementType.class),
                            SIGIL_SEMANTIC_STREAM_CODEC.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer),
                            EXACT_POINT_STREAM_CODEC.decode(buffer),
                            BOUNDING_BOX_STREAM_CODEC.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer),
                            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.VAR_INT).decode(buffer));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, CompiledSigil sigil) {
                    Identifier.STREAM_CODEC.encode(buffer, sigil.semanticId());
                    ByteBufCodecs.STRING_UTF8.encode(buffer, sigil.matchedTemplateId());
                    ByteBufCodecs.STRING_UTF8.encode(buffer, sigil.displayName());
                    encodeEnum(buffer, sigil.element());
                    SIGIL_SEMANTIC_STREAM_CODEC.encode(buffer, sigil.semantic());
                    ByteBufCodecs.DOUBLE.encode(buffer, sigil.recognitionConfidence());
                    EXACT_POINT_STREAM_CODEC.encode(buffer, sigil.centroid());
                    BOUNDING_BOX_STREAM_CODEC.encode(buffer, sigil.bounds());
                    ByteBufCodecs.DOUBLE.encode(buffer, sigil.orientationDegrees());
                    ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.VAR_INT)
                            .encode(buffer, new ArrayList<>(sigil.sourceStrokeIndices()));
                }
            };

    private static final StreamCodec<RegistryFriendlyByteBuf, CompiledSign> COMPILED_SIGN_STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public CompiledSign decode(RegistryFriendlyByteBuf buffer) {
                    return new CompiledSign(
                            Identifier.STREAM_CODEC.decode(buffer),
                            ByteBufCodecs.STRING_UTF8.decode(buffer),
                            SIGN_SEMANTIC_STREAM_CODEC.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer),
                            decodeEnum(buffer, SpellLayer.class),
                            EXACT_POINT_STREAM_CODEC.decode(buffer),
                            BOUNDING_BOX_STREAM_CODEC.decode(buffer),
                            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.VAR_INT).decode(buffer),
                            ByteBufCodecs.BOOL.decode(buffer));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, CompiledSign sign) {
                    Identifier.STREAM_CODEC.encode(buffer, sign.semanticId());
                    ByteBufCodecs.STRING_UTF8.encode(buffer, sign.matchedTemplateId());
                    SIGN_SEMANTIC_STREAM_CODEC.encode(buffer, sign.semantic());
                    ByteBufCodecs.DOUBLE.encode(buffer, sign.confidence());
                    ByteBufCodecs.DOUBLE.encode(buffer, sign.angleAroundRing());
                    ByteBufCodecs.DOUBLE.encode(buffer, sign.orientationDegrees());
                    ByteBufCodecs.DOUBLE.encode(buffer, sign.radialPosition());
                    encodeEnum(buffer, sign.layer());
                    EXACT_POINT_STREAM_CODEC.encode(buffer, sign.centroid());
                    BOUNDING_BOX_STREAM_CODEC.encode(buffer, sign.bounds());
                    ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.VAR_INT)
                            .encode(buffer, new ArrayList<>(sign.sourceStrokeIndices()));
                    ByteBufCodecs.BOOL.encode(buffer, sign.reversed());
                }
            };

    private static final StreamCodec<RegistryFriendlyByteBuf, SpellGeometry> SPELL_GEOMETRY_STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public SpellGeometry decode(RegistryFriendlyByteBuf buffer) {
                    return new SpellGeometry(
                            EXACT_POINT_STREAM_CODEC.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer),
                            EXACT_POINT_STREAM_CODEC.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer),
                            ByteBufCodecs.DOUBLE.decode(buffer));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, SpellGeometry geometry) {
                    EXACT_POINT_STREAM_CODEC.encode(buffer, geometry.ringCenter());
                    ByteBufCodecs.DOUBLE.encode(buffer, geometry.ringRadius());
                    ByteBufCodecs.DOUBLE.encode(buffer, geometry.ringArea());
                    ByteBufCodecs.DOUBLE.encode(buffer, geometry.normalizedRingDiameter());
                    ByteBufCodecs.DOUBLE.encode(buffer, geometry.ringCompleteness());
                    ByteBufCodecs.DOUBLE.encode(buffer, geometry.ringCircularity());
                    ByteBufCodecs.DOUBLE.encode(buffer, geometry.ringNormalizedRmse());
                    EXACT_POINT_STREAM_CODEC.encode(buffer, geometry.directionalBias());
                    ByteBufCodecs.DOUBLE.encode(buffer, geometry.radialSymmetryScore());
                    ByteBufCodecs.DOUBLE.encode(buffer, geometry.bilateralSymmetryScore());
                    ByteBufCodecs.DOUBLE.encode(buffer, geometry.signBalanceScore());
                }
            };

    public static final StreamCodec<RegistryFriendlyByteBuf, StoredSpell> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public StoredSpell decode(RegistryFriendlyByteBuf buffer) {
                    int formatVersion = ByteBufCodecs.VAR_INT.decode(buffer);
                    SpellState state = decodeEnum(buffer, SpellState.class);
                    List<CompiledSigil> compiledSigils = ByteBufCodecs.collection(
                            ArrayList::new, COMPILED_SIGIL_STREAM_CODEC).decode(buffer);
                    List<CompiledSign> compiledSigns = ByteBufCodecs.collection(
                            ArrayList::new, COMPILED_SIGN_STREAM_CODEC).decode(buffer);
                    SpellGeometry geometry = ByteBufCodecs.BOOL.decode(buffer)
                            ? SPELL_GEOMETRY_STREAM_CODEC.decode(buffer)
                            : null;
                    StoredSpell spell = new StoredSpell(
                            formatVersion,
                            state,
                            compiledSigils,
                            compiledSigns,
                            geometry,
                            ByteBufCodecs.STRING_UTF8.decode(buffer),
                            ByteBufCodecs.VAR_INT.decode(buffer),
                            ByteBufCodecs.STRING_UTF8.decode(buffer),
                            ByteBufCodecs.STRING_UTF8.decode(buffer),
                            ByteBufCodecs.STRING_UTF8.decode(buffer),
                            ByteBufCodecs.STRING_UTF8.decode(buffer));
                    if (spell.formatVersion() == FORMAT_VERSION
                            && !spell.hasStructurallyValidCurrentPayload(-1)) {
                        throw new IllegalArgumentException("Malformed v3 stored-spell network payload");
                    }
                    return spell;
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, StoredSpell spell) {
                    ByteBufCodecs.VAR_INT.encode(buffer, spell.formatVersion());
                    encodeEnum(buffer, spell.state());
                    ByteBufCodecs.collection(ArrayList::new, COMPILED_SIGIL_STREAM_CODEC)
                            .encode(buffer, new ArrayList<>(spell.compiledSigils()));
                    ByteBufCodecs.collection(ArrayList::new, COMPILED_SIGN_STREAM_CODEC)
                            .encode(buffer, new ArrayList<>(spell.compiledSigns()));
                    ByteBufCodecs.BOOL.encode(buffer, spell.geometry() != null);
                    if (spell.geometry() != null) {
                        SPELL_GEOMETRY_STREAM_CODEC.encode(buffer, spell.geometry());
                    }
                    ByteBufCodecs.STRING_UTF8.encode(buffer, spell.displayName());
                    ByteBufCodecs.VAR_INT.encode(buffer, spell.strokeHash());
                    ByteBufCodecs.STRING_UTF8.encode(buffer, spell.dictionaryVersion());
                    ByteBufCodecs.STRING_UTF8.encode(buffer, spell.dictionaryHash());
                    ByteBufCodecs.STRING_UTF8.encode(buffer, spell.recognizerVersion());
                    ByteBufCodecs.STRING_UTF8.encode(buffer, spell.authoritySignature());
                }
            };

    public StoredSpell {
        state = state == null ? SpellState.INVALID : state;
        compiledSigils = compiledSigils == null ? List.of() : List.copyOf(compiledSigils);
        compiledSigns = compiledSigns == null ? List.of() : List.copyOf(compiledSigns);
        displayName = safeString(displayName);
        dictionaryVersion = safeString(dictionaryVersion);
        dictionaryHash = safeString(dictionaryHash);
        recognizerVersion = safeString(recognizerVersion);
        authoritySignature = safeString(authoritySignature);
    }

    /** Build a current authoritative component from a successful server parse. */
    public static StoredSpell fromIr(SpellIr ir, List<List<Point>> strokes) {
        SpellDictionary.DictionarySnapshot dictionary = SpellDictionary.snapshot();
        int strokeHash = computeStrokeHash(strokes);
        String recognizerVersion = PointCloudRecognizer.RECOGNIZER_VERSION;
        String authoritySignature = signCompiledPayload(
                FORMAT_VERSION,
                ir.state(),
                ir.compiledSigils(),
                ir.compiledSigns(),
                ir.geometry(),
                ir.displayName(),
                strokeHash,
                dictionary.version(),
                dictionary.hash(),
                recognizerVersion);
        return new StoredSpell(
                FORMAT_VERSION,
                ir.state(),
                ir.compiledSigils(),
                ir.compiledSigns(),
                ir.geometry(),
                ir.displayName(),
                strokeHash,
                dictionary.version(),
                dictionary.hash(),
                recognizerVersion,
                authoritySignature);
    }

    /** True only when every input that governs execution still matches. */
    public boolean isCurrentFor(List<List<Point>> strokes) {
        if (formatVersion != FORMAT_VERSION) {
            return false;
        }
        List<List<Point>> sourceStrokes = strokes == null ? List.of() : strokes;
        SpellDictionary.DictionarySnapshot dictionary = SpellDictionary.snapshot();
        return hasStructurallyValidCurrentPayload(sourceStrokes.size())
                && hasKnownTemplates(dictionary)
                && strokeHash == computeStrokeHash(sourceStrokes)
                && dictionary.version().equals(dictionaryVersion)
                && dictionary.hash().equals(dictionaryHash)
                && PointCloudRecognizer.RECOGNIZER_VERSION.equals(recognizerVersion)
                && authoritySignature.equals(signCompiledPayload(
                        formatVersion,
                        state,
                        compiledSigils,
                        compiledSigns,
                        geometry,
                        displayName,
                        strokeHash,
                        dictionaryVersion,
                        dictionaryHash,
                        recognizerVersion));
    }

    private boolean hasStructurallyValidCurrentPayload(int sourceStrokeCount) {
        if ((state != SpellState.PREPARED && state != SpellState.ACTIVE)
                || compiledSigils.isEmpty()
                || compiledSigils.size() > MAX_COMPILED_SYMBOLS
                || compiledSigns.size() > MAX_COMPILED_SYMBOLS
                || compiledSigils.size() + compiledSigns.size() > MAX_COMPILED_SYMBOLS
                || displayName.isBlank()
                || dictionaryVersion.isBlank()
                || dictionaryHash.isBlank()
                || recognizerVersion.isBlank()
                || !validAuthoritySignature(authoritySignature)
                || !validGeometry(geometry)) {
            return false;
        }

        for (CompiledSigil sigil : compiledSigils) {
            if (!validCompiledSigil(sigil, sourceStrokeCount)) {
                return false;
            }
        }
        for (CompiledSign sign : compiledSigns) {
            if (!validCompiledSign(sign, geometry, sourceStrokeCount)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasKnownTemplates(SpellDictionary.DictionarySnapshot dictionary) {
        for (CompiledSigil sigil : compiledSigils) {
            if (!matchesKnownTemplate(
                    dictionary,
                    sigil.semanticId(),
                    sigil.matchedTemplateId(),
                    SymbolKind.SIGIL,
                    sigil.displayName())) {
                return false;
            }
        }
        for (CompiledSign sign : compiledSigns) {
            if (!matchesKnownTemplate(
                    dictionary,
                    sign.semanticId(),
                    sign.matchedTemplateId(),
                    SymbolKind.SIGN,
                    null)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesKnownTemplate(
            SpellDictionary.DictionarySnapshot dictionary,
            Identifier semanticId,
            String matchedTemplateId,
            SymbolKind kind,
            String expectedDisplayName) {
        Identifier templateId = Identifier.tryParse(matchedTemplateId);
        if (semanticId == null || templateId == null) {
            return false;
        }
        for (SpellDictionary.TemplateIdentity template : dictionary.templates()) {
            Identifier knownSemanticId = Identifier.tryParse(template.semanticId());
            Identifier knownTemplateId = Identifier.tryParse(template.templateId());
            if (semanticId.equals(knownSemanticId)
                    && templateId.equals(knownTemplateId)
                    && template.kind() == kind
                    && (expectedDisplayName == null || expectedDisplayName.equals(template.displayName()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean validCompiledSigil(CompiledSigil sigil, int sourceStrokeCount) {
        return sigil != null
                && sigil.semanticId() != null
                && validIdentifierString(sigil.matchedTemplateId())
                && sigil.displayName() != null
                && !sigil.displayName().isBlank()
                && sigil.element() != null
                && validSigilSemantic(sigil.semantic())
                && unitInterval(sigil.recognitionConfidence())
                && validCoordinatePoint(sigil.centroid())
                && validBounds(sigil.bounds())
                && pointInsideBounds(sigil.centroid(), sigil.bounds())
                && validDegrees(sigil.orientationDegrees())
                && validSourceStrokeIndices(sigil.sourceStrokeIndices(), sourceStrokeCount);
    }

    private static boolean validCompiledSign(
            CompiledSign sign,
            SpellGeometry geometry,
            int sourceStrokeCount) {
        if (sign == null
                || sign.semanticId() == null
                || !validIdentifierString(sign.matchedTemplateId())
                || !validSignSemantic(sign.semantic())
                || !unitInterval(sign.confidence())
                || !validDegrees(sign.angleAroundRing())
                || !validDegrees(sign.orientationDegrees())
                || !finiteRange(sign.radialPosition(), 0.0, MAX_NORMALIZED_RADIAL_POSITION)
                || sign.layer() == null
                || !validCoordinatePoint(sign.centroid())
                || !validBounds(sign.bounds())
                || !pointInsideBounds(sign.centroid(), sign.bounds())
                || !validSourceStrokeIndices(sign.sourceStrokeIndices(), sourceStrokeCount)) {
            return false;
        }

        double deltaX = sign.centroid().x - geometry.ringCenter().x;
        double deltaY = sign.centroid().y - geometry.ringCenter().y;
        double expectedRadialPosition = Math.hypot(deltaX, deltaY) / geometry.ringRadius();
        double expectedAngle = Math.toDegrees(Math.atan2(deltaY, deltaX));
        if (expectedAngle < 0.0) {
            expectedAngle += 360.0;
        }
        return nearlyEqual(sign.radialPosition(), expectedRadialPosition)
                && circularDegreesNearlyEqual(sign.angleAroundRing(), expectedAngle)
                && sign.layer() == SpellLayer.fromRadialPosition(expectedRadialPosition);
    }

    private static boolean validGeometry(SpellGeometry geometry) {
        if (geometry == null
                || !validCoordinatePoint(geometry.ringCenter())
                || !finiteRangeExclusive(geometry.ringRadius(), MIN_RING_RADIUS, MAX_RING_RADIUS)
                || !finiteRangeExclusive(
                        geometry.ringArea(),
                        Math.PI * MIN_RING_RADIUS * MIN_RING_RADIUS,
                        Math.PI * MAX_RING_RADIUS * MAX_RING_RADIUS)
                || !finiteRangeExclusive(
                        geometry.normalizedRingDiameter(),
                        MIN_RING_RADIUS * 2.0,
                        MAX_RING_RADIUS * 2.0)
                || !unitInterval(geometry.ringCompleteness())
                || !unitInterval(geometry.ringCircularity())
                || !unitInterval(geometry.ringNormalizedRmse())
                || !validBiasVector(geometry.directionalBias())
                || !unitInterval(geometry.radialSymmetryScore())
                || !unitInterval(geometry.bilateralSymmetryScore())
                || !unitInterval(geometry.signBalanceScore())) {
            return false;
        }

        return nearlyEqual(geometry.ringArea(), Math.PI * geometry.ringRadius() * geometry.ringRadius())
                && nearlyEqual(geometry.normalizedRingDiameter(), geometry.ringRadius() * 2.0);
    }

    private static boolean validSigilSemantic(SigilSemantic semantic) {
        return semantic != null
                && Double.isFinite(semantic.force())
                && Double.isFinite(semantic.focus())
                && Double.isFinite(semantic.spread())
                && Double.isFinite(semantic.range())
                && Double.isFinite(semantic.lifetimeBias());
    }

    private static boolean validSignSemantic(SignSemantic semantic) {
        return semantic != null
                && semantic.manifestation() != null
                && !semantic.manifestation().isBlank()
                && semantic.directionMode() != null
                && !semantic.directionMode().isBlank()
                && Double.isFinite(semantic.force())
                && Double.isFinite(semantic.focus())
                && Double.isFinite(semantic.spread())
                && Double.isFinite(semantic.range())
                && Double.isFinite(semantic.lifetimeBias());
    }

    private static boolean validCoordinatePoint(Point point) {
        return point != null
                && finiteRange(point.x, MIN_NORMALIZED_COORDINATE, MAX_NORMALIZED_COORDINATE)
                && finiteRange(point.y, MIN_NORMALIZED_COORDINATE, MAX_NORMALIZED_COORDINATE);
    }

    private static boolean validBiasVector(Point point) {
        return point != null
                && Double.isFinite(point.x)
                && Double.isFinite(point.y)
                && Math.hypot(point.x, point.y)
                        <= MAX_DIRECTIONAL_BIAS_MAGNITUDE + DERIVED_VALUE_TOLERANCE;
    }

    private static boolean validBounds(BoundingBox bounds) {
        return bounds != null
                && finiteRange(bounds.minX(), MIN_NORMALIZED_COORDINATE, MAX_NORMALIZED_COORDINATE)
                && finiteRange(bounds.minY(), MIN_NORMALIZED_COORDINATE, MAX_NORMALIZED_COORDINATE)
                && finiteRange(bounds.maxX(), MIN_NORMALIZED_COORDINATE, MAX_NORMALIZED_COORDINATE)
                && finiteRange(bounds.maxY(), MIN_NORMALIZED_COORDINATE, MAX_NORMALIZED_COORDINATE)
                && bounds.minX() <= bounds.maxX()
                && bounds.minY() <= bounds.maxY();
    }

    private static boolean pointInsideBounds(Point point, BoundingBox bounds) {
        return point.x + DERIVED_VALUE_TOLERANCE >= bounds.minX()
                && point.x - DERIVED_VALUE_TOLERANCE <= bounds.maxX()
                && point.y + DERIVED_VALUE_TOLERANCE >= bounds.minY()
                && point.y - DERIVED_VALUE_TOLERANCE <= bounds.maxY();
    }

    private static boolean validSourceStrokeIndices(List<Integer> indices, int sourceStrokeCount) {
        if (indices == null || indices.isEmpty() || indices.size() > MAX_SOURCE_STROKES) {
            return false;
        }
        Set<Integer> unique = new HashSet<>();
        for (Integer index : indices) {
            if (index == null
                    || index < 0
                    || index >= MAX_SOURCE_STROKES
                    || (sourceStrokeCount >= 0 && index >= sourceStrokeCount)
                    || !unique.add(index)) {
                return false;
            }
        }
        return true;
    }

    private static boolean validIdentifierString(String value) {
        return value != null && Identifier.tryParse(value) != null;
    }

    private static boolean validDegrees(double value) {
        return Double.isFinite(value) && value >= 0.0 && value < 360.0;
    }

    private static boolean unitInterval(double value) {
        return finiteRange(value, 0.0, 1.0);
    }

    private static boolean finiteRange(double value, double minimum, double maximum) {
        return Double.isFinite(value) && value >= minimum && value <= maximum;
    }

    private static boolean finiteRangeExclusive(double value, double minimum, double maximum) {
        return Double.isFinite(value) && value > minimum && value < maximum;
    }

    private static boolean nearlyEqual(double left, double right) {
        double scale = Math.max(1.0, Math.max(Math.abs(left), Math.abs(right)));
        return Math.abs(left - right) <= DERIVED_VALUE_TOLERANCE * scale;
    }

    private static boolean circularDegreesNearlyEqual(double left, double right) {
        double difference = Math.abs(left - right) % 360.0;
        difference = Math.min(difference, 360.0 - difference);
        return difference <= DERIVED_VALUE_TOLERANCE;
    }

    private static boolean validAuthoritySignature(String signature) {
        if (signature == null || signature.length() != 64) {
            return false;
        }
        for (int index = 0; index < signature.length(); index++) {
            char character = signature.charAt(index);
            if ((character < '0' || character > '9')
                    && (character < 'a' || character > 'f')) {
                return false;
            }
        }
        return true;
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
                compiledSigils,
                compiledSigns,
                geometry,
                displayName,
                prefix + displayName);
    }

    /**
     * Hash raw drawing geometry while retaining pen-lift boundaries. Quantization
     * matches the historical hash, but stroke and point counts are explicit.
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
                int quantizedX = (int) Math.round(point.x * 1000);
                int quantizedY = (int) Math.round(point.y * 1000);
                hash = 31 * hash + quantizedX;
                hash = 31 * hash + quantizedY;
            }
        }
        return hash;
    }

    private static byte[] createAuthorityKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private static String signCompiledPayload(
            int formatVersion,
            SpellState state,
            List<CompiledSigil> compiledSigils,
            List<CompiledSign> compiledSigns,
            SpellGeometry geometry,
            String displayName,
            int strokeHash,
            String dictionaryVersion,
            String dictionaryHash,
            String recognizerVersion) {
        Mac mac = AUTHORITY_MAC.get();
        mac.reset();
        updateString(mac, "wha-magic/stored-spell-authority");
        updateInt(mac, formatVersion);
        updateString(mac, state == null ? "" : state.name());

        updateInt(mac, compiledSigils == null ? -1 : compiledSigils.size());
        if (compiledSigils != null) {
            for (CompiledSigil sigil : compiledSigils) {
                updateCompiledSigil(mac, sigil);
            }
        }

        updateInt(mac, compiledSigns == null ? -1 : compiledSigns.size());
        if (compiledSigns != null) {
            for (CompiledSign sign : compiledSigns) {
                updateCompiledSign(mac, sign);
            }
        }

        updateSpellGeometry(mac, geometry);
        updateString(mac, safeString(displayName));
        updateInt(mac, strokeHash);
        updateString(mac, safeString(dictionaryVersion));
        updateString(mac, safeString(dictionaryHash));
        updateString(mac, safeString(recognizerVersion));
        return HexFormat.of().formatHex(mac.doFinal());
    }

    private static void updateCompiledSigil(Mac mac, CompiledSigil sigil) {
        if (sigil == null) {
            updateInt(mac, 0);
            return;
        }
        updateInt(mac, 1);
        updateIdentifier(mac, sigil.semanticId());
        updateString(mac, safeString(sigil.matchedTemplateId()));
        updateString(mac, safeString(sigil.displayName()));
        updateString(mac, sigil.element() == null ? "" : sigil.element().name());
        updateSigilSemantic(mac, sigil.semantic());
        updateDouble(mac, sigil.recognitionConfidence());
        updatePoint(mac, sigil.centroid());
        updateBounds(mac, sigil.bounds());
        updateDouble(mac, sigil.orientationDegrees());
        updateSourceStrokeIndices(mac, sigil.sourceStrokeIndices());
    }

    private static void updateCompiledSign(Mac mac, CompiledSign sign) {
        if (sign == null) {
            updateInt(mac, 0);
            return;
        }
        updateInt(mac, 1);
        updateIdentifier(mac, sign.semanticId());
        updateString(mac, safeString(sign.matchedTemplateId()));
        updateSignSemantic(mac, sign.semantic());
        updateDouble(mac, sign.confidence());
        updateDouble(mac, sign.angleAroundRing());
        updateDouble(mac, sign.orientationDegrees());
        updateDouble(mac, sign.radialPosition());
        updateString(mac, sign.layer() == null ? "" : sign.layer().name());
        updatePoint(mac, sign.centroid());
        updateBounds(mac, sign.bounds());
        updateSourceStrokeIndices(mac, sign.sourceStrokeIndices());
        updateInt(mac, sign.reversed() ? 1 : 0);
    }

    private static void updateSpellGeometry(Mac mac, SpellGeometry geometry) {
        if (geometry == null) {
            updateInt(mac, 0);
            return;
        }
        updateInt(mac, 1);
        updatePoint(mac, geometry.ringCenter());
        updateDouble(mac, geometry.ringRadius());
        updateDouble(mac, geometry.ringArea());
        updateDouble(mac, geometry.normalizedRingDiameter());
        updateDouble(mac, geometry.ringCompleteness());
        updateDouble(mac, geometry.ringCircularity());
        updateDouble(mac, geometry.ringNormalizedRmse());
        updatePoint(mac, geometry.directionalBias());
        updateDouble(mac, geometry.radialSymmetryScore());
        updateDouble(mac, geometry.bilateralSymmetryScore());
        updateDouble(mac, geometry.signBalanceScore());
    }

    private static void updateSigilSemantic(Mac mac, SigilSemantic semantic) {
        if (semantic == null) {
            updateInt(mac, 0);
            return;
        }
        updateInt(mac, 1);
        updateDouble(mac, semantic.force());
        updateDouble(mac, semantic.focus());
        updateDouble(mac, semantic.spread());
        updateDouble(mac, semantic.range());
        updateDouble(mac, semantic.lifetimeBias());
    }

    private static void updateSignSemantic(Mac mac, SignSemantic semantic) {
        if (semantic == null) {
            updateInt(mac, 0);
            return;
        }
        updateInt(mac, 1);
        updateString(mac, safeString(semantic.manifestation()));
        updateString(mac, safeString(semantic.directionMode()));
        updateDouble(mac, semantic.force());
        updateDouble(mac, semantic.focus());
        updateDouble(mac, semantic.spread());
        updateDouble(mac, semantic.range());
        updateDouble(mac, semantic.lifetimeBias());
    }

    private static void updateIdentifier(Mac mac, Identifier identifier) {
        updateString(mac, identifier == null ? "" : identifier.toString());
    }

    private static void updatePoint(Mac mac, Point point) {
        if (point == null) {
            updateInt(mac, 0);
            return;
        }
        updateInt(mac, 1);
        updateDouble(mac, point.x);
        updateDouble(mac, point.y);
    }

    private static void updateBounds(Mac mac, BoundingBox bounds) {
        if (bounds == null) {
            updateInt(mac, 0);
            return;
        }
        updateInt(mac, 1);
        updateDouble(mac, bounds.minX());
        updateDouble(mac, bounds.minY());
        updateDouble(mac, bounds.maxX());
        updateDouble(mac, bounds.maxY());
    }

    private static void updateSourceStrokeIndices(Mac mac, List<Integer> sourceStrokeIndices) {
        updateInt(mac, sourceStrokeIndices == null ? -1 : sourceStrokeIndices.size());
        if (sourceStrokeIndices == null) {
            return;
        }
        for (Integer sourceStrokeIndex : sourceStrokeIndices) {
            updateInt(mac, sourceStrokeIndex == null ? -1 : sourceStrokeIndex);
        }
    }

    private static void updateDouble(Mac mac, double value) {
        updateLong(mac, Double.doubleToLongBits(value));
    }

    private static void updateString(Mac mac, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        updateInt(mac, bytes.length);
        mac.update(bytes);
    }

    private static void updateInt(Mac mac, int value) {
        mac.update((byte) (value >>> 24));
        mac.update((byte) (value >>> 16));
        mac.update((byte) (value >>> 8));
        mac.update((byte) value);
    }

    private static void updateLong(Mac mac, long value) {
        mac.update((byte) (value >>> 56));
        mac.update((byte) (value >>> 48));
        mac.update((byte) (value >>> 40));
        mac.update((byte) (value >>> 32));
        mac.update((byte) (value >>> 24));
        mac.update((byte) (value >>> 16));
        mac.update((byte) (value >>> 8));
        mac.update((byte) value);
    }

    private static StoredSpell fromPersisted(PersistedSpell persisted) {
        return new StoredSpell(
                persisted.formatVersion(),
                persisted.state(),
                persisted.compiledSigils(),
                persisted.compiledSigns(),
                persisted.geometry().orElse(null),
                persisted.displayName(),
                persisted.strokeHash(),
                persisted.dictionaryVersion(),
                persisted.dictionaryHash(),
                persisted.recognizerVersion(),
                persisted.authoritySignature());
    }

    private static DataResult<StoredSpell> decodePersisted(PersistedSpell persisted) {
        StoredSpell spell = fromPersisted(persisted);
        if (spell.formatVersion() == FORMAT_VERSION
                && !spell.hasStructurallyValidCurrentPayload(-1)) {
            return DataResult.error(() -> "Malformed v3 stored-spell payload");
        }
        return DataResult.success(spell);
    }

    private DataResult<PersistedSpell> encodePersisted() {
        if (formatVersion == FORMAT_VERSION && !hasStructurallyValidCurrentPayload(-1)) {
            return DataResult.error(() -> "Cannot encode malformed v3 stored-spell payload");
        }
        return DataResult.success(new PersistedSpell(
                formatVersion,
                state,
                compiledSigils,
                compiledSigns,
                Optional.ofNullable(geometry),
                displayName,
                strokeHash,
                dictionaryVersion,
                dictionaryHash,
                recognizerVersion,
                authoritySignature,
                List.of(),
                "",
                Map.of(),
                Optional.empty(),
                List.of()));
    }

    private static <E extends Enum<E>> DataResult<E> enumValue(
            Class<E> enumClass,
            String value,
            String label) {
        try {
            return DataResult.success(Enum.valueOf(enumClass, value.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return DataResult.error(() -> "Unknown " + label + ": " + value);
        }
    }

    private static <E extends Enum<E>> E decodeEnum(
            RegistryFriendlyByteBuf buffer,
            Class<E> enumClass) {
        return Enum.valueOf(
                enumClass,
                ByteBufCodecs.STRING_UTF8.decode(buffer).toUpperCase(Locale.ROOT));
    }

    private static void encodeEnum(RegistryFriendlyByteBuf buffer, Enum<?> value) {
        ByteBufCodecs.STRING_UTF8.encode(buffer, value.name().toLowerCase(Locale.ROOT));
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }
}
