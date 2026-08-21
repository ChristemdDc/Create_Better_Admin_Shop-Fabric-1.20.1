package com.example.betteradminshop.block;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * Inventario de stock de la tienda de jugador: 16 slots tipados.
 *
 * Cada slot guarda UN tipo de ítem (prototipo con count 1) y un contador
 * aparte — el contador puede superar 99 sin romper el codec de ItemStack.
 *
 * Capacidad por slot (en stacks de 64):
 *   tier 0 → 4 stacks (256) · tier 1 → 8 stacks (512) · tier 2 → 16 stacks (1024)
 *
 * Mejora "vacío" (void): si está activa, los ítems que entren por el chute de
 * import cuando su stock está lleno se DESCARTAN en vez de rebotar.
 *
 * Slots BLOQUEADOS: un slot bloqueado RESERVA su tipo de ítem. Aunque el
 * contador baje a 0 el prototipo se queda ahí, así que ningún otro ítem puede
 * ocupar el hueco y solo se puede rellenar con el mismo ítem.
 */
public class StockInventory {

    public static final int SLOTS = 16;
    /** Stacks de 64 por tier de capacidad. */
    public static final int[] TIER_STACKS = {4, 8, 16};
    public static final int MAX_TIER = TIER_STACKS.length - 1;

    /** Prototipos (count 1). EMPTY = slot libre. */
    private final ItemStack[] items = new ItemStack[SLOTS];
    private final int[] counts = new int[SLOTS];
    /** Slots bloqueados: conservan su prototipo aunque el contador llegue a 0. */
    private final boolean[] locked = new boolean[SLOTS];

    private int capacityTier = 0;
    /** ¿La mejora de vacío está COMPRADA? */
    private boolean voidUpgrade = false;
    /** ¿Está ACTIVADA? (el dueño puede apagarla sin perder la compra). */
    private boolean voidEnabled = true;

    public StockInventory() {
        for (int i = 0; i < SLOTS; i++) items[i] = ItemStack.EMPTY;
    }

    // ── Consultas ─────────────────────────────────────────────────────────────

    public int getCapacityTier() { return capacityTier; }
    public void setCapacityTier(int tier) { this.capacityTier = Math.max(0, Math.min(MAX_TIER, tier)); }
    public boolean hasVoidUpgrade() { return voidUpgrade; }
    public void setVoidUpgrade(boolean v) { this.voidUpgrade = v; }
    public boolean isVoidEnabled() { return voidEnabled; }
    public void setVoidEnabled(boolean v) { this.voidEnabled = v; }
    /** ¿Debe descartar el excedente? Comprada Y activada. */
    public boolean voidActive() { return voidUpgrade && voidEnabled; }

    /** Capacidad por slot en ÍTEMS. */
    public int capacityPerSlot() {
        return TIER_STACKS[capacityTier] * 64;
    }

    public ItemStack getItem(int slot) { return items[slot]; }
    public int getCount(int slot) { return counts[slot]; }

    public boolean isLocked(int slot) {
        return slot >= 0 && slot < SLOTS && locked[slot];
    }

    /**
     * Alterna el bloqueo de un slot.
     *
     * Bloquear reserva el ítem que hay ahora en el slot: al agotarse el stock
     * el prototipo NO se borra, así que el hueco sigue siendo suyo y solo
     * admite ese mismo ítem. Desbloquear un slot ya vacío libera la reserva.
     *
     * @return true si el estado cambió (false: slot inválido o vacío sin reserva).
     */
    public boolean toggleLock(int slot) {
        if (slot < 0 || slot >= SLOTS) return false;
        if (locked[slot]) {
            locked[slot] = false;
            // Estaba reservado en seco: al soltar el candado el hueco queda libre.
            if (counts[slot] <= 0) items[slot] = ItemStack.EMPTY;
            return true;
        }
        if (items[slot].isEmpty()) return false; // no hay tipo que reservar
        locked[slot] = true;
        return true;
    }

    /** ¿Hay algún slot bloqueado que reserve este tipo de ítem? */
    public boolean isReserved(ItemStack proto) {
        if (proto.isEmpty()) return false;
        for (int i = 0; i < SLOTS; i++) {
            if (locked[i] && !items[i].isEmpty()
                    && ItemStack.isSameItemSameComponents(items[i], proto)) {
                return true;
            }
        }
        return false;
    }

    /** Total de unidades disponibles de un ítem (suma de todos sus slots). */
    public int countOf(ItemStack proto) {
        if (proto.isEmpty()) return 0;
        int total = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (!items[i].isEmpty() && ItemStack.isSameItemSameComponents(items[i], proto)) {
                total += counts[i];
            }
        }
        return total;
    }

    // ── Mutación ─────────────────────────────────────────────────────────────

    /**
     * Inserta ítems (merge en slots del mismo tipo, luego slots libres).
     * @return unidades que NO entraron (0 si entró todo). Si la mejora "vacío"
     *         está activa, el sobrante de un ítem YA presente se descarta (devuelve 0).
     */
    public int insert(ItemStack proto, int amount) {
        if (proto.isEmpty() || amount <= 0) return amount;
        int remaining = amount;
        boolean typePresent = false;
        // 1) rellenar slots existentes del mismo tipo
        for (int i = 0; i < SLOTS && remaining > 0; i++) {
            if (!items[i].isEmpty() && ItemStack.isSameItemSameComponents(items[i], proto)) {
                typePresent = true;
                int free = capacityPerSlot() - counts[i];
                int add = Math.min(free, remaining);
                counts[i] += add;
                remaining -= add;
            }
        }
        // 2) ocupar slots libres
        for (int i = 0; i < SLOTS && remaining > 0; i++) {
            if (items[i].isEmpty()) {
                typePresent = true;
                items[i] = proto.copyWithCount(1);
                int add = Math.min(capacityPerSlot(), remaining);
                counts[i] = add;
                remaining -= add;
            }
        }
        // 3) mejora "vacío": descartar sobrante de tipos ya almacenados
        if (remaining > 0 && voidActive() && typePresent) {
            remaining = 0;
        }
        return remaining;
    }

    /** Igual que {@link #insert} pero SIN mutar: devuelve el sobrante que quedaría. */
    public int simulateInsert(ItemStack proto, int amount) {
        if (proto.isEmpty() || amount <= 0) return amount;
        int remaining = amount;
        boolean typePresent = false;
        for (int i = 0; i < SLOTS && remaining > 0; i++) {
            if (!items[i].isEmpty() && ItemStack.isSameItemSameComponents(items[i], proto)) {
                typePresent = true;
                remaining -= Math.min(capacityPerSlot() - counts[i], remaining);
            }
        }
        for (int i = 0; i < SLOTS && remaining > 0; i++) {
            if (items[i].isEmpty()) {
                typePresent = true;
                remaining -= Math.min(capacityPerSlot(), remaining);
            }
        }
        if (remaining > 0 && voidActive() && typePresent) remaining = 0;
        return remaining;
    }

    /**
     * Retira hasta {@code amount} unidades del ítem. @return unidades retiradas.
     */
    public int remove(ItemStack proto, int amount) {
        if (proto.isEmpty() || amount <= 0) return 0;
        int taken = 0;
        for (int i = 0; i < SLOTS && taken < amount; i++) {
            if (!items[i].isEmpty() && ItemStack.isSameItemSameComponents(items[i], proto)) {
                int take = Math.min(counts[i], amount - taken);
                counts[i] -= take;
                taken += take;
                if (counts[i] <= 0) {
                    counts[i] = 0;
                    // Un slot bloqueado conserva su ítem: sigue reservado.
                    if (!locked[i]) items[i] = ItemStack.EMPTY;
                }
            }
        }
        return taken;
    }

    /** Vacía por completo un slot concreto. @return unidades que contenía. */
    public int clearSlot(int slot) {
        int n = counts[slot];
        if (!locked[slot]) items[slot] = ItemStack.EMPTY;
        counts[slot] = 0;
        return n;
    }

    // ── NBT ───────────────────────────────────────────────────────────────────

    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Tier", capacityTier);
        tag.putBoolean("Void", voidUpgrade);
        tag.putBoolean("VoidEnabled", voidEnabled);
        ListTag list = new ListTag();
        for (int i = 0; i < SLOTS; i++) {
            // Un slot bloqueado se guarda aunque esté a 0: la reserva persiste.
            if (items[i].isEmpty() || (counts[i] <= 0 && !locked[i])) continue;
            CompoundTag e = new CompoundTag();
            e.putInt("Slot", i);
            e.put("Item", items[i].save(provider)); // prototipo count 1: nunca >99
            e.putInt("Count", counts[i]);
            if (locked[i]) e.putBoolean("Locked", true);
            list.add(e);
        }
        tag.put("Entries", list);
        return tag;
    }

    public void load(HolderLookup.Provider provider, CompoundTag tag) {
        capacityTier = Math.max(0, Math.min(MAX_TIER, tag.getInt("Tier")));
        voidUpgrade = tag.getBoolean("Void");
        voidEnabled = !tag.contains("VoidEnabled") || tag.getBoolean("VoidEnabled");
        for (int i = 0; i < SLOTS; i++) {
            items[i] = ItemStack.EMPTY;
            counts[i] = 0;
            locked[i] = false;
        }
        ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            int slot = e.getInt("Slot");
            if (slot < 0 || slot >= SLOTS) continue;
            ItemStack proto = ItemStack.parseOptional(provider, e.getCompound("Item"));
            if (proto.isEmpty()) continue;
            items[slot] = proto.copyWithCount(1);
            counts[slot] = Math.max(0, e.getInt("Count"));
            locked[slot] = e.getBoolean("Locked");
        }
    }
}
