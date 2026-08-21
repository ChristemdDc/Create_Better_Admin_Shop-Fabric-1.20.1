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
        // Espejo opcional a MongoDB. Solo se toca MongoStore si el driver está
        // presente (lo aporta otro mod); si no, se usa solo SQLite.
        if (com.example.betteradminshop.data.MongoDriver.AVAILABLE) {
            com.example.betteradminshop.data.MongoStore.getInstance().initialize();
        } else if (com.example.betteradminshop.config.BetterAdminShopConfig.MONGO_ENABLED.get()) {
            BetterAdminShop.LOGGER.warn("[BetterAdminShop] MongoDB está habilitado en la config pero "
                    + "no hay driver de MongoDB en el pack. Se usa solo SQLite.");
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PurchaseDatabase.getInstance().close();
        if (com.example.betteradminshop.data.MongoDriver.AVAILABLE) {
            com.example.betteradminshop.data.MongoStore.getInstance().close();
        }
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
                                .executes(ctx -> globalRestock(ctx.getSource()))
                                // Restock INDIVIDUAL: solo ese jugador; el resto sigue su curso.
                                .then(Commands.literal("jugador")
                                        .then(Commands.argument("jugador",
                                                        net.minecraft.commands.arguments.GameProfileArgument.gameProfile())
                                                .executes(ctx -> restockPlayers(ctx.getSource(),
                                                        net.minecraft.commands.arguments.GameProfileArgument
                                                                .getGameProfiles(ctx, "jugador")))))
                                // Tiempo del ciclo de stock por jugador (global).
                                .then(Commands.literal("tiempo")
                                        .executes(ctx -> showResetTime(ctx.getSource()))
                                        .then(Commands.argument("horas",
                                                        com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.0))
                                                .executes(ctx -> setResetTime(ctx.getSource(),
                                                        com.mojang.brigadier.arguments.DoubleArgumentType
                                                                .getDouble(ctx, "horas"))))))
                        // ── Fondo común de rentas (solo administración) ────────────
                        .then(Commands.literal("fondo")
                                .executes(ctx -> showFund(ctx.getSource()))
                                .then(Commands.literal("retirar")
                                        .executes(ctx -> {
                                            if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
                                                ctx.getSource().sendFailure(Component.literal("Solo jugadores pueden retirar el fondo."));
                                                return 0;
                                            }
                                            return withdrawFund(player);
                                        })))
                        // ── Panel visual de configuración (tiendas de jugador) ─────
                        .then(Commands.literal("config")
                                .executes(ctx -> {
                                    if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
                                        ctx.getSource().sendFailure(Component.literal("Solo jugadores pueden abrir el panel."));
                                        return 0;
                                    }
                                    return openShopConfig(player);
                                }))
                        // ── Renta de las tiendas de JUGADOR (cuota global) ─────────
                        .then(Commands.literal("renta")
                                .executes(ctx -> showRent(ctx.getSource()))
                                .then(Commands.literal("off")
                                        .executes(ctx -> disableRent(ctx.getSource())))
                                .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
                                        .then(Commands.argument("cantidad",
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                                .then(Commands.argument("dias",
                                                                com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.01))
                                                        .executes(ctx -> setRent(ctx.getSource(),
                                                                ItemArgument.getItem(ctx, "item").createItemStack(1, false),
                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "cantidad"),
                                                                com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "dias")))))))
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

    /**
     * Reinicia el stock de uno o varios jugadores concretos. El stock del resto
     * sigue su curso normal (no se toca su ciclo).
     */
    private static int restockPlayers(CommandSourceStack src,
                                      java.util.Collection<com.mojang.authlib.GameProfile> profiles) {
        MinecraftServer server = src.getServer();
        if (server == null || profiles.isEmpty()) return 0;

        long now = System.currentTimeMillis();
        com.example.betteradminshop.data.GlobalRestockData data =
                com.example.betteradminshop.data.GlobalRestockData.get(server);

        int shops = 0;
        StringBuilder names = new StringBuilder();
        for (com.mojang.authlib.GameProfile profile : profiles) {
            // Registrarlo también en el estado global: las tiendas en chunks
            // descargados lo aplicarán al cargarse.
            data.setPlayerRestock(profile.getId(), now);
            shops = com.example.betteradminshop.block.ShopBlockEntity
                    .restockPlayerAllLoaded(profile.getId(), now);

            if (names.length() > 0) names.append(", ");
            names.append(profile.getName());

            ServerPlayer target = server.getPlayerList().getPlayer(profile.getId());
            if (target != null) {
                target.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
                target.connection.send(new ClientboundSetSubtitleTextPacket(
                        Component.literal("§7Tu stock se restableció en las tiendas")));
                target.connection.send(new ClientboundSetTitleTextPacket(
                        Component.literal("§a§l✦ Stock Restablecido ✦")));
                target.sendSystemMessage(Component.literal(
                        "§a[Tienda] §fTu stock ha sido restablecido."));
                target.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.MASTER, 0.6f, 1.4f);
            }
        }

        final int shopCount = shops;
        final String who = names.toString();
        src.sendSuccess(() -> Component.literal("§a[BetterAdminShop] Stock restablecido para §f" + who
                + " §7en " + shopCount + " tiendas cargadas. "
                + "(las descargadas lo aplican al cargarse; el resto de jugadores no se ve afectado)"), true);
        return Command.SINGLE_SUCCESS;
    }

    /** Abre el panel visual de configuración de tiendas de jugador (Fase 5). */
    private static int openShopConfig(ServerPlayer player) {
        var settings = com.example.betteradminshop.data.PlayerShopSettings.get(player.server);
        java.util.List<net.minecraft.world.item.ItemStack> items = new java.util.ArrayList<>();
        java.util.List<Integer> amounts = new java.util.ArrayList<>();
        for (String key : com.example.betteradminshop.data.PlayerShopSettings.UPGRADE_KEYS) {
            var cost = settings.getUpgradeCost(key);
            items.add(cost.item());
            amounts.add(cost.amount());
        }
        PacketDistributor.sendToPlayer(player,
                new com.example.betteradminshop.network.PlayerShopNetworking.ShopConfig(
                        settings.getRentItem(), settings.getRentAmount(), settings.getRentPeriodMs(),
                        items, amounts));
        return Command.SINGLE_SUCCESS;
    }

    // ── Fondo común de rentas ──────────────────────────────────────────────────

    private static int showFund(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        if (server == null) return 0;
        var fund = com.example.betteradminshop.data.RentFund.get(server);
        if (fund.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                    "§7[BetterAdminShop] El fondo común de rentas está §evacío§7."), false);
            return Command.SINGLE_SUCCESS;
        }
        src.sendSuccess(() -> Component.literal("§6[BetterAdminShop] Fondo común de rentas:"), false);
        for (var entry : fund.view()) {
            src.sendSuccess(() -> Component.literal("  §f" + entry.count() + "× "
                    + entry.proto().getHoverName().getString()), false);
        }
        src.sendSuccess(() -> Component.literal(
                "§8Retira todo con /tiendas fondo retirar"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int withdrawFund(ServerPlayer player) {
        var fund = com.example.betteradminshop.data.RentFund.get(player.server);
        if (fund.isEmpty()) {
            player.displayClientMessage(Component.literal(
                    "§e[BetterAdminShop] El fondo está vacío."), true);
            return 0;
        }
        var stacks = fund.withdrawAll();
        int total = 0;
        for (var stack : stacks) {
            total += stack.getCount();
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false); // inventario lleno → al suelo
            }
        }
        final int totalItems = total;
        player.displayClientMessage(Component.literal(
                "§a[BetterAdminShop] Fondo retirado: §f" + totalItems + " ítems§a."), false);
        return Command.SINGLE_SUCCESS;
    }

    // ── Renta de tiendas de jugador ────────────────────────────────────────────

    private static int showRent(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        if (server == null) return 0;
        var s = com.example.betteradminshop.data.PlayerShopSettings.get(server);
        if (!s.isRentConfigured()) {
            src.sendSuccess(() -> Component.literal("§7[BetterAdminShop] Renta §cdeshabilitada§7 — "
                    + "las tiendas de jugador operan gratis. Configura con "
                    + "§f/tiendas renta <item> <cantidad> <días>"), false);
        } else {
            String days = String.format("%.1f", s.getRentPeriodMs() / 86_400_000.0);
            src.sendSuccess(() -> Component.literal("§7[BetterAdminShop] Renta: §f"
                    + s.getRentAmount() + "× " + s.getRentItem().getHoverName().getString()
                    + " §7cada §f" + days + " días§7."), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int setRent(CommandSourceStack src, ItemStack item, int amount, double days) {
        MinecraftServer server = src.getServer();
        if (server == null || item.isEmpty()) return 0;
        long periodMs = (long) (days * 86_400_000.0);
        com.example.betteradminshop.data.PlayerShopSettings.get(server).setRent(item, amount, periodMs);
        com.example.betteradminshop.block.PlayerShopBlockEntity.resyncAllLoaded();
        String daysStr = String.format("%.1f", days);
        src.sendSuccess(() -> Component.literal("§a[BetterAdminShop] Renta configurada: §f"
                + amount + "× " + item.getHoverName().getString() + " §7cada §f" + daysStr
                + " días§7. Las tiendas sin renta al día quedan cerradas."), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int disableRent(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        if (server == null) return 0;
        com.example.betteradminshop.data.PlayerShopSettings.get(server).disableRent();
        com.example.betteradminshop.block.PlayerShopBlockEntity.resyncAllLoaded();
        src.sendSuccess(() -> Component.literal(
                "§a[BetterAdminShop] Renta deshabilitada: las tiendas de jugador operan gratis."), true);
        return Command.SINGLE_SUCCESS;
    }

    /** Muestra el tiempo actual del ciclo de stock por jugador. */
    private static int showResetTime(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        if (server == null) return 0;
        long ms = com.example.betteradminshop.data.GlobalRestockData.get(server).getResetDurationMs();
        src.sendSuccess(() -> Component.literal("§7[BetterAdminShop] El stock de cada jugador se reinicia "
                + "§f" + com.example.betteradminshop.block.ShopBlock.formatDuration(ms / 1000)
                + " §7después de su primera compra."), false);
        return Command.SINGLE_SUCCESS;
    }

    /** Fija (global) el tiempo del ciclo de stock por jugador. */
    private static int setResetTime(CommandSourceStack src, double hours) {
        MinecraftServer server = src.getServer();
        if (server == null) return 0;
        long ms = (long) (hours * 3600_000.0);
        com.example.betteradminshop.data.GlobalRestockData.get(server).setResetDurationMs(ms);
        String pretty = com.example.betteradminshop.block.ShopBlock.formatDuration(ms / 1000);
        src.sendSuccess(() -> Component.literal("§a[BetterAdminShop] Tiempo de reseteo de stock: §f" + pretty
                + " §7· se aplica a los ciclos que empiecen desde ahora "
                + "(usa §f/tiendas restock§7 para aplicarlo a todos ya)."), true);
        return Command.SINGLE_SUCCESS;
    }

    // ── MongoDB ────────────────────────────────────────────────────────────────

    private static int republishShops(CommandSourceStack src) {
        if (!com.example.betteradminshop.data.MongoDriver.AVAILABLE
                || !com.example.betteradminshop.data.MongoStore.getInstance().isEnabled()) {
            src.sendFailure(Component.literal("§cMongoDB no está habilitado (revisa la config o el driver)."));
            return 0;
        }
        int n = com.example.betteradminshop.block.ShopBlockEntity.republishAllLoaded();
        src.sendSuccess(() -> Component.literal("§a[BetterAdminShop] Republicadas " + n
                + " tiendas cargadas a MongoDB. §7(las de chunks no cargados se publican al cargarse/editarse)"), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int mongoStatus(CommandSourceStack src) {
        boolean enabled = com.example.betteradminshop.data.MongoDriver.AVAILABLE
                && com.example.betteradminshop.data.MongoStore.getInstance().isEnabled();
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
