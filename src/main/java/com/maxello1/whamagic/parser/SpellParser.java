package com.maxello1.whamagic.parser;

import com.maxello1.whamagic.magic.CandidateGenerationSettings;
import com.maxello1.whamagic.magic.ClassifiedUnknownInk;
import com.maxello1.whamagic.magic.GlyphAst;
import com.maxello1.whamagic.magic.RecognitionRejectionReason;
import com.maxello1.whamagic.magic.RingDetector;
import com.maxello1.whamagic.magic.SpellCompiler;
import com.maxello1.whamagic.magic.SpellIr;
import com.maxello1.whamagic.magic.UnknownInkClassification;
import com.maxello1.whamagic.magic.UnknownSymbol;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public final class SpellParser {
    private SpellParser() {}

    public static final class ParseResult {
        public final GlyphAst ast;
        public final SpellIr ir;
        public final ParseDetail detail;
        public final ParseSummary summary;
        public final SegmentationDebugResult debugResult;
        
        public ParseResult(GlyphAst ast, SpellIr ir) {
            this(ast, ir, ParseDetail.FULL_DIAGNOSTICS, null, null);
        }

        public ParseResult(GlyphAst ast, SpellIr ir, SegmentationDebugResult debugResult) {
            this(ast, ir, ParseDetail.FULL_DIAGNOSTICS, null, debugResult);
        }

        public ParseResult(
                GlyphAst ast,
                SpellIr ir,
                ParseDetail detail,
                ParseSummary summary,
                SegmentationDebugResult debugResult) {
            this.ast = ast;
            this.ir = ir;
            this.detail = detail;
            this.summary = summary;
            this.debugResult = debugResult;
        }

        public boolean isValidSpell() {
            return ir.valid();
        }
    }

    public static ParseResult parse(List<List<Point>> strokes) {
        return parse(
                strokes,
                CandidateGenerationSettings.DEFAULTS,
                ParseDetail.FULL_DIAGNOSTICS);
    }

    public static ParseResult parse(List<List<Point>> strokes, ParseDetail detail) {
        return parse(strokes, CandidateGenerationSettings.DEFAULTS, detail);
    }

    /** Parse with explicit candidate and recognition limits, primarily for deterministic tests. */
    public static ParseResult parse(List<List<Point>> strokes, CandidateGenerationSettings settings) {
        return parse(strokes, settings, ParseDetail.FULL_DIAGNOSTICS);
    }

    /** Parse with explicit bounded settings and result-detail policy. */
    public static ParseResult parse(
            List<List<Point>> strokes,
            CandidateGenerationSettings settings,
            ParseDetail detail) {
        return parseInternal(strokes, settings, detail);
    }

    private static ParseResult parseInternal(
            List<List<Point>> strokes,
            CandidateGenerationSettings settings,
            ParseDetail detail) {
        if (strokes == null || strokes.isEmpty()) {
            GlyphAst emptyAst = new GlyphAst(null, List.of(), List.of(), List.of());
            return new ParseResult(
                    emptyAst,
                    SpellCompiler.compile(emptyAst),
                    detail,
                    detail == ParseDetail.RUNTIME ? null : ParseSummary.EMPTY,
                    null);
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

        CandidateGenerator.GenerationResult genResult =
                CandidateGenerator.generateCandidates(nonRingStrokes, ring, settings);

        SelectionEngine.SelectedSymbols selection = SelectionEngine.select(
                genResult.candidates(), ring, settings.maxRecognitionCalls(), detail);

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

        ParseSummary summary = detail == ParseDetail.RUNTIME
                ? null
                : new ParseSummary(
                        genResult.primitiveGroups().size(),
                        genResult.candidates().size(),
                        selection.sigils().size() + selection.signs().size()
                                + selection.unknowns().size(),
                        selection.recognitionCalls(),
                        selection.unknowns().size(),
                        genResult.candidateLimitReached(),
                        ringDiagnostics.budgetExhausted(),
                        selection.recognitionBudgetExhausted(),
                        selection.unevaluatedCandidateCount(),
                        droppedSourceStrokeIndices.size());

        SegmentationDebugResult debugResult = null;
        if (detail.retainsFullDiagnostics()) {
            debugResult = new SegmentationDebugResult(
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
                    selection.allEvaluated());
        }

        return new ParseResult(ast, ir, detail, summary, debugResult);
    }
}
