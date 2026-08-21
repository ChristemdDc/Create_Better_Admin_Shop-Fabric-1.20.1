package com.example.betteradminshop.registry;

import com.example.betteradminshop.BetterAdminShop;
import com.example.betteradminshop.block.PlayerShopBlock;
import com.example.betteradminshop.block.ShopBlock;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(BetterAdminShop.ID);

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(BetterAdminShop.ID);

    public static final Supplier<ShopBlock> SHOP_BLOCK = BLOCKS.register("shop_block",
            () -> new ShopBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.5f)
                    .sound(SoundType.WOOD)
                    .noOcclusion()));

    public static final Supplier<BlockItem> SHOP_BLOCK_ITEM = ITEMS.register("shop_block",
            () -> new BlockItem(SHOP_BLOCK.get(), new Item.Properties()));

    public static final Supplier<PlayerShopBlock> PLAYER_SHOP = BLOCKS.register("player_shop",
            () -> new PlayerShopBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.5f)
                    .sound(SoundType.WOOD)
                    .noOcclusion()));

    public static final Supplier<BlockItem> PLAYER_SHOP_ITEM = ITEMS.register("player_shop",
            () -> new BlockItem(PLAYER_SHOP.get(), new Item.Properties()));

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
    }

    private ModBlocks() {}

    /** Convenience accessor used by other registries / handlers. */
    public static Item shopBlockItem() {
        return SHOP_BLOCK_ITEM.get();
    }
}
