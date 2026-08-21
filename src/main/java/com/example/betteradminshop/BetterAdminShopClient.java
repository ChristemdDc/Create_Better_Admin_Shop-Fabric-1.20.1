package com.example.betteradminshop;

import com.example.betteradminshop.client.EntregaHudOverlay;
import com.example.betteradminshop.client.ShopBlockEntityRenderer;
import com.example.betteradminshop.client.ShopKeybinds;
import com.example.betteradminshop.client.SlotInfoHudOverlay;
import com.example.betteradminshop.registry.ModBlockEntities;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * Client-only setup. Equivalent of the Fabric {@code ClientModInitializer}.
 * NeoForge calls this automatically because of the {@link EventBusSubscriber}
 * annotation: client-only, mod event bus.
 */
@EventBusSubscriber(modid = BetterAdminShop.ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class BetterAdminShopClient {
    private BetterAdminShopClient() {}

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.SHOP_BLOCK_ENTITY.get(),
                ShopBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.PLAYER_SHOP_BLOCK_ENTITY.get(),
                com.example.betteradminshop.client.PlayerShopRenderer::new);
        event.registerEntityRenderer(com.example.betteradminshop.registry.ModEntities.SHOP_PACKAGE.get(),
                com.example.betteradminshop.client.ShopPackageEntityRenderer::new);
        BetterAdminShop.LOGGER.info("{} client initialized.", BetterAdminShop.NAME);
    }

    @SubscribeEvent
    public static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(BetterAdminShop.ID, "entrega_hud"),
                new EntregaHudOverlay());
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(BetterAdminShop.ID, "slot_info_hud"),
                new SlotInfoHudOverlay());
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(BetterAdminShop.ID, "player_shop_hud"),
                new com.example.betteradminshop.client.PlayerShopHudOverlay());
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ShopKeybinds.TOGGLE_BOXES);
    }
}
