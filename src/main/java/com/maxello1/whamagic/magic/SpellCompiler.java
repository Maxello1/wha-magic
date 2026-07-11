package com.maxello1.whamagic.magic;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

public class SpellCompiler {
    public static SpellIr compile(GlyphAst ast) {
        if (ast.ring() == null) {
            String msg = (ast.sigils() != null && !ast.sigils().isEmpty()) ? "Drafting: " + ast.sigils().get(0).id().getPath() : "Drafting: Needs Ring";
            return new SpellIr(SpellState.INVALID, GlyphWarning.MISSING_RING, List.of(), Map.of(), null, List.of(), "", msg);
        }
        
        if (ast.sigils() == null || ast.sigils().isEmpty()) {
            return new SpellIr(SpellState.INVALID, GlyphWarning.MISSING_CORE_SIGIL, List.of(), Map.of(), null, List.of(), "", "Drafting: Missing Core Sigil");
        }
        
        List<ElementType> elements = new ArrayList<>();
        List<String> displayNames = new ArrayList<>();
        SigilSemantic primarySemantic = null;
        
        for (RecognizedSigil sigil : ast.sigils()) {
            if (sigil.element() != null) {
                elements.add(sigil.element());
            }
            displayNames.add(sigil.alternatives().isEmpty() ? sigil.id().getPath() : sigil.alternatives().get(0).displayName());
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
        
        Map<net.minecraft.resources.Identifier, Integer> signCounts = new LinkedHashMap<>();
        List<SignSemantic> signSemantics = new ArrayList<>();
        boolean invalidSigns = false;
        
        if (ast.signs() != null) {
            for (RecognizedSign sign : ast.signs()) {
                if (sign.semantic() != null) {
                    net.minecraft.resources.Identifier signId = net.minecraft.resources.Identifier.tryParse(sign.id());
                    if (signId != null) {
                        signCounts.put(signId, signCounts.getOrDefault(signId, 0) + 1);
                    }
                    signSemantics.add(sign.semantic());
                } else {
                    invalidSigns = true;
                }
            }
        }
        
        if (invalidSigns && warning == null) {
            warning = GlyphWarning.INVALID_SIGNS;
        }
        
        String displayName = String.join(" + ", displayNames);
        if (!signCounts.isEmpty()) {
            displayName += " [" + signCounts.entrySet().stream()
                .map(e -> e.getKey().getPath() + " x" + e.getValue())
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
        
        return new SpellIr(state, warning, elements, signCounts, primarySemantic, signSemantics, displayName, statusMessage);
    }
}
