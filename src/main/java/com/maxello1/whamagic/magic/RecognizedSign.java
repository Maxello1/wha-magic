package com.maxello1.whamagic.magic;

import java.util.List;

public record RecognizedSign(
    String id,
    double confidence,
    double angleAroundRing,
    double orientationDeg,
    String layer,
    SignSemantic semantic,
    List<Integer> sourceStrokeIndices
) {}
