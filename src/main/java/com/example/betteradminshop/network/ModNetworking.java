package com.example.betteradminshop.network;

import com.example.betteradminshop.BetterAdminShop;
import com.example.betteradminshop.block.ShopBlockEntity;
import com.example.betteradminshop.command.AdminShopCommand;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Networking layer for the shop, ported from Fabric Networking-API v1
 * to NeoForge's {@link CustomPacketPayload} system (Minecraft 1.20.5+).
 *
 * All 6 packets keep their original ResourceLocation / wire fields /
 * server-side behavior. Permission level 4 is enforced exactly like in
 * the Fabric version.
 */
public final class ModNetworking {

    private ModNetworking() {}

    // -------- Payload records ----------------------------------------------

    public record SetSlotItem(BlockPos pos, int slotIndex, ItemStack item) implements CustomPacketPayload {
        public static final Type<SetSlotItem> TYPE = new Type<>(BetterAdminShop.id("set_slot_item"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SetSlotItem> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SetSlotItem::pos,
                ByteBufCodecs.VAR_INT, SetSlotItem::slotIndex,
                ItemStack.OPTIONAL_STREAM_CODEC, SetSlotItem::item,
                SetSlotItem::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SetSlotPrice(BlockPos pos, int slotIndex, ItemStack priceItem, int priceAmount) implements CustomPacketPayload {
        public static final Type<SetSlotPrice> TYPE = new Type<>(BetterAdminShop.id("set_slot_price"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SetSlotPrice> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SetSlotPrice::pos,
                ByteBufCodecs.VAR_INT, SetSlotPrice::slotIndex,
                ItemStack.OPTIONAL_STREAM_CODEC, SetSlotPrice::priceItem,
                ByteBufCodecs.VAR_INT, SetSlotPrice::priceAmount,
                SetSlotPrice::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SetSlotStock(BlockPos pos, int slotIndex, int maxStock) implements CustomPacketPayload {
        public static final Type<SetSlotStock> TYPE = new Type<>(BetterAdminShop.id("set_slot_stock"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SetSlotStock> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SetSlotStock::pos,
                ByteBufCodecs.VAR_INT, SetSlotStock::slotIndex,
                ByteBufCodecs.VAR_INT, SetSlotStock::maxStock,
                SetSlotStock::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record RestockSlot(BlockPos pos, int slotIndex) implements CustomPacketPayload {
        public static final Type<RestockSlot> TYPE = new Type<>(BetterAdminShop.id("restock_slot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RestockSlot> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, RestockSlot::pos,
                ByteBufCodecs.VAR_INT, RestockSlot::slotIndex,
                RestockSlot::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ClearSlot(BlockPos pos, int slotIndex) implements CustomPacketPayload {
        public static final Type<ClearSlot> TYPE = new Type<>(BetterAdminShop.id("clear_slot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClearSlot> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, ClearSlot::pos,
                ByteBufCodecs.VAR_INT, ClearSlot::slotIndex,
                ClearSlot::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // -------- Registration --------------------------------------------------

    public static void register(RegisterPayloadHandlersEvent event) {
        // executesOn(MAIN) so handlers fire on the server thread directly — no
        // need for context.enqueueWork(...). Mirrors the server.execute(...) in
        // the Fabric version.
        PayloadRegistrar r = event.registrar("1");
        r.playToServer(SetSlotItem.TYPE,  SetSlotItem.STREAM_CODEC,  ModNetworking::handleSetSlotItem);
        r.playToServer(SetSlotPrice.TYPE, SetSlotPrice.STREAM_CODEC, ModNetworking::handleSetSlotPrice);
        r.playToServer(SetSlotStock.TYPE, SetSlotStock.STREAM_CODEC, ModNetworking::handleSetSlotStock);
        r.playToServer(RestockSlot.TYPE,  RestockSlot.STREAM_CODEC,  ModNetworking::handleRestockSlot);
        r.playToServer(ClearSlot.TYPE,    ClearSlot.STREAM_CODEC,    ModNetworking::handleClearSlot);

        // Records panel
        r.playToServer(RequestRecordsPayload.TYPE, RequestRecordsPayload.STREAM_CODEC, ModNetworking::handleRequestRecords);
        r.playToClient(RecordsDataPayload.TYPE,    RecordsDataPayload.STREAM_CODEC,    ModNetworking::handleRecordsData);
    }

    // -------- Server-side handlers (run on main thread) --------------------

    private static void handleSetSlotItem(SetSlotItem msg, IPayloadContext ctx) {
        ServerPlayer player = asServer(ctx);
        if (player == null || !player.hasPermissions(4)) return;
        ShopBlockEntity shop = getShop(player, msg.pos());
        if (shop != null) shop.setSlotItem(msg.slotIndex(), msg.item());
    }

    private static void handleSetSlotPrice(SetSlotPrice msg, IPayloadContext ctx) {
        ServerPlayer player = asServer(ctx);
        if (player == null || !player.hasPermissions(4)) return;
        ShopBlockEntity shop = getShop(player, msg.pos());
        if (shop != null) shop.setSlotPrice(msg.slotIndex(), msg.priceItem(), msg.priceAmount());
    }

    private static void handleSetSlotStock(SetSlotStock msg, IPayloadContext ctx) {
        ServerPlayer player = asServer(ctx);
        if (player == null || !player.hasPermissions(4)) return;
        ShopBlockEntity shop = getShop(player, msg.pos());
        if (shop != null) shop.setSlotMaxStock(msg.slotIndex(), msg.maxStock());
    }

    private static void handleRestockSlot(RestockSlot msg, IPayloadContext ctx) {
        ServerPlayer player = asServer(ctx);
        if (player == null || !player.hasPermissions(4)) return;
        ShopBlockEntity shop = getShop(player, msg.pos());
        if (shop != null) shop.restockSlot(msg.slotIndex());
    }

    private static void handleClearSlot(ClearSlot msg, IPayloadContext ctx) {
        ServerPlayer player = asServer(ctx);
        if (player == null || !player.hasPermissions(4)) return;
        ShopBlockEntity shop = getShop(player, msg.pos());
        if (shop != null) shop.clearSlot(msg.slotIndex());
    }

    // ---- Records panel (server side) --------------------------------------

    private static void handleRequestRecords(RequestRecordsPayload msg, IPayloadContext ctx) {
        ServerPlayer player = asServer(ctx);
        if (player == null || !player.hasPermissions(4)) return;
        ctx.enqueueWork(() -> AdminShopCommand.openRecords(
                player, msg.page(), msg.playerFilter(), msg.sortColumn(), msg.ascending()));
    }

    /** Runs client-side — opens or refreshes the AdminRecordsScreen. */
    @SuppressWarnings("deprecation")
    private static void handleRecordsData(RecordsDataPayload msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null) return;
            if (mc.screen instanceof com.example.betteradminshop.client.AdminRecordsScreen existing) {
                existing.updateData(msg);
            } else {
                mc.setScreen(new com.example.betteradminshop.client.AdminRecordsScreen(msg));
            }
        });
    }

    // -------- Helpers -------------------------------------------------------

    private static ServerPlayer asServer(IPayloadContext ctx) {
        return ctx.player() instanceof ServerPlayer sp ? sp : null;
    }

    private static ShopBlockEntity getShop(ServerPlayer player, BlockPos pos) {
        if (player.level().isLoaded(pos)) {
            BlockEntity be = player.level().getBlockEntity(pos);
            if (be instanceof ShopBlockEntity shop) return shop;
        }
        return null;
    }

    // -------- Client-side send helpers -------------------------------------

    public static void sendSetSlotItem(BlockPos pos, int slotIndex, ItemStack item) {
        PacketDistributor.sendToServer(new SetSlotItem(pos, slotIndex, item));
    }

    public static void sendSetSlotPrice(BlockPos pos, int slotIndex, ItemStack priceItem, int priceAmount) {
        PacketDistributor.sendToServer(new SetSlotPrice(pos, slotIndex, priceItem, priceAmount));
    }

    public static void sendSetSlotStock(BlockPos pos, int slotIndex, int maxStock) {
        PacketDistributor.sendToServer(new SetSlotStock(pos, slotIndex, maxStock));
    }

    public static void sendRestockSlot(BlockPos pos, int slotIndex) {
        PacketDistributor.sendToServer(new RestockSlot(pos, slotIndex));
    }

    public static void sendClearSlot(BlockPos pos, int slotIndex) {
        PacketDistributor.sendToServer(new ClearSlot(pos, slotIndex));
    }
}
