package com.example.betteradminshop.registry;

import com.example.betteradminshop.BetterAdminShop;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class ModCreativeTab {

    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BetterAdminShop.ID);

    public static final Supplier<CreativeModeTab> SHOP_TAB = CREATIVE_TABS.register("shop_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.literal(BetterAdminShop.NAME))
                    .icon(() -> new ItemStack(ModBlocks.SHOP_BLOCK_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(ModBlocks.SHOP_BLOCK_ITEM.get());
                        output.accept(ModBlocks.PLAYER_SHOP_ITEM.get());
                    })
                    .build());

    public static void register(IEventBus modBus) {
        CREATIVE_TABS.register(modBus);
    }

    private ModCreativeTab() {}
}
