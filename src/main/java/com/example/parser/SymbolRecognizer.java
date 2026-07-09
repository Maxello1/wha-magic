package com.example.parser;

import java.util.List;

public class SymbolRecognizer {
    
    /**
     * Basic prototype recognizer.
     * Evaluates if a list of strokes form a valid prepared spell.
     */
    public static SpellResult recognize(List<List<Point>> strokes) {
        if (strokes == null || strokes.isEmpty()) {
            return new SpellResult(false, "No strokes");
        }
        
        // MVP: Check if any stroke represents a closed ring.
        boolean hasRing = false;
        for (List<Point> stroke : strokes) {
            if (isClosedRing(stroke)) {
                hasRing = true;
                break;
            }
        }
        
        if (hasRing) {
            return new SpellResult(true, "Fire"); // Hardcoded MVP mapping
        }
        
        return new SpellResult(false, "Unknown or incomplete");
    }
    
    private static boolean isClosedRing(List<Point> stroke) {
        if (stroke.size() < 10) return false; // Too short to be a ring
        
        Point start = stroke.get(0);
        Point end = stroke.get(stroke.size() - 1);
        
        double distance = Math.hypot(start.x - end.x, start.y - end.y);
        // If the start and end are close to each other, it's a closed shape
        return distance < 50.0;
    }
    
    public static class SpellResult {
        public final boolean isValid;
        public final String element;
        
        public SpellResult(boolean isValid, String element) {
            this.isValid = isValid;
            this.element = element;
        }
    }
}
