package com.example.betteradminshop.block;

import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

public class ShopOrder {
    private final Map<Integer, Integer> items = new LinkedHashMap<>();

    public void addItem(int slotIndex) {
        items.merge(slotIndex, 1, Integer::sum);
    }

    public void removeItem(int slotIndex) {
        Integer count = items.get(slotIndex);
        if (count != null) {
            if (count <= 1) {
                items.remove(slotIndex);
            } else {
                items.put(slotIndex, count - 1);
            }
        }
    }

    public Map<Integer, Integer> getItems() {
        return items;
    }

    public int getQuantity(int slotIndex) {
        return items.getOrDefault(slotIndex, 0);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public void clear() {
        items.clear();
    }

    public int getTotalItems() {
        return items.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Ítems que el jugador debe TENER para completar la orden.
     *  - Slots de venta: los ítems de precio.
     *  - Slots de compra: el propio ítem que la tienda le compra.
     */
    public Map<ItemStack, Integer> computeRequired(ShopSlot[] slots) {
        Map<ItemStack, Integer> map = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> entry : items.entrySet()) {
            int slotIdx = entry.getKey();
            int bundles = entry.getValue();
            if (slotIdx < 0 || slotIdx >= slots.length) continue;
            ShopSlot slot = slots[slotIdx];
            if (slot.isEmpty()) continue;

            if (slot.isCompra()) {
                addItem(map, slot.getDisplayItem(), bundles * slot.getSellAmount());
            } else {
                addItem(map, slot.getPriceItem(), slot.getPriceAmount() * bundles);
                if (slot.hasSecondPrice()) {
                    addItem(map, slot.getPriceItem2(), slot.getPriceAmount2() * bundles);
                }
            }
        }
        return map;
    }

    /**
     * Ítems que el jugador RECIBE al completar la orden.
     *  - Slots de venta: el ítem vendido.
     *  - Slots de compra: los ítems de pago de la tienda.
     */
    public Map<ItemStack, Integer> computeRewards(ShopSlot[] slots) {
        Map<ItemStack, Integer> map = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> entry : items.entrySet()) {
            int slotIdx = entry.getKey();
            int bundles = entry.getValue();
            if (slotIdx < 0 || slotIdx >= slots.length) continue;
            ShopSlot slot = slots[slotIdx];
            if (slot.isEmpty()) continue;

            if (slot.isCompra()) {
                addItem(map, slot.getPriceItem(), slot.getPriceAmount() * bundles);
                if (slot.hasSecondPrice()) {
                    addItem(map, slot.getPriceItem2(), slot.getPriceAmount2() * bundles);
                }
            } else {
                addItem(map, slot.getDisplayItem(), bundles * slot.getSellAmount());
            }
        }
        return map;
    }

    private static void addItem(Map<ItemStack, Integer> map, ItemStack item, int amount) {
        if (item.isEmpty() || amount <= 0) return;
        for (Map.Entry<ItemStack, Integer> pe : map.entrySet()) {
            // 1.20.5+: isSameItemSameTags was replaced by
            // isSameItemSameComponents (data components migration).
            if (ItemStack.isSameItemSameComponents(pe.getKey(), item)) {
                pe.setValue(pe.getValue() + amount);
                return;
            }
        }
        map.put(item.copy(), amount);
    }
}
