package com.example.betteradminshop.registry;

import com.example.betteradminshop.BetterAdminShop;
import com.example.betteradminshop.block.ShopBlockEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, BetterAdminShop.ID);

    public static final Supplier<BlockEntityType<ShopBlockEntity>> SHOP_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("shop_block_entity",
                    () -> BlockEntityType.Builder
                            .of(ShopBlockEntity::new, ModBlocks.SHOP_BLOCK.get())
                            .build(null));

    public static void register(IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
    }

    private ModBlockEntities() {}
}
