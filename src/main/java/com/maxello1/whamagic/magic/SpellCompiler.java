package com.maxello1.whamagic.magic;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

public class SpellCompiler {
    public static SpellIr compile(GlyphAst ast) {
        if (ast.ring() == null) {
            String msg = ast.primarySigil() != null ? "Drafting: " + ast.primarySigil().displayName : "Drafting: Needs Ring";
            return new SpellIr(SpellState.INVALID, GlyphWarning.MISSING_RING, null, null, null, null, null, msg);
        }
        
        if (ast.primarySigil() == null) {
            return new SpellIr(SpellState.INVALID, GlyphWarning.MISSING_CORE_SIGIL, null, null, null, null, null, "Drafting: Missing Core Sigil");
        }
        
        if (!ast.primarySigil().recognized) {
            return new SpellIr(SpellState.INVALID, GlyphWarning.UNRECOGNIZED_CORE_SIGIL, null, null, null, null, null, "Drafting: Unrecognized Core Sigil");
        }
        
        boolean isClosed = ast.ring().isClosed();
        boolean isStrong = ast.ring().completeness() > 0.5;
        
        SpellState state = SpellState.DRAFT;
        GlyphWarning warning = null;
        
        if (isClosed) {
            state = SpellState.ACTIVE;
        } else if (isStrong) {
            state = SpellState.PREPARED;
        } else {
            warning = GlyphWarning.WEAK_RING;
        }
        
        String element = ast.primarySigil().element != null ? ast.primarySigil().element : ast.primarySigil().id;
        
        Map<String, Integer> signCounts = new LinkedHashMap<>();
        List<SignSemantic> signSemantics = new ArrayList<>();
        boolean invalidSigns = false;
        
        if (ast.signs() != null) {
            for (RecognizedSign sign : ast.signs()) {
                if (sign.semantic() != null) {
                    signCounts.put(sign.id(), signCounts.getOrDefault(sign.id(), 0) + 1);
                    signSemantics.add(sign.semantic());
                } else {
                    invalidSigns = true;
                }
            }
        }
        
        if (invalidSigns && warning == null) {
            warning = GlyphWarning.INVALID_SIGNS;
        }
        
        String displayName = ast.primarySigil().displayName;
        if (!signCounts.isEmpty()) {
            displayName += " [" + signCounts.entrySet().stream()
                .map(e -> e.getKey() + " x" + e.getValue())
                .collect(Collectors.joining(", ")) + "]";
        }
        
        String statusPrefix = "";
        switch (state) {
            case ACTIVE: statusPrefix = "Active: "; break;
            case PREPARED: statusPrefix = "Prepared: "; break;
            case DRAFT: statusPrefix = "Drafting: "; break;
            case INVALID: statusPrefix = "Invalid: "; break;
        }
        
        String statusMessage = statusPrefix + displayName;
        if (warning != null) {
            statusMessage += " (" + warning.name() + ")";
        }
        
        return new SpellIr(state, warning, element, signCounts, ast.primarySigil().sigilSemantic, signSemantics, displayName, statusMessage);
    }
}
