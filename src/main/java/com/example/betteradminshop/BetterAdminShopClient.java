package com.example.betteradminshop;

import com.example.betteradminshop.client.ShopBlockEntityRenderer;
import com.example.betteradminshop.registry.ModBlockEntities;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

public class BetterAdminShopClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Register block entity renderer
        BlockEntityRenderers.register(ModBlockEntities.SHOP_BLOCK_ENTITY, ShopBlockEntityRenderer::new);

        BetterAdminShop.LOGGER.info("{} client initialized.", BetterAdminShop.NAME);
    }
}
