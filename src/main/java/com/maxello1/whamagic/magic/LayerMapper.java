package com.maxello1.whamagic.magic;

import com.maxello1.whamagic.parser.Point;
import java.util.List;
import java.util.ArrayList;

public class LayerMapper {
    public static class LayeredStrokes {
        public final List<List<Point>> coreStrokes = new ArrayList<>();
        public final List<List<Point>> signStrokes = new ArrayList<>();
    }

    public static LayeredStrokes mapLayers(List<List<Point>> strokes, RingDetector.RingGlyph ring) {
        LayeredStrokes result = new LayeredStrokes();
        
        if (ring == null) {
            result.coreStrokes.addAll(strokes);
            return result;
        }
        
        for (List<Point> stroke : strokes) {
            // Calculate center of stroke
            double cx = 0, cy = 0;
            for (Point p : stroke) { cx += p.x; cy += p.y; }
            cx /= stroke.size();
            cy /= stroke.size();
            
            double distToCenter = Math.hypot(cx - ring.center().x, cy - ring.center().y);
            
            // If the stroke is the ring itself, ignore it
            // Simple heuristic: if it's very close to the ring radius, it's the ring
            if (Math.abs(distToCenter - ring.radius()) < ring.radius() * 0.2) {
                continue; // It's probably the ring stroke
            }
            
            if (distToCenter < ring.radius() * 0.45) {
                result.coreStrokes.add(stroke);
            } else if (distToCenter < ring.radius() * 1.1) {
                result.signStrokes.add(stroke);
            }
        }
        
        return result;
    }
}
