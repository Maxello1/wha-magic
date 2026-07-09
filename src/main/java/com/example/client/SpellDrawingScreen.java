package com.example.client;

import net.minecraft.client.gui.GuiGraphics;
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
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            currentStroke = new ArrayList<>();
            currentStroke.add(new Point(mouseX, mouseY));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (button == 0 && currentStroke != null) {
            currentStroke.add(new Point(mouseX, mouseY));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && currentStroke != null) {
            currentStroke.add(new Point(mouseX, mouseY));
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
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.renderBackground(graphics, mouseX, mouseY, delta);

        // Draw saved strokes
        for (List<Point> stroke : strokes) {
            drawStroke(graphics, stroke, 0xFFFFFFFF);
        }

        // Draw current stroke
        if (currentStroke != null) {
            drawStroke(graphics, currentStroke, 0xFFFF5555);
        }
        
        graphics.drawString(this.font, currentSpell, 10, 10, 0xFF00FF00, true);

        super.render(graphics, mouseX, mouseY, delta);
    }
    
    private void drawStroke(GuiGraphics graphics, List<Point> stroke, int color) {
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
