package com.example.betteradminshop.block;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * Un slot de venta de la TIENDA DE JUGADOR.
 *
 * Mucho más simple que el de administrador: el jugador solo vende (no compra),
 * hay un único ítem de precio, y el stock NO vive aquí — sale del inventario
 * de stock de la tienda ({@link StockInventory}): un slot está "agotado"
 * cuando el stock no tiene unidades del ítem en venta.
 */
public class PlayerShopSlot {

    /** Ítem a la venta (count = unidades entregadas por compra). */
    private ItemStack saleItem = ItemStack.EMPTY;
    private ItemStack priceItem = ItemStack.EMPTY;
    private int priceAmount = 1;
    /**
     * Giro HORIZONTAL del ítem sobre el mostrador, en pasos de 90° (0-3).
     * Permite orientar la cara delantera del producto hacia el comprador.
     */
    private int rotation = 0;

    public boolean isEmpty() {
        return saleItem.isEmpty();
    }

    public ItemStack getSaleItem() { return saleItem; }
    public void setSaleItem(ItemStack item) { this.saleItem = item.copy(); }

    /** Unidades del ítem entregadas por cada compra. */
    public int getSellAmount() { return Math.max(1, saleItem.getCount()); }

    public ItemStack getPriceItem() { return priceItem; }
    public void setPriceItem(ItemStack item) { this.priceItem = item.copy(); }
    public int getPriceAmount() { return priceAmount; }
    public void setPriceAmount(int amount) { this.priceAmount = Math.max(1, amount); }

    /** Pasos de 90° (0-3). */
    public int getRotation() { return rotation; }
    public void setRotation(int steps) { this.rotation = ((steps % 4) + 4) % 4; }
    /** Avanza un cuarto de vuelta. */
    public void rotate() { setRotation(rotation + 1); }

    public void clear() {
        saleItem = ItemStack.EMPTY;
        priceItem = ItemStack.EMPTY;
        priceAmount = 1;
        rotation = 0;
    }

    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        if (!saleItem.isEmpty()) tag.put("SaleItem", saleItem.save(provider));
        if (!priceItem.isEmpty()) tag.put("PriceItem", priceItem.save(provider));
        tag.putInt("PriceAmount", priceAmount);
        tag.putInt("Rotation", rotation);
        return tag;
    }

    public void load(HolderLookup.Provider provider, CompoundTag tag) {
        saleItem = loadStack(provider, tag, "SaleItem");
        priceItem = loadStack(provider, tag, "PriceItem");
        priceAmount = Math.max(1, tag.getInt("PriceAmount"));
        setRotation(tag.getInt("Rotation"));
    }

    private static ItemStack loadStack(HolderLookup.Provider provider, CompoundTag tag, String key) {
        if (!tag.contains(key)) return ItemStack.EMPTY;
        CompoundTag st = tag.getCompound(key);
        return st.isEmpty() ? ItemStack.EMPTY : ItemStack.parseOptional(provider, st);
    }
}
