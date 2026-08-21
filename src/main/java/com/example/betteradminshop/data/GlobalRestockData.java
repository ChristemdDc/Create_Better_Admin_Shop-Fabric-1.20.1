package com.example.betteradminshop.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Guarda el instante del último "restock global". Cada tienda recuerda hasta
 * qué momento ya se reabasteció; al cargarse compara con este valor y, si hubo
 * un restock global mientras estaba descargada, se reabastece en ese momento.
 *
 * Así el restock global alcanza también a las tiendas en chunks descargados.
 */
public class GlobalRestockData extends SavedData {

    private static final String NAME = "betteradminshop_global_restock";

    private long timestamp = 0L;

    /** Duración global del ciclo de stock por jugador (configurable por comando). */
    private long resetDurationMs = com.example.betteradminshop.block.ShopSlot.DEFAULT_STOCK_RESET_MS;

    /**
     * Restocks INDIVIDUALES: instante del último reinicio de stock de cada
     * jugador. Igual que el timestamp global, permite que las tiendas en chunks
     * descargados lo apliquen cuando se carguen.
     */
    private final java.util.Map<java.util.UUID, Long> playerRestocks = new java.util.HashMap<>();

    /** Entradas más viejas que esto se descartan (limpieza). */
    private static final long PLAYER_RESTOCK_TTL_MS = 30L * 24L * 60L * 60L * 1000L;

    public static GlobalRestockData get(MinecraftServer server) {
        ServerLevel level = server.overworld();
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(GlobalRestockData::new, GlobalRestockData::load, null), NAME);
    }

    public GlobalRestockData() {}

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long ts) {
        this.timestamp = ts;
        setDirty();
    }

    public long getResetDurationMs() {
        return resetDurationMs;
    }

    public void setResetDurationMs(long ms) {
        this.resetDurationMs = Math.max(0L, ms);
        setDirty();
    }

    /** Registra un restock individual (para que lo apliquen también las tiendas descargadas). */
    public void setPlayerRestock(java.util.UUID player, long timestamp) {
        playerRestocks.put(player, timestamp);
        playerRestocks.entrySet().removeIf(e -> timestamp - e.getValue() > PLAYER_RESTOCK_TTL_MS);
        setDirty();
    }

    public java.util.Map<java.util.UUID, Long> getPlayerRestocks() {
        return playerRestocks;
    }

    public static GlobalRestockData load(CompoundTag tag, HolderLookup.Provider provider) {
        GlobalRestockData data = new GlobalRestockData();
        data.timestamp = tag.getLong("Timestamp");
        if (tag.contains("ResetDurationMs")) {
            data.resetDurationMs = Math.max(0L, tag.getLong("ResetDurationMs"));
        }
        if (tag.contains("PlayerRestocks")) {
            net.minecraft.nbt.ListTag list =
                    tag.getList("PlayerRestocks", net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag e = list.getCompound(i);
                if (e.hasUUID("UUID")) {
                    data.playerRestocks.put(e.getUUID("UUID"), e.getLong("At"));
                }
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putLong("Timestamp", timestamp);
        tag.putLong("ResetDurationMs", resetDurationMs);
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (var e : playerRestocks.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putUUID("UUID", e.getKey());
            c.putLong("At", e.getValue());
            list.add(c);
        }
        tag.put("PlayerRestocks", list);
        return tag;
    }
}
