package com.maxello1.whamagic;

import com.maxello1.whamagic.config.WhaServerConfig;
import com.maxello1.whamagic.item.InkWandItem;
import com.maxello1.whamagic.item.SpellPaperItem;
import com.maxello1.whamagic.magic.SpellStackUpdater;
import com.maxello1.whamagic.magic.StoredSpell;
import com.maxello1.whamagic.network.OpenSpellScreenPayload;
import com.maxello1.whamagic.network.SaveSpellPayload;
import com.maxello1.whamagic.parser.Point;
import com.maxello1.whamagic.parser.SpellDictionary;
import com.maxello1.whamagic.parser.SpellParser;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WitchHatMod implements ModInitializer {
    public static final String MOD_ID = "wha-magic";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Map<UUID, Long> PARSE_COOLDOWNS = new ConcurrentHashMap<>();

    public static final ResourceKey<Item> SPELL_PAPER_KEY = ResourceKey.create(
            Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, "spell_paper"));
    public static final Item SPELL_PAPER = new SpellPaperItem(
            new Item.Properties().setId(SPELL_PAPER_KEY).stacksTo(1));

    public static final ResourceKey<Item> INK_WAND_KEY = ResourceKey.create(
            Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, "ink_wand"));
    public static final Item INK_WAND = new InkWandItem(
            new Item.Properties().setId(INK_WAND_KEY).stacksTo(1));

    public static final DataComponentType<StoredSpell> STORED_SPELL_COMPONENT = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE,
            Identifier.fromNamespaceAndPath(MOD_ID, "stored_spell"),
            new DataComponentType.Builder<StoredSpell>()
                    .persistent(StoredSpell.CODEC)
                    .networkSynchronized(StoredSpell.STREAM_CODEC)
                    .build()
    );

    public static final DataComponentType<List<List<Point>>> STROKES_COMPONENT = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE,
            Identifier.fromNamespaceAndPath(MOD_ID, "strokes"),
            new DataComponentType.Builder<List<List<Point>>>()
                    .persistent(Point.STROKES_CODEC)
                    .networkSynchronized(Point.STROKES_STREAM_CODEC)
                    .build()
    );

    @Override
    public void onInitialize() {
        Registry.register(BuiltInRegistries.ITEM, SPELL_PAPER_KEY, SPELL_PAPER);
        Registry.register(BuiltInRegistries.ITEM, INK_WAND_KEY, INK_WAND);
        
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(content -> {
            content.accept(SPELL_PAPER);
            content.accept(INK_WAND);
        });

        WhaServerConfig.load();

        PayloadTypeRegistry.serverboundPlay().register(SaveSpellPayload.ID, SaveSpellPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(OpenSpellScreenPayload.ID, OpenSpellScreenPayload.CODEC);
        
        ServerPlayNetworking.registerGlobalReceiver(SaveSpellPayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                InteractionHand usedHand = payload.hand();
                ItemStack stack = player.getItemInHand(usedHand);
                
                if (!stack.is(SPELL_PAPER)) {
                    LOGGER.warn("Player {} is not holding spell paper.", player.getName().getString());
                    return;
                }

                if (!hasInkWand(player) && !player.getAbilities().instabuild) {
                    LOGGER.warn("Player {} tried to save a spell without an Ink Wand.",
                            player.getName().getString());
                    return;
                }

                long currentTick = context.server().getTickCount();
                long lastTick = PARSE_COOLDOWNS.getOrDefault(player.getUUID(), 0L);
                if (currentTick - lastTick < WhaServerConfig.INSTANCE.network.parseCooldownTicks) {
                    LOGGER.warn("Player {} is parsing spells too frequently.", player.getName().getString());
                    return;
                }
                PARSE_COOLDOWNS.put(player.getUUID(), currentTick);

                LOGGER.debug("Parsing submitted spell for {}", player.getName().getString());
                
                SpellParser.ParseResult result = SpellParser.parse(payload.strokes());
                ItemStack newStack = stack.copy();
                applyParseResultToStack(newStack, result, payload.strokes());
                player.setItemInHand(usedHand, newStack);
                player.getInventory().setChanged();
                player.inventoryMenu.broadcastChanges();
            });
        });

        SpellDictionary.ensureLoaded();

        LOGGER.info("WHA Magic initialized");
    }

    private static boolean hasInkWand(ServerPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot).is(INK_WAND)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Apply a parse result to an item stack by setting or removing the StoredSpell component
     * and always storing the raw strokes. This represents the complete item-update operation
     * so both the network handler and tests exercise the same production code path.
     */
    public static void applyParseResultToStack(
            ItemStack stack,
            SpellParser.ParseResult result,
            List<List<Point>> strokes) {
        SpellStackUpdater.applyParseResultToStack(
                stack, result, strokes, STORED_SPELL_COMPONENT, STROKES_COMPONENT);
    }
}
