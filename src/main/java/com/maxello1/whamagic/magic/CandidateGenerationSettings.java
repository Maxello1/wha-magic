package com.maxello1.whamagic.magic;

/**
 * Deterministic limits for candidate generation.
 * Controls how many primitive groups, candidates, and recognition calls are allowed,
 * as well as geometric constraints for valid candidates.
 */
public record CandidateGenerationSettings(
    int maxPrimitiveGroups,
    int maxGroupsPerCandidate,
    int maxCandidates,
    int maxRecognitionCalls,
    double maxCandidateWidthRatio,
    double maxCandidateHeightRatio,
    double maxAngularSpanDeg,
    double maxInternalGapRatio,
    double maxEmptySpaceRatio
) {
    public static final CandidateGenerationSettings DEFAULTS = new CandidateGenerationSettings(
        16,     // maxPrimitiveGroups
        6,      // maxGroupsPerCandidate
        128,    // maxCandidates
        512,    // maxRecognitionCalls
        0.75,   // maxCandidateWidthRatio
        0.75,   // maxCandidateHeightRatio
        150.0,  // maxAngularSpanDeg
        0.25,   // maxInternalGapRatio — max gap between sub-groups within a candidate
        0.60    // maxEmptySpaceRatio — max fraction of bounding box that's empty
    );
}
