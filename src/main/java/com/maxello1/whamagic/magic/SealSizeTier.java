package com.maxello1.whamagic.magic;

public enum SealSizeTier {
    TINY,
    SMALL,
    STANDARD,
    LARGE,
    GRAND;

    public static final double SMALL_THRESHOLD = 0.70;
    public static final double STANDARD_THRESHOLD = 0.90;
    public static final double LARGE_THRESHOLD = 1.15;
    public static final double GRAND_THRESHOLD = 1.50;

    public static SealSizeTier fromScale(double scale) {
        if (!Double.isFinite(scale) || scale < 0.0) {
            throw new IllegalArgumentException("scale must be finite and non-negative: " + scale);
        }
        if (scale < SMALL_THRESHOLD) return TINY;
        if (scale < STANDARD_THRESHOLD) return SMALL;
        if (scale < LARGE_THRESHOLD) return STANDARD;
        if (scale < GRAND_THRESHOLD) return LARGE;
        return GRAND;
    }
}
