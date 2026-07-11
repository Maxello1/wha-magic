package com.maxello1.whamagic.magic;

public record SignSemantic(
    String manifestation,
    String directionMode,
    double force,
    double focus,
    double spread,
    double range,
    double lifetimeBias
) {
    public static SignSemantic empty() {
        return new SignSemantic(null, null, 0, 0, 0, 0, 0);
    }
}
