package com.maxello1.whamagic.parser;

import com.maxello1.whamagic.magic.CandidateGenerationSettings;
import com.maxello1.whamagic.magic.PrimitiveStrokeGroup;
import com.maxello1.whamagic.magic.SymbolCandidate;
import com.maxello1.whamagic.magic.RingDetector;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

public class CandidateGenerator {

    /** Result of candidate generation, including diagnostic data. */
    public record GenerationResult(
        List<PrimitiveStrokeGroup> primitiveGroups,
        List<SymbolCandidate> candidates,
        boolean candidateLimitReached
    ) {}

    public static GenerationResult generateCandidates(List<List<Point>> strokes, RingDetector.RingGlyph ring, CandidateGenerationSettings settings) {
        List<PrimitiveStrokeGroup> primitives = groupPrimitives(strokes, ring, settings);
        
        if (primitives.size() > settings.maxPrimitiveGroups()) {
            primitives.sort((a, b) -> Double.compare(b.pathLength(), a.pathLength()));
            primitives = new ArrayList<>(primitives.subList(0, settings.maxPrimitiveGroups()));
        }
        
        List<SymbolCandidate> candidates = new ArrayList<>();
        boolean limitReached = false;
        int n = primitives.size();
        
        // Insert the all-strokes super-candidate FIRST so it is always tested.
        // For complex symbols like Light (12 strokes), the combinatorial explosion
        // of smaller candidates would otherwise exhaust the recognition call budget.
        if (n > 1) {
            SymbolCandidate allStrokesCandidate = buildCandidate(new ArrayList<>(primitives), ring, 0);
            candidates.add(allStrokesCandidate);
        }
        
        // Build proximity adjacency to avoid combining distant groups
        boolean[][] adjacentGroups = buildGroupAdjacency(primitives, ring, settings);
        
        int maxK = Math.min(n, settings.maxGroupsPerCandidate());
        for (int k = 1; k <= maxK; k++) {
            List<List<PrimitiveStrokeGroup>> combos = new ArrayList<>();
            generateConnectedCombinations(primitives, adjacentGroups, k, 0, new ArrayList<>(), new HashSet<>(), combos);
            for (List<PrimitiveStrokeGroup> combo : combos) {
                if (candidates.size() >= settings.maxCandidates()) {
                    limitReached = true;
                    break;
                }
                SymbolCandidate cand = buildCandidate(combo, ring, candidates.size());
                if (isValidCandidate(cand, ring, settings)) {
                    // Skip if it duplicates the all-strokes candidate
                    if (n > 1 && combo.size() == primitives.size()) continue;
                    candidates.add(cand);
                }
            }
            if (candidates.size() >= settings.maxCandidates()) {
                limitReached = true;
                break;
            }
        }
        
        return new GenerationResult(primitives, candidates, limitReached);
    }
    
    /**
     * Generate combinations of groups where at least one group in the combination
     * is adjacent to at least one other group already in the combination.
     * This prevents combining spatially distant groups.
     */
    private static void generateConnectedCombinations(
            List<PrimitiveStrokeGroup> primitives, boolean[][] adjacent,
            int k, int start, List<PrimitiveStrokeGroup> current, 
            Set<Integer> currentIndices, List<List<PrimitiveStrokeGroup>> result) {
        if (current.size() == k) {
            result.add(new ArrayList<>(current));
            return;
        }
        for (int i = start; i < primitives.size(); i++) {
            // For k=1, allow any single group
            // For k>1, the new group must be adjacent to at least one group already selected
            if (!current.isEmpty()) {
                boolean hasAdjacentNeighbor = false;
                for (int existing : currentIndices) {
                    if (adjacent[existing][i]) {
                        hasAdjacentNeighbor = true;
                        break;
                    }
                }
                if (!hasAdjacentNeighbor) continue;
            }
            
            current.add(primitives.get(i));
            currentIndices.add(i);
            generateConnectedCombinations(primitives, adjacent, k, i + 1, current, currentIndices, result);
            current.remove(current.size() - 1);
            currentIndices.remove(i);
        }
    }
    
    /**
     * Build adjacency matrix between primitive groups based on spatial proximity.
     * Two groups are adjacent if their centroids, bounds, or endpoints are close enough.
     */
    private static boolean[][] buildGroupAdjacency(List<PrimitiveStrokeGroup> groups, RingDetector.RingGlyph ring, CandidateGenerationSettings settings) {
        int n = groups.size();
        boolean[][] adj = new boolean[n][n];
        
        // Reference dimension: ring diameter or canvas size
        double refSize = ring != null ? ring.radius() * 2 : 1.0;
        double maxGap = refSize * settings.maxInternalGapRatio();
        double maxGapSq = maxGap * maxGap;
        
        for (int i = 0; i < n; i++) {
            adj[i][i] = true;
            for (int j = i + 1; j < n; j++) {
                PrimitiveStrokeGroup a = groups.get(i);
                PrimitiveStrokeGroup b = groups.get(j);
                
                // Check bounds proximity (expanded bounds overlap)
                boolean boundsClose = boundsOverlapOrClose(a.bounds(), b.bounds(), maxGap);
                
                // Check centroid distance
                double centroidDistSq = distSq(a.centroid(), b.centroid());
                boolean centroidClose = centroidDistSq < maxGapSq * 4;
                
                // Check endpoint proximity (closest stroke endpoints)
                boolean endpointClose = checkEndpointProximity(a.strokes(), b.strokes(), maxGapSq);
                
                // Check angular proximity (for ring-based layouts)
                boolean angularClose = true;
                if (ring != null) {
                    double angleDiff = Math.abs(a.angularPosition() - b.angularPosition());
                    if (angleDiff > 180) angleDiff = 360 - angleDiff;
                    // Two groups on opposite sides of the ring should not be combined
                    angularClose = angleDiff < settings.maxAngularSpanDeg() * 0.6;
                }
                
                // Require at least two proximity measures to agree
                int proximity = 0;
                if (boundsClose) proximity++;
                if (centroidClose) proximity++;
                if (endpointClose) proximity++;
                if (angularClose) proximity++;
                
                adj[i][j] = proximity >= 2;
                adj[j][i] = adj[i][j];
            }
        }
        
        return adj;
    }
    
    /** Check if two bounding boxes overlap or are within maxGap of each other. */
    private static boolean boundsOverlapOrClose(BoundingBox a, BoundingBox b, double maxGap) {
        return a.minX() - maxGap <= b.maxX() && b.minX() - maxGap <= a.maxX() &&
               a.minY() - maxGap <= b.maxY() && b.minY() - maxGap <= a.maxY();
    }
    
    /** Check if any endpoint of one group is close to any endpoint of another. */
    private static boolean checkEndpointProximity(List<List<Point>> strokesA, List<List<Point>> strokesB, double maxDistSq) {
        for (List<Point> sa : strokesA) {
            if (sa.isEmpty()) continue;
            Point startA = sa.get(0);
            Point endA = sa.get(sa.size() - 1);
            for (List<Point> sb : strokesB) {
                if (sb.isEmpty()) continue;
                Point startB = sb.get(0);
                Point endB = sb.get(sb.size() - 1);
                if (distSq(startA, startB) < maxDistSq || distSq(startA, endB) < maxDistSq ||
                    distSq(endA, startB) < maxDistSq || distSq(endA, endB) < maxDistSq) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * Group strokes into primitive stroke groups based on proximity.
     * Uses limited connectivity: strokes are grouped only if they are directly close,
     * with a limit on how far transitive grouping can extend.
     */
    private static List<PrimitiveStrokeGroup> groupPrimitives(List<List<Point>> strokes, RingDetector.RingGlyph ring, CandidateGenerationSettings settings) {
        if (strokes.isEmpty()) return new ArrayList<>();
        
        int n = strokes.size();
        double refSize = ring != null ? ring.radius() * 2 : 1.0;
        double directThresh = 0.02; // direct endpoint proximity
        double directThreshSq = directThresh * directThresh;
        double maxGroupSpan = refSize * settings.maxInternalGapRatio() * 2; // max span before splitting
        
        // Build direct connectivity based on endpoint proximity
        boolean[][] connected = new boolean[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                List<Point> s1 = strokes.get(i);
                List<Point> s2 = strokes.get(j);
                
                if (checkStrokeProximity(s1, s2, directThreshSq)) {
                    connected[i][j] = true;
                    connected[j][i] = true;
                }
            }
        }
        
        // Find connected components via DFS
        boolean[] visited = new boolean[n];
        List<PrimitiveStrokeGroup> groups = new ArrayList<>();
        int groupId = 0;
        
        for (int i = 0; i < n; i++) {
            if (!visited[i]) {
                List<Integer> comp = new ArrayList<>();
                dfs(i, connected, visited, comp);
                
                // Check if the component is too large (spans too much space)
                // If so, split it into sub-groups
                List<List<Integer>> subGroups = maybeSplitComponent(comp, strokes, maxGroupSpan);
                
                for (List<Integer> subGroup : subGroups) {
                    PrimitiveStrokeGroup group = buildPrimitiveGroup(groupId++, subGroup, strokes, ring);
                    groups.add(group);
                }
            }
        }
        
        return groups;
    }
    
    /** Check if two strokes are close enough to be in the same primitive group. */
    private static boolean checkStrokeProximity(List<Point> s1, List<Point> s2, double threshSq) {
        if (s1.isEmpty() || s2.isEmpty()) return false;
        
        // Check endpoints first (fast path)
        Point s1s = s1.get(0), s1e = s1.get(s1.size() - 1);
        Point s2s = s2.get(0), s2e = s2.get(s2.size() - 1);
        if (distSq(s1s, s2s) < threshSq || distSq(s1s, s2e) < threshSq || 
            distSq(s1e, s2s) < threshSq || distSq(s1e, s2e) < threshSq) {
            return true;
        }
        
        // Check segment proximity (sample every few points)
        int step1 = Math.max(1, s1.size() / 16);
        int step2 = Math.max(1, s2.size() / 16);
        for (int i = 0; i < s1.size(); i += step1) {
            for (int j = 0; j < s2.size(); j += step2) {
                if (distSq(s1.get(i), s2.get(j)) < threshSq) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * If a connected component spans too much space, split it into spatially coherent sub-groups.
     * This prevents transitive merging from combining distant strokes that happen to be
     * connected through a chain of intermediary strokes.
     */
    private static List<List<Integer>> maybeSplitComponent(List<Integer> comp, List<List<Point>> strokes, double maxSpan) {
        if (comp.size() <= 1) {
            List<List<Integer>> result = new ArrayList<>();
            result.add(comp);
            return result;
        }
        
        // Compute bounding box of this component
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int idx : comp) {
            for (Point p : strokes.get(idx)) {
                minX = Math.min(minX, p.x);
                maxX = Math.max(maxX, p.x);
                minY = Math.min(minY, p.y);
                maxY = Math.max(maxY, p.y);
            }
        }
        double spanX = maxX - minX;
        double spanY = maxY - minY;
        
        // If the component fits within the max span, keep it as-is
        if (spanX <= maxSpan && spanY <= maxSpan) {
            List<List<Integer>> result = new ArrayList<>();
            result.add(comp);
            return result;
        }
        
        // Split along the larger axis using centroid k-means (simple 2-split)
        boolean splitX = spanX > spanY;
        double splitPoint = splitX ? (minX + maxX) / 2 : (minY + maxY) / 2;
        
        List<Integer> left = new ArrayList<>();
        List<Integer> right = new ArrayList<>();
        
        for (int idx : comp) {
            double centroid = 0;
            List<Point> s = strokes.get(idx);
            for (Point p : s) {
                centroid += splitX ? p.x : p.y;
            }
            centroid /= s.size();
            
            if (centroid < splitPoint) {
                left.add(idx);
            } else {
                right.add(idx);
            }
        }
        
        // If the split is degenerate (everything on one side), don't split
        if (left.isEmpty() || right.isEmpty()) {
            List<List<Integer>> result = new ArrayList<>();
            result.add(comp);
            return result;
        }
        
        // Recursively check if sub-groups need further splitting
        List<List<Integer>> result = new ArrayList<>();
        result.addAll(maybeSplitComponent(left, strokes, maxSpan));
        result.addAll(maybeSplitComponent(right, strokes, maxSpan));
        return result;
    }
    
    /** Build a PrimitiveStrokeGroup from a list of stroke indices. */
    private static PrimitiveStrokeGroup buildPrimitiveGroup(int groupId, List<Integer> indices, List<List<Point>> strokes, RingDetector.RingGlyph ring) {
        List<List<Point>> groupStrokes = new ArrayList<>();
        List<Integer> sourceIndices = new ArrayList<>();
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double cx = 0, cy = 0;
        int totalPoints = 0;
        double pathLength = 0;
        
        for (int idx : indices) {
            List<Point> s = strokes.get(idx);
            sourceIndices.add(idx);
            groupStrokes.add(s);
            
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
        return new PrimitiveStrokeGroup(groupId, sourceIndices, groupStrokes, bounds, new Point(cx, cy), pathLength, radiusNorm, angleAroundRing);
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
