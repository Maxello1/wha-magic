package com.maxello1.whamagic.magic;
import com.maxello1.whamagic.parser.BoundingBox;
import com.maxello1.whamagic.parser.Point;
import java.util.List;
public record SymbolCandidate(int id, List<Integer> primitiveGroupIds, List<Integer> sourceStrokeIndices, List<List<Point>> strokes, BoundingBox bounds, Point centroid, double radialPosition, double angularPosition, double angularSpan, boolean isSuperCandidate) {
    public SymbolCandidate(int id, List<Integer> primitiveGroupIds, List<Integer> sourceStrokeIndices, List<List<Point>> strokes, BoundingBox bounds, Point centroid, double radialPosition, double angularPosition, double angularSpan) {
        this(id, primitiveGroupIds, sourceStrokeIndices, strokes, bounds, centroid, radialPosition, angularPosition, angularSpan, false);
    }

    /** Return a copy with isSuperCandidate set to true. */
    public SymbolCandidate withSuperCandidate() {
        return new SymbolCandidate(id, primitiveGroupIds, sourceStrokeIndices, strokes, bounds, centroid, radialPosition, angularPosition, angularSpan, true);
    }
}
