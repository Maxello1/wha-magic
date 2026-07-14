package com.maxello1.whamagic.dev;

import com.maxello1.whamagic.magic.SymbolKind;
import net.minecraft.resources.Identifier;

import java.util.List;

/** User-supplied intent stored alongside a recognition sample. */
public record RecognitionSampleMetadata(
        String sampleName,
        List<IntendedSymbol> intendedSymbols,
        boolean includesCircle,
        RingStyle ringStyle,
        SampleRole sampleRole,
        boolean expectedValid,
        String notes,
        boolean influencedTemplateOrThreshold) {

    public static final int MAX_SAMPLE_NAME_LENGTH = 80;
    public static final int MAX_NOTES_LENGTH = 1024;

    public RecognitionSampleMetadata {
        sampleName = cleanOptional(sampleName, MAX_SAMPLE_NAME_LENGTH, "Sample name");
        intendedSymbols = List.copyOf(intendedSymbols == null ? List.of() : intendedSymbols);
        ringStyle = ringStyle == null ? RingStyle.NONE : ringStyle;
        sampleRole = sampleRole == null ? SampleRole.EXPERIMENTAL : sampleRole;
        notes = cleanOptional(notes, MAX_NOTES_LENGTH, "Notes");
        if (includesCircle == (ringStyle == RingStyle.NONE)) {
            throw new IllegalArgumentException(
                    includesCircle
                            ? "Choose a ring style when the sample includes a spell circle."
                            : "Ring style must be none when the sample has no spell circle.");
        }
        if (sampleRole == SampleRole.HOLDOUT && influencedTemplateOrThreshold) {
            throw new IllegalArgumentException(
                    "Holdout samples cannot be marked as influencing templates or thresholds.");
        }
    }

    public List<IntendedSymbol> sigils() {
        return intendedSymbols.stream()
                .filter(symbol -> symbol.kind() == SymbolKind.SIGIL)
                .toList();
    }

    public List<IntendedSymbol> signs() {
        return intendedSymbols.stream()
                .filter(symbol -> symbol.kind() == SymbolKind.SIGN)
                .toList();
    }

    private static String cleanOptional(String value, int maximumLength, String label) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.length() > maximumLength) {
            throw new IllegalArgumentException(label + " must be at most " + maximumLength + " characters.");
        }
        return cleaned;
    }

    public record IntendedSymbol(Identifier id, SymbolKind kind, Double rotationDeg) {
        public IntendedSymbol {
            if (id == null || kind == null) {
                throw new IllegalArgumentException("Intended symbols require an ID and kind.");
            }
            if (rotationDeg != null) {
                if (!Double.isFinite(rotationDeg)) {
                    throw new IllegalArgumentException("Rotation must be finite.");
                }
                rotationDeg = normalizeRotation(rotationDeg);
            }
        }

        private static double normalizeRotation(double rotation) {
            double normalized = rotation % 360.0;
            if (normalized < 0.0) normalized += 360.0;
            return normalized == -0.0 ? 0.0 : normalized;
        }
    }

    public enum RingStyle {
        NONE("none"),
        SINGLE_STROKE("single_stroke"),
        MULTI_STROKE("multi_stroke"),
        OVERTRACED("overtraced"),
        INCOMPLETE("incomplete");

        private final String jsonName;

        RingStyle(String jsonName) {
            this.jsonName = jsonName;
        }

        public String jsonName() {
            return jsonName;
        }

        public String displayName() {
            return switch (this) {
                case NONE -> "None";
                case SINGLE_STROKE -> "Single stroke";
                case MULTI_STROKE -> "Multi-stroke";
                case OVERTRACED -> "Overtraced";
                case INCOMPLETE -> "Incomplete";
            };
        }
    }

    public enum SampleRole {
        TRAINING_CANDIDATE("training_candidate"),
        HOLDOUT("holdout"),
        NEGATIVE_CONFUSION("negative_confusion"),
        EXPERIMENTAL("experimental");

        private final String jsonName;

        SampleRole(String jsonName) {
            this.jsonName = jsonName;
        }

        public String jsonName() {
            return jsonName;
        }

        public String displayName() {
            return switch (this) {
                case TRAINING_CANDIDATE -> "Training candidate";
                case HOLDOUT -> "Holdout";
                case NEGATIVE_CONFUSION -> "Negative confusion";
                case EXPERIMENTAL -> "Experimental";
            };
        }
    }
}
