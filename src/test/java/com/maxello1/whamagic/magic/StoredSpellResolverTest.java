package com.maxello1.whamagic.magic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maxello1.whamagic.parser.Point;
import com.maxello1.whamagic.parser.SpellDictionary;
import com.maxello1.whamagic.parser.SpellParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoredSpellResolverTest {
    private static final Identifier LEVITATION = Identifier.parse("levitation");
    private static final Identifier CONVERGENCE = Identifier.parse("convergence");
    private static final List<List<Point>> STROKES = List.of(
            List.of(new Point(0.10, 0.20), new Point(0.30, 0.40)),
            List.of(new Point(0.50, 0.60), new Point(0.70, 0.80)));
    private static final List<List<Point>> OTHER_STROKES = List.of(
            List.of(new Point(0.10, 0.20), new Point(0.30, 0.41)),
            List.of(new Point(0.50, 0.60), new Point(0.70, 0.80)));

    @BeforeAll
    static void loadDictionary() {
        SpellDictionary.ensureLoaded();
    }

    @Test
    void currentCachedSpellDoesNotInvokeParser() {
        StoredSpell cached = StoredSpell.fromIr(authoritativeIr(), STROKES);
        AtomicInteger parseCalls = new AtomicInteger();

        StoredSpellResolver.Resolution resolution = StoredSpellResolver.resolve(
                cached, STROKES, strokes -> {
                    parseCalls.incrementAndGet();
                    throw new AssertionError("A current compiled spell must not be reparsed");
                });

        assertAll(
                () -> assertEquals(0, parseCalls.get()),
                () -> assertFalse(resolution.reparsed()),
                () -> assertEquals(cached.toIr(), resolution.ir()),
                () -> assertNull(resolution.refreshedSpell()));
    }

    @Test
    void untrustedCurrentComponentIsReparsedExactlyOnce() {
        StoredSpell cached = StoredSpell.fromIr(authoritativeIr(), STROKES);
        SpellIr reparsedIr = reparsedIr();
        AtomicInteger parseCalls = new AtomicInteger();

        StoredSpellResolver.Resolution resolution = StoredSpellResolver.resolve(
                cached,
                STROKES,
                false,
                strokes -> {
                    parseCalls.incrementAndGet();
                    return new SpellParser.ParseResult(null, reparsedIr);
                });

        assertAll(
                () -> assertEquals(1, parseCalls.get()),
                () -> assertTrue(resolution.reparsed()),
                () -> assertEquals(reparsedIr, resolution.ir()),
                () -> assertNotNull(resolution.refreshedSpell()));
    }

    @Test
    void staleStrokeHashReparsesExactlyOnceAndRefreshesComponent() {
        StoredSpell stale = StoredSpell.fromIr(authoritativeIr(), OTHER_STROKES);

        assertSuccessfulRefresh(stale);
    }

    @Test
    void staleDictionaryHashReparsesExactlyOnceAndRefreshesComponent() {
        StoredSpell stale = withStringField(
                StoredSpell.fromIr(authoritativeIr(), STROKES),
                "dictionaryHash", "stale-dictionary-hash");

        assertSuccessfulRefresh(stale);
    }

    @Test
    void staleDictionaryVersionReparsesExactlyOnceAndRefreshesComponent() {
        StoredSpell stale = withStringField(
                StoredSpell.fromIr(authoritativeIr(), STROKES),
                "dictionaryVersion", "obsolete-dictionary-version");

        assertSuccessfulRefresh(stale);
    }

    @Test
    void staleRecognizerVersionReparsesExactlyOnceAndRefreshesComponent() {
        StoredSpell stale = withStringField(
                StoredSpell.fromIr(authoritativeIr(), STROKES),
                "recognizerVersion", "obsolete-recognizer");

        assertSuccessfulRefresh(stale);
    }

    @Test
    void malformedCurrentStampedPayloadReparsesInsteadOfExecuting() {
        StoredSpell source = StoredSpell.fromIr(authoritativeIr(), STROKES);
        StoredSpell malformed = new StoredSpell(
                source.formatVersion(),
                source.state(),
                List.of(),
                source.signCounts(),
                source.sigilSemantic(),
                source.signSemantics(),
                source.displayName(),
                source.strokeHash(),
                source.dictionaryVersion(),
                source.dictionaryHash(),
                source.recognizerVersion());

        assertSuccessfulRefresh(malformed);
    }

    @Test
    void invalidStaleParseResultIsRejectedAndNotCached() {
        StoredSpell stale = StoredSpell.fromIr(authoritativeIr(), OTHER_STROKES);
        AtomicInteger parseCalls = new AtomicInteger();
        SpellIr invalid = new SpellIr(
                SpellState.INVALID,
                GlyphWarning.MISSING_RING,
                List.of(),
                Map.of(),
                null,
                List.of(),
                "",
                "Missing ring");

        StoredSpellResolver.Resolution resolution = StoredSpellResolver.resolve(
                stale, STROKES, strokes -> {
                    parseCalls.incrementAndGet();
                    assertEquals(STROKES, strokes);
                    return new SpellParser.ParseResult(null, invalid);
                });

        assertAll(
                () -> assertEquals(1, parseCalls.get()),
                () -> assertTrue(resolution.reparsed()),
                () -> assertNull(resolution.ir(), "Invalid reparses must never reach execution"),
                () -> assertNull(resolution.refreshedSpell(),
                        "Invalid reparses must not replace the cached component"));
    }

    @Test
    void formatTwoRoundTripsAllExecutionSemanticsThroughToIr() {
        SpellIr original = authoritativeIr();

        StoredSpell stored = StoredSpell.fromIr(original, STROKES);
        SpellIr restored = stored.toIr();

        assertAll(
                () -> assertEquals(StoredSpell.FORMAT_VERSION, stored.formatVersion()),
                () -> assertEquals(original.state(), restored.state()),
                () -> assertEquals(original.elements(), restored.elements()),
                () -> assertEquals(original.signCounts(), restored.signCounts()),
                () -> assertEquals(original.sigilSemantic(), restored.sigilSemantic()),
                () -> assertEquals(original.signSemantics(), restored.signSemantics()),
                () -> assertEquals(original.displayName(), restored.displayName()),
                () -> assertTrue(restored.valid()));
    }

    @Test
    void persistentAndNetworkCodecsRoundTripAllCompiledFields() {
        StoredSpell expected = StoredSpell.fromIr(authoritativeIr(), STROKES);
        StoredSpell persistent = StoredSpell.CODEC.parse(
                        JsonOps.INSTANCE,
                        StoredSpell.CODEC.encodeStart(JsonOps.INSTANCE, expected)
                                .result().orElseThrow())
                .result().orElseThrow();

        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), RegistryAccess.EMPTY);
        StoredSpell network;
        try {
            StoredSpell.STREAM_CODEC.encode(buffer, expected);
            network = StoredSpell.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }

        assertAll(
                () -> assertEquals(expected, persistent),
                () -> assertEquals(expected, network));
    }

    @Test
    void malformedLegacySignIdsAreSkippedDuringSafeMigration() {
        JsonObject legacy = JsonParser.parseString("""
                {
                  "state": "ACTIVE",
                  "element": "earth",
                  "signCounts": {
                    "levitation": 3,
                    "not a valid identifier!": 7
                  },
                  "displayName": "legacy earth",
                  "strokeHash": 12345
                }
                """).getAsJsonObject();

        StoredSpell migrated = StoredSpell.CODEC.parse(JsonOps.INSTANCE, legacy)
                .result()
                .orElseThrow(() -> new AssertionError("Legacy component must decode safely"));

        assertAll(
                () -> assertTrue(migrated.formatVersion() < StoredSpell.FORMAT_VERSION,
                        "Legacy compiled data must remain explicitly stale"),
                () -> assertEquals(SpellState.ACTIVE, migrated.state()),
                () -> assertEquals(List.of(ElementType.EARTH), migrated.elements()),
                () -> assertEquals(3, migrated.signCounts().get(LEVITATION)),
                () -> assertEquals(1, migrated.signCounts().size(),
                        "Malformed legacy IDs must be dropped without losing valid entries"),
                () -> assertEquals("legacy earth", migrated.displayName()),
                () -> assertFalse(migrated.isCurrentFor(STROKES)));
    }

    @Test
    void strokeHashIncludesStrokeBoundaries() {
        Point a = new Point(0.10, 0.20);
        Point b = new Point(0.30, 0.40);
        Point c = new Point(0.50, 0.60);
        Point d = new Point(0.70, 0.80);
        List<List<Point>> split = List.of(List.of(a, b), List.of(c, d));
        List<List<Point>> joined = List.of(List.of(a, b, c, d));

        int splitHash = StoredSpell.computeStrokeHash(split);
        int joinedHash = StoredSpell.computeStrokeHash(joined);

        assertAll(
                () -> assertNotEquals(splitHash, joinedHash),
                () -> assertEquals(splitHash, StoredSpell.computeStrokeHash(split)),
                () -> assertEquals(joinedHash, StoredSpell.computeStrokeHash(joined)));
    }

    private static void assertSuccessfulRefresh(StoredSpell stale) {
        assertFalse(stale.isCurrentFor(STROKES), "Test precondition: component must be stale");
        SpellIr reparsedIr = reparsedIr();
        AtomicInteger parseCalls = new AtomicInteger();

        StoredSpellResolver.Resolution resolution = StoredSpellResolver.resolve(
                stale, STROKES, strokes -> {
                    parseCalls.incrementAndGet();
                    assertEquals(STROKES, strokes);
                    return new SpellParser.ParseResult(null, reparsedIr);
                });

        StoredSpell refreshed = resolution.refreshedSpell();
        assertAll(
                () -> assertEquals(1, parseCalls.get()),
                () -> assertTrue(resolution.reparsed()),
                () -> assertEquals(reparsedIr, resolution.ir()),
                () -> assertNotNull(refreshed),
                () -> assertTrue(refreshed.isCurrentFor(STROKES)),
                () -> assertExecutionSemantics(reparsedIr, refreshed.toIr()));
    }

    private static StoredSpell withStringField(StoredSpell spell, String field, String value) {
        JsonObject encoded = StoredSpell.CODEC.encodeStart(JsonOps.INSTANCE, spell)
                .result()
                .orElseThrow(() -> new AssertionError("Current component must encode"))
                .getAsJsonObject();
        encoded.addProperty(field, value);
        return StoredSpell.CODEC.parse(JsonOps.INSTANCE, encoded)
                .result()
                .orElseThrow(() -> new AssertionError("Mutated component must decode"));
    }

    private static SpellIr authoritativeIr() {
        Map<Identifier, Integer> signCounts = new LinkedHashMap<>();
        signCounts.put(LEVITATION, 3);
        signCounts.put(CONVERGENCE, 1);
        return new SpellIr(
                SpellState.ACTIVE,
                null,
                List.of(ElementType.EARTH, ElementType.WIND),
                signCounts,
                new SigilSemantic(0.75, 0.60, -0.10, 0.45, 0.25),
                List.of(
                        new SignSemantic("levitation", "up", 0.25, 0.30, 0.15, 0.50, 0.10),
                        new SignSemantic("levitation", "up", 0.25, 0.30, 0.15, 0.50, 0.10),
                        new SignSemantic("levitation", "up", 0.25, 0.30, 0.15, 0.50, 0.10),
                        new SignSemantic("convergence", "inward", 0.05, 0.70, -0.25, 0.10, 0.20)),
                "earth levitation",
                "ready");
    }

    private static SpellIr reparsedIr() {
        return new SpellIr(
                SpellState.ACTIVE,
                null,
                List.of(ElementType.FIRE),
                Map.of(CONVERGENCE, 2),
                new SigilSemantic(0.90, 0.55, 0.05, 0.30, 0.40),
                List.of(
                        new SignSemantic(
                                "convergence", "inward", 0.10, 0.80, -0.30, 0.15, 0.35),
                        new SignSemantic(
                                "convergence", "inward", 0.10, 0.80, -0.30, 0.15, 0.35)),
                "focused fire",
                "ready");
    }

    private static void assertExecutionSemantics(SpellIr expected, SpellIr actual) {
        assertAll(
                () -> assertEquals(expected.state(), actual.state()),
                () -> assertEquals(expected.elements(), actual.elements()),
                () -> assertEquals(expected.signCounts(), actual.signCounts()),
                () -> assertEquals(expected.sigilSemantic(), actual.sigilSemantic()),
                () -> assertEquals(expected.signSemantics(), actual.signSemantics()),
                () -> assertEquals(expected.displayName(), actual.displayName()));
    }
}
