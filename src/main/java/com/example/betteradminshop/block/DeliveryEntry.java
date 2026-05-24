package com.example.betteradminshop.block;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a single pending delivery waiting for pick-up at the entrega zone.
 * The 5-minute protection window only starts once this entry reaches the
 * front of the delivery queue — earlier purchases don't erode the timer.
 */
public class DeliveryEntry {

    private static final long PROTECTION_DURATION_MS = 5L * 60L * 1000L; // 5 minutes

    private final List<ItemStack> items;
    private final UUID buyerUUID;
    private final long purchaseTimeMs;
    private final String buyerName;
    /** Millisecond timestamp when this entry reached the head of the queue.
     *  -1 means it has not yet reached the front (protection not started). */
    private long protectionStartMs = -1;

    public DeliveryEntry(List<ItemStack> items, UUID buyerUUID, long purchaseTimeMs, String buyerName) {
        this.items = new ArrayList<>(items);
        this.buyerUUID = buyerUUID;
        this.purchaseTimeMs = purchaseTimeMs;
        this.buyerName = buyerName != null ? buyerName : "?";
    }

    public List<ItemStack> getItems() {
        return items;
    }

    public UUID getBuyerUUID() {
        return buyerUUID;
    }

    public String getBuyerName() {
        return buyerName;
    }

    public long getPurchaseTimeMs() {
        return purchaseTimeMs;
    }

    /**
     * Called by ShopBlockEntity when this entry becomes the head of the queue.
     * Has no effect if protection was already started.
     */
    public void startProtection() {
        if (protectionStartMs < 0) {
            protectionStartMs = System.currentTimeMillis();
        }
    }

    public boolean isProtectionStarted() {
        return protectionStartMs >= 0;
    }

    /** Returns true if this delivery is still protected AND the player is NOT the buyer. */
    public boolean isProtectedFrom(UUID queryingPlayer) {
        if (queryingPlayer.equals(buyerUUID)) return false;
        if (protectionStartMs < 0) return true; // not yet at the front – fully protected
        return (System.currentTimeMillis() - protectionStartMs) < PROTECTION_DURATION_MS;
    }

    /** How many seconds remain in the protection window (0 when expired, full 5 min when not started). */
    public long remainingProtectionSeconds() {
        if (protectionStartMs < 0) return PROTECTION_DURATION_MS / 1000;
        long elapsed = System.currentTimeMillis() - protectionStartMs;
        return Math.max(0L, (PROTECTION_DURATION_MS - elapsed) / 1000L);
    }

    // ===== NBT ================================================================

    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putString("BuyerUUID", buyerUUID.toString());
        tag.putLong("PurchaseTimeMs", purchaseTimeMs);
        tag.putString("BuyerName", buyerName);
        tag.putLong("ProtectionStartMs", protectionStartMs);
        ListTag itemsList = new ListTag();
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                itemsList.add(stack.save(provider));
            }
        }
        tag.put("Items", itemsList);
        return tag;
    }

    public static DeliveryEntry load(CompoundTag tag, HolderLookup.Provider provider) {
        UUID uuid = UUID.fromString(tag.getString("BuyerUUID"));
        long timeMs = tag.getLong("PurchaseTimeMs");
        String buyerName = tag.contains("BuyerName") ? tag.getString("BuyerName") : uuid.toString().substring(0, 8);
        // Backward compat: old saves without ProtectionStartMs use purchaseTimeMs (old behaviour)
        long protStartMs = tag.contains("ProtectionStartMs") ? tag.getLong("ProtectionStartMs") : timeMs;
        ListTag itemsList = tag.getList("Items", Tag.TAG_COMPOUND);
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < itemsList.size(); i++) {
            ItemStack stack = ItemStack.parseOptional(provider, itemsList.getCompound(i));
            if (!stack.isEmpty()) {
                items.add(stack);
            }
        }
        DeliveryEntry entry = new DeliveryEntry(items, uuid, timeMs, buyerName);
        entry.protectionStartMs = protStartMs;
        return entry;
    }
}
