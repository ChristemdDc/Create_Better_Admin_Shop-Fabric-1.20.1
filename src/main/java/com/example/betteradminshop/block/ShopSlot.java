package com.example.betteradminshop.block;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

public class ShopSlot {
    public static final int INFINITE_STOCK = -1;

    /**
     * Tipo de slot.
     *  - VENTA:  la tienda VENDE al jugador. saleItem = lo que recibe el
     *            jugador; priceItem(s) = lo que paga.
     *  - COMPRA: la tienda COMPRA al jugador. saleItem = el ítem que el jugador
     *            entrega; priceItem(s) = lo que la tienda le paga.
     */
    public enum Type { VENTA, COMPRA }

    private Type type = Type.VENTA;

    /** Ítem que realmente se vende (lo que recibe el jugador). */
    private ItemStack saleItem = ItemStack.EMPTY;
    /** Ítem que se renderiza sobre el mostrador. EMPTY = usar saleItem. */
    private ItemStack renderItem = ItemStack.EMPTY;
    /** Unidades de saleItem entregadas por cada compra. */
    private int sellAmount = 1;
    private ItemStack priceItem = ItemStack.EMPTY;
    private int priceAmount = 1;
    /** Segundo ítem de precio (opcional). EMPTY = solo se cobra priceItem. */
    private ItemStack priceItem2 = ItemStack.EMPTY;
    private int priceAmount2 = 1;
    /** Stock en unidades de saleItem (no en compras). */
    private int maxStock = INFINITE_STOCK;
    private int currentStock = INFINITE_STOCK;

    public ShopSlot() {}

    public boolean isEmpty() {
        return saleItem.isEmpty();
    }

    public boolean isOutOfStock() {
        return maxStock != INFINITE_STOCK && currentStock < sellAmount;
    }

    public boolean hasInfiniteStock() {
        return maxStock == INFINITE_STOCK;
    }

    /** @param bundles número de compras (cada una entrega {@link #sellAmount} unidades). */
    public boolean canPurchase(int bundles) {
        if (isEmpty()) return false;
        if (hasInfiniteStock()) return true;
        return currentStock >= bundles * sellAmount;
    }

    /** @param bundles número de compras a descontar del stock. */
    public void reduceStock(int bundles) {
        if (!hasInfiniteStock()) {
            currentStock = Math.max(0, currentStock - bundles * sellAmount);
        }
    }

    public void restock() {
        if (!hasInfiniteStock()) {
            currentStock = maxStock;
        }
    }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type == null ? Type.VENTA : type; }
    public boolean isCompra() { return type == Type.COMPRA; }

    public ItemStack getDisplayItem() { return saleItem; }
    public void setDisplayItem(ItemStack item) { this.saleItem = item.copy(); }

    /** Ítem que debe renderizarse en el mostrador (override o el vendido). */
    public ItemStack getRenderItem() { return renderItem.isEmpty() ? saleItem : renderItem; }
    public ItemStack getRenderOverride() { return renderItem; }
    public boolean hasRenderOverride() { return !renderItem.isEmpty(); }
    public void setRenderOverride(ItemStack item) { this.renderItem = item.copy(); }

    public int getSellAmount() { return sellAmount; }
    public void setSellAmount(int amount) { this.sellAmount = Math.max(1, amount); }

    public ItemStack getPriceItem() { return priceItem; }
    public void setPriceItem(ItemStack item) { this.priceItem = item.copy(); }
    public int getPriceAmount() { return priceAmount; }
    public void setPriceAmount(int amount) { this.priceAmount = Math.max(1, amount); }

    public ItemStack getPriceItem2() { return priceItem2; }
    public void setPriceItem2(ItemStack item) { this.priceItem2 = item.copy(); }
    public int getPriceAmount2() { return priceAmount2; }
    public void setPriceAmount2(int amount) { this.priceAmount2 = Math.max(1, amount); }
    public boolean hasSecondPrice() { return !priceItem2.isEmpty(); }

    public int getMaxStock() { return maxStock; }
    public void setMaxStock(int max) {
        this.maxStock = max;
        if (max != INFINITE_STOCK && currentStock == INFINITE_STOCK) {
            currentStock = max;
        }
    }
    public int getCurrentStock() { return currentStock; }
    public void setCurrentStock(int stock) { this.currentStock = stock; }

    /**
     * In 1.20.5+ ItemStack serialization changed: it now requires a
     * {@link HolderLookup.Provider} (so it can resolve component registries).
     * The previous {@code ItemStack.save(CompoundTag)} signature is gone.
     */
    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Type", type.name());
        if (!saleItem.isEmpty()) {
            tag.put("DisplayItem", saleItem.save(provider));
        }
        if (!renderItem.isEmpty()) {
            tag.put("RenderItem", renderItem.save(provider));
        }
        tag.putInt("SellAmount", sellAmount);
        if (!priceItem.isEmpty()) {
            tag.put("PriceItem", priceItem.save(provider));
        }
        tag.putInt("PriceAmount", priceAmount);
        if (!priceItem2.isEmpty()) {
            tag.put("PriceItem2", priceItem2.save(provider));
        }
        tag.putInt("PriceAmount2", priceAmount2);
        tag.putInt("MaxStock", maxStock);
        tag.putInt("CurrentStock", currentStock);
        return tag;
    }

    public void load(HolderLookup.Provider provider, CompoundTag tag) {
        // 1.21.1 firma: ItemStack.parseOptional(HolderLookup.Provider, CompoundTag)
        // - usamos getCompound() para garantizar el subtipo correcto.
        try {
            type = tag.contains("Type") ? Type.valueOf(tag.getString("Type")) : Type.VENTA;
        } catch (IllegalArgumentException e) {
            type = Type.VENTA;
        }
        saleItem = loadStack(provider, tag, "DisplayItem");
        renderItem = loadStack(provider, tag, "RenderItem");
        sellAmount = tag.getInt("SellAmount");
        if (sellAmount <= 0) sellAmount = 1;
        priceItem = loadStack(provider, tag, "PriceItem");
        priceAmount = tag.getInt("PriceAmount");
        if (priceAmount <= 0) priceAmount = 1;
        priceItem2 = loadStack(provider, tag, "PriceItem2");
        priceAmount2 = tag.getInt("PriceAmount2");
        if (priceAmount2 <= 0) priceAmount2 = 1;
        maxStock = tag.getInt("MaxStock");
        currentStock = tag.getInt("CurrentStock");
    }

    private static ItemStack loadStack(HolderLookup.Provider provider, CompoundTag tag, String key) {
        if (!tag.contains(key)) return ItemStack.EMPTY;
        CompoundTag st = tag.getCompound(key);
        return st.isEmpty() ? ItemStack.EMPTY : ItemStack.parseOptional(provider, st);
    }

    public void clear() {
        type = Type.VENTA;
        saleItem = ItemStack.EMPTY;
        renderItem = ItemStack.EMPTY;
        sellAmount = 1;
        priceItem = ItemStack.EMPTY;
        priceAmount = 1;
        priceItem2 = ItemStack.EMPTY;
        priceAmount2 = 1;
        maxStock = INFINITE_STOCK;
        currentStock = INFINITE_STOCK;
    }
}
