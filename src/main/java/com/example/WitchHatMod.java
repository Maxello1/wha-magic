package com.example;

import net.fabricmc.api.ModInitializer;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.item.SpellPaperItem;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.world.item.CreativeModeTabs;

public class WitchHatMod implements ModInitializer {
    public static final String MOD_ID = "witchhat";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final Item SPELL_PAPER = new SpellPaperItem(new Item.Properties().stacksTo(1));

    @Override
    public void onInitialize() {
        Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, "spell_paper"), SPELL_PAPER);
        
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(content -> {
            content.accept(SPELL_PAPER);
        });

        LOGGER.info("Witch Hat Atelier mod initialized!");
    }
}
