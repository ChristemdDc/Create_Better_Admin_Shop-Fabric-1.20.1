package com.example.betteradminshop;

import com.example.betteradminshop.config.BetterAdminShopConfig;
import com.example.betteradminshop.network.ModNetworking;
import com.example.betteradminshop.registry.ModBlockEntities;
import com.example.betteradminshop.registry.ModBlocks;
import com.example.betteradminshop.registry.ModCreativeTab;
import com.example.betteradminshop.registry.ModSounds;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(BetterAdminShop.ID)
public class BetterAdminShop {
    public static final String ID = "betteradminshop";
    public static final String NAME = "Create: Better Admin Shop";
    public static final Logger LOGGER = LoggerFactory.getLogger(NAME);

    public BetterAdminShop(IEventBus modBus, ModContainer modContainer) {
        LOGGER.info("Create addon mod [{}] is loading alongside Create!", NAME);

        // Register blocks, block entities, creative tab and sounds on the mod event bus
        ModBlocks.register(modBus);
        ModBlockEntities.register(modBus);
        ModCreativeTab.register(modBus);
        ModSounds.register(modBus);

        // Register network payloads
        modBus.addListener(ModNetworking::register);

        // Config común (incluye la integración opcional con MongoDB)
        modContainer.registerConfig(ModConfig.Type.COMMON, BetterAdminShopConfig.SPEC);

        LOGGER.info("[{}] Registration complete!", NAME);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(ID, path);
    }
}
