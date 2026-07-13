package com.maxello1.whamagic.magic;

import net.minecraft.resources.Identifier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SpellIr(
    SpellState state,
    GlyphWarning warning,
    List<ElementType> elements,
    Map<Identifier, Integer> signCounts,
    SigilSemantic sigilSemantic,
    List<SignSemantic> signSemantics,
    String displayName,
    String statusMessage
) {
    public SpellIr {
        elements = elements == null ? List.of() : List.copyOf(elements);
        signCounts = signCounts == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(signCounts));
        signSemantics = signSemantics == null ? List.of() : List.copyOf(signSemantics);
    }

    public boolean valid() {
        return state == SpellState.PREPARED || state == SpellState.ACTIVE;
    }
}
