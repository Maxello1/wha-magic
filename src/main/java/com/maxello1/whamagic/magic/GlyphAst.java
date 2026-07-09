package com.maxello1.whamagic.magic;

import com.maxello1.whamagic.parser.CloudRecognizer.RecognitionResult;
import java.util.List;

public record GlyphAst(
    RingDetector.RingGlyph ring,
    RecognitionResult primarySigil,
    List<RecognitionResult> signs
) {}
