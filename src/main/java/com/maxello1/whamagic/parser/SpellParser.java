package com.maxello1.whamagic.parser;

import java.util.ArrayList;
import java.util.List;

import com.maxello1.whamagic.magic.*;

public class SpellParser {

    public static class ParseResult {
        public final GlyphAst ast;
        public final SpellIr ir;
        
        public ParseResult(GlyphAst ast, SpellIr ir) {
            this.ast = ast;
            this.ir = ir;
        }

        public boolean isValidSpell() {
            return ir.valid();
        }
    }

    public static ParseResult parse(List<List<Point>> strokes) {
        if (strokes == null || strokes.isEmpty()) {
            GlyphAst emptyAst = new GlyphAst(null, null, new ArrayList<>());
            return new ParseResult(emptyAst, SpellCompiler.compile(emptyAst));
        }

        RingDetector.RingGlyph ring = RingDetector.detectRing(strokes);
        
        LayerMapper.LayeredStrokes layers = LayerMapper.mapLayers(strokes, ring);
        
        CloudRecognizer.RecognitionResult primarySigil = null;
        if (!layers.coreStrokes.isEmpty()) {
            primarySigil = CloudRecognizer.recognize(layers.coreStrokes);
        }
        
        List<CloudRecognizer.RecognitionResult> signs = new ArrayList<>();
        if (!layers.signStrokes.isEmpty()) {
            signs.add(CloudRecognizer.recognize(layers.signStrokes));
        }

        GlyphAst ast = new GlyphAst(ring, primarySigil, signs);
        SpellIr ir = SpellCompiler.compile(ast);

        return new ParseResult(ast, ir);
    }
}
