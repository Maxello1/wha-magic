package com.maxello1.whamagic.magic;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

public class SpellCompiler {
    public static SpellIr compile(GlyphAst ast) {
        if (ast.ring() == null) {
            String msg = ast.primarySigil() != null ? "Drafting: " + ast.primarySigil().displayName : "Drafting: Needs Ring";
            return new SpellIr(false, false, false, null, null, msg);
        }
        
        if (ast.primarySigil() == null || !ast.primarySigil().recognized) {
            return new SpellIr(false, false, false, null, null, "Drafting: Missing Core Sigil");
        }
        
        boolean prepared = ast.ring().completeness() > 0.5 && !ast.ring().isClosed();
        boolean active = ast.ring().isClosed();
        
        String element = ast.primarySigil().element != null ? ast.primarySigil().element : ast.primarySigil().id;
        
        Map<String, Integer> signCounts = new LinkedHashMap<>();
        if (ast.signs() != null) {
            for (RecognizedSign sign : ast.signs()) {
                signCounts.put(sign.id(), signCounts.getOrDefault(sign.id(), 0) + 1);
            }
        }
        
        String compiledSpellString = element;
        if (!signCounts.isEmpty()) {
            compiledSpellString += "[" + signCounts.entrySet().stream()
                .map(e -> e.getKey() + " x" + e.getValue())
                .collect(Collectors.joining(", ")) + "]";
        }
        
        String statusMessage = (active ? "Active: " : "Prepared: ") + compiledSpellString;
        
        return new SpellIr(true, active, prepared, element, compiledSpellString, statusMessage);
    }
}
