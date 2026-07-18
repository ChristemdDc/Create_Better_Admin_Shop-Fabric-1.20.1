package com.example.betteradminshop.client;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Caché del lado cliente con los ítems dinámicos sincronizados desde el
 * servidor. El selector de la tienda los añade a los ítems del registro.
 */
public final class ClientDynamicItems {

    private static volatile List<ItemStack> items = List.of();

    private ClientDynamicItems() {}

    public static void set(List<ItemStack> list) {
        items = (list == null) ? List.of() : List.copyOf(list);
    }

    public static List<ItemStack> get() {
        return items;
    }
}
