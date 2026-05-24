package com.example.betteradminshop.command;

import com.example.betteradminshop.BetterAdminShop;
import com.example.betteradminshop.data.PurchaseDatabase;
import com.example.betteradminshop.data.PurchaseRecord;
import com.example.betteradminshop.network.ModNetworking;
import com.example.betteradminshop.network.RecordsDataPayload;

import com.mojang.brigadier.Command;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Path;
import java.util.List;

/**
 * Registers the {@code /adminishop records} command (OP level 4) and
 * handles server lifecycle events for the SQLite database.
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
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PurchaseDatabase.getInstance().close();
    }

    // ── Command registration ──────────────────────────────────────────────────

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("adminishop")
                        .requires(src -> src.hasPermission(4))
                        .then(Commands.literal("records")
                                .executes(ctx -> {
                                    if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
                                        ctx.getSource().sendFailure(Component.literal("Solo jugadores pueden usar este comando."));
                                        return 0;
                                    }
                                    return openRecords(player, 0, "", "purchase_timestamp_utc", false);
                                }))
        );
    }

    // ── Handler ───────────────────────────────────────────────────────────────

    /** Called by the command AND by the request packet handler on page/filter change. */
    public static int openRecords(ServerPlayer player, int page, String playerFilter,
                                  String sortColumn, boolean ascending) {
        PurchaseDatabase db = PurchaseDatabase.getInstance();
        if (!db.isReady()) {
            player.sendSystemMessage(Component.literal(
                    "[BetterAdminShop] La base de datos no está disponible aún."));
            return 0;
        }

        int pageSize = RecordsDataPayload.PAGE_SIZE;
        List<PurchaseRecord> records = db.getRecords(page, pageSize, sortColumn, ascending, playerFilter);
        int total = db.getTotalCount(playerFilter);

        PacketDistributor.sendToPlayer(player,
                new RecordsDataPayload(records, total, page, sortColumn, ascending, playerFilter));

        return Command.SINGLE_SUCCESS;
    }
}
