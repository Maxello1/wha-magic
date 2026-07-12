package com.maxello1.whamagic.magic;

/**
 * Reason why a recognition result was rejected.
 * Reports only existing rejection behavior — no new rules.
 */
public enum RecognitionRejectionReason {
    NONE,
    NO_STROKES,
    NO_TEMPLATES,
    SCORE_BELOW_THRESHOLD,
    AMBIGUOUS_TOP_MATCHES,
    LOW_TEMPLATE_COVERAGE,
    EXCESS_UNEXPLAINED_INK,
    INVALID_SYMBOL_KIND,
    CANDIDATE_LIMIT_REACHED
}
