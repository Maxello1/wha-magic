package com.maxello1.whamagic.magic;

import java.util.List;

public record GlyphAst(
    RingDetector.RingGlyph ring,
    List<RecognizedSigil> sigils,
    List<RecognizedSign> signs,
    List<UnknownSymbol> unknownSymbols,
    List<ClassifiedUnknownInk> unknownInk
) {
    public GlyphAst {
        sigils = sigils == null ? List.of() : List.copyOf(sigils);
        signs = signs == null ? List.of() : List.copyOf(signs);
        unknownSymbols = unknownSymbols == null ? List.of() : List.copyOf(unknownSymbols);
        unknownInk = unknownInk == null ? List.of() : List.copyOf(unknownInk);
        if (unknownInk.isEmpty() && !unknownSymbols.isEmpty()) {
            unknownInk = unknownSymbols.stream()
                    .map(symbol -> new ClassifiedUnknownInk(
                            symbol.classification(), symbol.candidateId(),
                            symbol.sourceStrokeIndices(), symbol.bounds(), symbol.rejectionReason()))
                    .toList();
        }
    }

    public GlyphAst(RingDetector.RingGlyph ring,
                    List<RecognizedSigil> sigils,
                    List<RecognizedSign> signs,
                    List<UnknownSymbol> unknownSymbols) {
        this(ring, sigils, signs, unknownSymbols, List.of());
    }
}
