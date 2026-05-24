package com.example.betteradminshop;

import com.example.betteradminshop.client.EntregaHudOverlay;
import com.example.betteradminshop.client.ShopBlockEntityRenderer;
import com.example.betteradminshop.registry.ModBlockEntities;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

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
        BetterAdminShop.LOGGER.info("{} client initialized.", BetterAdminShop.NAME);
    }

    @SubscribeEvent
    public static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(BetterAdminShop.ID, "entrega_hud"),
                new EntregaHudOverlay());
    }
}
