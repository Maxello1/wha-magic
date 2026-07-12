package com.maxello1.whamagic.magic;

/**
 * Complexity profile of a template, computed at registration time.
 * Used to reject candidates that are much simpler than the template they matched.
 */
public record TemplateComplexity(
    double pathLength,
    double inkCoverage,
    double dimensionality,
    int endpointEstimate,
    int componentEstimate
) {
    /**
     * Compute compatibility between a candidate and this template's complexity.
     * Returns 0.0 (incompatible) to 1.0 (fully compatible).
     */
    public double compatibilityWith(CandidateGeometry candidateGeometry) {
        // Path length ratio: candidate should have comparable path length
        double pathRatio = pathLength > 0 ? Math.min(candidateGeometry.pathLengthRatio(), pathLength) / Math.max(candidateGeometry.pathLengthRatio(), pathLength) : 0;
        
        // Ink coverage ratio: candidate should cover comparable area
        double inkRatio = inkCoverage > 0 ? Math.min(candidateGeometry.areaRatio(), inkCoverage) / Math.max(candidateGeometry.areaRatio(), inkCoverage) : 0;
        
        // Dimensionality should be similar
        double dimDiff = Math.abs(dimensionality - candidateGeometry.dimensionality());
        double dimScore = Math.max(0, 1.0 - dimDiff * 2);
        
        return pathRatio * 0.4 + inkRatio * 0.3 + dimScore * 0.3;
    }
}
