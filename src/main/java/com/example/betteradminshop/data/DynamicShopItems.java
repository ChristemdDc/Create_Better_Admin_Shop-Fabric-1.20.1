package com.example.betteradminshop.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;

/**
 * Lista auxiliar de ítems "dinámicos" (creados en juego por otro mod) que deben
 * aparecer en el selector de la tienda además de los ítems del registro.
 *
 * El selector escanea el registro de ítems, que está congelado tras el arranque;
 * los ítems dinámicos basados en componentes/NBT no son entradas nuevas del
 * registro, así que se guardan aquí y se sincronizan a los clientes.
 *
 * Persistido por mundo como {@link SavedData} en el overworld.
 */
public class DynamicShopItems extends SavedData {

    private static final String NAME = "betteradminshop_dynamic_items";

    private final List<ItemStack> items = new ArrayList<>();

    public static DynamicShopItems get(MinecraftServer server) {
        ServerLevel level = server.overworld();
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(DynamicShopItems::new, DynamicShopItems::load, null), NAME);
    }

    public DynamicShopItems() {}

    public List<ItemStack> getItems() {
        return items;
    }

    /** Añade un ítem (count 1). Devuelve false si ya existía uno idéntico. */
    public boolean add(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ItemStack copy = stack.copyWithCount(1);
        for (ItemStack s : items) {
            if (ItemStack.isSameItemSameComponents(s, copy)) return false;
        }
        items.add(copy);
        setDirty();
        return true;
    }

    /** Elimina el primer ítem idéntico. Devuelve true si se quitó alguno. */
    public boolean remove(ItemStack stack) {
        for (int i = 0; i < items.size(); i++) {
            if (ItemStack.isSameItemSameComponents(items.get(i), stack)) {
                items.remove(i);
                setDirty();
                return true;
            }
        }
        return false;
    }

    public int clearAll() {
        int n = items.size();
        items.clear();
        setDirty();
        return n;
    }

    public static DynamicShopItems load(CompoundTag tag, HolderLookup.Provider provider) {
        DynamicShopItems data = new DynamicShopItems();
        ListTag list = tag.getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            try {
                ItemStack s = ItemStack.parseOptional(provider, list.getCompound(i));
                if (!s.isEmpty()) data.items.add(s);
            } catch (Exception ignored) {
                // Un ítem corrupto no debe impedir cargar el resto
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (ItemStack s : items) {
            if (!s.isEmpty()) list.add(s.save(provider));
        }
        tag.put("Items", list);
        return tag;
    }
}
