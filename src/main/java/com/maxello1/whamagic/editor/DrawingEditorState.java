package com.maxello1.whamagic.editor;

import com.maxello1.whamagic.network.BoundedStrokeStreamCodec;
import com.maxello1.whamagic.network.DrawingLimits;
import com.maxello1.whamagic.parser.Point;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/** Mutable drawing state with bounded history and a cached committed-point count. */
public final class DrawingEditorState {
    public enum PointAdmission {
        ACCEPTED,
        TOO_CLOSE,
        STROKE_LIMIT,
        TOTAL_LIMIT
    }

    private final DrawingLimits limits;
    private final int historyLimit;
    private final double minimumPointDistanceSquared;
    private final List<List<Point>> strokes = new ArrayList<>();
    private final List<List<Point>> readOnlyStrokes = Collections.unmodifiableList(strokes);
    private final Deque<List<List<Point>>> undoHistory = new ArrayDeque<>();
    private final Deque<List<List<Point>>> redoHistory = new ArrayDeque<>();
    private int totalPointCount;

    public DrawingEditorState(
            List<List<Point>> initialStrokes,
            DrawingLimits limits,
            double minimumPointDistance,
            int historyLimit) {
        this.limits = Objects.requireNonNull(limits, "limits");
        if (!Double.isFinite(minimumPointDistance) || minimumPointDistance < 0.0) {
            throw new IllegalArgumentException("minimumPointDistance must be finite and non-negative");
        }
        if (historyLimit < 1) {
            throw new IllegalArgumentException("historyLimit must be positive");
        }
        this.minimumPointDistanceSquared = minimumPointDistance * minimumPointDistance;
        this.historyLimit = historyLimit;
        replaceStrokes(initialStrokes);
    }

    public DrawingLimits limits() {
        return limits;
    }

    public List<List<Point>> strokes() {
        return readOnlyStrokes;
    }

    public int strokeCount() {
        return strokes.size();
    }

    public int totalPointCount() {
        return totalPointCount;
    }

    public boolean canStartStroke() {
        return strokes.size() < limits.maxStrokes()
                && totalPointCount < limits.maxTotalPoints();
    }

    public PointAdmission admissionFor(
            Point previousPoint,
            Point nextPoint,
            int currentStrokePointCount) {
        Objects.requireNonNull(nextPoint, "nextPoint");
        if (currentStrokePointCount >= limits.maxPointsPerStroke()) {
            return PointAdmission.STROKE_LIMIT;
        }
        if (totalPointCount + currentStrokePointCount >= limits.maxTotalPoints()) {
            return PointAdmission.TOTAL_LIMIT;
        }
        if (previousPoint != null) {
            double dx = nextPoint.x - previousPoint.x;
            double dy = nextPoint.y - previousPoint.y;
            if (dx * dx + dy * dy < minimumPointDistanceSquared) {
                return PointAdmission.TOO_CLOSE;
            }
        }
        return PointAdmission.ACCEPTED;
    }

    public void saveState() {
        pushCapped(undoHistory, snapshot());
        redoHistory.clear();
    }

    public void addStroke(List<Point> stroke) {
        Objects.requireNonNull(stroke, "stroke");
        List<List<Point>> replacement = new ArrayList<>(strokes);
        replacement.add(stroke);
        replaceStrokes(replacement);
    }

    public void replaceStrokes(List<List<Point>> replacement) {
        List<List<Point>> copy = BoundedStrokeStreamCodec.immutableValidatedCopy(
                replacement,
                limits);
        strokes.clear();
        strokes.addAll(copy);
        totalPointCount = countPoints(copy);
    }

    public void clear() {
        strokes.clear();
        totalPointCount = 0;
    }

    public boolean undo() {
        if (undoHistory.isEmpty()) {
            return false;
        }
        pushCapped(redoHistory, snapshot());
        restore(undoHistory.removeLast());
        return true;
    }

    public boolean redo() {
        if (redoHistory.isEmpty()) {
            return false;
        }
        pushCapped(undoHistory, snapshot());
        restore(redoHistory.removeLast());
        return true;
    }

    private List<List<Point>> snapshot() {
        return List.copyOf(strokes);
    }

    private void restore(List<List<Point>> snapshot) {
        strokes.clear();
        strokes.addAll(snapshot);
        totalPointCount = countPoints(snapshot);
    }

    private void pushCapped(Deque<List<List<Point>>> history, List<List<Point>> snapshot) {
        if (history.size() == historyLimit) {
            history.removeFirst();
        }
        history.addLast(snapshot);
    }

    private static int countPoints(List<List<Point>> strokes) {
        int count = 0;
        for (List<Point> stroke : strokes) {
            count += stroke.size();
        }
        return count;
    }
}
