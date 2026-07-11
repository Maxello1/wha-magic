package com.maxello1.whamagic.magic;

public record RecognizedSign(
    String id,
    double confidence,
    double angleAroundRing,
    double orientationDeg,
    String layer,
    SignSemantic semantic
) {}
