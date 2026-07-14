package com.maxello1.whamagic.parser;

/** Controls how much recognition data survives a parse. */
public enum ParseDetail {
    /** Server execution path: semantic output and minimal validity state only. */
    RUNTIME,
    /** Normal drawing preview: selected symbols, bounds, and bounded summary counts. */
    PREVIEW,
    /** Development overlays, sample capture, metrics, and regression diagnostics. */
    FULL_DIAGNOSTICS;

    public boolean retainsAlternatives() {
        return this == FULL_DIAGNOSTICS;
    }

    public boolean retainsFullDiagnostics() {
        return this == FULL_DIAGNOSTICS;
    }
}
