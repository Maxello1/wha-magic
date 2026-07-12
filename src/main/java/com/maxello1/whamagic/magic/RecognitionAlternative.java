package com.maxello1.whamagic.magic;

import net.minecraft.resources.Identifier;

/**
 * Detailed scoring data for a single template match alternative.
 * Used for diagnostics and debug overlay display.
 */
public record RecognitionAlternative(
    Identifier id,
    String displayName,
    SymbolKind kind,
    double rawScore,
    double roleScore,
    double templateCoverage,
    double candidateExplainedRatio,
    double unexplainedInkRatio,
    double structuralScore,
    double rotationDeg
) {}
