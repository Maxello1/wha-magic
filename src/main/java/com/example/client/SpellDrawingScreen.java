package com.example.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import com.example.parser.Point;

import java.util.ArrayList;
import java.util.List;

public class SpellDrawingScreen extends Screen {
    private final Hand hand;
    private final List<List<Point>> strokes = new ArrayList<>();
    private List<Point> currentStroke = null;

    public SpellDrawingScreen(Hand hand) {
        super(Text.literal("Draw Spell"));
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

    private String currentSpell = "";

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
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        // Draw saved strokes
        for (List<Point> stroke : strokes) {
            drawStroke(context, stroke, 0xFFFFFFFF);
        }

        // Draw current stroke
        if (currentStroke != null) {
            drawStroke(context, currentStroke, 0xFFFF5555);
        }
        
        context.drawText(this.textRenderer, currentSpell, 10, 10, 0xFF00FF00, true);

        super.render(context, mouseX, mouseY, delta);
    }
    
    private void drawStroke(DrawContext context, List<Point> stroke, int color) {
        if (stroke.size() < 2) return;
        for (int i = 0; i < stroke.size() - 1; i++) {
            Point p1 = stroke.get(i);
            Point p2 = stroke.get(i + 1);
            // Draw a simple line using fill for prototype (1px thick)
            // A real implementation would use Tessellator to draw thick smooth lines.
            context.fill((int)p1.x, (int)p1.y, (int)p2.x + 1, (int)p2.y + 1, color);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
