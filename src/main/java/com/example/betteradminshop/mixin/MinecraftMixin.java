package com.example.betteradminshop.mixin;

import com.example.betteradminshop.BetterAdminShop;

import net.minecraft.client.Minecraft;

import net.minecraft.client.main.GameConfig;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Inject(method = "<init>", at = @At("TAIL"))
	private void betteradminshop$init(GameConfig gameConfig, CallbackInfo ci) {
		BetterAdminShop.LOGGER.info("Hello from {}", BetterAdminShop.NAME);
	}
}
