package com.maxello1.whamagic.magic;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

public interface ElementEffect {
    void apply(ServerLevel level, Player player, double power, ManifestationModifier modifier);
}
