package com.maxello1.whamagic.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

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
        return parse(strokes, CandidateGenerationSettings.DEFAULTS);
    }

    /** Parse with explicit candidate and recognition limits, primarily for deterministic tests. */
    public static ParseResult parse(List<List<Point>> strokes, CandidateGenerationSettings settings) {
        if (strokes == null || strokes.isEmpty()) {
            GlyphAst emptyAst = new GlyphAst(null, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            return new ParseResult(emptyAst, SpellCompiler.compile(emptyAst));
        }

        RingDetector.RingSearchResult ringSearch = RingDetector.searchRing(strokes);
        RingDetector.RingDetection ringDetection = ringSearch.detection();
        RingDetector.RingSearchDiagnostics ringDiagnostics = ringSearch.diagnostics();
        RingDetector.RingGlyph ring = ringDetection != null ? ringDetection.glyph() : null;

        List<IndexedStroke> nonRingStrokes = new ArrayList<>();
        for (int i = 0; i < strokes.size(); i++) {
            if (ringDetection == null || !ringDetection.ringStrokeIndices().contains(i)) {
                nonRingStrokes.add(new IndexedStroke(i, strokes.get(i)));
            }
        }

        CandidateGenerator.GenerationResult genResult = CandidateGenerator.generateCandidates(nonRingStrokes, ring, settings);

        SelectionEngine.SelectedSymbols selection = SelectionEngine.select(genResult.candidates(), ring, settings.maxRecognitionCalls());

        // Anything not owned by a selected symbol/unknown remains visible as dropped
        // input. Ring strokes are intentionally excluded, and ring-prefiltered strokes
        // still participate in symbol recognition.
        TreeSet<Integer> droppedSourceStrokeIndices = new TreeSet<>();
        for (IndexedStroke stroke : nonRingStrokes) {
            droppedSourceStrokeIndices.add(stroke.originalIndex());
        }
        selection.sigils().forEach(sigil -> droppedSourceStrokeIndices.removeAll(sigil.sourceStrokeIndices()));
        selection.signs().forEach(sign -> droppedSourceStrokeIndices.removeAll(sign.sourceStrokeIndices()));
        selection.unknowns().forEach(unknown -> droppedSourceStrokeIndices.removeAll(unknown.sourceStrokeIndices()));

        List<Integer> ringStrokeIndices = ringDetection == null
                ? List.of()
                : ringDetection.ringStrokeIndices().stream().sorted().toList();

        SegmentationDebugResult debugResult = new SegmentationDebugResult(
            genResult.primitiveGroups(),
            genResult.candidates(),
            selection.selectedCandidates(),
            selection.recognitionCalls(),
            genResult.candidateLimitReached(),
            ringDiagnostics.budgetExhausted(),
            selection.recognitionBudgetExhausted(),
            List.copyOf(droppedSourceStrokeIndices),
            selection.unevaluatedCandidateCount(),
            ringDiagnostics.combinationsConsidered(),
            ringDiagnostics.fitsAttempted(),
            ringDiagnostics.elapsedNanos(),
            ringStrokeIndices,
            selection.sigils(),
            selection.signs(),
            selection.unknowns(),
            genResult.primitiveGroups().size(),
            genResult.candidates().size(),
            selection.selectedCandidates().size(),
            selection.allEvaluated()
        );
        
        boolean recognitionComplete = !ringDiagnostics.budgetExhausted()
                && !genResult.candidateLimitReached()
                && !selection.recognitionBudgetExhausted();
        List<ClassifiedUnknownInk> unknownInk = new ArrayList<>();
        for (UnknownSymbol unknown : selection.unknowns()) {
            unknownInk.add(new ClassifiedUnknownInk(
                    unknown.classification(), unknown.candidateId(), unknown.sourceStrokeIndices(),
                    unknown.bounds(), unknown.rejectionReason()));
        }
        if (!recognitionComplete) {
            unknownInk.add(new ClassifiedUnknownInk(
                    UnknownInkClassification.BUDGET_SKIPPED,
                    -1,
                    List.copyOf(droppedSourceStrokeIndices),
                    null,
                    RecognitionRejectionReason.BUDGET_EXHAUSTED));
        } else {
            for (int sourceIndex : droppedSourceStrokeIndices) {
                List<List<Point>> droppedStroke = List.of(strokes.get(sourceIndex));
                unknownInk.add(new ClassifiedUnknownInk(
                        UnknownInkClassifier.classify(droppedStroke, RecognitionRejectionReason.NONE),
                        -1,
                        List.of(sourceIndex),
                        UnknownInkClassifier.bounds(droppedStroke),
                        RecognitionRejectionReason.NONE));
            }
        }

        GlyphAst ast = new GlyphAst(
                ring, selection.sigils(), selection.signs(), selection.unknowns(), unknownInk);
        SpellIr ir = SpellCompiler.compile(ast, recognitionComplete);

        return new ParseResult(ast, ir, debugResult);
    }
}
