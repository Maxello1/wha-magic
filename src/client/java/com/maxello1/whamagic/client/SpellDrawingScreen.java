package com.maxello1.whamagic.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import com.maxello1.whamagic.parser.Point;
import com.maxello1.whamagic.parser.SpellDictionary;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class SpellDrawingScreen extends Screen {
    private final InteractionHand hand;
    private final List<List<Point>> strokes = new ArrayList<>();
    private List<Point> currentStroke = null;
    private String currentSpellStatus = "";
    
    private double canvasX, canvasY, canvasSize;

    // Eraser state
    private boolean eraserMode = false;
    private boolean erasing = false;
    private static final double ERASER_RADIUS_NORM = 0.015;
    private double eraserX = 0, eraserY = 0;
    
    // Undo/Redo stack
    private final List<List<List<Point>>> undoStack = new ArrayList<>();
    private final List<List<List<Point>>> redoStack = new ArrayList<>();
    
    // Debug overlay: 0=off, 1=basic, 2=verbose
    private int debugMode = 0;
    private String parserDebugInfo = "";
    private com.maxello1.whamagic.parser.SpellParser.ParseResult lastParseResult = null;
    
    // Sample recording feedback
    private String sampleFeedback = "";
    private long sampleFeedbackTime = 0;

    public SpellDrawingScreen(InteractionHand hand, List<List<Point>> existingStrokes) {
        super(Component.literal("Draw Spell"));
        this.hand = hand;
        if (existingStrokes != null) {
            this.strokes.addAll(existingStrokes);
            reparse();
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

    private boolean isInsideCanvas(double mouseX, double mouseY) {
        return mouseX >= canvasX && mouseX <= canvasX + canvasSize && mouseY >= canvasY && mouseY <= canvasY + canvasSize;
    }

    private Point toNormalized(double mouseX, double mouseY) {
        double nx = (mouseX - canvasX) / canvasSize;
        double ny = (mouseY - canvasY) / canvasSize;
        return new Point(Math.max(0.0, Math.min(1.0, nx)), Math.max(0.0, Math.min(1.0, ny)));
    }
    
    private Point toScreen(Point p) {
        return new Point(p.x * canvasSize + canvasX, p.y * canvasSize + canvasY);
    }
    
    private void saveState() {
        List<List<Point>> copy = new ArrayList<>(strokes.size());
        for (List<Point> stroke : strokes) {
            copy.add(new ArrayList<>(stroke));
        }
        undoStack.add(copy);
        redoStack.clear();
    }

    private void undo() {
        if (!undoStack.isEmpty()) {
            List<List<Point>> copy = new ArrayList<>(strokes.size());
            for (List<Point> stroke : strokes) {
                copy.add(new ArrayList<>(stroke));
            }
            redoStack.add(copy);
            
            strokes.clear();
            strokes.addAll(undoStack.remove(undoStack.size() - 1));
            reparse();
        }
    }

    private void redo() {
        if (!redoStack.isEmpty()) {
            List<List<Point>> copy = new ArrayList<>(strokes.size());
            for (List<Point> stroke : strokes) {
                copy.add(new ArrayList<>(stroke));
            }
            undoStack.add(copy);
            
            strokes.clear();
            strokes.addAll(redoStack.remove(redoStack.size() - 1));
            reparse();
        }
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
                saveState();
                erasing = true;
                eraseAtPosition(event.x(), event.y());
                return true;
            } else if (isInsideCanvas(event.x(), event.y()) && strokes.size() < com.maxello1.whamagic.config.WhaServerConfig.INSTANCE.network.maxStrokes) {
                saveState();
                currentStroke = new ArrayList<>();
                currentStroke.add(toNormalized(event.x(), event.y()));
                return true;
            }
        }
        // Right-click to clear all
        if (event.button() == 1) {
            saveState();
            strokes.clear();
            currentStroke = null;
            reparse();
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
                int totalPoints = currentStroke.size();
                for (List<Point> s : strokes) totalPoints += s.size();
                if (isInsideCanvas(event.x(), event.y()) && currentStroke.size() < com.maxello1.whamagic.config.WhaServerConfig.INSTANCE.network.maxPointsPerStroke && totalPoints < com.maxello1.whamagic.config.WhaServerConfig.INSTANCE.network.maxTotalPoints) {
                    currentStroke.add(toNormalized(event.x(), event.y()));
                }
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
                reparse();
                return true;
            } else if (currentStroke != null) {
                int totalPoints = currentStroke.size();
                for (List<Point> s : strokes) totalPoints += s.size();
                if (isInsideCanvas(event.x(), event.y()) && currentStroke.size() < com.maxello1.whamagic.config.WhaServerConfig.INSTANCE.network.maxPointsPerStroke && totalPoints < com.maxello1.whamagic.config.WhaServerConfig.INSTANCE.network.maxTotalPoints) {
                    currentStroke.add(toNormalized(event.x(), event.y()));
                }
                if (currentStroke.size() >= 2) {
                    strokes.add(new ArrayList<>(currentStroke));
                }
                currentStroke = null;
                reparse();
                return true;
            }
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_E) {
            eraserMode = !eraserMode;
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_F3) {
            // Cycle: Off(0) -> Basic(1) -> Verbose(2) -> Off(0)
            debugMode = (debugMode + 1) % 3;
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_F5) {
            // Save sample
            String path = com.maxello1.whamagic.dev.SampleRecorder.saveSample(strokes, lastParseResult, null);
            if (path != null) {
                sampleFeedback = "Sample saved: " + path;
            } else {
                sampleFeedback = "Failed to save sample";
            }
            sampleFeedbackTime = System.currentTimeMillis();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_Z) {
            undo();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_Y) {
            redo();
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

    private void eraseAtPosition(double mx, double my) {
        eraserX = mx;
        eraserY = my;
        Point normMouse = toNormalized(mx, my);
        double rSq = ERASER_RADIUS_NORM * ERASER_RADIUS_NORM;
        List<List<Point>> newStrokes = new ArrayList<>();

        for (List<Point> stroke : strokes) {
            List<Point> segment = new ArrayList<>();
            for (Point p : stroke) {
                double dx = p.x - normMouse.x;
                double dy = p.y - normMouse.y;
                if (dx * dx + dy * dy <= rSq) {
                    if (segment.size() >= 2) {
                        newStrokes.add(new ArrayList<>(segment));
                    }
                    segment.clear();
                } else {
                    segment.add(p);
                }
            }
            if (segment.size() >= 2) {
                newStrokes.add(segment);
            }
        }

        strokes.clear();
        strokes.addAll(newStrokes);
    }

    private void reparse() {
        if (strokes.isEmpty()) {
            currentSpellStatus = "Cleared";
            parserDebugInfo = "";
            lastParseResult = null;
            return;
        }
        lastParseResult = com.maxello1.whamagic.parser.SpellParser.parse(strokes);
        currentSpellStatus = lastParseResult.ir.statusMessage();
        if (lastParseResult.isValidSpell()) {
            parserDebugInfo = "Valid: " + lastParseResult.ir.displayName();
        } else {
            parserDebugInfo = "Invalid or Incomplete";
        }
    }

    @Override
    public void onClose() {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                new com.maxello1.whamagic.network.SaveSpellPayload(hand, strokes));
        super.onClose();
    }

    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Draw Canvas Background
        graphics.fill((int)canvasX, (int)canvasY, (int)(canvasX + canvasSize), (int)(canvasY + canvasSize), 0x55000000);
        // Draw Canvas Border
        graphics.fill((int)canvasX, (int)canvasY, (int)(canvasX + canvasSize), (int)canvasY + 1, 0x88FFFFFF);
        graphics.fill((int)canvasX, (int)(canvasY + canvasSize) - 1, (int)(canvasX + canvasSize), (int)(canvasY + canvasSize), 0x88FFFFFF);
        graphics.fill((int)canvasX, (int)canvasY, (int)canvasX + 1, (int)(canvasY + canvasSize), 0x88FFFFFF);
        graphics.fill((int)(canvasX + canvasSize) - 1, (int)canvasY, (int)(canvasX + canvasSize), (int)(canvasY + canvasSize), 0x88FFFFFF);

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
        graphics.text(this.font, "Strokes: " + strokes.size() + " | Middle-click or E: toggle eraser | Z/Y: Undo/Redo", 10, 34, 0xFFAAAAAA);
        
        // Sample recording feedback (shown for 3 seconds)
        if (!sampleFeedback.isEmpty() && System.currentTimeMillis() - sampleFeedbackTime < 3000) {
            graphics.text(this.font, sampleFeedback, 10, this.height - 12, 0xFF55FF55);
        }
        
        // Debug overlay
        if (debugMode > 0) {
            renderDebugOverlay(graphics, mouseX, mouseY);
        }

        // Draw eraser cursor
        if (eraserMode) {
            int r = (int) (ERASER_RADIUS_NORM * canvasSize);
            int cx = mouseX;
            int cy = mouseY;
            for (int angle = 0; angle < 360; angle += 5) {
                double rad = Math.toRadians(angle);
                int px = cx + (int)(r * Math.cos(rad));
                int py = cy + (int)(r * Math.sin(rad));
                graphics.fill(px, py, px + 1, py + 1, 0xFFFF4444);
            }
        }

        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }
    
    private void renderDebugOverlay(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        String modeName = debugMode == 1 ? "Basic" : "Verbose";
        graphics.text(this.font, "Debug [" + modeName + "]: " + parserDebugInfo, 10, 46, 0xFFFFFF00);
        graphics.text(this.font, String.format("Mouse Norm: %.3f, %.3f", toNormalized(mouseX, mouseY).x, toNormalized(mouseX, mouseY).y), 10, 58, 0xFFFFFF00);
        graphics.text(this.font, "F3: cycle debug | F5: save sample", 10, 70, 0xFF888888);
        
        if (lastParseResult == null || lastParseResult.debugResult == null) return;
        
        var debug = lastParseResult.debugResult;
        int unknownCount = debug.unknowns() != null ? debug.unknowns().size() : 0;
        
        // Summary line
        graphics.text(this.font, String.format("Candidates: %d | Selected: %d | Calls: %d | Unknowns: %d%s",
                debug.candidateCount(), debug.selectedCandidateCount(),
                debug.recognitionCalls(), unknownCount,
                debug.candidateLimitReached() ? " [LIMIT]" : ""),
                10, 82, 0xFFFFFF00);
        
        // === VERBOSE: Primitive group boxes (blue) ===
        if (debugMode >= 2 && debug.primitiveGroups() != null) {
            for (var group : debug.primitiveGroups()) {
                if (group.bounds() != null) {
                    int x1 = (int)(group.bounds().minX() * canvasSize + canvasX);
                    int y1 = (int)(group.bounds().minY() * canvasSize + canvasY);
                    int x2 = (int)(group.bounds().maxX() * canvasSize + canvasX);
                    int y2 = (int)(group.bounds().maxY() * canvasSize + canvasY);
                    drawRectOutline(graphics, x1, y1, x2, y2, 0x555555FF); // Blue
                }
            }
        }
        
        // === VERBOSE: All generated candidate boxes (grey) ===
        if (debugMode >= 2) {
            for (com.maxello1.whamagic.magic.SymbolCandidate cand : debug.generatedCandidates()) {
                if (cand.bounds() != null) {
                    int x1 = (int)(cand.bounds().minX() * canvasSize + canvasX);
                    int y1 = (int)(cand.bounds().minY() * canvasSize + canvasY);
                    int x2 = (int)(cand.bounds().maxX() * canvasSize + canvasX);
                    int y2 = (int)(cand.bounds().maxY() * canvasSize + canvasY);
                    drawRectOutline(graphics, x1, y1, x2, y2, 0x33AAAAAA); // Grey
                }
            }
        }
        
        // === BASIC+: Recognized sigils (green with element color) ===
        if (debug.sigils() != null) {
            for (com.maxello1.whamagic.magic.RecognizedSigil sigil : debug.sigils()) {
                if (sigil.bounds() != null) {
                    int x1 = (int)(sigil.bounds().minX() * canvasSize + canvasX);
                    int y1 = (int)(sigil.bounds().minY() * canvasSize + canvasY);
                    int x2 = (int)(sigil.bounds().maxX() * canvasSize + canvasX);
                    int y2 = (int)(sigil.bounds().maxY() * canvasSize + canvasY);
                    
                    int color = getElementColor(sigil.element());
                    drawRectOutline(graphics, x1, y1, x2, y2, color);
                    
                    String label = sigil.id() != null ? sigil.id().getPath() : "unknown";
                    graphics.text(this.font, label + " " + String.format("%.0f%%", sigil.recognitionConfidence() * 100), x1, y1 - 10, color);
                    
                    // VERBOSE: Show alternatives and gap
                    if (debugMode >= 2 && sigil.alternatives() != null && !sigil.alternatives().isEmpty()) {
                        int altY = y2 + 2;
                        for (int i = 0; i < Math.min(3, sigil.alternatives().size()); i++) {
                            var alt = sigil.alternatives().get(i);
                            String altText = String.format("#%d %s %.0f%% (cov=%.0f%% struct=%.0f%%)",
                                    i + 1, alt.displayName(), alt.rawScore() * 100,
                                    alt.templateCoverage() * 100, alt.structuralScore() * 100);
                            graphics.text(this.font, altText, x1, altY, 0xFFAAAA00);
                            altY += 10;
                        }
                        if (sigil.alternatives().size() >= 2) {
                            double gap = sigil.alternatives().get(0).rawScore() - sigil.alternatives().get(1).rawScore();
                            graphics.text(this.font, String.format("Gap: %.1f%%", gap * 100), x1, altY, 0xFFAAAA00);
                        }
                    }
                }
            }
        }
        
        // === BASIC+: Recognized signs (green) ===
        if (debug.signs() != null) {
            for (com.maxello1.whamagic.magic.RecognizedSign sign : debug.signs()) {
                double rad = Math.toRadians(sign.angleAroundRing());
                int cx = (int)(canvasX + canvasSize / 2);
                int cy = (int)(canvasY + canvasSize / 2);
                int px = cx + (int)(Math.cos(rad) * canvasSize * 0.4);
                int py = cy + (int)(Math.sin(rad) * canvasSize * 0.4);
                graphics.text(this.font, sign.id() + " " + String.format("%.0f%%", sign.confidence() * 100), px, py, 0xFF00FF00);
            }
        }
        
        // === BASIC+: Unknown symbols (red) ===
        if (debug.unknowns() != null) {
            for (com.maxello1.whamagic.magic.UnknownSymbol unk : debug.unknowns()) {
                if (unk.bounds() != null) {
                    int x1 = (int)(unk.bounds().minX() * canvasSize + canvasX);
                    int y1 = (int)(unk.bounds().minY() * canvasSize + canvasY);
                    int x2 = (int)(unk.bounds().maxX() * canvasSize + canvasX);
                    int y2 = (int)(unk.bounds().maxY() * canvasSize + canvasY);
                    
                    int color = unk.state() == com.maxello1.whamagic.magic.CandidateState.AMBIGUOUS ? 0xFFFFFF00 : 0xFFFF4444;
                    drawRectOutline(graphics, x1, y1, x2, y2, color);
                    graphics.text(this.font, "UNKNOWN", x1, y1 - 10, color);
                    
                    // VERBOSE: Show rejection reason and top alternatives
                    if (debugMode >= 2) {
                        graphics.text(this.font, "Reason: " + unk.rejectionReason().name(), x1, y2 + 2, 0xFFFF6666);
                        if (unk.alternatives() != null) {
                            int altY = y2 + 12;
                            for (int i = 0; i < Math.min(3, unk.alternatives().size()); i++) {
                                var alt = unk.alternatives().get(i);
                                String altText = String.format("#%d %s [%s] %.0f%%",
                                        i + 1, alt.displayName(), alt.kind().name(), alt.rawScore() * 100);
                                graphics.text(this.font, altText, x1, altY, 0xFFFF8888);
                                altY += 10;
                            }
                        }
                    }
                }
            }
        }
        
        // === VERBOSE: Evaluated candidate details ===
        if (debugMode >= 2 && debug.allEvaluated() != null) {
            int infoY = 94;
            graphics.text(this.font, "Prim groups: " + debug.primitiveGroupCount(), 10, infoY, 0xFF88CCFF);
            infoY += 10;
            for (int i = 0; i < Math.min(8, debug.allEvaluated().size()); i++) {
                var eval = debug.allEvaluated().get(i);
                String evalText = String.format("Cand#%d: sigil=%.0f%% sign=%.0f%% strokes=%s",
                        eval.cand.id(),
                        eval.sigilRoleScore * 100, eval.signRoleScore * 100,
                        eval.cand.sourceStrokeIndices().toString());
                graphics.text(this.font, evalText, 10, infoY, 0xFF88CCFF);
                infoY += 10;
            }
        }
    }
    
    private int getElementColor(com.maxello1.whamagic.magic.ElementType element) {
        if (element == null) return 0xFF88CCFF;
        return switch (element) {
            case FIRE -> 0xFFFF5555;
            case WATER -> 0xFF5555FF;
            case EARTH -> 0xFF55FF55;
            case WIND -> 0xFFFFFF55;
            case LIGHT -> 0xFFFF55FF;
        };
    }

    private void drawRectOutline(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int color) {
        graphics.fill(x1, y1, x2, y1 + 1, color);
        graphics.fill(x1, y2 - 1, x2, y2, color);
        graphics.fill(x1, y1, x1 + 1, y2, color);
        graphics.fill(x2 - 1, y1, x2, y2, color);
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
