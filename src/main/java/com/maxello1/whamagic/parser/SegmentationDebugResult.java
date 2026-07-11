package com.maxello1.whamagic.parser;
import com.maxello1.whamagic.magic.PrimitiveStrokeGroup;
import com.maxello1.whamagic.magic.SymbolCandidate;
import java.util.List;
public record SegmentationDebugResult(List<PrimitiveStrokeGroup> primitiveGroups, List<SymbolCandidate> generatedCandidates, List<SymbolCandidate> selectedCandidates, int recognitionCalls, boolean candidateLimitReached) {}
