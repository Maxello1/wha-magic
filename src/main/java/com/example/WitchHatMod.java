package com.example;

import net.fabricmc.api.ModInitializer;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.item.SpellPaperItem;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.ItemGroups;

public class WitchHatMod implements ModInitializer {
    public static final String MOD_ID = "witchhat";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final Item SPELL_PAPER = new SpellPaperItem(new Item.Settings().maxCount(1));

    @Override
    public void onInitialize() {
        Registry.register(Registries.ITEM, new Identifier(MOD_ID, "spell_paper"), SPELL_PAPER);
        
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(content -> {
            content.add(SPELL_PAPER);
        });

        LOGGER.info("Witch Hat Atelier mod initialized!");
    }
}
