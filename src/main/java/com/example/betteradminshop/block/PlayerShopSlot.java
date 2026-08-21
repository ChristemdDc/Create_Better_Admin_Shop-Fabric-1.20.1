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

    /**
     * Ítem a la venta. Se guarda SIEMPRE con count 1: el codec de ItemStack
     * solo admite counts 1..99, así que meter ahí la cantidad por venta
     * rompía el guardado al superar 99. La cantidad vive en {@link #sellAmount}.
     */
    private ItemStack saleItem = ItemStack.EMPTY;
    /** Unidades entregadas por compra (sin el límite de 99 del ItemStack). */
    private int sellAmount = 1;
    private ItemStack priceItem = ItemStack.EMPTY;
    private int priceAmount = 1;
    /** Segundo precio OPCIONAL: si está puesto, el comprador paga ambos. */
    private ItemStack priceItem2 = ItemStack.EMPTY;
    private int priceAmount2 = 1;
    /**
     * Giro HORIZONTAL del ítem sobre el mostrador, en pasos de 90° (0-3).
     * Permite orientar la cara delantera del producto hacia el comprador.
     */
    private int rotation = 0;

    public boolean isEmpty() {
        return saleItem.isEmpty();
    }

    /** Prototipo del ítem en venta (count 1). */
    public ItemStack getSaleItem() { return saleItem; }

    /** Acepta el stack con la cantidad en su count y la separa internamente. */
    public void setSaleItem(ItemStack item) {
        if (item.isEmpty()) {
            this.saleItem = ItemStack.EMPTY;
            this.sellAmount = 1;
            return;
        }
        this.sellAmount = Math.max(1, item.getCount());
        this.saleItem = item.copyWithCount(1);
    }

    /** Unidades del ítem entregadas por cada compra. */
    public int getSellAmount() { return Math.max(1, sellAmount); }
    public void setSellAmount(int amount) { this.sellAmount = Math.max(1, amount); }

    public ItemStack getPriceItem() { return priceItem; }
    public void setPriceItem(ItemStack item) {
        this.priceItem = item.isEmpty() ? ItemStack.EMPTY : item.copyWithCount(1);
    }
    public int getPriceAmount() { return priceAmount; }
    public void setPriceAmount(int amount) { this.priceAmount = Math.max(1, amount); }

    public ItemStack getPriceItem2() { return priceItem2; }
    public void setPriceItem2(ItemStack item) {
        this.priceItem2 = item.isEmpty() ? ItemStack.EMPTY : item.copyWithCount(1);
    }
    public int getPriceAmount2() { return priceAmount2; }
    public void setPriceAmount2(int amount) { this.priceAmount2 = Math.max(1, amount); }
    /** ¿Este slot cobra un segundo ítem además del primero? */
    public boolean hasSecondPrice() { return !priceItem2.isEmpty(); }

    /** Pasos de 90° (0-3). */
    public int getRotation() { return rotation; }
    public void setRotation(int steps) { this.rotation = ((steps % 4) + 4) % 4; }
    /** Avanza un cuarto de vuelta. */
    public void rotate() { setRotation(rotation + 1); }

    public void clear() {
        saleItem = ItemStack.EMPTY;
        sellAmount = 1;
        priceItem = ItemStack.EMPTY;
        priceAmount = 1;
        priceItem2 = ItemStack.EMPTY;
        priceAmount2 = 1;
        rotation = 0;
    }

    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        if (!saleItem.isEmpty()) tag.put("SaleItem", saleItem.save(provider));
        if (!priceItem.isEmpty()) tag.put("PriceItem", priceItem.save(provider));
        tag.putInt("PriceAmount", priceAmount);
        if (!priceItem2.isEmpty()) tag.put("PriceItem2", priceItem2.save(provider));
        tag.putInt("PriceAmount2", priceAmount2);
        tag.putInt("SellAmount", sellAmount);
        tag.putInt("Rotation", rotation);
        return tag;
    }

    public void load(HolderLookup.Provider provider, CompoundTag tag) {
        saleItem = loadStack(provider, tag, "SaleItem");
        priceItem = loadStack(provider, tag, "PriceItem");
        priceAmount = Math.max(1, tag.getInt("PriceAmount"));
        priceItem2 = loadStack(provider, tag, "PriceItem2");
        priceAmount2 = Math.max(1, tag.getInt("PriceAmount2"));
        // Compat: si no está guardado (mundos anteriores), la cantidad venía
        // en el count del propio ItemStack.
        sellAmount = tag.contains("SellAmount")
                ? Math.max(1, tag.getInt("SellAmount"))
                : Math.max(1, saleItem.getCount());
        if (!saleItem.isEmpty()) saleItem = saleItem.copyWithCount(1);
        setRotation(tag.getInt("Rotation"));
    }

    private static ItemStack loadStack(HolderLookup.Provider provider, CompoundTag tag, String key) {
        if (!tag.contains(key)) return ItemStack.EMPTY;
        CompoundTag st = tag.getCompound(key);
        return st.isEmpty() ? ItemStack.EMPTY : ItemStack.parseOptional(provider, st);
    }
}
