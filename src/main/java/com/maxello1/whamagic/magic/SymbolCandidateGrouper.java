package com.maxello1.whamagic.magic;

import com.maxello1.whamagic.parser.Point;
import java.util.ArrayList;
import java.util.List;

public class SymbolCandidateGrouper {
    public record BoundingBox(double minX, double minY, double maxX, double maxY) {
        public boolean overlaps(BoundingBox other) {
            return this.minX <= other.maxX && this.maxX >= other.minX &&
                   this.minY <= other.maxY && this.maxY >= other.minY;
        }
        public BoundingBox expand(double margin) {
            return new BoundingBox(minX - margin, minY - margin, maxX + margin, maxY + margin);
        }
    }

    public record SymbolCandidate(
        List<List<Point>> strokes,
        BoundingBox bounds,
        Point centroid,
        double angleAroundRing,
        double radiusNorm,
        String layer
    ) {}

    public static List<SymbolCandidate> groupSigns(List<List<Point>> strokes, RingDetector.RingGlyph ring) {
        if (strokes.isEmpty()) return new ArrayList<>();
        
        List<SymbolCandidate> candidates = new ArrayList<>();
        int n = strokes.size();
        boolean[][] connected = new boolean[n][n];
        
        BoundingBox[] bounds = new BoundingBox[n];
        double[] angles = new double[n];
        
        for (int i = 0; i < n; i++) {
            List<Point> stroke = strokes.get(i);
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            double cx = 0, cy = 0;
            for (Point p : stroke) {
                if (p.x < minX) minX = p.x;
                if (p.y < minY) minY = p.y;
                if (p.x > maxX) maxX = p.x;
                if (p.y > maxY) maxY = p.y;
                cx += p.x;
                cy += p.y;
            }
            bounds[i] = new BoundingBox(minX, minY, maxX, maxY);
            if (ring != null) {
                cx /= stroke.size();
                cy /= stroke.size();
                double angle = Math.toDegrees(Math.atan2(cy - ring.center().y, cx - ring.center().x));
                angles[i] = angle < 0 ? angle + 360 : angle;
            }
        }
        
        double endpointDistSq = 0.025 * 0.025;
        
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                List<Point> s1 = strokes.get(i);
                List<Point> s2 = strokes.get(j);
                
                Point s1s = s1.get(0), s1e = s1.get(s1.size() - 1);
                Point s2s = s2.get(0), s2e = s2.get(s2.size() - 1);
                
                boolean endpointsClose = distSq(s1s, s2s) < endpointDistSq || distSq(s1s, s2e) < endpointDistSq || 
                                         distSq(s1e, s2s) < endpointDistSq || distSq(s1e, s2e) < endpointDistSq;
                
                boolean boundsOverlap = bounds[i].expand(0.05).overlaps(bounds[j].expand(0.05));
                boolean anglesClose = false;
                if (ring != null) {
                    double diff = Math.abs(angles[i] - angles[j]);
                    if (diff > 180) diff = 360 - diff;
                    anglesClose = diff < 45;
                } else {
                    anglesClose = true;
                }
                
                if (endpointsClose || (boundsOverlap && anglesClose)) {
                    connected[i][j] = true;
                    connected[j][i] = true;
                }
            }
        }
        
        boolean[] visited = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (!visited[i]) {
                List<Integer> comp = new ArrayList<>();
                dfs(i, connected, visited, comp);
                
                List<List<Point>> compStrokes = new ArrayList<>();
                double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
                double cx = 0, cy = 0;
                int totalPoints = 0;
                
                for (int idx : comp) {
                    List<Point> s = strokes.get(idx);
                    compStrokes.add(s);
                    BoundingBox b = bounds[idx];
                    if (b.minX < minX) minX = b.minX;
                    if (b.minY < minY) minY = b.minY;
                    if (b.maxX > maxX) maxX = b.maxX;
                    if (b.maxY > maxY) maxY = b.maxY;
                    for (Point p : s) {
                        cx += p.x;
                        cy += p.y;
                        totalPoints++;
                    }
                }
                cx /= totalPoints;
                cy /= totalPoints;
                
                double angleAroundRing = 0;
                double radiusNorm = 0;
                if (ring != null) {
                    double angle = Math.toDegrees(Math.atan2(cy - ring.center().y, cx - ring.center().x));
                    angleAroundRing = angle < 0 ? angle + 360 : angle;
                    double r = Math.hypot(cx - ring.center().x, cy - ring.center().y);
                    radiusNorm = r / ring.radius();
                }
                
                candidates.add(new SymbolCandidate(compStrokes, new BoundingBox(minX, minY, maxX, maxY), 
                    new Point(cx, cy), angleAroundRing, radiusNorm, "sign"));
            }
        }
        
        return candidates;
    }
    
    private static void dfs(int u, boolean[][] connected, boolean[] visited, List<Integer> comp) {
        visited[u] = true;
        comp.add(u);
        for (int v = 0; v < connected.length; v++) {
            if (connected[u][v] && !visited[v]) {
                dfs(v, connected, visited, comp);
            }
        }
    }
    
    private static double distSq(Point a, Point b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        return dx * dx + dy * dy;
    }
}
