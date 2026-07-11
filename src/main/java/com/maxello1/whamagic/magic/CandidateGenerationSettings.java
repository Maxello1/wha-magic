package com.maxello1.whamagic.magic;
public record CandidateGenerationSettings(int maxPrimitiveGroups, int maxGroupsPerCandidate, int maxCandidates, int maxRecognitionCalls, double maxCandidateWidthRatio, double maxCandidateHeightRatio, double maxAngularSpanDeg) {
    public static final CandidateGenerationSettings DEFAULTS = new CandidateGenerationSettings(10, 6, 128, 128, 0.75, 0.75, 150.0);
}
