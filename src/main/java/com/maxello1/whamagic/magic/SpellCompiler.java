package com.maxello1.whamagic.magic;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

public class SpellCompiler {
    public static SpellIr compile(GlyphAst ast) {
        return compile(ast, true);
    }

    /**
     * Compile an AST and fail closed when any bounded recognition search was incomplete.
     * The partial interpretation is retained for diagnostics, but can never be saved or cast.
     */
    public static SpellIr compile(GlyphAst ast, boolean recognitionComplete) {
        SpellIr result = compileComplete(ast);
        if (recognitionComplete) {
            return result;
        }

        String detail = result.displayName() == null || result.displayName().isEmpty()
                ? "Incomplete recognition search"
                : result.displayName() + " (incomplete recognition search)";
        return new SpellIr(
                SpellState.INVALID,
                GlyphWarning.INCOMPLETE_RECOGNITION,
                result.elements(),
                result.signCounts(),
                result.sigilSemantic(),
                result.signSemantics(),
                result.displayName(),
                "Invalid: " + detail);
    }

    private static SpellIr compileComplete(GlyphAst ast) {
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
            if (primarySemantic == null && sigil.semantic() != null) {
                primarySemantic = sigil.semantic();
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

        boolean hasAmbiguousInk = ast.unknownInk().stream()
                .anyMatch(ink -> ink.classification() == UnknownInkClassification.AMBIGUOUS);
        boolean hasSubstantialUnknownInk = ast.unknownInk().stream()
                .anyMatch(ink -> ink.classification() == UnknownInkClassification.SUBSTANTIAL_UNKNOWN);
        boolean hasBudgetSkippedInk = ast.unknownInk().stream()
                .anyMatch(ink -> ink.classification() == UnknownInkClassification.BUDGET_SKIPPED);
        if (hasAmbiguousInk || hasSubstantialUnknownInk || hasBudgetSkippedInk) {
            state = SpellState.INVALID;
            warning = hasAmbiguousInk
                    ? GlyphWarning.AMBIGUOUS_INK
                    : hasBudgetSkippedInk
                        ? GlyphWarning.INCOMPLETE_RECOGNITION
                        : GlyphWarning.SUBSTANTIAL_UNKNOWN_INK;
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
