package com.maxello1.whamagic.magic;

public record SigilSemantic(
    double force,
    double focus,
    double spread,
    double range,
    double lifetimeBias
) {
    public static SigilSemantic empty() {
        return new SigilSemantic(0, 0, 0, 0, 0);
    }
}
