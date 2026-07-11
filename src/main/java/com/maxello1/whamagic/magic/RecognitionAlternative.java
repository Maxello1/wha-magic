package com.maxello1.whamagic.magic;
import net.minecraft.resources.Identifier;
public record RecognitionAlternative(Identifier id, String displayName, double score, SymbolKind kind) {}
