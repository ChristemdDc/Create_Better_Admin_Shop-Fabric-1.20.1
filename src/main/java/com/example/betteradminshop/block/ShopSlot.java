package com.example.betteradminshop.block;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ShopSlot {
    public static final int INFINITE_STOCK = -1;

    /** Tiempo de reabastecimiento por jugador: 24 horas. */
    public static final long STOCK_RESET_MS = 24L * 60L * 60L * 1000L;

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
    /**
     * Stock máximo POR JUGADOR (en unidades de saleItem). -1 = infinito.
     * Cada jugador tiene su propio consumo; al agotarlo arranca un temporizador
     * de 24h tras el cual su stock se restablece (solo el suyo).
     */
    private int maxStock = INFINITE_STOCK;
    /** Unidades consumidas por cada jugador en el ciclo actual. */
    private final Map<UUID, Integer> consumed = new HashMap<>();
    /** Instante (ms) en que el stock de cada jugador vuelve a llenarse. */
    private final Map<UUID, Long> resetAt = new HashMap<>();

    public ShopSlot() {}

    public boolean isEmpty() {
        return saleItem.isEmpty();
    }

    public boolean hasInfiniteStock() {
        return maxStock == INFINITE_STOCK;
    }

    /**
     * Unidades que le quedan a {@code player} en este momento. Si su
     * temporizador de 24h ya venció, se considera stock lleno.
     */
    public int getRemaining(UUID player, long now) {
        if (hasInfiniteStock()) return Integer.MAX_VALUE;
        Long reset = resetAt.get(player);
        if (reset != null && now >= reset) return maxStock; // ciclo terminado
        return Math.max(0, maxStock - consumed.getOrDefault(player, 0));
    }

    public boolean isOutOfStockFor(UUID player, long now) {
        if (hasInfiniteStock()) return false;
        return getRemaining(player, now) < sellAmount;
    }

    /** @param bundles número de compras (cada una entrega {@link #sellAmount} unidades). */
    public boolean canPurchase(UUID player, int bundles, long now) {
        if (isEmpty()) return false;
        if (hasInfiniteStock()) return true;
        return getRemaining(player, now) >= bundles * sellAmount;
    }

    /**
     * Descuenta stock del jugador (servidor). Si venció su temporizador, primero
     * reinicia; si agota su cupo, arranca el temporizador de 24h.
     */
    public void consume(UUID player, int bundles, long now) {
        if (hasInfiniteStock()) return;
        maybeReset(player, now);
        int used = consumed.getOrDefault(player, 0) + bundles * sellAmount;
        consumed.put(player, used);
        if (used >= maxStock) {
            resetAt.put(player, now + STOCK_RESET_MS);
        }
    }

    private void maybeReset(UUID player, long now) {
        Long reset = resetAt.get(player);
        if (reset != null && now >= reset) {
            consumed.remove(player);
            resetAt.remove(player);
        }
    }

    /**
     * Segundos que faltan para reabastecer al jugador (0 si no está agotado o
     * si el temporizador ya venció).
     */
    public long getResetRemainingSeconds(UUID player, long now) {
        Long reset = resetAt.get(player);
        if (reset == null || now >= reset) return 0;
        return (reset - now) / 1000L;
    }

    /** ¿Este jugador tiene un temporizador de reabastecimiento activo? */
    public boolean hasActiveTimer(UUID player, long now) {
        Long reset = resetAt.get(player);
        return reset != null && now < reset;
    }

    /** Reinicia el stock de TODOS los jugadores (botón Restock del admin). */
    public void restock() {
        consumed.clear();
        resetAt.clear();
    }

    /** Elimina entradas de jugadores cuyo ciclo de 24h ya venció (limpieza). */
    public void pruneExpired(long now) {
        Set<UUID> toRemove = new HashSet<>();
        for (Map.Entry<UUID, Long> e : resetAt.entrySet()) {
            if (now >= e.getValue()) toRemove.add(e.getKey());
        }
        for (UUID id : toRemove) {
            consumed.remove(id);
            resetAt.remove(id);
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
        // Cambiar el límite reinicia el consumo de todos los jugadores.
        consumed.clear();
        resetAt.clear();
    }

    /**
     * In 1.20.5+ ItemStack serialization changed: it now requires a
     * {@link HolderLookup.Provider} (so it can resolve component registries).
     * The previous {@code ItemStack.save(CompoundTag)} signature is gone.
     */
    public CompoundTag save(HolderLookup.Provider provider) {
        // Limpieza: descarta ciclos de 24h ya vencidos antes de serializar.
        pruneExpired(System.currentTimeMillis());
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

        // Consumo por jugador (solo entradas relevantes; se descartan las que
        // no tienen consumo ni temporizador).
        ListTag stockList = new ListTag();
        Set<UUID> players = new HashSet<>();
        players.addAll(consumed.keySet());
        players.addAll(resetAt.keySet());
        for (UUID id : players) {
            int used = consumed.getOrDefault(id, 0);
            Long reset = resetAt.get(id);
            if (used <= 0 && reset == null) continue;
            CompoundTag pt = new CompoundTag();
            pt.putUUID("UUID", id);
            pt.putInt("Consumed", used);
            if (reset != null) pt.putLong("ResetAt", reset);
            stockList.add(pt);
        }
        tag.put("PlayerStock", stockList);
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

        consumed.clear();
        resetAt.clear();
        if (tag.contains("PlayerStock")) {
            ListTag stockList = tag.getList("PlayerStock", Tag.TAG_COMPOUND);
            for (int i = 0; i < stockList.size(); i++) {
                CompoundTag pt = stockList.getCompound(i);
                if (!pt.hasUUID("UUID")) continue;
                UUID id = pt.getUUID("UUID");
                int used = pt.getInt("Consumed");
                if (used > 0) consumed.put(id, used);
                if (pt.contains("ResetAt")) resetAt.put(id, pt.getLong("ResetAt"));
            }
        }
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
        consumed.clear();
        resetAt.clear();
    }
}
