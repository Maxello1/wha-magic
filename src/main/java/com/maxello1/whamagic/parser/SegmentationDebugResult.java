package com.maxello1.whamagic.parser;
import com.maxello1.whamagic.magic.PrimitiveStrokeGroup;
import com.maxello1.whamagic.magic.SymbolCandidate;
import com.maxello1.whamagic.magic.RecognizedSigil;
import com.maxello1.whamagic.magic.RecognizedSign;
import com.maxello1.whamagic.magic.UnknownSymbol;
import java.util.List;

/**
 * Full diagnostic snapshot of a single parse operation.
 * Used by the debug overlay and sample recorder.
 */
public record SegmentationDebugResult(
    List<PrimitiveStrokeGroup> primitiveGroups,
    List<SymbolCandidate> generatedCandidates,
    List<SymbolCandidate> selectedCandidates,
    int recognitionCalls,
    boolean candidateLimitReached,
    List<RecognizedSigil> sigils,
    List<RecognizedSign> signs,
    List<UnknownSymbol> unknowns,
    int primitiveGroupCount,
    int candidateCount,
    int selectedCandidateCount,
    List<SelectionEngine.EvaluatedCandidate> allEvaluated
) {}
