package com.example.betteradminshop.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Configuración GLOBAL de las tiendas de jugador (por mundo, editada por
 * administración): la cuota de renta (ítem, cantidad y periodo). La Fase 5
 * añadirá aquí los precios de las mejoras (estantes, capacidad de stock, vacío)
 * y el panel visual de administración.
 *
 * Si la renta NO está configurada (ítem vacío o cantidad 0), las tiendas de
 * jugador operan gratis.
 */
public class PlayerShopSettings extends SavedData {

    private static final String NAME = "betteradminshop_player_shop_settings";

    /** Prototipo del ítem de la cuota (EMPTY = renta deshabilitada). */
    private ItemStack rentItem = ItemStack.EMPTY;
    private int rentAmount = 0;
    /** Periodo que cubre cada pago. Por defecto 7 días. */
    private long rentPeriodMs = 7L * 24L * 60L * 60L * 1000L;

    // ── Precios de mejoras (editables por administración; Fase 5: panel) ─────
    // Claves: shelf3, shelf4 (estante a 3/4 slots) · stock1, stock2 (capacidad
    // a 8/16 stacks) · void (mejora de vacío).
    public static final String UP_SHELF3 = "shelf3";
    public static final String UP_SHELF4 = "shelf4";
    public static final String UP_STOCK1 = "stock1";
    public static final String UP_STOCK2 = "stock2";
    public static final String UP_VOID   = "void";
    public static final String[] UPGRADE_KEYS = {UP_SHELF3, UP_SHELF4, UP_STOCK1, UP_STOCK2, UP_VOID};

    public record UpgradeCost(ItemStack item, int amount) {}

    private final java.util.Map<String, UpgradeCost> upgradeCosts = new java.util.LinkedHashMap<>();

    private void fillDefaultCosts() {
        ItemStack diamond = new ItemStack(net.minecraft.world.item.Items.DIAMOND);
        upgradeCosts.putIfAbsent(UP_SHELF3, new UpgradeCost(diamond, 8));
        upgradeCosts.putIfAbsent(UP_SHELF4, new UpgradeCost(diamond, 16));
        upgradeCosts.putIfAbsent(UP_STOCK1, new UpgradeCost(diamond, 8));
        upgradeCosts.putIfAbsent(UP_STOCK2, new UpgradeCost(diamond, 16));
        upgradeCosts.putIfAbsent(UP_VOID,   new UpgradeCost(diamond, 12));
    }

    public UpgradeCost getUpgradeCost(String key) {
        fillDefaultCosts();
        return upgradeCosts.get(key);
    }

    public void setUpgradeCost(String key, ItemStack item, int amount) {
        upgradeCosts.put(key, new UpgradeCost(item.copyWithCount(1), Math.max(0, amount)));
        setDirty();
    }

    public static PlayerShopSettings get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(PlayerShopSettings::new, PlayerShopSettings::load, null), NAME);
    }

    public PlayerShopSettings() {}

    public boolean isRentConfigured() {
        return !rentItem.isEmpty() && rentAmount > 0;
    }

    public ItemStack getRentItem() { return rentItem; }
    public int getRentAmount() { return rentAmount; }
    public long getRentPeriodMs() { return rentPeriodMs; }

    public void setRent(ItemStack item, int amount, long periodMs) {
        this.rentItem = item == null ? ItemStack.EMPTY : item.copyWithCount(1);
        this.rentAmount = Math.max(0, amount);
        this.rentPeriodMs = Math.max(60_000L, periodMs);
        setDirty();
    }

    public void disableRent() {
        this.rentItem = ItemStack.EMPTY;
        this.rentAmount = 0;
        setDirty();
    }

    public static PlayerShopSettings load(CompoundTag tag, HolderLookup.Provider provider) {
        PlayerShopSettings s = new PlayerShopSettings();
        if (tag.contains("RentItem")) {
            s.rentItem = ItemStack.parseOptional(provider, tag.getCompound("RentItem"));
        }
        s.rentAmount = tag.getInt("RentAmount");
        if (tag.contains("RentPeriodMs")) {
            s.rentPeriodMs = Math.max(60_000L, tag.getLong("RentPeriodMs"));
        }
        if (tag.contains("UpgradeCosts")) {
            CompoundTag costs = tag.getCompound("UpgradeCosts");
            for (String key : UPGRADE_KEYS) {
                if (costs.contains(key)) {
                    CompoundTag c = costs.getCompound(key);
                    ItemStack item = ItemStack.parseOptional(provider, c.getCompound("Item"));
                    if (!item.isEmpty()) {
                        s.upgradeCosts.put(key, new UpgradeCost(item, Math.max(0, c.getInt("Amount"))));
                    }
                }
            }
        }
        s.fillDefaultCosts();
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        if (!rentItem.isEmpty()) tag.put("RentItem", rentItem.save(provider));
        tag.putInt("RentAmount", rentAmount);
        tag.putLong("RentPeriodMs", rentPeriodMs);
        CompoundTag costs = new CompoundTag();
        for (var e : upgradeCosts.entrySet()) {
            CompoundTag c = new CompoundTag();
            if (!e.getValue().item().isEmpty()) c.put("Item", e.getValue().item().save(provider));
            c.putInt("Amount", e.getValue().amount());
            costs.put(e.getKey(), c);
        }
        tag.put("UpgradeCosts", costs);
        return tag;
    }
}
