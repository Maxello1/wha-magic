package com.maxello1.whamagic.client;

import com.maxello1.whamagic.dev.RecognitionSampleCapture;
import com.maxello1.whamagic.dev.RecognitionSampleMetadata;
import com.maxello1.whamagic.dev.SampleIntentResolver;
import com.maxello1.whamagic.parser.SpellDictionary;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Collects user intent before an F5 recognition sample is written. */
final class SaveRecognitionSampleScreen extends Screen {
    private final SpellDrawingScreen parent;
    private final RecognitionSampleCapture capture;
    private final SampleIntentResolver intentResolver;

    private EditBox sampleNameField;
    private EditBox intendedSymbolsField;
    private EditBox notesField;
    private Checkbox includesCircleCheckbox;
    private Checkbox expectedValidCheckbox;
    private Checkbox influencedCheckbox;
    private CycleButton<RecognitionSampleMetadata.RingStyle> ringStyleButton;
    private CycleButton<RecognitionSampleMetadata.SampleRole> sampleRoleButton;
    private Button saveButton;
    private String validationError = "";
    private int formX;
    private int formY;

    SaveRecognitionSampleScreen(
            SpellDrawingScreen parent,
            RecognitionSampleCapture capture) {
        super(Component.literal("Save Recognition Sample"));
        this.parent = parent;
        this.capture = capture;
        this.intentResolver = new SampleIntentResolver(SpellDictionary.snapshot());
    }

    @Override
    protected void init() {
        super.init();
        formX = Math.max(10, width / 2 - 170);
        formY = Math.max(24, height / 2 - 108);
        int fieldX = formX + 104;
        int fieldWidth = Math.min(236, width - fieldX - 10);

        sampleNameField = addRenderableWidget(new EditBox(
                font, fieldX, formY, fieldWidth, 20, Component.literal("Sample name")));
        sampleNameField.setMaxLength(RecognitionSampleMetadata.MAX_SAMPLE_NAME_LENGTH);
        sampleNameField.setHint(Component.literal("optional label"));
        sampleNameField.setResponder(ignored -> validateForm());

        intendedSymbolsField = addRenderableWidget(new EditBox(
                font, fieldX, formY + 24, fieldWidth, 20, Component.literal("Intended symbols")));
        intendedSymbolsField.setMaxLength(512);
        intendedSymbolsField.setHint(Component.literal("Fire, Column@90"));
        intendedSymbolsField.setResponder(value -> {
            intendedSymbolsField.setSuggestion(intentResolver.suggestionSuffix(value));
            validateForm();
        });

        includesCircleCheckbox = addRenderableWidget(Checkbox.builder(
                        Component.literal("Includes spell circle"), font)
                .pos(formX, formY + 50)
                .selected(false)
                .onValueChange((checkbox, selected) -> {
                    ringStyleButton.setValue(selected
                            ? RecognitionSampleMetadata.RingStyle.SINGLE_STROKE
                            : RecognitionSampleMetadata.RingStyle.NONE);
                    validateForm();
                })
                .build());

        ringStyleButton = addRenderableWidget(CycleButton.builder(
                        style -> Component.literal(style.displayName()),
                        RecognitionSampleMetadata.RingStyle.NONE)
                .withValues(RecognitionSampleMetadata.RingStyle.values())
                .create(fieldX, formY + 48, fieldWidth, 20,
                        Component.literal("Ring style"),
                        (button, style) -> validateForm()));

        sampleRoleButton = addRenderableWidget(CycleButton.builder(
                        role -> Component.literal(role.displayName()),
                        RecognitionSampleMetadata.SampleRole.EXPERIMENTAL)
                .withValues(RecognitionSampleMetadata.SampleRole.values())
                .create(fieldX, formY + 72, fieldWidth, 20,
                        Component.literal("Sample role"),
                        (button, role) -> validateForm()));

        expectedValidCheckbox = addRenderableWidget(Checkbox.builder(
                        Component.literal("Expected valid"), font)
                .pos(formX, formY + 98)
                .selected(capture.actualResult() != null && capture.actualResult().isValidSpell())
                .onValueChange((checkbox, selected) -> validateForm())
                .build());

        influencedCheckbox = addRenderableWidget(Checkbox.builder(
                        Component.literal("Influenced recognition"), font)
                .pos(fieldX, formY + 98)
                .selected(false)
                .onValueChange((checkbox, selected) -> validateForm())
                .build());

        notesField = addRenderableWidget(new EditBox(
                font, fieldX, formY + 120, fieldWidth, 20, Component.literal("Notes")));
        notesField.setMaxLength(RecognitionSampleMetadata.MAX_NOTES_LENGTH);
        notesField.setHint(Component.literal("optional notes"));
        notesField.setResponder(ignored -> validateForm());

        int buttonY = formY + 168;
        saveButton = addRenderableWidget(Button.builder(
                Component.literal("Save Sample"), button -> save())
                .bounds(width / 2 - 104, buttonY, 100, 20)
                .build());
        addRenderableWidget(Button.builder(
                Component.literal("Cancel"), button -> cancel())
                .bounds(width / 2 + 4, buttonY, 100, 20)
                .build());

        intendedSymbolsField.setFocused(true);
        validateForm();
    }

    private RecognitionSampleMetadata metadata() {
        List<RecognitionSampleMetadata.IntendedSymbol> intended =
                intentResolver.parse(intendedSymbolsField.getValue());
        return new RecognitionSampleMetadata(
                sampleNameField.getValue(),
                intended,
                includesCircleCheckbox.selected(),
                ringStyleButton.getValue(),
                sampleRoleButton.getValue(),
                expectedValidCheckbox.selected(),
                notesField.getValue(),
                influencedCheckbox.selected());
    }

    private void validateForm() {
        if (saveButton == null) return;
        try {
            metadata();
            validationError = "";
            saveButton.active = true;
        } catch (IllegalArgumentException exception) {
            validationError = exception.getMessage();
            saveButton.active = false;
        }
    }

    private void save() {
        try {
            String path = capture.save(metadata());
            if (path == null) {
                validationError = "Could not write the sample file. Check the log for details.";
                return;
            }
            parent.returnFromSampleScreen("Sample saved: " + path, true);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            validationError = exception.getMessage();
            validateForm();
        }
    }

    private void cancel() {
        capture.cancel();
        parent.returnFromSampleScreen("Sample save cancelled.", true);
    }

    @Override
    public void onClose() {
        cancel();
    }

    @Override
    public void extractRenderState(
            net.minecraft.client.gui.GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float delta) {
        graphics.centeredText(font, title, width / 2, formY - 18, 0xFFFFFFFF);
        graphics.text(font, "Sample name", formX, formY + 6, 0xFFCCCCCC);
        graphics.text(font, "Intended symbols", formX, formY + 30, 0xFFCCCCCC);
        graphics.text(font, "Sample role", formX, formY + 78, 0xFFCCCCCC);
        graphics.text(font, "Notes", formX, formY + 126, 0xFFCCCCCC);
        graphics.text(font,
                "Use commas and optional rotations, for example: Earth, Levitation@180",
                formX, formY + 144, 0xFF888888);
        if (!validationError.isEmpty()) {
            graphics.text(font, validationError, formX, formY + 192, 0xFFFF5555);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
