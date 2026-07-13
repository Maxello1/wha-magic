/*
 * WHA Magic — Symbol Recognizer Interface
 *
 * Copyright (c) 2026 Maxello1.
 * Licensed under the WHA Magic Restricted Use License.
 */
package com.maxello1.whamagic.parser;

import com.maxello1.whamagic.magic.SymbolKind;
import com.maxello1.whamagic.magic.SymbolRecognitionResult;

import java.util.List;

/**
 * Interface for symbol recognition implementations.
 *
 * <p>Abstracts the recognition algorithm so that different implementations
 * (Raster, $P, $P+, and future $Q) can be swapped in SelectionEngine
 * without code changes.</p>
 *
 * <p>Recognizer implementations consume the immutable active dictionary
 * snapshot and never mutate template state during recognition.</p>
 */
public interface SymbolRecognizer {

    /** Human-readable name for logging and benchmark reports. */
    String name();

    /** How many templates are loaded. */
    int getTemplateCount();

    /**
     * Recognize a candidate drawing against templates of the given kind.
     *
     * @param strokes       the player's drawn strokes for this candidate
     * @param expectedKind  SIGIL or SIGN — only templates of this kind are tested
     * @return recognizer-neutral result consumed by SelectionEngine
     */
    SymbolRecognitionResult recognize(List<List<Point>> strokes, SymbolKind expectedKind);
}
