package com.maxello1.whamagic.magic;

public record SpellIr(
    boolean valid,
    boolean active,
    boolean prepared,
    String element,
    String manifestation,
    double power,
    String statusMessage
) {}
