package com.example.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import com.example.parser.Point;

import java.util.ArrayList;
import java.util.List;

public class SpellDrawingScreen extends Screen {
    private final InteractionHand hand;
    private final List<List<Point>> strokes = new ArrayList<>();
    private List<Point> currentStroke = null;
    private String currentSpell = "";

    public SpellDrawingScreen(InteractionHand hand) {
        super(Component.literal("Draw Spell"));
        this.hand = hand;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            currentStroke = new ArrayList<>();
            currentStroke.add(new Point(event.x(), event.y()));
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
            
            // Evaluate spell
            com.example.parser.SymbolRecognizer.SpellResult result = com.example.parser.SymbolRecognizer.recognize(strokes);
            if (result.isValid) {
                currentSpell = "Prepared: " + result.element;
            } else {
                currentSpell = "Drafting...";
            }
            
            return true;
        }
        return super.mouseReleased(event);
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
        
        graphics.text(this.font, currentSpell, 10, 10, 0xFF00FF00);

        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }
    
    private void drawStroke(net.minecraft.client.gui.GuiGraphicsExtractor graphics, List<Point> stroke, int color) {
        if (stroke.size() < 2) return;
        for (int i = 0; i < stroke.size() - 1; i++) {
            Point p1 = stroke.get(i);
            Point p2 = stroke.get(i + 1);
            graphics.fill((int)p1.x, (int)p1.y, (int)p2.x + 1, (int)p2.y + 1, color);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
