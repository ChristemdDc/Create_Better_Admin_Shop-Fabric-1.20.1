package com.example.betteradminshop.registry;

import com.example.betteradminshop.BetterAdminShop;

import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class ModSounds {

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, BetterAdminShop.ID);

    public static final Supplier<SoundEvent> DESK_BELL = SOUNDS.register("desk_bell",
            () -> SoundEvent.createVariableRangeEvent(BetterAdminShop.id("desk_bell")));

    public static void register(IEventBus modBus) {
        SOUNDS.register(modBus);
    }

    private ModSounds() {}
}
