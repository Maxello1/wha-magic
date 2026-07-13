package com.maxello1.whamagic.parser;

import com.maxello1.whamagic.magic.SigilSemantic;
import com.maxello1.whamagic.magic.SignSemantic;
import com.maxello1.whamagic.magic.SymbolKind;
import com.maxello1.whamagic.magic.SymbolRecognitionRules;

import java.util.List;
import java.util.Objects;

/** Parsed template definition used to build recognizer-specific state. */
record DictionaryTemplate(
        String semanticId,
        String templateId,
        String displayName,
        SymbolKind kind,
        String element,
        List<List<Point>> strokes,
        SigilSemantic sigilSemantic,
        SignSemantic signSemantic,
        SymbolRecognitionRules recognitionRules
) {
    DictionaryTemplate {
        semanticId = Objects.requireNonNull(semanticId, "semanticId");
        templateId = Objects.requireNonNull(templateId, "templateId");
        displayName = Objects.requireNonNull(displayName, "displayName");
        kind = Objects.requireNonNull(kind, "kind");
        strokes = Objects.requireNonNull(strokes, "strokes").stream()
                .map(List::copyOf)
                .toList();
        recognitionRules = Objects.requireNonNull(recognitionRules, "recognitionRules");
    }
}
