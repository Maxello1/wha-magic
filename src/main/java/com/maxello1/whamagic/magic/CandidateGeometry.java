package com.maxello1.whamagic.magic;

/**
 * Pre-normalization geometry of a symbol candidate, used for noise rejection.
 * Ratios use the ring diameter (or canvas) as reference.
 */
public record CandidateGeometry(
    double widthRatio,
    double heightRatio,
    double areaRatio,
    double pathLengthRatio,
    double dimensionality,
    int pointCount,
    int strokeCount,
    int rasterInkCount
) {
    /** Whether this candidate is effectively a dot (tiny dimensions). */
    public boolean isDot(double minDimension) {
        return widthRatio < minDimension && heightRatio < minDimension;
    }

    /** Whether this is line-like (one dimension much larger than the other). */
    public boolean isLineLike(double minDimensionRatio) {
        return dimensionality < minDimensionRatio;
    }
}
