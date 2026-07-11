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

        RingDetector.RingDetection ringDetection = RingDetector.detectRing(strokes);
        RingDetector.RingGlyph ring = ringDetection != null ? ringDetection.glyph() : null;
        
        LayerMapper.LayeredStrokes layers = LayerMapper.mapLayers(strokes, ringDetection);
        
        RasterRecognizer.RecognitionResult primarySigil = null;
        if (!layers.coreStrokes.isEmpty()) {
            primarySigil = RasterRecognizer.recognize(layers.coreStrokes, "sigil", 0);
        }
        
        List<RecognizedSign> signs = new ArrayList<>();
        if (!layers.signStrokes.isEmpty()) {
            List<SymbolCandidateGrouper.SymbolCandidate> candidates = SymbolCandidateGrouper.groupSigns(layers.signStrokes, ring);
            for (SymbolCandidateGrouper.SymbolCandidate candidate : candidates) {
                double baseAngle = candidate.angleAroundRing();
                
                RasterRecognizer.RecognitionResult bestRes = null;
                double bestAngle = 0;
                double[] offsets = {0, 15, -15, 30, -30, 180};
                for (double offset : offsets) {
                    double angleToTest = (baseAngle + offset) % 360;
                    if (angleToTest < 0) angleToTest += 360;
                    
                    RasterRecognizer.RecognitionResult res = RasterRecognizer.recognize(candidate.strokes(), "sign", angleToTest);
                    if (bestRes == null || res.score > bestRes.score) {
                        bestRes = res;
                        bestAngle = angleToTest;
                    }
                }
                
                if (bestRes != null && bestRes.recognized) {
                    signs.add(new RecognizedSign(bestRes.id, bestRes.score, candidate.angleAroundRing(), bestAngle, candidate.layer(), bestRes.signSemantic));
                }
            }
        }

        GlyphAst ast = new GlyphAst(ring, primarySigil, signs);
        SpellIr ir = SpellCompiler.compile(ast);

        return new ParseResult(ast, ir);
    }
}
