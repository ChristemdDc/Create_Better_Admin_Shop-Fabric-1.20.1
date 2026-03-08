package com.example.betteradminshop.registry;

import com.example.betteradminshop.BetterAdminShop;
import com.example.betteradminshop.block.ShopBlock;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

public class ModBlocks {

    public static final Block SHOP_BLOCK = new ShopBlock(
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.5f)
                    .sound(SoundType.WOOD)
                    .noOcclusion()
    );

    public static final Item SHOP_BLOCK_ITEM = new BlockItem(SHOP_BLOCK,
            new Item.Properties());

    public static void register() {
        Registry.register(BuiltInRegistries.BLOCK, BetterAdminShop.id("shop_block"), SHOP_BLOCK);
        Registry.register(BuiltInRegistries.ITEM, BetterAdminShop.id("shop_block"), SHOP_BLOCK_ITEM);
    }
}
