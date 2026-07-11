package com.maxello1.whamagic.magic;
import com.maxello1.whamagic.parser.BoundingBox;
import com.maxello1.whamagic.parser.Point;
import java.util.List;
public record SymbolCandidate(int id, List<Integer> primitiveGroupIds, List<Integer> sourceStrokeIndices, List<List<Point>> strokes, BoundingBox bounds, Point centroid, double radialPosition, double angularPosition, double angularSpan) {}
