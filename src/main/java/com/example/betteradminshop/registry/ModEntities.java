package com.example.betteradminshop.registry;

import com.example.betteradminshop.BetterAdminShop;
import com.example.betteradminshop.item.ShopPackageEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/** Entidades del mod. */
public final class ModEntities {

    private ModEntities() {}

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, BetterAdminShop.ID);

    /**
     * Paquete de tienda soltado en el mundo. Tamaño acorde al modelo de la
     * cardboard 12x12 de Create (12/16 de bloque).
     */
    public static final Supplier<EntityType<ShopPackageEntity>> SHOP_PACKAGE =
            ENTITY_TYPES.register("shop_package",
                    () -> EntityType.Builder
                            .<ShopPackageEntity>of(ShopPackageEntity::new, MobCategory.MISC)
                            .sized(com.example.betteradminshop.item.ShopPackageEntity.SIZE,
                                    com.example.betteradminshop.item.ShopPackageEntity.SIZE)
                            .clientTrackingRange(8)
                            // 1 = posición sincronizada cada tick: con valores
                            // altos el empuje se ve a saltos en el cliente.
                            .updateInterval(1)
                            .build("shop_package"));

    public static void register(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
        modBus.addListener(ModEntities::onEntityAttributes);
    }

    /** LivingEntity necesita sus atributos registrados o falla al aparecer. */
    private static void onEntityAttributes(
            net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent event) {
        event.put(SHOP_PACKAGE.get(),
                com.example.betteradminshop.item.ShopPackageEntity.createAttributes().build());
    }
}
