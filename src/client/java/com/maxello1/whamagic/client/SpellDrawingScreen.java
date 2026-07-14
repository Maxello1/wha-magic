package com.maxello1.whamagic.client;

import com.maxello1.whamagic.editor.DrawingEditorState;
import com.maxello1.whamagic.network.CancelSpellEditPayload;
import com.maxello1.whamagic.network.DrawingLimits;
import com.maxello1.whamagic.network.SaveSpellPayload;
import com.maxello1.whamagic.network.SpellEditResultPayload;
import com.maxello1.whamagic.parser.Point;
import com.maxello1.whamagic.parser.ParseDetail;
import com.maxello1.whamagic.parser.SpellDictionary;
import com.maxello1.whamagic.parser.SpellParser;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class SpellDrawingScreen extends Screen {
    private static final double ERASER_RADIUS_NORM = 0.015;
    private static final double MINIMUM_POINT_DISTANCE = 0.0025;
    private static final int HISTORY_LIMIT = 64;
    private static final long PREVIEW_DEBOUNCE_MILLIS = 150;

    private final InteractionHand hand;
    private final long revision;
    private final int originalStrokeItemHash;
    private final DrawingEditorState editor;
    private List<Point> currentStroke = null;
    private String currentSpellStatus = "";
    private String editorMessage = "";
    private int editorMessageColor = 0xFFFF6666;
    private boolean previewDirty;
    private long previewDueAt;
    private boolean savePending;
    private boolean closingWithoutCancel;
    private Button saveButton;
    
    private double canvasX, canvasY, canvasSize;

    private boolean eraserMode = false;
    private boolean erasing = false;
    private double eraserX = 0, eraserY = 0;
    
    // Debug overlay: 0=off, 1=basic, 2=verbose
    private int debugMode = 0;
    private String parserDebugInfo = "";
    private com.maxello1.whamagic.parser.SpellParser.ParseResult lastParseResult = null;
    private com.maxello1.whamagic.parser.SpellParser.ParseResult fullDiagnosticsResult = null;
    
    // Sample recording feedback
    private String sampleFeedback = "";
    private long sampleFeedbackTime = 0;

    public SpellDrawingScreen(
            InteractionHand hand,
            long revision,
            int originalStrokeItemHash,
            DrawingLimits limits,
            List<List<Point>> existingStrokes) {
        super(Component.literal("Draw Spell"));
        this.hand = hand;
        this.revision = revision;
        this.originalStrokeItemHash = originalStrokeItemHash;
        this.editor = new DrawingEditorState(
                existingStrokes,
                limits,
                MINIMUM_POINT_DISTANCE,
                HISTORY_LIMIT);
        SpellDictionary.ensureLoaded();
        reparseNow();
    }

    @Override
    protected void init() {
        super.init();
        this.canvasSize = Math.max(100, Math.min(this.width - 20, this.height - 76));
        this.canvasX = (this.width - this.canvasSize) / 2.0;
        this.canvasY = Math.max(40, (this.height - this.canvasSize - 36) / 2.0);

        int buttonY = Math.min(this.height - 26, (int) (canvasY + canvasSize + 8));
        this.saveButton = addRenderableWidget(Button.builder(
                Component.literal("Save"),
                button -> submitSave()).bounds(this.width / 2 - 104, buttonY, 100, 20).build());
        addRenderableWidget(Button.builder(
                Component.literal("Cancel"),
                button -> cancelAndClose()).bounds(this.width / 2 + 4, buttonY, 100, 20).build());
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
    
    private void undo() {
        if (editor.undo()) {
            currentStroke = null;
            schedulePreview();
        }
    }

    private void redo() {
        if (editor.redo()) {
            currentStroke = null;
            schedulePreview();
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
                editor.saveState();
                erasing = true;
                eraseAtPosition(event.x(), event.y());
                return true;
            } else if (isInsideCanvas(event.x(), event.y())) {
                if (!editor.canStartStroke()) {
                    showLimitError(editor.strokeCount() >= editor.limits().maxStrokes()
                            ? "Stroke limit reached (" + editor.limits().maxStrokes() + ")."
                            : "Total point limit reached (" + editor.limits().maxTotalPoints() + ").");
                    return true;
                }
                currentStroke = new ArrayList<>();
                currentStroke.add(toNormalized(event.x(), event.y()));
                clearEditorMessage();
                return true;
            }
        }
        if (event.button() == 1) {
            if (editor.strokeCount() > 0) {
                editor.saveState();
                editor.clear();
            }
            currentStroke = null;
            schedulePreview();
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
                if (isInsideCanvas(event.x(), event.y())) {
                    appendCurrentPoint(toNormalized(event.x(), event.y()));
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
                schedulePreview();
                return true;
            } else if (currentStroke != null) {
                if (isInsideCanvas(event.x(), event.y())) {
                    appendCurrentPoint(toNormalized(event.x(), event.y()));
                }
                if (currentStroke.size() >= 2) {
                    editor.saveState();
                    editor.addStroke(currentStroke);
                }
                currentStroke = null;
                schedulePreview();
                return true;
            }
        }
        return super.mouseReleased(event);
    }

    private void appendCurrentPoint(Point point) {
        Point previous = currentStroke.isEmpty()
                ? null
                : currentStroke.get(currentStroke.size() - 1);
        DrawingEditorState.PointAdmission admission = editor.admissionFor(
                previous,
                point,
                currentStroke.size());
        switch (admission) {
            case ACCEPTED -> currentStroke.add(point);
            case STROKE_LIMIT -> showLimitError(
                    "Point limit reached for this stroke ("
                            + editor.limits().maxPointsPerStroke() + ").");
            case TOTAL_LIMIT -> showLimitError(
                    "Total point limit reached (" + editor.limits().maxTotalPoints() + ").");
            case TOO_CLOSE -> {
                // Mouse events closer than the sampling distance do not add useful geometry.
            }
        }
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
            if (debugMode > 0) {
                ensureFullDiagnostics();
            }
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_F5) {
            if (savePending) {
                showLimitError("Wait for the spell save to finish before recording a sample.");
                return true;
            }
            SpellParser.ParseResult diagnostics = ensureFullDiagnostics();
            if (minecraft != null) {
                minecraft.setScreenAndShow(new SaveRecognitionSampleScreen(
                        this,
                        new com.maxello1.whamagic.dev.RecognitionSampleCapture(
                                editor.strokes(), diagnostics)));
            }
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

        for (List<Point> stroke : editor.strokes()) {
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

        if (newStrokes.size() > editor.limits().maxStrokes()) {
            showLimitError("Erasing would exceed the stroke limit ("
                    + editor.limits().maxStrokes() + ").");
            return;
        }
        editor.replaceStrokes(newStrokes);
    }

    private void schedulePreview() {
        fullDiagnosticsResult = null;
        if (editor.strokeCount() == 0) {
            currentSpellStatus = "Cleared";
            parserDebugInfo = "";
            lastParseResult = null;
            previewDirty = false;
            return;
        }
        previewDirty = true;
        previewDueAt = System.currentTimeMillis() + PREVIEW_DEBOUNCE_MILLIS;
        currentSpellStatus = "Updating preview...";
    }

    private void reparseNow() {
        previewDirty = false;
        if (editor.strokeCount() == 0) {
            currentSpellStatus = "Cleared";
            parserDebugInfo = "";
            lastParseResult = null;
            return;
        }
        ParseDetail detail = debugMode > 0
                ? ParseDetail.FULL_DIAGNOSTICS
                : ParseDetail.PREVIEW;
        lastParseResult = SpellParser.parse(editor.strokes(), detail);
        if (detail == ParseDetail.FULL_DIAGNOSTICS) {
            fullDiagnosticsResult = lastParseResult;
        }
        updateParseStatus(lastParseResult);
    }

    private SpellParser.ParseResult ensureFullDiagnostics() {
        if (fullDiagnosticsResult == null) {
            previewDirty = false;
            fullDiagnosticsResult = SpellParser.parse(
                    editor.strokes(), ParseDetail.FULL_DIAGNOSTICS);
        }
        lastParseResult = fullDiagnosticsResult;
        updateParseStatus(lastParseResult);
        return fullDiagnosticsResult;
    }

    private void updateParseStatus(SpellParser.ParseResult result) {
        if (result == null) {
            currentSpellStatus = "Cleared";
            parserDebugInfo = "";
            return;
        }
        currentSpellStatus = result.ir.statusMessage();
        if (result.isValidSpell()) {
            parserDebugInfo = "Valid: " + result.ir.displayName();
        } else {
            parserDebugInfo = "Invalid or Incomplete";
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (previewDirty && System.currentTimeMillis() >= previewDueAt) {
            reparseNow();
        }
    }

    private void submitSave() {
        if (savePending) {
            return;
        }
        if (currentStroke != null) {
            showLimitError("Finish the current stroke before saving.");
            return;
        }
        savePending = true;
        saveButton.active = false;
        editorMessage = "Saving...";
        editorMessageColor = 0xFFFFFF55;
        ClientPlayNetworking.send(new SaveSpellPayload(
                hand,
                revision,
                originalStrokeItemHash,
                editor.strokes()));
    }

    void handleEditResult(SpellEditResultPayload payload) {
        if (payload.revision() != revision) {
            return;
        }
        if (payload.accepted()) {
            closingWithoutCancel = true;
            if (minecraft != null) {
                minecraft.setScreenAndShow(null);
            }
            return;
        }
        savePending = false;
        if (saveButton != null) {
            saveButton.active = false;
            saveButton.setMessage(Component.literal("Reopen required"));
        }
        editorMessage = payload.message();
        editorMessageColor = 0xFFFF5555;
    }

    private void cancelAndClose() {
        if (!closingWithoutCancel) {
            closingWithoutCancel = true;
            ClientPlayNetworking.send(new CancelSpellEditPayload(
                    hand,
                    revision,
                    originalStrokeItemHash));
        }
        if (minecraft != null) {
            minecraft.setScreenAndShow(null);
        }
    }

    @Override
    public void onClose() {
        cancelAndClose();
    }

    @Override
    public void removed() {
        ClientUtils.clearSpellScreen(this);
        super.removed();
    }

    void returnFromSampleScreen(String message, boolean success) {
        sampleFeedback = message;
        sampleFeedbackTime = System.currentTimeMillis();
        editorMessageColor = success ? 0xFF55FF55 : 0xFFFF5555;
        ClientUtils.showSpellScreen(this);
    }

    private void showLimitError(String message) {
        editorMessage = message;
        editorMessageColor = 0xFFFF5555;
    }

    private void clearEditorMessage() {
        if (!savePending) {
            editorMessage = "";
        }
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
        for (List<Point> stroke : editor.strokes()) {
            drawStroke(graphics, stroke, 0xFFFFFFFF);
        }

        // Draw current stroke
        if (currentStroke != null) {
            drawStroke(graphics, currentStroke, 0xFFFF5555);
        }

        // Status text
        graphics.text(this.font, currentSpellStatus, 10, 10, 0xFF00FF00);
        String modeText = eraserMode
                ? "ERASER MODE (E to toggle)"
                : "Right-click to clear | ESC cancels without saving";
        graphics.text(this.font, modeText, 10, 22, eraserMode ? 0xFFFF8844 : 0xFFAAAAAA);
        int displayedPointCount = editor.totalPointCount()
                + (currentStroke == null ? 0 : currentStroke.size());
        graphics.text(this.font, String.format(
                        "Strokes: %d/%d | Points: %d/%d | Per stroke: %d",
                        editor.strokeCount(), editor.limits().maxStrokes(),
                        displayedPointCount, editor.limits().maxTotalPoints(),
                        editor.limits().maxPointsPerStroke()),
                10, 34, 0xFFAAAAAA);
        if (!editorMessage.isEmpty()) {
            graphics.text(this.font, editorMessage, 10, 46, editorMessageColor);
        }
        
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
        graphics.text(this.font, "Debug [" + modeName + "]: " + parserDebugInfo, 10, 58, 0xFFFFFF00);
        graphics.text(this.font, String.format("Mouse Norm: %.3f, %.3f", toNormalized(mouseX, mouseY).x, toNormalized(mouseX, mouseY).y), 10, 70, 0xFFFFFF00);
        graphics.text(this.font, "F3: cycle debug | F5: save sample", 10, 82, 0xFF888888);
        
        if (lastParseResult == null || lastParseResult.debugResult == null) return;
        
        var debug = lastParseResult.debugResult;
        int unknownCount = debug.unknowns() != null ? debug.unknowns().size() : 0;
        
        // Summary line
        StringBuilder limitStatus = new StringBuilder();
        if (debug.ringBudgetExhausted()) limitStatus.append(" [RING LIMIT]");
        if (debug.candidateLimitReached()) limitStatus.append(" [CANDIDATE LIMIT]");
        if (debug.recognitionBudgetExhausted()) {
            limitStatus.append(" [CALL LIMIT +")
                    .append(debug.unevaluatedCandidateCount())
                    .append(" UNEVALUATED]");
        }
        if (!debug.droppedSourceStrokeIndices().isEmpty()) {
            limitStatus.append(" [DROPPED ")
                    .append(debug.droppedSourceStrokeIndices().size())
                    .append(']');
        }
        graphics.text(this.font, String.format("Candidates: %d | Selected: %d | Calls: %d | Unknowns: %d%s",
                debug.candidateCount(), debug.selectedCandidateCount(),
                debug.recognitionCalls(), unknownCount,
                limitStatus),
                10, 94, 0xFFFFFF00);
        
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
            int infoY = 106;
            graphics.text(this.font, "Prim groups: " + debug.primitiveGroupCount(), 10, infoY, 0xFF88CCFF);
            infoY += 10;
            graphics.text(this.font, String.format("Ring work: %d combos / %d fits / %.3f ms | strokes=%s",
                    debug.ringCombinationsConsidered(), debug.ringFitsAttempted(),
                    debug.ringElapsedNanos() / 1_000_000.0, debug.ringStrokeIndices()),
                    10, infoY, 0xFF88CCFF);
            infoY += 10;
            if (!debug.droppedSourceStrokeIndices().isEmpty()) {
                graphics.text(this.font, "Dropped source strokes: " + debug.droppedSourceStrokeIndices(),
                        10, infoY, 0xFFFFAA55);
                infoY += 10;
            }
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
