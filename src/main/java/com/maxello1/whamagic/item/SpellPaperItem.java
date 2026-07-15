package com.maxello1.whamagic.item;

import com.maxello1.whamagic.WitchHatMod;
import com.maxello1.whamagic.magic.SpellExecutionService;
import com.maxello1.whamagic.magic.SpellState;
import com.maxello1.whamagic.magic.StoredSpell;
import com.maxello1.whamagic.magic.StoredSpellResolver;
import com.maxello1.whamagic.parser.Point;
import com.maxello1.whamagic.parser.ParseDetail;
import com.maxello1.whamagic.parser.SpellParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.function.Consumer;

public class SpellPaperItem extends Item {
    public SpellPaperItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        List<List<Point>> strokes = stack.get(WitchHatMod.STROKES_COMPONENT);
        
        if (!level.isClientSide() && strokes != null && !strokes.isEmpty()) {
            StoredSpellResolver.Resolution resolution = StoredSpellResolver.resolve(
                    stack.get(WitchHatMod.STORED_SPELL_COMPONENT),
                    strokes,
                    source -> SpellParser.parse(source, ParseDetail.RUNTIME));
            if (resolution.reparsed()) {
                if (resolution.refreshedSpell() != null) {
                    stack.set(WitchHatMod.STORED_SPELL_COMPONENT, resolution.refreshedSpell());
                } else {
                    stack.remove(WitchHatMod.STORED_SPELL_COMPONENT);
                }
            }

            if (resolution.valid() && resolution.ir().state() == SpellState.ACTIVE) {
                SpellExecutionService.execute(level, player, resolution.ir());
                if (!player.getAbilities().instabuild) {
                    stack.shrink(1);
                }
                return InteractionResult.SUCCESS;
            }
        }
        
        if (!level.isClientSide()) {
            if (hasInkWand(player) || player.getAbilities().instabuild) {
                List<List<Point>> existingStrokes = strokes == null ? List.of() : strokes;
                WitchHatMod.openSpellEditor(
                        (ServerPlayer) player,
                        hand,
                        stack,
                        existingStrokes);
            } else {
                player.sendSystemMessage(Component.literal("§cYou need an Ink Wand to draw spells."));
            }
        }
        return InteractionResult.SUCCESS;
    }

    private static boolean hasInkWand(Player player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot).getItem() instanceof InkWandItem) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Component getName(ItemStack stack) {
        StoredSpell spell = stack.get(WitchHatMod.STORED_SPELL_COMPONENT);
        if (spell != null && !spell.displayName().isEmpty()) {
            String name = spell.displayName();
            String capitalized = name.substring(0, 1).toUpperCase() + name.substring(1);
            return Component.literal(capitalized + " Spell");
        }
        return super.getName(stack);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void appendHoverText(
            ItemStack stack,
            Item.TooltipContext context,
            TooltipDisplay display,
            Consumer<Component> tooltipComponents,
            TooltipFlag tooltipFlag) {
        StoredSpell spell = stack.get(WitchHatMod.STORED_SPELL_COMPONENT);
        if (spell != null && !spell.displayName().isEmpty()) {
            String prefix = spell.state() == SpellState.ACTIVE ? "§aActive: " : "§dPrepared: ";
            tooltipComponents.accept(Component.literal(prefix + spell.displayName()));
        } else {
            tooltipComponents.accept(Component.literal("§7Empty Spell Paper"));
        }
        super.appendHoverText(stack, context, display, tooltipComponents, tooltipFlag);
    }
}
