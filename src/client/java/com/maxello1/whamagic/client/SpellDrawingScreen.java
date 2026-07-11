package com.maxello1.whamagic.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import com.maxello1.whamagic.parser.Point;
import com.maxello1.whamagic.parser.SpellDictionary;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class SpellDrawingScreen extends Screen {
    private final InteractionHand hand;
    private final List<List<Point>> strokes = new ArrayList<>();
    private List<Point> currentStroke = null;
    private String currentSpellStatus = "";
    private final java.util.UUID sessionId = java.util.UUID.randomUUID();
    private int revision = 0;
    
    private double canvasX, canvasY, canvasSize;

    // Eraser state
    private boolean eraserMode = false;
    private boolean erasing = false;
    private static final double ERASER_RADIUS_NORM = 0.015;
    private double eraserX = 0, eraserY = 0;

    public SpellDrawingScreen(InteractionHand hand, List<List<Point>> existingStrokes) {
        super(Component.literal("Draw Spell"));
        this.hand = hand;
        if (existingStrokes != null) {
            this.strokes.addAll(existingStrokes);
            com.maxello1.whamagic.parser.SpellParser.ParseResult result = com.maxello1.whamagic.parser.SpellParser.parse(strokes);
            currentSpellStatus = result.ir.statusMessage();
        }
        // Ensure dictionary is loaded on the client
        SpellDictionary.ensureLoaded();
    }

    @Override
    protected void init() {
        super.init();
        this.canvasSize = Math.max(100, Math.min(this.width, this.height) - 40);
        this.canvasX = (this.width - this.canvasSize) / 2.0;
        this.canvasY = (this.height - this.canvasSize) / 2.0;
    }

    private Point toNormalized(double mouseX, double mouseY) {
        return new Point((mouseX - canvasX) / canvasSize, (mouseY - canvasY) / canvasSize);
    }
    
    private Point toScreen(Point p) {
        return new Point(p.x * canvasSize + canvasX, p.y * canvasSize + canvasY);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        // Middle-click toggles eraser mode
        if (event.button() == 2) {
            eraserMode = !eraserMode;
            return true;
        }

        if (event.button() == 0) {
            if (eraserMode) {
                // Start erasing
                erasing = true;
                eraseAtPosition(event.x(), event.y());
                return true;
            } else {
                // Start drawing
                currentStroke = new ArrayList<>();
                currentStroke.add(toNormalized(event.x(), event.y()));
                return true;
            }
        }
        // Right-click to clear all
        if (event.button() == 1) {
            strokes.clear();
            currentStroke = null;
            currentSpellStatus = "Cleared";
            revision++;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double deltaX, double deltaY) {
        if (event.button() == 0) {
            if (eraserMode && erasing) {
                eraseAtPosition(event.x(), event.y());
                return true;
            } else if (currentStroke != null) {
                currentStroke.add(toNormalized(event.x(), event.y()));
                return true;
            }
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        if (event.button() == 0) {
            if (eraserMode && erasing) {
                erasing = false;
                revision++;
                reparse();
                return true;
            } else if (currentStroke != null && !currentStroke.isEmpty()) {
                currentStroke.add(toNormalized(event.x(), event.y()));
                strokes.add(new ArrayList<>(currentStroke));
                currentStroke.clear();
                revision++;
                reparse();
            }
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        // Press 'E' to toggle eraser mode
        if (event.key() == 69) { // GLFW_KEY_E
            eraserMode = !eraserMode;
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        eraserX = mouseX;
        eraserY = mouseY;
        super.mouseMoved(mouseX, mouseY);
    }

    /**
     * Erase all stroke points within ERASER_RADIUS_NORM of the given position.
     * Strokes that get split in the middle are broken into two sub-strokes.
     * Strokes that become too short (< 2 points) are removed entirely.
     */
    private void eraseAtPosition(double mx, double my) {
        eraserX = mx;
        eraserY = my;
        Point normMouse = toNormalized(mx, my);
        double rSq = ERASER_RADIUS_NORM * ERASER_RADIUS_NORM;
        List<List<Point>> newStrokes = new ArrayList<>();

        for (List<Point> stroke : strokes) {
            // Walk through the stroke, collecting segments that are outside the eraser
            List<Point> segment = new ArrayList<>();
            for (Point p : stroke) {
                double dx = p.x - normMouse.x;
                double dy = p.y - normMouse.y;
                if (dx * dx + dy * dy <= rSq) {
                    // This point is inside the eraser radius — break the segment
                    if (segment.size() >= 2) {
                        newStrokes.add(new ArrayList<>(segment));
                    }
                    segment.clear();
                } else {
                    segment.add(p);
                }
            }
            // Don't forget the trailing segment
            if (segment.size() >= 2) {
                newStrokes.add(segment);
            }
        }

        strokes.clear();
        strokes.addAll(newStrokes);
    }

    private void reparse() {
        com.maxello1.whamagic.parser.SpellParser.ParseResult result = com.maxello1.whamagic.parser.SpellParser.parse(strokes);
        currentSpellStatus = result.ir.statusMessage();
    }

    @Override
    public void onClose() {
        System.out.println("Sending SpellDrawnPacket with strokes");
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                new com.maxello1.whamagic.network.SpellDrawnPacket(sessionId, hand, revision, strokes));
        super.onClose();
    }

    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Draw saved strokes
        for (List<Point> stroke : strokes) {
            drawStroke(graphics, stroke, 0xFFFFFFFF);
        }

        // Draw current stroke
        if (currentStroke != null) {
            drawStroke(graphics, currentStroke, 0xFFFF5555);
        }

        // Status text
        graphics.text(this.font, currentSpellStatus, 10, 10, 0xFF00FF00);
        String modeText = eraserMode ? "ERASER MODE (E to toggle)" : "Right-click to clear | ESC to save & close";
        graphics.text(this.font, modeText, 10, 22, eraserMode ? 0xFFFF8844 : 0xFFAAAAAA);
        graphics.text(this.font, "Strokes: " + strokes.size() + " | Middle-click or E: toggle eraser", 10, 34, 0xFFAAAAAA);

        // Draw Canvas Border
        graphics.fill((int)canvasX, (int)canvasY, (int)(canvasX + canvasSize), (int)(canvasY + canvasSize), 0x33FFFFFF);
        
        // Draw eraser cursor
        if (eraserMode) {
            int r = (int) (ERASER_RADIUS_NORM * canvasSize);
            int cx = mouseX;
            int cy = mouseY;
            // Draw a circle outline for the eraser
            for (int angle = 0; angle < 360; angle += 5) {
                double rad = Math.toRadians(angle);
                int px = cx + (int)(r * Math.cos(rad));
                int py = cy + (int)(r * Math.sin(rad));
                graphics.fill(px, py, px + 1, py + 1, 0xFFFF4444);
            }
        }

        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private void drawStroke(net.minecraft.client.gui.GuiGraphicsExtractor graphics, List<Point> stroke, int color) {
        if (stroke.size() < 2) {
            if (stroke.size() == 1) {
                Point p = toScreen(stroke.get(0));
                graphics.fill((int)p.x, (int)p.y, (int)p.x + 2, (int)p.y + 2, color);
            }
            return;
        }
        for (int i = 0; i < stroke.size() - 1; i++) {
            Point p1 = toScreen(stroke.get(i));
            Point p2 = toScreen(stroke.get(i + 1));
            
            // Interpolate line
            double dx = p2.x - p1.x;
            double dy = p2.y - p1.y;
            double dist = Math.hypot(dx, dy);
            int steps = Math.max(1, (int) Math.ceil(dist));
            
            for (int step = 0; step <= steps; step++) {
                double t = (double) step / steps;
                int x = (int) (p1.x + dx * t);
                int y = (int) (p1.y + dy * t);
                graphics.fill(x, y, x + 2, y + 2, color);
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
