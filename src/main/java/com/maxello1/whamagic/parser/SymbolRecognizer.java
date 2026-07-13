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
 * <p>Lifecycle:</p>
 * <ol>
 *   <li>{@link #clearTemplates()} — reset before loading</li>
 *   <li>{@link #registerTemplate} — load all templates from dictionary</li>
 *   <li>{@link #recognize} — called per candidate during spell parsing</li>
 * </ol>
 */
public interface SymbolRecognizer {

    /** Human-readable name for logging and benchmark reports. */
    String name();

    /** Remove all registered templates. Called before (re-)loading the dictionary. */
    void clearTemplates();

    /** Register a template from stroke data. */
    void registerTemplate(String id, String displayName,
                          SymbolKind kind, String element,
                          List<List<Point>> strokes,
                          com.maxello1.whamagic.magic.SigilSemantic sigilSemantic,
                          com.maxello1.whamagic.magic.SignSemantic signSemantic,
                          com.maxello1.whamagic.magic.SymbolRecognitionRules recognitionRules);

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
