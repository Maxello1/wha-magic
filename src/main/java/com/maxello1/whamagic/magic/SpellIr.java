package com.maxello1.whamagic.magic;

import net.minecraft.resources.Identifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable compiled representation consumed by spell execution and persistence. */
public record SpellIr(
        SpellState state,
        GlyphWarning warning,
        List<CompiledSigil> compiledSigils,
        List<CompiledSign> compiledSigns,
        SpellGeometry geometry,
        SpellQuality quality,
        SpellParameters parameters,
        String displayName,
        String statusMessage
) {
    public SpellIr {
        compiledSigils = compiledSigils == null ? List.of() : List.copyOf(compiledSigils);
        compiledSigns = compiledSigns == null ? List.of() : List.copyOf(compiledSigns);
        quality = quality == null ? SpellQuality.UNASSESSED : quality;
        parameters = parameters == null ? SpellParameters.NEUTRAL : parameters;
        displayName = displayName == null ? "" : displayName;
        statusMessage = statusMessage == null ? "" : statusMessage;
    }

    /**
     * Compatibility constructor for drafts and legacy cache adapters that have not
     * yet been assessed. Authoritative compilation supplies quality and parameters.
     */
    public SpellIr(
            SpellState state,
            GlyphWarning warning,
            List<CompiledSigil> compiledSigils,
            List<CompiledSign> compiledSigns,
            SpellGeometry geometry,
            String displayName,
            String statusMessage) {
        this(
                state,
                warning,
                compiledSigils,
                compiledSigns,
                geometry,
                SpellQuality.UNASSESSED,
                SpellParameters.NEUTRAL,
                displayName,
                statusMessage);
    }

    /** Temporary execution compatibility view derived from compiled sigils. */
    public List<ElementType> elements() {
        return compiledSigils.stream().map(CompiledSigil::element).toList();
    }

    /** Temporary execution compatibility view preserving first-seen sign order. */
    public Map<Identifier, Integer> signCounts() {
        Map<Identifier, Integer> counts = new LinkedHashMap<>();
        for (CompiledSign sign : compiledSigns) {
            counts.merge(sign.semanticId(), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(counts);
    }

    /** Temporary execution compatibility view: the first compiled sigil is primary. */
    public SigilSemantic sigilSemantic() {
        return compiledSigils.isEmpty() ? null : compiledSigils.getFirst().semantic();
    }

    /** Temporary execution compatibility view retaining sign order and multiplicity. */
    public List<SignSemantic> signSemantics() {
        return compiledSigns.stream().map(CompiledSign::semantic).toList();
    }

    public boolean valid() {
        return state == SpellState.PREPARED || state == SpellState.ACTIVE;
    }
}
