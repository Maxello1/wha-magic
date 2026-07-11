package com.maxello1.whamagic.magic;
import com.maxello1.whamagic.parser.BoundingBox;
import com.maxello1.whamagic.parser.Point;
import java.util.List;
public record PrimitiveStrokeGroup(int id, List<Integer> sourceStrokeIndices, List<List<Point>> strokes, BoundingBox bounds, Point centroid, double pathLength, double radialPosition, double angularPosition) {}
