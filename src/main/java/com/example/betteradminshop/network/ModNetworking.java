package com.example.betteradminshop.network;

import com.example.betteradminshop.BetterAdminShop;
import com.example.betteradminshop.block.ShopBlockEntity;
import com.example.betteradminshop.block.ShopSlot;
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

    /**
     * Configuración completa de un slot en un solo paquete: ítem vendido,
     * ítem de render (override opcional), cantidad por venta, hasta dos
     * ítems de precio y stock máximo.
     */
    public record SetSlotConfig(BlockPos pos, int slotIndex, boolean isCompra,
                                ItemStack saleItem, ItemStack renderOverride, int sellAmount,
                                ItemStack priceItem, int priceAmount,
                                ItemStack priceItem2, int priceAmount2,
                                int maxStock) implements CustomPacketPayload {
        public static final Type<SetSlotConfig> TYPE = new Type<>(BetterAdminShop.id("set_slot_config"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SetSlotConfig> STREAM_CODEC =
                new StreamCodec<>() {
                    @Override
                    public SetSlotConfig decode(RegistryFriendlyByteBuf buf) {
                        BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                        int slotIndex = buf.readVarInt();
                        boolean isCompra = buf.readBoolean();
                        ItemStack saleItem = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                        ItemStack renderOverride = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                        int sellAmount = buf.readVarInt();
                        ItemStack priceItem = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                        int priceAmount = buf.readVarInt();
                        ItemStack priceItem2 = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                        int priceAmount2 = buf.readVarInt();
                        int maxStock = buf.readInt();
                        return new SetSlotConfig(pos, slotIndex, isCompra, saleItem, renderOverride, sellAmount,
                                priceItem, priceAmount, priceItem2, priceAmount2, maxStock);
                    }

                    @Override
                    public void encode(RegistryFriendlyByteBuf buf, SetSlotConfig p) {
                        BlockPos.STREAM_CODEC.encode(buf, p.pos());
                        buf.writeVarInt(p.slotIndex());
                        buf.writeBoolean(p.isCompra());
                        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, p.saleItem());
                        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, p.renderOverride());
                        buf.writeVarInt(p.sellAmount());
                        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, p.priceItem());
                        buf.writeVarInt(p.priceAmount());
                        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, p.priceItem2());
                        buf.writeVarInt(p.priceAmount2());
                        // maxStock puede ser -1 (infinito): writeInt, no VarInt negativo
                        buf.writeInt(p.maxStock());
                    }
                };
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

    /** Intercambia (o mueve) dos slots del estante. */
    public record SwapSlots(BlockPos pos, int indexA, int indexB) implements CustomPacketPayload {
        public static final Type<SwapSlots> TYPE = new Type<>(BetterAdminShop.id("swap_slots"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SwapSlots> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SwapSlots::pos,
                ByteBufCodecs.VAR_INT, SwapSlots::indexA,
                ByteBufCodecs.VAR_INT, SwapSlots::indexB,
                SwapSlots::new
        );
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // -------- Registration --------------------------------------------------

    public static void register(RegisterPayloadHandlersEvent event) {
        // executesOn(MAIN) so handlers fire on the server thread directly — no
        // need for context.enqueueWork(...). Mirrors the server.execute(...) in
        // the Fabric version.
        PayloadRegistrar r = event.registrar("2");
        r.playToServer(SetSlotConfig.TYPE, SetSlotConfig.STREAM_CODEC, ModNetworking::handleSetSlotConfig);
        r.playToServer(RestockSlot.TYPE,   RestockSlot.STREAM_CODEC,   ModNetworking::handleRestockSlot);
        r.playToServer(ClearSlot.TYPE,     ClearSlot.STREAM_CODEC,     ModNetworking::handleClearSlot);
        r.playToServer(SwapSlots.TYPE,     SwapSlots.STREAM_CODEC,     ModNetworking::handleSwapSlots);

        // Records panel
        r.playToServer(RequestRecordsPayload.TYPE, RequestRecordsPayload.STREAM_CODEC, ModNetworking::handleRequestRecords);
        // Client-only handler: reference it through a deferring lambda so the
        // client class (and Screen) is never classloaded on a dedicated server.
        r.playToClient(RecordsDataPayload.TYPE,    RecordsDataPayload.STREAM_CODEC,
                (msg, ctx) -> com.example.betteradminshop.client.ClientPayloadHandler.handleRecordsData(msg, ctx));

        // Ítems dinámicos auxiliares
        r.playToServer(RequestDynamicItemsPayload.TYPE, RequestDynamicItemsPayload.STREAM_CODEC,
                ModNetworking::handleRequestDynamicItems);
        r.playToClient(DynamicItemsPayload.TYPE, DynamicItemsPayload.STREAM_CODEC,
                (msg, ctx) -> com.example.betteradminshop.client.ClientPayloadHandler.handleDynamicItems(msg, ctx));
    }

    // -------- Server-side handlers (run on main thread) --------------------

    private static void handleSetSlotConfig(SetSlotConfig msg, IPayloadContext ctx) {
        ServerPlayer player = asServer(ctx);
        if (player == null || !player.hasPermissions(4)) return;
        ShopBlockEntity shop = getShop(player, msg.pos());
        if (shop != null) {
            ShopSlot.Type type = msg.isCompra() ? ShopSlot.Type.COMPRA : ShopSlot.Type.VENTA;
            shop.applySlotConfig(msg.slotIndex(), type, msg.saleItem(), msg.renderOverride(),
                    msg.sellAmount(), msg.priceItem(), msg.priceAmount(),
                    msg.priceItem2(), msg.priceAmount2(), msg.maxStock());
        }
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

    private static void handleSwapSlots(SwapSlots msg, IPayloadContext ctx) {
        ServerPlayer player = asServer(ctx);
        if (player == null || !player.hasPermissions(4)) return;
        ShopBlockEntity shop = getShop(player, msg.pos());
        if (shop != null) shop.swapSlots(msg.indexA(), msg.indexB());
    }

    // ---- Records panel (server side) --------------------------------------

    private static void handleRequestRecords(RequestRecordsPayload msg, IPayloadContext ctx) {
        ServerPlayer player = asServer(ctx);
        if (player == null || !player.hasPermissions(4)) return;
        ctx.enqueueWork(() -> AdminShopCommand.openRecords(
                player, msg.page(), msg.playerFilter(), msg.sortColumn(), msg.ascending(), msg.typeFilter()));
    }

    // Client-side handling of RecordsDataPayload lives in
    // com.example.betteradminshop.client.ClientPayloadHandler so this class
    // (loaded on the dedicated server) never references client-only types.

    // ---- Ítems dinámicos (server side) ------------------------------------

    private static void handleRequestDynamicItems(RequestDynamicItemsPayload msg, IPayloadContext ctx) {
        ServerPlayer player = asServer(ctx);
        if (player == null || !player.hasPermissions(4)) return;
        ctx.enqueueWork(() -> AdminShopCommand.syncDynamicItemsTo(player));
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

    public static void sendSetSlotConfig(BlockPos pos, int slotIndex, boolean isCompra,
                                         ItemStack saleItem, ItemStack renderOverride, int sellAmount,
                                         ItemStack priceItem, int priceAmount,
                                         ItemStack priceItem2, int priceAmount2, int maxStock) {
        PacketDistributor.sendToServer(new SetSlotConfig(pos, slotIndex, isCompra, saleItem, renderOverride,
                sellAmount, priceItem, priceAmount, priceItem2, priceAmount2, maxStock));
    }

    public static void sendRestockSlot(BlockPos pos, int slotIndex) {
        PacketDistributor.sendToServer(new RestockSlot(pos, slotIndex));
    }

    public static void sendClearSlot(BlockPos pos, int slotIndex) {
        PacketDistributor.sendToServer(new ClearSlot(pos, slotIndex));
    }

    public static void sendSwapSlots(BlockPos pos, int indexA, int indexB) {
        PacketDistributor.sendToServer(new SwapSlots(pos, indexA, indexB));
    }
}
