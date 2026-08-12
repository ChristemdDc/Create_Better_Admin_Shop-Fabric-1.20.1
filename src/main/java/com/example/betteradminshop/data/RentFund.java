package com.example.betteradminshop.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;

/**
 * FONDO COMÚN de las rentas de las tiendas de jugador. Cada pago de renta se
 * deposita aquí; solo administración puede consultarlo y retirarlo
 * (/tiendas fondo · /tiendas fondo retirar).
 *
 * Cada entrada guarda el prototipo del ítem (count 1) y un contador aparte —
 * así el total puede superar 99 sin romper el codec de ItemStack.
 */
public class RentFund extends SavedData {

    private static final String NAME = "betteradminshop_rent_fund";

    public record Entry(ItemStack proto, int count) {}

    private final List<ItemStack> protos = new ArrayList<>();
    private final List<Integer> counts = new ArrayList<>();

    public static RentFund get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(RentFund::new, RentFund::load, null), NAME);
    }

    public RentFund() {}

    /** Deposita unidades de un ítem en el fondo (merge por tipo). */
    public void deposit(ItemStack proto, int amount) {
        if (proto.isEmpty() || amount <= 0) return;
        for (int i = 0; i < protos.size(); i++) {
            if (ItemStack.isSameItemSameComponents(protos.get(i), proto)) {
                counts.set(i, counts.get(i) + amount);
                setDirty();
                return;
            }
        }
        protos.add(proto.copyWithCount(1));
        counts.add(amount);
        setDirty();
    }

    public boolean isEmpty() {
        return protos.isEmpty();
    }

    public List<Entry> view() {
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < protos.size(); i++) {
            out.add(new Entry(protos.get(i).copyWithCount(1), counts.get(i)));
        }
        return out;
    }

    /** Vacía el fondo y devuelve su contenido en stacks válidos (≤ maxStackSize). */
    public List<ItemStack> withdrawAll() {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < protos.size(); i++) {
            ItemStack proto = protos.get(i);
            int remaining = counts.get(i);
            int max = Math.max(1, Math.min(proto.getMaxStackSize(), 99));
            while (remaining > 0) {
                int n = Math.min(remaining, max);
                out.add(proto.copyWithCount(n));
                remaining -= n;
            }
        }
        protos.clear();
        counts.clear();
        setDirty();
        return out;
    }

    public static RentFund load(CompoundTag tag, HolderLookup.Provider provider) {
        RentFund fund = new RentFund();
        ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            ItemStack proto = ItemStack.parseOptional(provider, e.getCompound("Item"));
            int count = e.getInt("Count");
            if (!proto.isEmpty() && count > 0) {
                fund.protos.add(proto.copyWithCount(1));
                fund.counts.add(count);
            }
        }
        return fund;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (int i = 0; i < protos.size(); i++) {
            CompoundTag e = new CompoundTag();
            e.put("Item", protos.get(i).save(provider)); // proto count 1: nunca >99
            e.putInt("Count", counts.get(i));
            list.add(e);
        }
        tag.put("Entries", list);
        return tag;
    }
}
