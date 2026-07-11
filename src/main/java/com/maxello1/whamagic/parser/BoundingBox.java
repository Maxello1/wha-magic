package com.maxello1.whamagic.parser;
public record BoundingBox(double minX, double minY, double maxX, double maxY) {
    public double width() { return maxX - minX; }
    public double height() { return maxY - minY; }
    public boolean intersects(BoundingBox other) {
        return minX <= other.maxX && maxX >= other.minX && minY <= other.maxY && maxY >= other.minY;
    }
}
