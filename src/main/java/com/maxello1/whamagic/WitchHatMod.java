package com.maxello1.whamagic;

import net.fabricmc.api.ModInitializer;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponentType;
import com.mojang.serialization.Codec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import com.maxello1.whamagic.network.SpellDrawnPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.maxello1.whamagic.item.SpellPaperItem;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.world.item.CreativeModeTabs;

public class WitchHatMod implements ModInitializer {
    public static final String MOD_ID = "wha-magic";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final ResourceKey<Item> SPELL_PAPER_KEY = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, "spell_paper"));
    public static final Item SPELL_PAPER = new SpellPaperItem(new Item.Properties().setId(SPELL_PAPER_KEY).stacksTo(64));

    public static final ResourceKey<Item> INK_WAND_KEY = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, "ink_wand"));
    public static final Item INK_WAND = new com.maxello1.whamagic.item.InkWandItem(new Item.Properties().setId(INK_WAND_KEY).stacksTo(1));

    public static final DataComponentType<String> SPELL_COMPONENT = Registry.register(
        BuiltInRegistries.DATA_COMPONENT_TYPE,
        Identifier.fromNamespaceAndPath(MOD_ID, "spell"),
        new DataComponentType.Builder<String>().persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8).build()
    );

    public static final DataComponentType<java.util.List<java.util.List<com.maxello1.whamagic.parser.Point>>> STROKES_COMPONENT = Registry.register(
        BuiltInRegistries.DATA_COMPONENT_TYPE,
        Identifier.fromNamespaceAndPath(MOD_ID, "strokes"),
        new DataComponentType.Builder<java.util.List<java.util.List<com.maxello1.whamagic.parser.Point>>>().persistent(com.maxello1.whamagic.parser.Point.STROKES_CODEC).networkSynchronized(com.maxello1.whamagic.parser.Point.STROKES_STREAM_CODEC).build()
    );

    @Override
    public void onInitialize() {
        Registry.register(BuiltInRegistries.ITEM, SPELL_PAPER_KEY, SPELL_PAPER);
        Registry.register(BuiltInRegistries.ITEM, INK_WAND_KEY, INK_WAND);
        
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(content -> {
            content.accept(SPELL_PAPER);
            content.accept(INK_WAND);
        });

        // Networking
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(SpellDrawnPacket.ID, SpellDrawnPacket.CODEC);
        
        ServerPlayNetworking.registerGlobalReceiver(SpellDrawnPacket.ID, (payload, context) -> {
            context.server().execute(() -> {
                net.minecraft.world.InteractionHand usedHand = net.minecraft.world.InteractionHand.MAIN_HAND;
                net.minecraft.world.item.ItemStack stack = context.player().getMainHandItem();
                
                if (!stack.is(SPELL_PAPER)) {
                    stack = context.player().getOffhandItem();
                    usedHand = net.minecraft.world.InteractionHand.OFF_HAND;
                }
                
                if (stack.is(SPELL_PAPER)) {
                    LOGGER.info("Spell drawn packet received, parsing on server...");
                    
                    // Parse the strokes server-side for authority
                    com.maxello1.whamagic.parser.SpellParser.ParseResult result = com.maxello1.whamagic.parser.SpellParser.parse(payload.strokes());
                    String compiledSpellString = "";
                    if (result.isValidSpell()) {
                        compiledSpellString = result.ir.element();
                        LOGGER.info("Compiled spell: {}", compiledSpellString);
                    } else {
                        LOGGER.info("Invalid or incomplete spell drawn.");
                    }
                    
                    net.minecraft.world.item.ItemStack newStack = stack.copy();
                    newStack.set(SPELL_COMPONENT, compiledSpellString);
                    newStack.set(STROKES_COMPONENT, payload.strokes());
                    context.player().setItemInHand(usedHand, newStack);
                    context.player().getInventory().setChanged();
                    if (context.player() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                        serverPlayer.inventoryMenu.broadcastChanges();
                    }
                    LOGGER.info("Item updated in hand.");
                } else {
                    LOGGER.warn("Player is not holding spell paper.");
                }
            });
        });

        // Load spell dictionary templates
        com.maxello1.whamagic.parser.SpellDictionary.ensureLoaded();

        LOGGER.info("Witch Hat Atelier mod initialized!");
    }
}
