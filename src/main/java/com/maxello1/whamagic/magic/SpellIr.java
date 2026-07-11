package com.maxello1.whamagic.magic;

import java.util.List;
import java.util.Map;

public record SpellIr(
    SpellState state,
    GlyphWarning warning,
    String element,
    Map<String, Integer> signCounts,
    SigilSemantic sigilSemantic,
    List<SignSemantic> signSemantics,
    String displayName,
    String statusMessage
) {
    public boolean valid() {
        return state == SpellState.PREPARED || state == SpellState.ACTIVE;
    }
}
