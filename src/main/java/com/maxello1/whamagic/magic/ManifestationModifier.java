package com.maxello1.whamagic.magic;

public enum ManifestationModifier {
    NONE,
    COLUMN,
    LEVITATION,
    CONVERGENCE;

    public static ManifestationModifier fromString(String str) {
        if (str == null || str.isEmpty() || str.equals("none")) return NONE;
        try {
            return valueOf(str.toUpperCase());
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
