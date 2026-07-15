package com.maxello1.whamagic.magic;

/** Ring-relative layer occupied by a compiled sign. */
public enum SpellLayer {
    CORE,
    INNER,
    MIDDLE,
    OUTER;

    public static final double CORE_MAX_RADIAL_POSITION = 0.25;
    public static final double INNER_MAX_RADIAL_POSITION = 0.50;
    public static final double MIDDLE_MAX_RADIAL_POSITION = 0.75;

    /** Resolve a normalized centre-to-sign distance into a named spell layer. */
    public static SpellLayer fromRadialPosition(double radialPosition) {
        if (!Double.isFinite(radialPosition) || radialPosition < 0.0) {
            throw new IllegalArgumentException(
                    "radialPosition must be finite and non-negative: " + radialPosition);
        }
        if (radialPosition <= CORE_MAX_RADIAL_POSITION) return CORE;
        if (radialPosition <= INNER_MAX_RADIAL_POSITION) return INNER;
        if (radialPosition <= MIDDLE_MAX_RADIAL_POSITION) return MIDDLE;
        return OUTER;
    }
}
