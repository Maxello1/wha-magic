package com.example.item;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import com.example.client.ClientUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;
import java.util.List;
import com.example.WitchHatMod;

public class SpellPaperItem extends Item {
    public SpellPaperItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            ClientUtils.openSpellScreen(hand);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, net.minecraft.world.item.component.TooltipDisplay display, java.util.function.Consumer<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        String spell = stack.get(WitchHatMod.SPELL_COMPONENT);
        if (spell != null && !spell.isEmpty()) {
            tooltipComponents.accept(Component.literal("§dSpell: " + spell));
        } else {
            tooltipComponents.accept(Component.literal("§7Empty Spell Paper"));
        }
        super.appendHoverText(stack, context, display, tooltipComponents, tooltipFlag);
    }
}
