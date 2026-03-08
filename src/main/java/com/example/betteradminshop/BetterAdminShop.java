package com.example.betteradminshop;

import com.example.betteradminshop.network.ModNetworking;
import com.example.betteradminshop.registry.ModBlockEntities;
import com.example.betteradminshop.registry.ModBlocks;

import io.github.fabricators_of_create.porting_lib.util.EnvExecutor;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BetterAdminShop implements ModInitializer {
	public static final String ID = "betteradminshop";
	public static final String NAME = "Create: Better Admin Shop";
	public static final Logger LOGGER = LoggerFactory.getLogger(NAME);

	public static final CreativeModeTab SHOP_TAB = FabricItemGroup.builder()
			.title(Component.literal(NAME))
			.icon(() -> new ItemStack(ModBlocks.SHOP_BLOCK_ITEM))
			.displayItems((params, output) -> {
				output.accept(ModBlocks.SHOP_BLOCK_ITEM);
			})
			.build();

	@Override
	public void onInitialize() {
		LOGGER.info("Create addon mod [{}] is loading alongside Create!", NAME);
		LOGGER.info(EnvExecutor.unsafeRunForDist(
				() -> () -> "{} is accessing Porting Lib from the client!",
				() -> () -> "{} is accessing Porting Lib from the server!"
		), NAME);

		// Register blocks and block entities
		ModBlocks.register();
		ModBlockEntities.register();

		// Register custom creative tab
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, new ResourceLocation(ID, "shop_tab"), SHOP_TAB);

		// Register network handlers
		ModNetworking.registerServerReceivers();

		LOGGER.info("[{}] Registration complete!", NAME);
	}

	public static ResourceLocation id(String path) {
		return new ResourceLocation(ID, path);
	}
}
