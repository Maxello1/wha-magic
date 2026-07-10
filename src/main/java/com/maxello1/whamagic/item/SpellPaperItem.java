package com.maxello1.whamagic.item;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import com.maxello1.whamagic.client.ClientUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;
import java.util.List;
import com.maxello1.whamagic.WitchHatMod;

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
            if (result.ir.valid() && result.ir.active()) {
                if (!level.isClientSide()) {
                    com.maxello1.whamagic.magic.SpellExecutionService.execute(level, player, result.ir);
                    if (!player.getAbilities().instabuild) {
                        stack.shrink(1);
                    }
                }
                return InteractionResult.SUCCESS;
            }
        }
        
        if (level.isClientSide()) {
            boolean hasWand = false;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                if (player.getInventory().getItem(i).getItem() instanceof InkWandItem) {
                    hasWand = true;
                    break;
                }
            }
            
            if (hasWand || player.getAbilities().instabuild) {
                ClientUtils.openSpellScreen(hand, strokes);
            } else {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§cYou need an Ink Wand to draw spells."));
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public Component getName(ItemStack stack) {
        String spell = stack.get(WitchHatMod.SPELL_COMPONENT);
        if (spell != null && !spell.isEmpty()) {
            // Capitalize first letter of spell
            String capitalized = spell.substring(0, 1).toUpperCase() + spell.substring(1);
            return Component.literal(capitalized + " Spell");
        }
        return super.getName(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, net.minecraft.world.item.component.TooltipDisplay display, java.util.function.Consumer<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        String spell = stack.get(WitchHatMod.SPELL_COMPONENT);
        if (spell != null && !spell.isEmpty()) {
            tooltipComponents.accept(Component.literal("§dPrepared: " + spell));
        } else {
            tooltipComponents.accept(Component.literal("§7Empty Spell Paper"));
        }
        super.appendHoverText(stack, context, display, tooltipComponents, tooltipFlag);
    }
}
