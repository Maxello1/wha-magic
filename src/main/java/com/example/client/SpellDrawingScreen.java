package com.example.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import com.example.parser.Point;
import com.example.parser.CloudRecognizer;
import com.example.parser.SpellDictionary;

import java.util.ArrayList;
import java.util.List;

public class SpellDrawingScreen extends Screen {
    private final InteractionHand hand;
    private final List<List<Point>> strokes = new ArrayList<>();
    private List<Point> currentStroke = null;
    private String currentSpellStatus = "";
    private String recognizedSpellId = null;
    private String recognizedSpellElement = null;

    public SpellDrawingScreen(InteractionHand hand, List<List<Point>> existingStrokes) {
        super(Component.literal("Draw Spell"));
        this.hand = hand;
        if (existingStrokes != null) {
            this.strokes.addAll(existingStrokes);
            // Immediately evaluate existing strokes
            com.example.parser.SpellParser.ParseResult result = com.example.parser.SpellParser.parse(strokes);
            if (result.isValidSpell()) {
                recognizedSpellId = result.sign.id;
                recognizedSpellElement = result.sign.element;
                currentSpellStatus = result.statusMessage + " (" + String.format("%.0f%%", result.sign.score * 100) + ")";
            } else {
                recognizedSpellId = null;
                recognizedSpellElement = null;
                currentSpellStatus = result.statusMessage;
            }
        }
        // Ensure dictionary is loaded on the client
        SpellDictionary.ensureLoaded();
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            currentStroke = new ArrayList<>();
            currentStroke.add(new Point(event.x(), event.y()));
            return true;
        }
        // Right-click to clear
        if (event.button() == 1) {
            strokes.clear();
            currentStroke = null;
            recognizedSpellId = null;
            recognizedSpellElement = null;
            currentSpellStatus = "Cleared";
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double deltaX, double deltaY) {
        if (event.button() == 0 && currentStroke != null) {
            currentStroke.add(new Point(event.x(), event.y()));
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        if (event.button() == 0 && currentStroke != null) {
            currentStroke.add(new Point(event.x(), event.y()));
            strokes.add(currentStroke);
            currentStroke = null;

            // Evaluate spell using SpellParser (Ring + Sign grammar)
            com.example.parser.SpellParser.ParseResult result = com.example.parser.SpellParser.parse(strokes);
            
            if (result.isValidSpell()) {
                recognizedSpellId = result.sign.id;
                recognizedSpellElement = result.sign.element;
                currentSpellStatus = result.statusMessage + " (" + String.format("%.0f%%", result.sign.score * 100) + ")";
            } else {
                recognizedSpellId = null;
                recognizedSpellElement = null;
                currentSpellStatus = result.statusMessage;
            }

            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void onClose() {
        if (recognizedSpellId != null) {
            // Send the recognized spell element (or id if no element) to the server
            String spellValue = recognizedSpellElement != null ? recognizedSpellElement : recognizedSpellId;
            System.out.println("Sending SpellDrawnPacket with value: " + spellValue);
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                    new com.example.network.SpellDrawnPacket(spellValue, strokes));
        } else {
            System.out.println("Closing screen without valid spell.");
        }
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

        graphics.text(this.font, currentSpellStatus, 10, 10, 0xFF00FF00);
        graphics.text(this.font, "Right-click to clear | ESC to save & close", 10, 22, 0xFFAAAAAA);
        graphics.text(this.font, "Strokes: " + strokes.size(), 10, 34, 0xFFAAAAAA);

        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private void drawStroke(net.minecraft.client.gui.GuiGraphicsExtractor graphics, List<Point> stroke, int color) {
        if (stroke.size() < 2) return;
        for (int i = 0; i < stroke.size() - 1; i++) {
            Point p1 = stroke.get(i);
            Point p2 = stroke.get(i + 1);
            graphics.fill((int) p1.x, (int) p1.y, (int) p2.x + 1, (int) p2.y + 1, color);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
