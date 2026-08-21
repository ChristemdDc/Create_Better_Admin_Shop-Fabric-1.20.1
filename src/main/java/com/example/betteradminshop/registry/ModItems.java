package com.example.betteradminshop.registry;

import com.example.betteradminshop.item.ShopPackageItem;

import net.minecraft.world.item.Item;

import java.util.function.Supplier;

/**
 * Ítems propios del mod (los BlockItems viven en {@link ModBlocks}).
 *
 * Se registran sobre el mismo DeferredRegister de ítems de ModBlocks para no
 * duplicar la infraestructura de registro.
 */
public final class ModItems {

    private ModItems() {}

    /**
     * Paquete de entrega de las tiendas: aspecto de cardboard de Create, pero
     * sin su límite de 9 stacks. Solo lo entregan las tiendas — no tiene receta
     * ni aparece en pestañas creativas.
     */
    public static final Supplier<ShopPackageItem> SHOP_PACKAGE =
            ModBlocks.ITEMS.register("shop_package",
                    () -> new ShopPackageItem(new Item.Properties().stacksTo(1)));

    /**
     * Fuerza la carga de esta clase para que sus registros se apliquen.
     * Debe llamarse DESPUÉS de {@link ModBlocks#register} (usa su registro de ítems).
     */
    public static void register() {
        // Tocar la clase basta: los campos estáticos ya quedaron registrados.
    }
}
