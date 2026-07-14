package com.maxello1.whamagic.dev;

import com.maxello1.whamagic.magic.SymbolKind;
import com.maxello1.whamagic.parser.SpellDictionary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleIntentResolverTest {

    private final SampleIntentResolver resolver = new SampleIntentResolver(SpellDictionary.snapshot());

    @Test
    void resolvesDisplayNamesSemanticIdsAndCaseInsensitively() {
        List<RecognitionSampleMetadata.IntendedSymbol> symbols =
                resolver.parse("Wind (Directs Air), fIrE, WHA-MAGIC:column");

        assertEquals("wha-magic:wind-directs-air", symbols.get(0).id().toString());
        assertEquals(SymbolKind.SIGIL, symbols.get(0).kind());
        assertEquals("wha-magic:fire", symbols.get(1).id().toString());
        assertEquals("wha-magic:column", symbols.get(2).id().toString());
        assertEquals(SymbolKind.SIGN, symbols.get(2).kind());
    }

    @Test
    void preservesDuplicateInstancesAndNormalizesRotations() {
        List<RecognitionSampleMetadata.IntendedSymbol> symbols =
                resolver.parse("Earth, Levitation@-180, levitation@540, Fire@450");

        assertEquals(4, symbols.size());
        assertEquals(symbols.get(1).id(), symbols.get(2).id());
        assertEquals(180.0, symbols.get(1).rotationDeg());
        assertEquals(180.0, symbols.get(2).rotationDeg());
        assertEquals(90.0, symbols.get(3).rotationDeg());
    }

    @Test
    void reportsUnknownAndAmbiguousNames() {
        IllegalArgumentException unknown = assertThrows(
                IllegalArgumentException.class, () -> resolver.parse("Not a symbol"));
        assertTrue(unknown.getMessage().contains("Unknown"));

        SpellDictionary.DictionarySnapshot ambiguousDictionary = new SpellDictionary.DictionarySnapshot(
                "test", "hash", List.of(
                new SpellDictionary.TemplateIdentity("fire", "fire-a", "Shared", SymbolKind.SIGIL),
                new SpellDictionary.TemplateIdentity("column", "column-a", "Shared", SymbolKind.SIGN)));
        SampleIntentResolver ambiguousResolver = new SampleIntentResolver(ambiguousDictionary);

        IllegalArgumentException ambiguous = assertThrows(
                IllegalArgumentException.class, () -> ambiguousResolver.parse("shared"));
        assertTrue(ambiguous.getMessage().contains("Ambiguous"));
        assertTrue(ambiguous.getMessage().contains("wha-magic:fire"));
        assertTrue(ambiguous.getMessage().contains("wha-magic:column"));
    }

    @Test
    void offersDisplayNameCompletions() {
        assertEquals("re", resolver.suggestionSuffix("Fi"));
    }
}
