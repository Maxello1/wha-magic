package com.maxello1.whamagic.magic;

import com.maxello1.whamagic.parser.Point;
import java.util.List;
import java.util.ArrayList;

public class LayerMapper {
    public static class LayeredStrokes {
        public final List<List<Point>> coreStrokes = new ArrayList<>();
        public final List<List<Point>> signStrokes = new ArrayList<>();
    }

    public static LayeredStrokes mapLayers(List<List<Point>> strokes, RingDetector.RingDetection detection) {
        LayeredStrokes result = new LayeredStrokes();
        
        if (detection == null || detection.glyph() == null) {
            result.coreStrokes.addAll(strokes);
            return result;
        }
        
        RingDetector.RingGlyph ring = detection.glyph();
        
        for (int i = 0; i < strokes.size(); i++) {
            if (detection.ringStrokeIndices().contains(i)) {
                continue;
            }
            
            List<Point> stroke = strokes.get(i);
            
            // Calculate average radius of the stroke's points from the ring center
            double avgR = 0;
            for (Point p : stroke) {
                avgR += Math.hypot(p.x - ring.center().x, p.y - ring.center().y);
            }
            avgR /= stroke.size();
            
            if (avgR < ring.radius() * 0.95) {
                result.coreStrokes.add(stroke);
            } else {
                result.signStrokes.add(stroke);
            }
        }
        
        return result;
    }
}
