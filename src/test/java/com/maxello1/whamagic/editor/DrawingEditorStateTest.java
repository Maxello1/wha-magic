package com.maxello1.whamagic.editor;

import com.maxello1.whamagic.network.DrawingLimits;
import com.maxello1.whamagic.parser.Point;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DrawingEditorStateTest {
    @Test
    void samplingUsesMinimumDistanceAndServerPointLimits() {
        DrawingEditorState editor = new DrawingEditorState(
                List.of(),
                new DrawingLimits(2, 3, 4),
                0.01,
                8);
        Point first = new Point(0.25, 0.25);

        assertEquals(
                DrawingEditorState.PointAdmission.TOO_CLOSE,
                editor.admissionFor(first, new Point(0.255, 0.255), 1));
        assertEquals(
                DrawingEditorState.PointAdmission.ACCEPTED,
                editor.admissionFor(first, new Point(0.27, 0.25), 1));
        assertEquals(
                DrawingEditorState.PointAdmission.STROKE_LIMIT,
                editor.admissionFor(first, new Point(0.30, 0.25), 3));

        editor.addStroke(List.of(first, new Point(0.30, 0.25), new Point(0.35, 0.25)));
        assertEquals(3, editor.totalPointCount());
        assertEquals(
                DrawingEditorState.PointAdmission.TOTAL_LIMIT,
                editor.admissionFor(null, new Point(0.50, 0.50), 1));
    }

    @Test
    void cachedPointCountTracksReplaceUndoAndRedo() {
        DrawingEditorState editor = new DrawingEditorState(
                List.of(List.of(new Point(0.1, 0.1), new Point(0.2, 0.2))),
                new DrawingLimits(8, 8, 32),
                0.001,
                4);
        assertEquals(2, editor.totalPointCount());

        editor.saveState();
        editor.addStroke(List.of(
                new Point(0.3, 0.3),
                new Point(0.4, 0.4),
                new Point(0.5, 0.5)));
        assertEquals(5, editor.totalPointCount());
        assertTrue(editor.undo());
        assertEquals(2, editor.totalPointCount());
        assertTrue(editor.redo());
        assertEquals(5, editor.totalPointCount());
    }

    @Test
    void undoAndRedoHistoryStayCapped() {
        DrawingEditorState editor = new DrawingEditorState(
                List.of(),
                new DrawingLimits(8, 8, 32),
                0.001,
                2);
        for (int index = 0; index < 3; index++) {
            editor.saveState();
            double coordinate = 0.1 + index * 0.1;
            editor.addStroke(List.of(
                    new Point(coordinate, coordinate),
                    new Point(coordinate + 0.02, coordinate + 0.02)));
        }

        assertEquals(2, editor.undoHistorySize());
        assertTrue(editor.undo());
        assertTrue(editor.undo());
        assertFalse(editor.undo());
        assertEquals(2, editor.redoHistorySize());
    }
}
