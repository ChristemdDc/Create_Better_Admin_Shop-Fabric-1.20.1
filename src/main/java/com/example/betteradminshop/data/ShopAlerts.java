package com.example.betteradminshop.data;

import com.example.betteradminshop.BetterAdminShop;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Avisos diferidos para los DUEÑOS de tiendas de jugador (producto agotado,
 * recaudación llena…).
 *
 * Coste en caliente: cero. No hay tick ni sondeo — los avisos se generan por
 * evento (justo en la compra que agota el stock) y se entregan al instante si
 * el dueño está conectado. Solo cuando está desconectado se guardan aquí, y
 * este {@link SavedData} se escribe a disco en el ciclo de guardado normal del
 * mundo, no en el momento del aviso.
 */
@EventBusSubscriber(modid = BetterAdminShop.ID, bus = EventBusSubscriber.Bus.GAME)
public class ShopAlerts extends SavedData {

    private static final String NAME = "betteradminshop_shop_alerts";

    /** Tope de avisos guardados por jugador: evita que un dueño ausente crezca sin fin. */
    private static final int MAX_PER_PLAYER = 10;

    /** dueño → avisos pendientes (Set: mensajes repetidos no se duplican). */
    private final Map<UUID, Set<String>> pending = new LinkedHashMap<>();

    public static ShopAlerts get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ShopAlerts::new, ShopAlerts::load, null), NAME);
    }

    public ShopAlerts() {}

    // ── API ──────────────────────────────────────────────────────────────────

    /**
     * Hace llegar un aviso al dueño: al instante si está conectado, y si no,
     * en su próximo inicio de sesión.
     *
     * Seguro de llamar desde el hilo del servidor en mitad de una compra: si el
     * dueño está en línea ni siquiera se toca el {@link SavedData}.
     */
    public static void notifyOwner(MinecraftServer server, UUID ownerId, String message) {
        if (server == null || ownerId == null || message == null || message.isEmpty()) return;
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
        if (owner != null) {
            owner.sendSystemMessage(Component.literal(message));
            return;
        }
        get(server).queue(ownerId, message);
    }

    private void queue(UUID ownerId, String message) {
        Set<String> box = pending.computeIfAbsent(ownerId, k -> new LinkedHashSet<>());
        if (box.size() >= MAX_PER_PLAYER || !box.add(message)) return;
        setDirty();
    }

    /** Devuelve y borra los avisos de un jugador. */
    private List<String> drain(UUID ownerId) {
        Set<String> box = pending.remove(ownerId);
        if (box == null || box.isEmpty()) return List.of();
        setDirty();
        return new ArrayList<>(box);
    }

    // ── Entrega al iniciar sesión ────────────────────────────────────────────

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        List<String> messages = get(server).drain(player.getUUID());
        if (messages.isEmpty()) return;
        player.sendSystemMessage(Component.literal(
                "§6[Tus tiendas] §7Mientras no estabas:"));
        for (String m : messages) player.sendSystemMessage(Component.literal(m));
    }

    // ── NBT ──────────────────────────────────────────────────────────────────

    public static ShopAlerts load(CompoundTag tag, HolderLookup.Provider provider) {
        ShopAlerts a = new ShopAlerts();
        ListTag list = tag.getList("Pending", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            if (!e.hasUUID("UUID")) continue;
            ListTag msgs = e.getList("Messages", Tag.TAG_STRING);
            Set<String> box = new LinkedHashSet<>();
            for (int j = 0; j < msgs.size() && box.size() < MAX_PER_PLAYER; j++) {
                box.add(msgs.getString(j));
            }
            if (!box.isEmpty()) a.pending.put(e.getUUID("UUID"), box);
        }
        return a;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Set<String>> e : pending.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            CompoundTag c = new CompoundTag();
            c.putUUID("UUID", e.getKey());
            ListTag msgs = new ListTag();
            for (String m : e.getValue()) msgs.add(net.minecraft.nbt.StringTag.valueOf(m));
            c.put("Messages", msgs);
            list.add(c);
        }
        tag.put("Pending", list);
        return tag;
    }
}
