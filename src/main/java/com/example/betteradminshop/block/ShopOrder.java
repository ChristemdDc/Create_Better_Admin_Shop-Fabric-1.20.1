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

    public Map<ItemStack, Integer> calculateTotalPrice(ShopSlot[] slots) {
        Map<ItemStack, Integer> priceMap = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> entry : items.entrySet()) {
            int slotIdx = entry.getKey();
            int qty = entry.getValue();
            if (slotIdx >= 0 && slotIdx < slots.length) {
                ShopSlot slot = slots[slotIdx];
                if (!slot.isEmpty() && !slot.getPriceItem().isEmpty()) {
                    ItemStack priceItem = slot.getPriceItem();
                    boolean found = false;
                    for (Map.Entry<ItemStack, Integer> pe : priceMap.entrySet()) {
                        // 1.20.5+: isSameItemSameTags was replaced by
                        // isSameItemSameComponents (data components migration).
                        if (ItemStack.isSameItemSameComponents(pe.getKey(), priceItem)) {
                            priceMap.put(pe.getKey(), pe.getValue() + slot.getPriceAmount() * qty);
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        priceMap.put(priceItem.copy(), slot.getPriceAmount() * qty);
                    }
                }
            }
        }
        return priceMap;
    }
}
