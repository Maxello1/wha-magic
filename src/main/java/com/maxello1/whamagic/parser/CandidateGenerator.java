package com.maxello1.whamagic.parser;

import com.maxello1.whamagic.magic.CandidateGenerationSettings;
import com.maxello1.whamagic.magic.PrimitiveStrokeGroup;
import com.maxello1.whamagic.magic.SymbolCandidate;
import com.maxello1.whamagic.magic.RingDetector;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Comparator;

public class CandidateGenerator {

    public static List<SymbolCandidate> generateCandidates(List<List<Point>> strokes, RingDetector.RingGlyph ring, CandidateGenerationSettings settings) {
        List<PrimitiveStrokeGroup> primitives = groupPrimitives(strokes, ring);
        
        if (primitives.size() > settings.maxPrimitiveGroups()) {
            primitives.sort((a, b) -> Double.compare(b.pathLength(), a.pathLength()));
            primitives = new ArrayList<>(primitives.subList(0, settings.maxPrimitiveGroups()));
        }
        
        List<SymbolCandidate> candidates = new ArrayList<>();
        int n = primitives.size();
        
        int maxK = Math.min(n, settings.maxGroupsPerCandidate());
        for (int k = 1; k <= maxK; k++) {
            List<List<PrimitiveStrokeGroup>> combos = new ArrayList<>();
            generateCombinations(primitives, k, 0, new ArrayList<>(), combos);
            for (List<PrimitiveStrokeGroup> combo : combos) {
                if (candidates.size() >= settings.maxCandidates()) {
                    break;
                }
                SymbolCandidate cand = buildCandidate(combo, ring, candidates.size());
                if (isValidCandidate(cand, ring, settings)) {
                    candidates.add(cand);
                }
            }
            if (candidates.size() >= settings.maxCandidates()) {
                break;
            }
        }
        
        return candidates;
    }
    
    private static void generateCombinations(List<PrimitiveStrokeGroup> primitives, int k, int start, List<PrimitiveStrokeGroup> current, List<List<PrimitiveStrokeGroup>> result) {
        if (current.size() == k) {
            result.add(new ArrayList<>(current));
            return;
        }
        for (int i = start; i < primitives.size(); i++) {
            current.add(primitives.get(i));
            generateCombinations(primitives, k, i + 1, current, result);
            current.remove(current.size() - 1);
        }
    }
    
    private static List<PrimitiveStrokeGroup> groupPrimitives(List<List<Point>> strokes, RingDetector.RingGlyph ring) {
        if (strokes.isEmpty()) return new ArrayList<>();
        
        int n = strokes.size();
        boolean[][] connected = new boolean[n][n];
        
        double distSqThresh = 0.02 * 0.02; 
        
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                List<Point> s1 = strokes.get(i);
                List<Point> s2 = strokes.get(j);
                boolean isConnected = false;
                
                Point s1s = s1.get(0), s1e = s1.get(s1.size() - 1);
                Point s2s = s2.get(0), s2e = s2.get(s2.size() - 1);
                if (distSq(s1s, s2s) < distSqThresh || distSq(s1s, s2e) < distSqThresh || 
                    distSq(s1e, s2s) < distSqThresh || distSq(s1e, s2e) < distSqThresh) {
                    isConnected = true;
                } else {
                    double minDistSq = Double.MAX_VALUE;
                    for (Point p1 : s1) {
                        for (Point p2 : s2) {
                            double d2 = distSq(p1, p2);
                            if (d2 < minDistSq) minDistSq = d2;
                        }
                    }
                    if (minDistSq < distSqThresh) {
                        isConnected = true;
                    }
                }
                
                if (isConnected) {
                    connected[i][j] = true;
                    connected[j][i] = true;
                }
            }
        }
        
        boolean[] visited = new boolean[n];
        List<PrimitiveStrokeGroup> groups = new ArrayList<>();
        int groupId = 0;
        
        for (int i = 0; i < n; i++) {
            if (!visited[i]) {
                List<Integer> comp = new ArrayList<>();
                dfs(i, connected, visited, comp);
                
                List<List<Point>> compStrokes = new ArrayList<>();
                List<Integer> sourceIndices = new ArrayList<>();
                double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
                double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
                double cx = 0, cy = 0;
                int totalPoints = 0;
                double pathLength = 0;
                
                for (int idx : comp) {
                    List<Point> s = strokes.get(idx);
                    sourceIndices.add(idx);
                    compStrokes.add(s);
                    
                    for (int pIdx = 0; pIdx < s.size(); pIdx++) {
                        Point p = s.get(pIdx);
                        if (p.x < minX) minX = p.x;
                        if (p.y < minY) minY = p.y;
                        if (p.x > maxX) maxX = p.x;
                        if (p.y > maxY) maxY = p.y;
                        cx += p.x;
                        cy += p.y;
                        totalPoints++;
                        
                        if (pIdx > 0) {
                            Point prev = s.get(pIdx - 1);
                            pathLength += Math.hypot(p.x - prev.x, p.y - prev.y);
                        }
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
                
                BoundingBox bounds = new BoundingBox(minX, minY, maxX, maxY);
                groups.add(new PrimitiveStrokeGroup(groupId++, sourceIndices, compStrokes, bounds, new Point(cx, cy), pathLength, radiusNorm, angleAroundRing));
            }
        }
        
        return groups;
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
    
    private static SymbolCandidate buildCandidate(List<PrimitiveStrokeGroup> groups, RingDetector.RingGlyph ring, int candId) {
        List<List<Point>> allStrokes = new ArrayList<>();
        List<Integer> primitiveIds = new ArrayList<>();
        List<Integer> sourceIndices = new ArrayList<>();
        
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double cx = 0, cy = 0;
        int totalPoints = 0;
        
        for (PrimitiveStrokeGroup g : groups) {
            primitiveIds.add(g.id());
            for (List<Point> s : g.strokes()) {
                allStrokes.add(s);
                for (Point p : s) {
                    if (p.x < minX) minX = p.x;
                    if (p.y < minY) minY = p.y;
                    if (p.x > maxX) maxX = p.x;
                    if (p.y > maxY) maxY = p.y;
                    cx += p.x;
                    cy += p.y;
                    totalPoints++;
                }
            }
            sourceIndices.addAll(g.sourceStrokeIndices());
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
        
        double angularSpan = 0;
        if (ring != null && groups.size() > 1) {
            double minAngle = Double.MAX_VALUE;
            double maxAngle = -Double.MAX_VALUE;
            for (PrimitiveStrokeGroup g : groups) {
                double a = g.angularPosition();
                double diff = a - angleAroundRing;
                while (diff > 180) diff -= 360;
                while (diff < -180) diff += 360;
                if (diff < minAngle) minAngle = diff;
                if (diff > maxAngle) maxAngle = diff;
            }
            angularSpan = maxAngle - minAngle;
        }
        
        BoundingBox bounds = new BoundingBox(minX, minY, maxX, maxY);
        return new SymbolCandidate(candId, primitiveIds, sourceIndices, allStrokes, bounds, new Point(cx, cy), radiusNorm, angleAroundRing, angularSpan);
    }
    
    private static boolean isValidCandidate(SymbolCandidate cand, RingDetector.RingGlyph ring, CandidateGenerationSettings settings) {
        if (ring != null) {
            if (cand.bounds().width() > ring.radius() * 2 * settings.maxCandidateWidthRatio()) return false;
            if (cand.bounds().height() > ring.radius() * 2 * settings.maxCandidateHeightRatio()) return false;
            if (cand.angularSpan() > settings.maxAngularSpanDeg()) return false;
        }
        return true;
    }
    
    private static double distSq(Point a, Point b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        return dx * dx + dy * dy;
    }
}
