package com.maxello1.whamagic.magic;

import java.util.List;

public record GlyphAst(
    RingDetector.RingGlyph ring,
    List<RecognizedSigil> sigils,
    List<RecognizedSign> signs,
    List<UnknownSymbol> unknownSymbols
) {}
