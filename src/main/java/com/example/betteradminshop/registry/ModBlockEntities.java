package com.example.betteradminshop.registry;

import com.example.betteradminshop.BetterAdminShop;
import com.example.betteradminshop.block.ShopBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

public class ModBlockEntities {

    public static final BlockEntityType<ShopBlockEntity> SHOP_BLOCK_ENTITY =
            FabricBlockEntityTypeBuilder.create(ShopBlockEntity::new, ModBlocks.SHOP_BLOCK)
                    .build();

    public static void register() {
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                BetterAdminShop.id("shop_block_entity"), SHOP_BLOCK_ENTITY);
    }
}
