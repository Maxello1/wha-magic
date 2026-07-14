package com.maxello1.whamagic.parser;

/** Lightweight bounded counters retained by preview and full parse results. */
public record ParseSummary(
        int primitiveGroupCount,
        int candidateCount,
        int selectedCandidateCount,
        int recognitionCalls,
        int unknownCount,
        boolean candidateLimitReached,
        boolean ringBudgetExhausted,
        boolean recognitionBudgetExhausted,
        int unevaluatedCandidateCount,
        int droppedSourceStrokeCount
) {
    public static final ParseSummary EMPTY = new ParseSummary(
            0, 0, 0, 0, 0, false, false, false, 0, 0);
}
