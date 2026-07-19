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

    public static GlobalRestockData load(CompoundTag tag, HolderLookup.Provider provider) {
        GlobalRestockData data = new GlobalRestockData();
        data.timestamp = tag.getLong("Timestamp");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putLong("Timestamp", timestamp);
        return tag;
    }
}
