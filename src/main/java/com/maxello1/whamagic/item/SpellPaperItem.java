package com.maxello1.whamagic.item;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.TooltipFlag;
import java.util.List;
import java.util.ArrayList;
import com.maxello1.whamagic.WitchHatMod;
import com.maxello1.whamagic.network.OpenSpellScreenPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

public class SpellPaperItem extends Item {
    public SpellPaperItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        java.util.List<java.util.List<com.maxello1.whamagic.parser.Point>> strokes = stack.get(WitchHatMod.STROKES_COMPONENT);
        
        if (strokes != null && !strokes.isEmpty()) {
            com.maxello1.whamagic.parser.SpellParser.ParseResult result = com.maxello1.whamagic.parser.SpellParser.parse(strokes);
            if (result.ir.valid() && result.ir.state() == com.maxello1.whamagic.magic.SpellState.ACTIVE) {
                if (!level.isClientSide()) {
                    com.maxello1.whamagic.magic.SpellExecutionService.execute(level, player, result.ir);
                    if (!player.getAbilities().instabuild) {
                        stack.shrink(1);
                    }
                }
                return InteractionResult.SUCCESS;
            }
        }
        
        if (!level.isClientSide()) {
            boolean hasWand = false;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                if (player.getInventory().getItem(i).getItem() instanceof InkWandItem) {
                    hasWand = true;
                    break;
                }
            }
            
            if (hasWand || player.getAbilities().instabuild) {
                ServerPlayNetworking.send((ServerPlayer) player, new OpenSpellScreenPayload(hand, strokes == null ? new ArrayList<>() : strokes));
            } else {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§cYou need an Ink Wand to draw spells."));
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public Component getName(ItemStack stack) {
        com.maxello1.whamagic.magic.StoredSpell spell = stack.get(WitchHatMod.STORED_SPELL_COMPONENT);
        if (spell != null && spell.displayName() != null && !spell.displayName().isEmpty()) {
            String name = spell.displayName();
            String capitalized = name.substring(0, 1).toUpperCase() + name.substring(1);
            return Component.literal(capitalized + " Spell");
        }
        return super.getName(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, net.minecraft.world.item.component.TooltipDisplay display, java.util.function.Consumer<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        com.maxello1.whamagic.magic.StoredSpell spell = stack.get(WitchHatMod.STORED_SPELL_COMPONENT);
        if (spell != null && spell.displayName() != null && !spell.displayName().isEmpty()) {
            String prefix = spell.state() == com.maxello1.whamagic.magic.SpellState.ACTIVE ? "§aActive: " : "§dPrepared: ";
            tooltipComponents.accept(Component.literal(prefix + spell.displayName()));
        } else {
            tooltipComponents.accept(Component.literal("§7Empty Spell Paper"));
        }
        super.appendHoverText(stack, context, display, tooltipComponents, tooltipFlag);
    }
}
