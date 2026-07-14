package com.maxello1.whamagic.dev;

import com.maxello1.whamagic.magic.SymbolKind;
import com.maxello1.whamagic.parser.SpellDictionary;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Resolves human-entered sample intent against immutable dictionary metadata. */
public final class SampleIntentResolver {
    private static final String DEFAULT_NAMESPACE = "wha-magic";

    private final Map<String, List<SymbolDescriptor>> aliases;
    private final List<String> suggestions;

    public SampleIntentResolver(SpellDictionary.DictionarySnapshot dictionary) {
        Map<Identifier, SymbolDescriptor> symbols = new LinkedHashMap<>();
        for (SpellDictionary.TemplateIdentity template : dictionary.templates()) {
            Identifier id = semanticIdentifier(template.semanticId());
            SymbolDescriptor descriptor = new SymbolDescriptor(id, template.displayName(), template.kind());
            SymbolDescriptor previous = symbols.putIfAbsent(id, descriptor);
            if (previous != null && (!previous.displayName().equals(descriptor.displayName())
                    || previous.kind() != descriptor.kind())) {
                throw new IllegalArgumentException("Inconsistent dictionary metadata for " + id);
            }
        }

        Map<String, Set<SymbolDescriptor>> mutableAliases = new LinkedHashMap<>();
        LinkedHashSet<String> suggestionValues = new LinkedHashSet<>();
        for (SymbolDescriptor symbol : symbols.values()) {
            addAlias(mutableAliases, symbol.id().toString(), symbol);
            addAlias(mutableAliases, symbol.id().getPath(), symbol);
            addAlias(mutableAliases, symbol.displayName(), symbol);
            suggestionValues.add(symbol.displayName());
            suggestionValues.add(symbol.id().toString());
        }
        Map<String, List<SymbolDescriptor>> completedAliases = new LinkedHashMap<>();
        mutableAliases.forEach((key, value) -> completedAliases.put(key, List.copyOf(value)));
        aliases = Map.copyOf(completedAliases);
        suggestions = suggestionValues.stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public List<RecognitionSampleMetadata.IntendedSymbol> parse(String text) {
        if (text == null || text.isBlank()) return List.of();
        String[] entries = text.split(",", -1);
        List<RecognitionSampleMetadata.IntendedSymbol> resolved = new ArrayList<>(entries.length);
        for (int index = 0; index < entries.length; index++) {
            String entry = entries[index].trim();
            if (entry.isEmpty()) {
                throw new IllegalArgumentException("Intended symbol " + (index + 1) + " is empty.");
            }
            resolved.add(resolveEntry(entry));
        }
        return List.copyOf(resolved);
    }

    /** Returns an inline completion suffix for the final comma-separated entry. */
    public String suggestionSuffix(String text) {
        String value = text == null ? "" : text;
        int separator = value.lastIndexOf(',');
        String current = value.substring(separator + 1).stripLeading();
        if (current.isEmpty() || current.contains("@")) return null;
        String match = suggestions.stream()
                .filter(suggestion -> suggestion.regionMatches(true, 0, current, 0, current.length()))
                .min(Comparator.comparingInt(String::length).thenComparing(String.CASE_INSENSITIVE_ORDER))
                .orElse(null);
        return match == null || match.length() == current.length()
                ? null
                : match.substring(current.length());
    }

    public List<String> suggestions() {
        return suggestions;
    }

    private RecognitionSampleMetadata.IntendedSymbol resolveEntry(String entry) {
        int at = entry.lastIndexOf('@');
        if (at != entry.indexOf('@')) {
            throw new IllegalArgumentException("Invalid rotation syntax in '" + entry + "'.");
        }
        String name = at < 0 ? entry : entry.substring(0, at).trim();
        Double rotation = null;
        if (at >= 0) {
            String rotationText = entry.substring(at + 1).trim();
            if (name.isEmpty() || rotationText.isEmpty()) {
                throw new IllegalArgumentException("Use Name@degrees for intended rotation.");
            }
            try {
                rotation = Double.valueOf(rotationText);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid rotation '" + rotationText + "'.");
            }
            if (!Double.isFinite(rotation)) {
                throw new IllegalArgumentException("Rotation must be finite.");
            }
        }

        List<SymbolDescriptor> matches = aliases.get(key(name));
        if (matches == null || matches.isEmpty()) {
            throw new IllegalArgumentException("Unknown intended symbol '" + name + "'.");
        }
        if (matches.size() > 1) {
            String ids = matches.stream().map(symbol -> symbol.id().toString()).sorted().reduce((a, b) -> a + ", " + b).orElse("");
            throw new IllegalArgumentException("Ambiguous intended symbol '" + name + "': " + ids + ".");
        }
        SymbolDescriptor symbol = matches.get(0);
        return new RecognitionSampleMetadata.IntendedSymbol(symbol.id(), symbol.kind(), rotation);
    }

    private static void addAlias(
            Map<String, Set<SymbolDescriptor>> aliases,
            String alias,
            SymbolDescriptor symbol) {
        aliases.computeIfAbsent(key(alias), ignored -> new LinkedHashSet<>()).add(symbol);
    }

    private static String key(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static Identifier semanticIdentifier(String rawId) {
        String value = rawId.contains(":") ? rawId : DEFAULT_NAMESPACE + ':' + rawId;
        Identifier id = Identifier.tryParse(value);
        if (id == null) throw new IllegalArgumentException("Invalid semantic symbol ID '" + rawId + "'.");
        return id;
    }

    private record SymbolDescriptor(Identifier id, String displayName, SymbolKind kind) {}
}
