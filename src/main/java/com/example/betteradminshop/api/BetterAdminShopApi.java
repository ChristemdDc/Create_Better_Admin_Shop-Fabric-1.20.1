package com.example.betteradminshop.api;

import com.example.betteradminshop.command.AdminShopCommand;
import com.example.betteradminshop.data.DynamicShopItems;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

/**
 * API pública y estable para que otros mods integren sus ítems dinámicos
 * (creados en juego) en el selector de la tienda.
 *
 * <p>Uso típico: cuando tu mod crea un ítem nuevo en juego, llama a
 * {@link #registerDynamicItem(MinecraftServer, ItemStack)} con ese ItemStack.
 * Quedará persistido en el mundo y sincronizado a los clientes, apareciendo en
 * el selector "Ítem en venta" / "Ítem de precio" de la tienda.
 *
 * <p>Equivalente por comando (solo OP): {@code /adminishop items add <item>}.
 *
 * <p>Todas las llamadas deben hacerse en el hilo del servidor.
 */
public final class BetterAdminShopApi {

    private BetterAdminShopApi() {}

    /**
     * Registra un ítem en el selector de la tienda y sincroniza a los clientes.
     *
     * @return true si se añadió; false si estaba vacío o ya existía uno idéntico.
     */
    public static boolean registerDynamicItem(MinecraftServer server, ItemStack stack) {
        if (server == null || stack == null || stack.isEmpty()) return false;
        boolean added = DynamicShopItems.get(server).add(stack);
        AdminShopCommand.syncDynamicItemsToAll(server);
        return added;
    }

    /**
     * Quita un ítem previamente registrado.
     *
     * @return true si se quitó alguno.
     */
    public static boolean unregisterDynamicItem(MinecraftServer server, ItemStack stack) {
        if (server == null || stack == null) return false;
        boolean removed = DynamicShopItems.get(server).remove(stack);
        AdminShopCommand.syncDynamicItemsToAll(server);
        return removed;
    }

    /** Fuerza un reenvío de la lista a todos los clientes (refresca selectores abiertos). */
    public static void refresh(MinecraftServer server) {
        if (server != null) {
            AdminShopCommand.syncDynamicItemsToAll(server);
        }
    }
}
