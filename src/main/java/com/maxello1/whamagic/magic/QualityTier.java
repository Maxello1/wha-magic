package com.maxello1.whamagic.magic;

public enum QualityTier {
    FLAWED,
    ROUGH,
    SERVICEABLE,
    REFINED,
    MASTERWORK;

    public static final double ROUGH_THRESHOLD = 0.40;
    public static final double SERVICEABLE_THRESHOLD = 0.60;
    public static final double REFINED_THRESHOLD = 0.75;
    public static final double MASTERWORK_THRESHOLD = 0.90;

    public static QualityTier fromOverall(double overall) {
        if (!Double.isFinite(overall)) {
            throw new IllegalArgumentException("overall must be finite: " + overall);
        }
        if (overall < ROUGH_THRESHOLD) return FLAWED;
        if (overall < SERVICEABLE_THRESHOLD) return ROUGH;
        if (overall < REFINED_THRESHOLD) return SERVICEABLE;
        if (overall < MASTERWORK_THRESHOLD) return REFINED;
        return MASTERWORK;
    }
}
