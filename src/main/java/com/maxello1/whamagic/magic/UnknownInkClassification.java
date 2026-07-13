package com.maxello1.whamagic.magic;

/** Compiler-facing classification for ink not owned by a recognized symbol. */
public enum UnknownInkClassification {
    NOISE,
    HARMLESS_UNEXPLAINED,
    AMBIGUOUS,
    SUBSTANTIAL_UNKNOWN,
    BUDGET_SKIPPED
}
