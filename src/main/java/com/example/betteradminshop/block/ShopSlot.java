package com.example.betteradminshop.block;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

public class ShopSlot {
    public static final int INFINITE_STOCK = -1;

    private ItemStack displayItem = ItemStack.EMPTY;
    private ItemStack priceItem = ItemStack.EMPTY;
    private int priceAmount = 1;
    private int maxStock = INFINITE_STOCK;
    private int currentStock = INFINITE_STOCK;

    public ShopSlot() {}

    public ShopSlot(ItemStack displayItem, ItemStack priceItem, int priceAmount, int maxStock) {
        this.displayItem = displayItem.copy();
        this.priceItem = priceItem.copy();
        this.priceAmount = priceAmount;
        this.maxStock = maxStock;
        this.currentStock = maxStock;
    }

    public boolean isEmpty() {
        return displayItem.isEmpty();
    }

    public boolean isOutOfStock() {
        return maxStock != INFINITE_STOCK && currentStock <= 0;
    }

    public boolean hasInfiniteStock() {
        return maxStock == INFINITE_STOCK;
    }

    public boolean canPurchase(int quantity) {
        if (isEmpty()) return false;
        if (hasInfiniteStock()) return true;
        return currentStock >= quantity;
    }

    public void reduceStock(int amount) {
        if (!hasInfiniteStock()) {
            currentStock = Math.max(0, currentStock - amount);
        }
    }

    public void restock() {
        if (!hasInfiniteStock()) {
            currentStock = maxStock;
        }
    }

    public ItemStack getDisplayItem() { return displayItem; }
    public void setDisplayItem(ItemStack item) { this.displayItem = item.copy(); }
    public ItemStack getPriceItem() { return priceItem; }
    public void setPriceItem(ItemStack item) { this.priceItem = item.copy(); }
    public int getPriceAmount() { return priceAmount; }
    public void setPriceAmount(int amount) { this.priceAmount = Math.max(1, amount); }
    public int getMaxStock() { return maxStock; }
    public void setMaxStock(int max) {
        this.maxStock = max;
        if (max != INFINITE_STOCK && currentStock == INFINITE_STOCK) {
            currentStock = max;
        }
    }
    public int getCurrentStock() { return currentStock; }
    public void setCurrentStock(int stock) { this.currentStock = stock; }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (!displayItem.isEmpty()) {
            tag.put("DisplayItem", displayItem.save(new CompoundTag()));
        }
        if (!priceItem.isEmpty()) {
            tag.put("PriceItem", priceItem.save(new CompoundTag()));
        }
        tag.putInt("PriceAmount", priceAmount);
        tag.putInt("MaxStock", maxStock);
        tag.putInt("CurrentStock", currentStock);
        return tag;
    }

    public void load(CompoundTag tag) {
        displayItem = tag.contains("DisplayItem") ?
                ItemStack.of(tag.getCompound("DisplayItem")) : ItemStack.EMPTY;
        priceItem = tag.contains("PriceItem") ?
                ItemStack.of(tag.getCompound("PriceItem")) : ItemStack.EMPTY;
        priceAmount = tag.getInt("PriceAmount");
        if (priceAmount <= 0) priceAmount = 1;
        maxStock = tag.getInt("MaxStock");
        currentStock = tag.getInt("CurrentStock");
    }

    public void clear() {
        displayItem = ItemStack.EMPTY;
        priceItem = ItemStack.EMPTY;
        priceAmount = 1;
        maxStock = INFINITE_STOCK;
        currentStock = INFINITE_STOCK;
    }
}
