package com.example.betteradminshop.command;

import com.example.betteradminshop.BetterAdminShop;
import com.example.betteradminshop.data.DynamicShopItems;
import com.example.betteradminshop.data.PurchaseDatabase;
import com.example.betteradminshop.data.PurchaseRecord;
import com.example.betteradminshop.network.DynamicItemsPayload;
import com.example.betteradminshop.network.RecordsDataPayload;

import com.mojang.brigadier.Command;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Path;
import java.util.List;

/**
 * Registers the {@code /tiendas} command tree (OP level 4) — records,
 * restock, items, mongo — and handles server lifecycle events for the DBs.
 */
@EventBusSubscriber(modid = BetterAdminShop.ID, bus = EventBusSubscriber.Bus.GAME)
public final class AdminShopCommand {

    private AdminShopCommand() {}

    // ── Database lifecycle ────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        Path dbPath = event.getServer()
                .getServerDirectory()
                .resolve("config")
                .resolve("betteradminshop_records.db");
        PurchaseDatabase.getInstance().initialize(dbPath);
        // Espejo opcional a MongoDB (no bloqueante; se autodeshabilita si falla).
        com.example.betteradminshop.data.MongoStore.getInstance().initialize();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PurchaseDatabase.getInstance().close();
        com.example.betteradminshop.data.MongoStore.getInstance().close();
    }

    // ── Command registration ──────────────────────────────────────────────────

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("tiendas")
                        .requires(src -> src.hasPermission(4))
                        .then(Commands.literal("records")
                                .executes(ctx -> {
                                    if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
                                        ctx.getSource().sendFailure(Component.literal("Solo jugadores pueden usar este comando."));
                                        return 0;
                                    }
                                    return openRecords(player, 0, "", "purchase_timestamp_utc", false, "");
                                }))
                        // ── Restock global (todas las tiendas, todos los jugadores) ─
                        .then(Commands.literal("restock")
                                .executes(ctx -> globalRestock(ctx.getSource())))
                        // ── Ítems dinámicos del selector de la tienda ──────────────
                        .then(Commands.literal("items")
                                // Añade un ítem (con componentes/NBT) a la lista del selector.
                                // Ej: /tiendas items add otromod:item_dinamico
                                .then(Commands.literal("add")
                                        .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
                                                .executes(ctx -> {
                                                    ItemStack stack = ItemArgument.getItem(ctx, "item")
                                                            .createItemStack(1, false);
                                                    return addDynamicItem(ctx.getSource(), stack);
                                                })))
                                // Añade el ítem que el jugador tiene en la mano.
                                .then(Commands.literal("addhand")
                                        .executes(ctx -> {
                                            if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) {
                                                ctx.getSource().sendFailure(Component.literal("Solo jugadores pueden usar 'addhand'."));
                                                return 0;
                                            }
                                            return addDynamicItem(ctx.getSource(), p.getMainHandItem());
                                        }))
                                // Reescanea/reenvía la lista y refresca el selector abierto.
                                .then(Commands.literal("scan")
                                        .executes(ctx -> scanDynamicItems(ctx.getSource())))
                                // Quita todos los ítems dinámicos añadidos.
                                .then(Commands.literal("clear")
                                        .executes(ctx -> clearDynamicItems(ctx.getSource())))
                                // Informa cuántos ítems dinámicos hay registrados.
                                .then(Commands.literal("list")
                                        .executes(ctx -> listDynamicItems(ctx.getSource()))))
                        // ── Integración con MongoDB ────────────────────────────────
                        .then(Commands.literal("mongo")
                                // Publica en Mongo el estado de todas las tiendas cargadas.
                                .then(Commands.literal("republish")
                                        .executes(ctx -> republishShops(ctx.getSource())))
                                // Informa el estado de la integración.
                                .then(Commands.literal("status")
                                        .executes(ctx -> mongoStatus(ctx.getSource()))))
        );
    }

    // ── Restock global ─────────────────────────────────────────────────────────

    private static int globalRestock(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        if (server == null) return 0;

        long now = System.currentTimeMillis();
        // Marca el instante global (para tiendas descargadas) y reabastece las cargadas.
        com.example.betteradminshop.data.GlobalRestockData.get(server).setTimestamp(now);
        int shops = com.example.betteradminshop.block.ShopBlockEntity.globalRestockAllLoaded(now);

        // Notificación a todos los jugadores: title + subtitle (+ chat y sonido).
        Component title = Component.literal("§a§l✦ Tiendas Reabastecidas ✦");
        Component subtitle = Component.literal("§7El stock se restableció para todos los jugadores");
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
            p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
            p.connection.send(new ClientboundSetTitleTextPacket(title));
            p.sendSystemMessage(Component.literal("§a[Tienda] §fLas tiendas han sido reabastecidas."));
            p.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.MASTER, 0.6f, 1.4f);
        }

        src.sendSuccess(() -> Component.literal("§a[BetterAdminShop] Restock global aplicado a "
                + shops + " tiendas cargadas. §7(las descargadas se reabastecen al cargarse)"), true);
        return Command.SINGLE_SUCCESS;
    }

    // ── MongoDB ────────────────────────────────────────────────────────────────

    private static int republishShops(CommandSourceStack src) {
        if (!com.example.betteradminshop.data.MongoStore.getInstance().isEnabled()) {
            src.sendFailure(Component.literal("§cMongoDB no está habilitado (revisa la config)."));
            return 0;
        }
        int n = com.example.betteradminshop.block.ShopBlockEntity.republishAllLoaded();
        src.sendSuccess(() -> Component.literal("§a[BetterAdminShop] Republicadas " + n
                + " tiendas cargadas a MongoDB. §7(las de chunks no cargados se publican al cargarse/editarse)"), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int mongoStatus(CommandSourceStack src) {
        boolean enabled = com.example.betteradminshop.data.MongoStore.getInstance().isEnabled();
        int loaded = com.example.betteradminshop.block.ShopBlockEntity.loadedShopCount();
        src.sendSuccess(() -> Component.literal("§7[BetterAdminShop] MongoDB: "
                + (enabled ? "§aconectado" : "§cdeshabilitado")
                + " §7· tiendas cargadas: §f" + loaded), false);
        return Command.SINGLE_SUCCESS;
    }

    // ── Ítems dinámicos del selector ──────────────────────────────────────────

    private static int addDynamicItem(CommandSourceStack src, ItemStack stack) {
        MinecraftServer server = src.getServer();
        if (server == null || stack.isEmpty()) {
            src.sendFailure(Component.literal("§cÍtem inválido."));
            return 0;
        }
        boolean added = DynamicShopItems.get(server).add(stack);
        syncDynamicItemsToAll(server);
        if (added) {
            src.sendSuccess(() -> Component.literal("§a[BetterAdminShop] Añadido al selector: §f"
                    + stack.getHoverName().getString()), true);
        } else {
            src.sendSuccess(() -> Component.literal("§e[BetterAdminShop] Ese ítem ya estaba en el selector: §f"
                    + stack.getHoverName().getString()), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int scanDynamicItems(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        if (server == null) return 0;
        // Reenvía la lista a todos (refresca cualquier selector abierto); el
        // cliente además reescanea el registro al refrescar.
        syncDynamicItemsToAll(server);
        int n = DynamicShopItems.get(server).getItems().size();
        src.sendSuccess(() -> Component.literal("§a[BetterAdminShop] Selector actualizado ("
                + n + " ítems dinámicos)."), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int clearDynamicItems(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        if (server == null) return 0;
        int n = DynamicShopItems.get(server).clearAll();
        syncDynamicItemsToAll(server);
        src.sendSuccess(() -> Component.literal("§a[BetterAdminShop] Se quitaron " + n
                + " ítems dinámicos del selector."), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int listDynamicItems(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        if (server == null) return 0;
        int n = DynamicShopItems.get(server).getItems().size();
        src.sendSuccess(() -> Component.literal("§7[BetterAdminShop] Hay §f" + n
                + " §7ítems dinámicos en el selector."), false);
        return Command.SINGLE_SUCCESS;
    }

    /** Envía la lista de ítems dinámicos a un jugador (respuesta a su petición). */
    public static void syncDynamicItemsTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        PacketDistributor.sendToPlayer(player,
                new DynamicItemsPayload(List.copyOf(DynamicShopItems.get(server).getItems())));
    }

    /** Envía la lista de ítems dinámicos a todos los jugadores conectados. */
    public static void syncDynamicItemsToAll(MinecraftServer server) {
        DynamicItemsPayload payload =
                new DynamicItemsPayload(List.copyOf(DynamicShopItems.get(server).getItems()));
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(p, payload);
        }
    }

    // ── Handler ───────────────────────────────────────────────────────────────

    /** Called by the command AND by the request packet handler on page/filter change. */
    public static int openRecords(ServerPlayer player, int page, String playerFilter,
                                  String sortColumn, boolean ascending, String typeFilter) {
        PurchaseDatabase db = PurchaseDatabase.getInstance();
        if (!db.isReady()) {
            player.sendSystemMessage(Component.literal(
                    "[BetterAdminShop] La base de datos no está disponible aún."));
            return 0;
        }

        int pageSize = RecordsDataPayload.PAGE_SIZE;
        List<PurchaseRecord> records = db.getRecords(page, pageSize, sortColumn, ascending, playerFilter, typeFilter);
        int total   = db.getTotalCount(playerFilter, typeFilter);
        int ventas  = db.getTotalCount(playerFilter, PurchaseDatabase.TYPE_VENTA);
        int compras = db.getTotalCount(playerFilter, PurchaseDatabase.TYPE_COMPRA);

        PacketDistributor.sendToPlayer(player,
                new RecordsDataPayload(records, total, ventas, compras, page,
                        sortColumn, ascending, playerFilter, typeFilter));

        return Command.SINGLE_SUCCESS;
    }
}
