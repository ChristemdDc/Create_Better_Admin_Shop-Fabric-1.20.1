package com.example.betteradminshop.network;

import com.example.betteradminshop.BetterAdminShop;
import com.example.betteradminshop.block.PlayerShopBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.UUID;

/**
 * Paquetes del MENÚ de la tienda de jugador (Fase 4). Todos C2S; el servidor
 * valida SIEMPRE con {@code canManage} (y dueño-only para empleados) antes de
 * tocar nada, y sincroniza el BE para que el cambio se vea al instante.
 */
public final class PlayerShopNetworking {

    private PlayerShopNetworking() {}

    // ── Payloads ─────────────────────────────────────────────────────────────

    /** Configura ítem en venta (count = unidades por compra) + precio de un slot. */
    /**
     * Configura un slot de venta. Lleva DOS precios: el segundo es opcional
     * (vacío = solo se cobra el primero). Codec escrito a mano porque
     * {@code StreamCodec.composite} solo admite hasta 6 campos.
     */
    public record SetSlot(BlockPos pos, int slot, ItemStack saleItem,
                          ItemStack priceItem, int priceAmount,
                          ItemStack priceItem2, int priceAmount2, int rotation)
            implements CustomPacketPayload {
        public static final Type<SetSlot> TYPE = new Type<>(BetterAdminShop.id("pshop_set_slot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SetSlot> STREAM_CODEC =
                new StreamCodec<>() {
                    @Override
                    public SetSlot decode(RegistryFriendlyByteBuf buf) {
                        BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                        int slot = buf.readVarInt();
                        ItemStack saleItem = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                        ItemStack priceItem = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                        int priceAmount = buf.readVarInt();
                        ItemStack priceItem2 = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                        int priceAmount2 = buf.readVarInt();
                        int rotation = buf.readVarInt();
                        return new SetSlot(pos, slot, saleItem, priceItem, priceAmount,
                                priceItem2, priceAmount2, rotation);
                    }

                    @Override
                    public void encode(RegistryFriendlyByteBuf buf, SetSlot p) {
                        BlockPos.STREAM_CODEC.encode(buf, p.pos());
                        buf.writeVarInt(p.slot());
                        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, p.saleItem());
                        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, p.priceItem());
                        buf.writeVarInt(p.priceAmount());
                        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, p.priceItem2());
                        buf.writeVarInt(p.priceAmount2());
                        buf.writeVarInt(p.rotation());
                    }
                };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ClearSlot(BlockPos pos, int slot) implements CustomPacketPayload {
        public static final Type<ClearSlot> TYPE = new Type<>(BetterAdminShop.id("pshop_clear_slot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClearSlot> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, ClearSlot::pos,
                ByteBufCodecs.VAR_INT, ClearSlot::slot,
                ClearSlot::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Gira el ítem del slot un cuarto de vuelta (horizontal). */
    public record RotateSlot(BlockPos pos, int slot) implements CustomPacketPayload {
        public static final Type<RotateSlot> TYPE = new Type<>(BetterAdminShop.id("pshop_rotate_slot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RotateSlot> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, RotateSlot::pos,
                ByteBufCodecs.VAR_INT, RotateSlot::slot,
                RotateSlot::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Intercambia dos slots de venta (reorganización por arrastre). */
    public record SwapSlots(BlockPos pos, int a, int b) implements CustomPacketPayload {
        public static final Type<SwapSlots> TYPE = new Type<>(BetterAdminShop.id("pshop_swap_slots"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SwapSlots> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SwapSlots::pos,
                ByteBufCodecs.VAR_INT, SwapSlots::a,
                ByteBufCodecs.VAR_INT, SwapSlots::b,
                SwapSlots::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** kind: 0 = estante izq, 1 = estante der, 2 = capacidad stock, 3 = vacío (comprar), 4 = vacío on/off. */
    public record Upgrade(BlockPos pos, int kind) implements CustomPacketPayload {
        public static final Type<Upgrade> TYPE = new Type<>(BetterAdminShop.id("pshop_upgrade"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Upgrade> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Upgrade::pos,
                ByteBufCodecs.VAR_INT, Upgrade::kind,
                Upgrade::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Purge(BlockPos pos, int stockSlot) implements CustomPacketPayload {
        public static final Type<Purge> TYPE = new Type<>(BetterAdminShop.id("pshop_purge"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Purge> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Purge::pos,
                ByteBufCodecs.VAR_INT, Purge::stockSlot,
                Purge::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Alterna el bloqueo (reserva de tipo) de un slot del stock. */
    public record LockStock(BlockPos pos, int stockSlot) implements CustomPacketPayload {
        public static final Type<LockStock> TYPE = new Type<>(BetterAdminShop.id("pshop_lock_stock"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LockStock> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, LockStock::pos,
                ByteBufCodecs.VAR_INT, LockStock::stockSlot,
                LockStock::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** add=true: value = nombre del jugador · add=false: value = UUID a quitar. */
    public record Manager(BlockPos pos, boolean add, String value) implements CustomPacketPayload {
        public static final Type<Manager> TYPE = new Type<>(BetterAdminShop.id("pshop_manager"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Manager> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Manager::pos,
                ByteBufCodecs.BOOL, Manager::add,
                ByteBufCodecs.STRING_UTF8, Manager::value,
                Manager::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record PayRent(BlockPos pos) implements CustomPacketPayload {
        public static final Type<PayRent> TYPE = new Type<>(BetterAdminShop.id("pshop_pay_rent"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PayRent> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, PayRent::pos,
                PayRent::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Configuración global de las tiendas de jugador (Fase 5). Se usa en ambos
     * sentidos: S2C abre el panel de administración con los valores actuales;
     * C2S guarda los cambios (validado con permiso 4).
     *
     * costs: 5 pares (ítem, cantidad) en el orden de
     * {@link com.example.betteradminshop.data.PlayerShopSettings#UPGRADE_KEYS}.
     */
    public record ShopConfig(ItemStack rentItem, int rentAmount, long rentPeriodMs,
                             java.util.List<ItemStack> costItems,
                             java.util.List<Integer> costAmounts) implements CustomPacketPayload {
        public static final Type<ShopConfig> TYPE = new Type<>(BetterAdminShop.id("pshop_config"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ShopConfig> STREAM_CODEC =
                new StreamCodec<>() {
                    @Override
                    public ShopConfig decode(RegistryFriendlyByteBuf buf) {
                        ItemStack rentItem = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                        int rentAmount = buf.readVarInt();
                        long period = buf.readLong();
                        int n = buf.readVarInt();
                        java.util.List<ItemStack> items = new java.util.ArrayList<>(n);
                        java.util.List<Integer> amounts = new java.util.ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            items.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
                            amounts.add(buf.readVarInt());
                        }
                        return new ShopConfig(rentItem, rentAmount, period, items, amounts);
                    }

                    @Override
                    public void encode(RegistryFriendlyByteBuf buf, ShopConfig p) {
                        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, p.rentItem());
                        buf.writeVarInt(p.rentAmount());
                        buf.writeLong(p.rentPeriodMs());
                        buf.writeVarInt(p.costItems().size());
                        for (int i = 0; i < p.costItems().size(); i++) {
                            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, p.costItems().get(i));
                            buf.writeVarInt(p.costAmounts().get(i));
                        }
                    }
                };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ── Registro ─────────────────────────────────────────────────────────────

    public static void register(PayloadRegistrar r) {
        r.playToServer(SetSlot.TYPE, SetSlot.STREAM_CODEC, PlayerShopNetworking::handleSetSlot);
        r.playToServer(ClearSlot.TYPE, ClearSlot.STREAM_CODEC, PlayerShopNetworking::handleClearSlot);
        r.playToServer(SwapSlots.TYPE, SwapSlots.STREAM_CODEC, PlayerShopNetworking::handleSwapSlots);
        r.playToServer(RotateSlot.TYPE, RotateSlot.STREAM_CODEC, PlayerShopNetworking::handleRotateSlot);
        r.playToServer(Upgrade.TYPE, Upgrade.STREAM_CODEC, PlayerShopNetworking::handleUpgrade);
        r.playToServer(Purge.TYPE, Purge.STREAM_CODEC, PlayerShopNetworking::handlePurge);
        r.playToServer(LockStock.TYPE, LockStock.STREAM_CODEC, PlayerShopNetworking::handleLockStock);
        r.playToServer(Manager.TYPE, Manager.STREAM_CODEC, PlayerShopNetworking::handleManager);
        r.playToServer(PayRent.TYPE, PayRent.STREAM_CODEC, PlayerShopNetworking::handlePayRent);
        // Panel de configuración de administración (Fase 5)
        r.playToClient(ShopConfig.TYPE, ShopConfig.STREAM_CODEC,
                (msg, ctx) -> com.example.betteradminshop.client.ClientPayloadHandler.handleShopConfig(msg, ctx));
        // C2S de guardado usa un TYPE distinto para no chocar con el S2C
        r.playToServer(SaveShopConfig.TYPE, SaveShopConfig.STREAM_CODEC,
                PlayerShopNetworking::handleSaveConfig);
    }

    /** C2S: guardar el panel de configuración (mismo cuerpo que ShopConfig). */
    public record SaveShopConfig(ShopConfig config) implements CustomPacketPayload {
        public static final Type<SaveShopConfig> TYPE = new Type<>(BetterAdminShop.id("pshop_config_save"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SaveShopConfig> STREAM_CODEC =
                new StreamCodec<>() {
                    @Override
                    public SaveShopConfig decode(RegistryFriendlyByteBuf buf) {
                        return new SaveShopConfig(ShopConfig.STREAM_CODEC.decode(buf));
                    }

                    @Override
                    public void encode(RegistryFriendlyByteBuf buf, SaveShopConfig p) {
                        ShopConfig.STREAM_CODEC.encode(buf, p.config());
                    }
                };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static void handleSaveConfig(SaveShopConfig msg, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player) || !player.hasPermissions(4)) return;
        var settings = com.example.betteradminshop.data.PlayerShopSettings.get(player.server);
        ShopConfig c = msg.config();
        if (c.rentItem().isEmpty() || c.rentAmount() <= 0) {
            settings.disableRent();
        } else {
            settings.setRent(c.rentItem(), c.rentAmount(), c.rentPeriodMs());
        }
        String[] keys = com.example.betteradminshop.data.PlayerShopSettings.UPGRADE_KEYS;
        for (int i = 0; i < keys.length && i < c.costItems().size(); i++) {
            if (!c.costItems().get(i).isEmpty()) {
                settings.setUpgradeCost(keys[i], c.costItems().get(i),
                        Math.max(0, c.costAmounts().get(i)));
            }
        }
        // Refrescar las cachés que muestran los menús/HUD de las tiendas abiertas
        PlayerShopBlockEntity.resyncAllLoaded();
        player.displayClientMessage(Component.literal(
                "§a[BetterAdminShop] Configuración de tiendas de jugador guardada."), true);
    }

    // ── Handlers (servidor) ──────────────────────────────────────────────────

    private static PlayerShopBlockEntity managedShop(IPayloadContext ctx, BlockPos pos) {
        if (!(ctx.player() instanceof ServerPlayer player)) return null;
        if (!player.level().isLoaded(pos)) return null;
        BlockEntity be = player.level().getBlockEntity(pos);
        if (!(be instanceof PlayerShopBlockEntity shop)) return null;
        if (!shop.canManage(player)) {
            player.displayClientMessage(Component.literal("§cNo puedes gestionar esta tienda."), true);
            return null;
        }
        return shop;
    }

    private static void handleSetSlot(SetSlot msg, IPayloadContext ctx) {
        PlayerShopBlockEntity shop = managedShop(ctx, msg.pos());
        if (shop == null) return;
        String result = shop.applySlotConfig(msg.slot(), msg.saleItem(), msg.priceItem(),
                msg.priceAmount(), msg.priceItem2(), msg.priceAmount2(), msg.rotation());
        if (ctx.player() instanceof ServerPlayer p) {
            if ("not_in_stock".equals(result)) {
                p.displayClientMessage(Component.literal(
                        "§cEse ítem ya no está en el stock de la tienda."), true);
            } else if ("no_price".equals(result)) {
                p.displayClientMessage(Component.literal(
                        "§cDebes fijar un precio antes de poner el ítem a la venta."), true);
            } else if ("dup_price".equals(result)) {
                p.displayClientMessage(Component.literal(
                        "§cLos dos precios deben ser ítems distintos."), true);
            }
        }
    }

    private static void handleClearSlot(ClearSlot msg, IPayloadContext ctx) {
        PlayerShopBlockEntity shop = managedShop(ctx, msg.pos());
        if (shop != null) shop.clearSlotConfig(msg.slot());
    }

    private static void handleRotateSlot(RotateSlot msg, IPayloadContext ctx) {
        PlayerShopBlockEntity shop = managedShop(ctx, msg.pos());
        if (shop != null) shop.rotateSlot(msg.slot());
    }

    private static void handleSwapSlots(SwapSlots msg, IPayloadContext ctx) {
        PlayerShopBlockEntity shop = managedShop(ctx, msg.pos());
        if (shop != null) shop.swapShopSlots(msg.a(), msg.b());
    }

    private static void handleUpgrade(Upgrade msg, IPayloadContext ctx) {
        PlayerShopBlockEntity shop = managedShop(ctx, msg.pos());
        if (shop == null || !(ctx.player() instanceof ServerPlayer player)) return;
        String result = shop.purchaseUpgrade(player, msg.kind());
        if (result == null) {
            player.displayClientMessage(Component.literal("§a¡Mejora comprada!"), true);
        } else if (result.startsWith("pay:")) {
            player.displayClientMessage(Component.literal(
                    "§cNecesitas " + result.substring(4) + " para esta mejora."), true);
        } else if (result.equals("max")) {
            player.displayClientMessage(Component.literal("§eYa está al máximo."), true);
        }
    }

    private static void handlePurge(Purge msg, IPayloadContext ctx) {
        PlayerShopBlockEntity shop = managedShop(ctx, msg.pos());
        if (shop == null || !(ctx.player() instanceof ServerPlayer player)) return;
        String result = shop.purgeStock(msg.stockSlot());
        if (result == null) {
            player.displayClientMessage(Component.literal(
                    "§a¡Purga enviada al chute de export en cardboards!"), true);
        } else if (result.equals("no_conduit")) {
            player.displayClientMessage(Component.literal(
                    "§cDebes conectar un ducto/tolva al chute de export para poder purgar."), true);
        } else if (result.equals("full")) {
            player.displayClientMessage(Component.literal(
                    "§cLa recaudación está llena: vacíala antes de purgar."), true);
        }
    }

    private static void handleLockStock(LockStock msg, IPayloadContext ctx) {
        PlayerShopBlockEntity shop = managedShop(ctx, msg.pos());
        if (shop == null || !(ctx.player() instanceof ServerPlayer player)) return;
        boolean wasLocked = shop.getStock().isLocked(msg.stockSlot());
        String result = shop.toggleStockLock(msg.stockSlot());
        if (result == null) {
            player.displayClientMessage(Component.literal(wasLocked
                    ? "§eSlot desbloqueado."
                    : "§a¡Slot bloqueado! Solo admitirá ese ítem."), true);
        } else {
            player.displayClientMessage(Component.literal(
                    "§cNo hay ningún ítem en ese slot que bloquear."), true);
        }
    }

    private static void handleManager(Manager msg, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        if (!player.level().isLoaded(msg.pos())) return;
        if (!(player.level().getBlockEntity(msg.pos()) instanceof PlayerShopBlockEntity shop)) return;
        // Solo el DUEÑO (o un admin) gestiona empleados
        if (!shop.isOwner(player) && !player.hasPermissions(2)) {
            player.displayClientMessage(Component.literal(
                    "§cSolo el dueño puede gestionar los empleados."), true);
            return;
        }
        if (msg.add()) {
            var profile = player.server.getProfileCache() != null
                    ? player.server.getProfileCache().get(msg.value()).orElse(null) : null;
            if (profile == null) {
                player.displayClientMessage(Component.literal(
                        "§cJugador '" + msg.value() + "' no encontrado."), true);
                return;
            }
            if (profile.getId().equals(shop.getOwnerId())) return;
            shop.addManager(profile.getId(), profile.getName());
            player.displayClientMessage(Component.literal(
                    "§a" + profile.getName() + " añadido al negocio."), true);
        } else {
            try {
                shop.removeManager(UUID.fromString(msg.value()));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private static void handlePayRent(PayRent msg, IPayloadContext ctx) {
        PlayerShopBlockEntity shop = managedShop(ctx, msg.pos());
        if (shop == null || !(ctx.player() instanceof ServerPlayer player)) return;
        String result = shop.payRent(player);
        if (result == null) {
            player.displayClientMessage(Component.literal("§a¡Renta pagada! Tienda operativa."), true);
        } else if (result.startsWith("pay:")) {
            player.displayClientMessage(Component.literal(
                    "§cNecesitas " + result.substring(4) + " para pagar la renta."), true);
        }
    }

    // ── Helpers de envío (cliente) ───────────────────────────────────────────

    public static void sendSetSlot(BlockPos pos, int slot, ItemStack sale, ItemStack price,
                                   int amount, ItemStack price2, int amount2, int rotation) {
        PacketDistributor.sendToServer(
                new SetSlot(pos, slot, sale, price, amount, price2, amount2, rotation));
    }

    public static void sendClearSlot(BlockPos pos, int slot) {
        PacketDistributor.sendToServer(new ClearSlot(pos, slot));
    }

    public static void sendRotateSlot(BlockPos pos, int slot) {
        PacketDistributor.sendToServer(new RotateSlot(pos, slot));
    }

    public static void sendSwapSlots(BlockPos pos, int a, int b) {
        PacketDistributor.sendToServer(new SwapSlots(pos, a, b));
    }

    public static void sendUpgrade(BlockPos pos, int kind) {
        PacketDistributor.sendToServer(new Upgrade(pos, kind));
    }

    public static void sendPurge(BlockPos pos, int stockSlot) {
        PacketDistributor.sendToServer(new Purge(pos, stockSlot));
    }

    public static void sendLockStock(BlockPos pos, int stockSlot) {
        PacketDistributor.sendToServer(new LockStock(pos, stockSlot));
    }

    public static void sendManager(BlockPos pos, boolean add, String value) {
        PacketDistributor.sendToServer(new Manager(pos, add, value));
    }

    public static void sendPayRent(BlockPos pos) {
        PacketDistributor.sendToServer(new PayRent(pos));
    }
}
