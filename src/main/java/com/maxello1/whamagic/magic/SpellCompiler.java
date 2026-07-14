package com.maxello1.whamagic.magic;

import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class SpellCompiler {
    private SpellCompiler() {}

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
            String message = ast.sigils().isEmpty()
                    ? "Drafting: Needs Ring"
                    : "Drafting: " + ast.sigils().get(0).id().getPath();
            return new SpellIr(
                    SpellState.INVALID, GlyphWarning.MISSING_RING,
                    List.of(), Map.of(), null, List.of(), "", message);
        }
        
        if (ast.sigils().isEmpty()) {
            return new SpellIr(
                    SpellState.INVALID, GlyphWarning.MISSING_CORE_SIGIL,
                    List.of(), Map.of(), null, List.of(), "",
                    "Drafting: Missing Core Sigil");
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
            displayNames.add(sigil.displayName());
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
        
        Map<Identifier, Integer> signCounts = new LinkedHashMap<>();
        List<SignSemantic> signSemantics = new ArrayList<>();
        boolean invalidSigns = false;
        
        for (RecognizedSign sign : ast.signs()) {
            if (sign.semantic() != null) {
                Identifier signId = Identifier.tryParse(sign.id());
                if (signId != null) {
                    signCounts.merge(signId, 1, Integer::sum);
                }
                signSemantics.add(sign.semantic());
            } else {
                invalidSigns = true;
            }
        }
        
        if (invalidSigns && warning == null) {
            warning = GlyphWarning.INVALID_SIGNS;
        }

        boolean hasAmbiguousInk = false;
        boolean hasSubstantialUnknownInk = false;
        boolean hasBudgetSkippedInk = false;
        for (ClassifiedUnknownInk ink : ast.unknownInk()) {
            UnknownInkClassification classification = ink.classification();
            if (classification == null) continue;
            switch (classification) {
                case AMBIGUOUS -> hasAmbiguousInk = true;
                case SUBSTANTIAL_UNKNOWN -> hasSubstantialUnknownInk = true;
                case BUDGET_SKIPPED -> hasBudgetSkippedInk = true;
                default -> {
                }
            }
        }
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
        
        String statusPrefix = switch (state) {
            case ACTIVE -> "Active: ";
            case PREPARED -> "Prepared: ";
            case DRAFT -> "Drafting: ";
            case INVALID -> "Invalid: ";
        };
        
        String statusMessage = statusPrefix + displayName;
        if (warning != null) {
            statusMessage += " (" + warning.name() + ")";
        }
        
        return new SpellIr(
                state, warning, elements, signCounts, primarySemantic,
                signSemantics, displayName, statusMessage);
    }
}
