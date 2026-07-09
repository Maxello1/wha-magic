package com.maxello1.whamagic.magic;

public class SpellCompiler {
    public static SpellIr compile(GlyphAst ast) {
        if (ast.ring() == null) {
            String msg = ast.primarySigil() != null ? "Drafting: " + ast.primarySigil().displayName : "Drafting: Needs Ring";
            return new SpellIr(false, false, false, null, null, 0.0, msg);
        }
        
        if (ast.primarySigil() == null || !ast.primarySigil().recognized) {
            return new SpellIr(false, false, false, null, null, 0.0, "Drafting: Missing Core Sigil");
        }
        
        boolean prepared = ast.ring().completeness() > 0.5 && !ast.ring().isClosed();
        boolean active = ast.ring().isClosed();
        
        String element = ast.primarySigil().element != null ? ast.primarySigil().element : ast.primarySigil().id;
        
        // Find primary manifestation from signs
        String manifestation = null;
        for (var sign : ast.signs()) {
            if (sign != null && sign.recognized) {
                manifestation = sign.element != null ? sign.element : sign.id;
                break; // Just take the first valid sign for MVP
            }
        }
        
        if (manifestation == null) {
            return new SpellIr(true, active, prepared, element, "basic", 1.0, 
                (active ? "Active: " : "Prepared: ") + element + " Spell");
        }
        
        double power = 1.0 + (ast.ring().radius() / 100.0); // Simple power scaling
        
        return new SpellIr(true, active, prepared, element, manifestation, power, 
            (active ? "Active: " : "Prepared: ") + element + " " + manifestation);
    }
}
