package com.maxello1.whamagic.magic;

import com.maxello1.whamagic.parser.Point;
import java.util.ArrayList;
import java.util.List;

public class SymbolCandidateGrouper {
    public record SymbolCandidate(
        List<List<Point>> strokes,
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
        
        double endpointDistSq = 0.03 * 0.03; // Group if endpoints are within 3% of canvas
        double generalDistSq = 0.05 * 0.05;  // Group if any points are within 5% of canvas
        
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                List<Point> s1 = strokes.get(i);
                List<Point> s2 = strokes.get(j);
                
                Point s1s = s1.get(0), s1e = s1.get(s1.size() - 1);
                Point s2s = s2.get(0), s2e = s2.get(s2.size() - 1);
                
                boolean endpointsClose = distSq(s1s, s2s) < endpointDistSq || distSq(s1s, s2e) < endpointDistSq || 
                                         distSq(s1e, s2s) < endpointDistSq || distSq(s1e, s2e) < endpointDistSq;
                
                boolean geometricallyClose = false;
                if (!endpointsClose) {
                    // Check if the strokes are geometrically close to each other anywhere
                    double minDistSq = Double.MAX_VALUE;
                    for(Point p1 : s1) {
                        for(Point p2 : s2) {
                            double d2 = distSq(p1, p2);
                            if (d2 < minDistSq) minDistSq = d2;
                        }
                    }
                    geometricallyClose = minDistSq < generalDistSq;
                }
                
                if (endpointsClose || geometricallyClose) {
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
                double cx = 0, cy = 0;
                int totalPoints = 0;
                
                for (int idx : comp) {
                    List<Point> s = strokes.get(idx);
                    compStrokes.add(s);
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
                
                candidates.add(new SymbolCandidate(compStrokes, new Point(cx, cy), angleAroundRing, radiusNorm, "sign"));
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
