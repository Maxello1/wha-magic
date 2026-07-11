package com.maxello1.whamagic.parser;

import java.util.ArrayList;
import java.util.List;

import com.maxello1.whamagic.magic.*;

public class SpellParser {

    public static class ParseResult {
        public final GlyphAst ast;
        public final SpellIr ir;
        public final SegmentationDebugResult debugResult;
        
        public ParseResult(GlyphAst ast, SpellIr ir) {
            this.ast = ast;
            this.ir = ir;
            this.debugResult = null;
        }

        public ParseResult(GlyphAst ast, SpellIr ir, SegmentationDebugResult debugResult) {
            this.ast = ast;
            this.ir = ir;
            this.debugResult = debugResult;
        }

        public boolean isValidSpell() {
            return ir.valid();
        }
    }

    public static ParseResult parse(List<List<Point>> strokes) {
        if (strokes == null || strokes.isEmpty()) {
            GlyphAst emptyAst = new GlyphAst(null, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            return new ParseResult(emptyAst, SpellCompiler.compile(emptyAst));
        }

        RingDetector.RingDetection ringDetection = RingDetector.detectRing(strokes);
        RingDetector.RingGlyph ring = ringDetection != null ? ringDetection.glyph() : null;
        
        List<List<Point>> nonRingStrokes = new ArrayList<>();
        if (ringDetection != null) {
            for (int i = 0; i < strokes.size(); i++) {
                if (!ringDetection.ringStrokeIndices().contains(i)) {
                    nonRingStrokes.add(strokes.get(i));
                }
            }
        } else {
            nonRingStrokes = strokes;
        }
        
        CandidateGenerationSettings settings = CandidateGenerationSettings.DEFAULTS;
        List<SymbolCandidate> candidates = CandidateGenerator.generateCandidates(nonRingStrokes, ring, settings);
        
        SelectionEngine.SelectedSymbols selection = SelectionEngine.select(candidates, ring, settings.maxRecognitionCalls());
        
        SegmentationDebugResult debugResult = new SegmentationDebugResult(
            new ArrayList<>(), // primitives
            candidates,
            selection.selectedCandidates(),
            selection.recognitionCalls(),
            false
        );
        
        GlyphAst ast = new GlyphAst(ring, selection.sigils(), selection.signs(), selection.unknowns());
        SpellIr ir = SpellCompiler.compile(ast);

        return new ParseResult(ast, ir, debugResult);
    }
}
