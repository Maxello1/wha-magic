package com.example.parser;

import java.util.ArrayList;
import java.util.List;

public class UnistrokeRecognizer {

    public static class Template {
        public String name;
        public List<Point> points;

        public Template(String name, List<Point> points) {
            this.name = name;
            this.points = resample(points, 64);
            this.points = scaleTo(this.points, 250.0);
            this.points = translateToOrigin(this.points);
        }
    }

    private static final List<Template> TEMPLATES = new ArrayList<>();
    
    static {
        // Build base templates (simplified versions)
        // Fire: Circle
        List<Point> fireCircle = new ArrayList<>();
        for (int i = 0; i <= 64; i++) {
            double angle = i * 2 * Math.PI / 64;
            fireCircle.add(new Point(Math.cos(angle) * 100, Math.sin(angle) * 100));
        }
        TEMPLATES.add(new Template("Fire", fireCircle));

        // Water: Horizontal line (wavy in canon, simplified to line here)
        List<Point> waterLine = new ArrayList<>();
        waterLine.add(new Point(0, 0));
        waterLine.add(new Point(200, 0));
        TEMPLATES.add(new Template("Water", waterLine));

        // Wind: Vertical line
        List<Point> windLine = new ArrayList<>();
        windLine.add(new Point(0, 0));
        windLine.add(new Point(0, 200));
        TEMPLATES.add(new Template("Wind", windLine));
    }

    public static SymbolRecognizer.SpellResult recognize(List<List<Point>> strokes) {
        if (strokes == null || strokes.isEmpty()) {
            return new SymbolRecognizer.SpellResult(false, "No strokes");
        }

        // Combine all strokes into a single path for unistroke recognition
        List<Point> combined = new ArrayList<>();
        for (List<Point> stroke : strokes) {
            combined.addAll(stroke);
        }

        if (combined.size() < 5) {
            return new SymbolRecognizer.SpellResult(false, "Too short");
        }

        List<Point> points = resample(combined, 64);
        points = scaleTo(points, 250.0);
        points = translateToOrigin(points);

        double bestDistance = Double.MAX_VALUE;
        String bestMatch = "Unknown";

        for (Template t : TEMPLATES) {
            double dist = distanceAtBestAngle(points, t, -Math.PI/4, Math.PI/4, Math.PI/90);
            if (dist < bestDistance) {
                bestDistance = dist;
                bestMatch = t.name;
            }
        }

        // 100.0 is an arbitrary threshold for matching error
        if (bestDistance < 100.0) {
            return new SymbolRecognizer.SpellResult(true, bestMatch);
        } else {
            return new SymbolRecognizer.SpellResult(false, "Unknown (dist " + (int)bestDistance + ")");
        }
    }

    private static double distanceAtBestAngle(List<Point> points, Template T, double a, double b, double threshold) {
        double x1 = 0.38197 * a + 0.61803 * b;
        double f1 = distanceAtAngle(points, T, x1);
        double x2 = 0.61803 * a + 0.38197 * b;
        double f2 = distanceAtAngle(points, T, x2);

        while (Math.abs(b - a) > threshold) {
            if (f1 < f2) {
                b = x2;
                x2 = x1;
                f2 = f1;
                x1 = 0.38197 * a + 0.61803 * b;
                f1 = distanceAtAngle(points, T, x1);
            } else {
                a = x1;
                x1 = x2;
                f1 = f2;
                x2 = 0.61803 * a + 0.38197 * b;
                f2 = distanceAtAngle(points, T, x2);
            }
        }
        return Math.min(f1, f2);
    }

    private static double distanceAtAngle(List<Point> points, Template T, double theta) {
        List<Point> newPoints = rotateBy(points, theta);
        return pathDistance(newPoints, T.points);
    }

    private static List<Point> rotateBy(List<Point> points, double theta) {
        Point c = centroid(points);
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        List<Point> newPoints = new ArrayList<>();
        for (Point p : points) {
            double qx = (p.x - c.x) * cos - (p.y - c.y) * sin + c.x;
            double qy = (p.x - c.x) * sin + (p.y - c.y) * cos + c.y;
            newPoints.add(new Point(qx, qy));
        }
        return newPoints;
    }

    private static double pathDistance(List<Point> pts1, List<Point> pts2) {
        double d = 0.0;
        for (int i = 0; i < Math.min(pts1.size(), pts2.size()); i++) {
            d += distance(pts1.get(i), pts2.get(i));
        }
        return d / pts1.size();
    }

    private static List<Point> resample(List<Point> points, int n) {
        double I = pathLength(points) / (n - 1);
        double D = 0.0;
        List<Point> newPoints = new ArrayList<>();
        newPoints.add(points.get(0));
        
        for (int i = 1; i < points.size(); i++) {
            Point p1 = points.get(i - 1);
            Point p2 = points.get(i);
            double d = distance(p1, p2);
            
            if ((D + d) >= I) {
                double qx = p1.x + ((I - D) / d) * (p2.x - p1.x);
                double qy = p1.y + ((I - D) / d) * (p2.y - p1.y);
                Point q = new Point(qx, qy);
                newPoints.add(q);
                points.add(i, q);
                D = 0.0;
            } else {
                D += d;
            }
        }
        
        if (newPoints.size() == n - 1) {
            newPoints.add(new Point(points.get(points.size() - 1).x, points.get(points.size() - 1).y));
        }
        return newPoints;
    }

    private static List<Point> scaleTo(List<Point> points, double size) {
        BoundingBox box = boundingBox(points);
        List<Point> newPoints = new ArrayList<>();
        for (Point p : points) {
            double qx = p.x * (size / box.width);
            double qy = p.y * (size / box.height);
            newPoints.add(new Point(qx, qy));
        }
        return newPoints;
    }

    private static List<Point> translateToOrigin(List<Point> points) {
        Point c = centroid(points);
        List<Point> newPoints = new ArrayList<>();
        for (Point p : points) {
            newPoints.add(new Point(p.x - c.x, p.y - c.y));
        }
        return newPoints;
    }

    private static Point centroid(List<Point> points) {
        double x = 0.0, y = 0.0;
        for (Point p : points) {
            x += p.x;
            y += p.y;
        }
        return new Point(x / points.size(), y / points.size());
    }

    private static BoundingBox boundingBox(List<Point> points) {
        double minX = Double.MAX_VALUE, maxX = Double.MIN_VALUE, minY = Double.MAX_VALUE, maxY = Double.MIN_VALUE;
        for (Point p : points) {
            if (p.x < minX) minX = p.x;
            if (p.x > maxX) maxX = p.x;
            if (p.y < minY) minY = p.y;
            if (p.y > maxY) maxY = p.y;
        }
        return new BoundingBox(Math.max(1, maxX - minX), Math.max(1, maxY - minY));
    }

    private static double pathLength(List<Point> points) {
        double d = 0.0;
        for (int i = 1; i < points.size(); i++) {
            d += distance(points.get(i - 1), points.get(i));
        }
        return d;
    }

    private static double distance(Point p1, Point p2) {
        return Math.hypot(p1.x - p2.x, p1.y - p2.y);
    }

    public static class BoundingBox {
        public double width, height;
        public BoundingBox(double w, double h) {
            this.width = w; this.height = h;
        }
    }
}
