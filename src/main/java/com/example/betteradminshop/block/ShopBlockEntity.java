package com.example.betteradminshop.block;

import com.example.betteradminshop.registry.ModBlockEntities;
import com.simibubi.create.content.logistics.box.PackageItem;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import com.example.betteradminshop.data.GlobalRestockData;
import com.example.betteradminshop.data.MongoDriver;
import com.example.betteradminshop.data.MongoStore;
import com.example.betteradminshop.data.PurchaseDatabase;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ShopBlockEntity extends BlockEntity {

    public static final int SLOTS_PER_GROUP = 12;
    public static final int TOTAL_SLOTS = SLOTS_PER_GROUP * 2;

    private final ShopSlot[] slots = new ShopSlot[TOTAL_SLOTS];
    private final Deque<DeliveryEntry> deliveryQueue = new ArrayDeque<>();
    private final Map<UUID, ShopOrder> playerOrders = new HashMap<>();

    /** Tiendas cargadas del lado servidor (para republicar el estado en Mongo). */
    private static final java.util.Set<ShopBlockEntity> LOADED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /** Momento (ms) hasta el que esta tienda ya aplicó el restock global. */
    private long lastGlobalRestockMs = 0L;

    // Render positions for group 1 (elements 37-48)
    public static final float[][] GROUP1_POSITIONS = {
            {3f/16, 13.8f/16, 3.7f/16},
            {6f/16, 13.8f/16, 3.7f/16},
            {9f/16, 13.8f/16, 3.7f/16},
            {3f/16, 12.3f/16, 6.7f/16},
            {6f/16, 12.3f/16, 6.7f/16},
            {9f/16, 12.3f/16, 6.7f/16},
            {9f/16, 10.8f/16, 9.7f/16},
            {3f/16, 10.8f/16, 9.7f/16},
            {6f/16, 10.8f/16, 9.7f/16},
            {3f/16, 9.4f/16, 12.7f/16},
            {6f/16, 9.4f/16, 12.7f/16},
            {9f/16, 9.4f/16, 12.7f/16}
    };

    // Render positions for group 2 (elements 49-60)
    public static final float[][] GROUP2_POSITIONS = {
            {14f/16, 13.8f/16, 3.7f/16},
            {17f/16, 13.8f/16, 3.7f/16},
            {20f/16, 13.8f/16, 3.7f/16},
            {14f/16, 12.3f/16, 6.7f/16},
            {17f/16, 12.3f/16, 6.7f/16},
            {20f/16, 12.3f/16, 6.7f/16},
            {20f/16, 10.8f/16, 9.7f/16},
            {14f/16, 10.8f/16, 9.7f/16},
            {17f/16, 10.8f/16, 9.7f/16},
            {14f/16, 9.4f/16, 12.7f/16},
            {17f/16, 9.4f/16, 12.7f/16},
            {20f/16, 9.4f/16, 12.7f/16}
    };

    // Size of each render slot (in block units) for hit detection
    public static final float SLOT_HIT_SIZE = 2.5f / 16f;

    // Confirmar-compra panel bounds (model group "confirmarcompra")
    // Model coords: x=[24,30], y=[13,20.7], z=[0.9,7.4] (pixels/16)
    public static final float[] CONFIRMAR_MIN = {24f/16, 13f/16, 0.9f/16};
    public static final float[] CONFIRMAR_MAX = {30f/16, 20.7f/16, 7.4f/16};

    // Entrega (delivery pickup) zone bounds (model group "entrega")
    // Model coords: x=[24,30], y=[12,12.5], z=[8.4,14.2] (pixels/16)
    public static final float[] ENTREGA_MIN = {24f/16, 12f/16, 8.4f/16};
    public static final float[] ENTREGA_MAX = {30f/16, 12.5f/16, 14.2f/16};

    public ShopBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SHOP_BLOCK_ENTITY.get(), pos, state);
        for (int i = 0; i < TOTAL_SLOTS; i++) {
            slots[i] = new ShopSlot();
        }
    }

    public ShopSlot[] getSlots() {
        return slots;
    }

    public ShopSlot getSlot(int index) {
        if (index < 0 || index >= TOTAL_SLOTS) return null;
        return slots[index];
    }

    public boolean hasDelivery() {
        return !deliveryQueue.isEmpty();
    }

    public DeliveryEntry peekDelivery() {
        return deliveryQueue.peek();
    }

    public ShopOrder getOrCreateOrder(UUID playerId) {
        return playerOrders.computeIfAbsent(playerId, k -> new ShopOrder());
    }

    public void clearOrder(UUID playerId) {
        playerOrders.remove(playerId);
    }

    public boolean addToOrder(UUID playerId, int slotIndex) {
        if (slotIndex < 0 || slotIndex >= TOTAL_SLOTS) return false;
        ShopSlot slot = slots[slotIndex];
        long now = System.currentTimeMillis();
        if (slot.isEmpty() || slot.isOutOfStockFor(playerId, now)) return false;

        ShopOrder order = getOrCreateOrder(playerId);
        int currentInOrder = order.getQuantity(slotIndex);

        if (!slot.canPurchase(playerId, currentInOrder + 1, now)) return false;

        order.addItem(slotIndex);
        return true;
    }

    public String processPurchase(ServerPlayer player) {
        UUID playerId = player.getUUID();
        ShopOrder order = playerOrders.get(playerId);
        if (order == null || order.isEmpty()) return null;

        // Requisitos (lo que el jugador debe entregar) y recompensas (lo que
        // recibe). Cada mapa ya combina slots de venta y de compra.
        Map<ItemStack, Integer> required = order.computeRequired(slots);
        Map<ItemStack, Integer> rewards  = order.computeRewards(slots);

        for (Map.Entry<ItemStack, Integer> entry : required.entrySet()) {
            int found = countItemInInventory(player, entry.getKey());
            if (found < entry.getValue()) {
                return entry.getKey().getHoverName().getString();
            }
        }

        for (Map.Entry<ItemStack, Integer> entry : required.entrySet()) {
            removeItemFromInventory(player, entry.getKey(), entry.getValue());
        }

        // Empaquetar las recompensas (divididas por maxStackSize: el codec de
        // ItemStack en 1.20.5+ solo admite counts 1..99, un stack mayor rompe
        // el guardado NBT del block entity y la tienda queda vacía).
        List<ItemStack> deliveredItems = new ArrayList<>();
        for (Map.Entry<ItemStack, Integer> entry : rewards.entrySet()) {
            int remaining = entry.getValue();
            int stackSize = Math.max(1, Math.min(entry.getKey().getMaxStackSize(), 99));
            while (remaining > 0) {
                int count = Math.min(remaining, stackSize);
                deliveredItems.add(entry.getKey().copyWithCount(count));
                remaining -= count;
            }
        }

        // Descontar stock de cada slot para ESTE jugador (por jugador)
        long nowStock = System.currentTimeMillis();
        for (Map.Entry<Integer, Integer> entry : order.getItems().entrySet()) {
            ShopSlot slot = slots[entry.getKey()];
            if (!slot.isEmpty()) slot.consume(playerId, entry.getValue(), nowStock);
        }

        DeliveryEntry newEntry = new DeliveryEntry(deliveredItems, player.getUUID(),
                System.currentTimeMillis(), player.getName().getString());
        // Start protection immediately if this will be the first (and only) entry
        if (deliveryQueue.isEmpty()) {
            newEntry.startProtection();
        }
        deliveryQueue.addLast(newEntry);

        // ── Log a SQLite (autoritativa) + espejo a MongoDB (opcional) ───────
        String transactionId = java.util.UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        String worldKey = (level != null) ? level.dimension().location().toString() : "unknown";
        for (Map.Entry<Integer, Integer> entry : order.getItems().entrySet()) {
            int slotIndex = entry.getKey();
            ShopSlot slot = slots[slotIndex];
            if (slot.isEmpty()) continue;
            int bundles = entry.getValue();
            int totalUnits = bundles * slot.getSellAmount();
            ItemStack item = slot.getDisplayItem();
            String type = slot.isCompra() ? PurchaseDatabase.TYPE_COMPRA : PurchaseDatabase.TYPE_VENTA;
            String itemId = BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
            String itemName = item.getHoverName().getString();
            String priceSummary = buildSlotPriceSummary(slot, bundles);

            PurchaseDatabase.getInstance().logTransaction(
                    type, transactionId, player.getUUID().toString(), player.getName().getString(),
                    itemId, itemName, totalUnits, priceSummary, now, worldPosition, worldKey);

            if (MongoDriver.AVAILABLE) {
                MongoStore.getInstance().logTransaction(
                        type, transactionId, slotIndex,
                        player.getUUID().toString(), player.getName().getString(),
                        itemId, itemName, totalUnits,
                        buildPriceLines(slot, bundles), priceSummary, now, worldKey, worldPosition);
            }
        }

        clearOrder(playerId);
        setChanged();
        syncToClient();
        publishState(); // stock cambió → publica el estado a Mongo

        return null;
    }

    /** Desglose de precio/pago de un slot como líneas estructuradas (para Mongo). */
    private static List<MongoStore.PriceLine> buildPriceLines(ShopSlot slot, int bundles) {
        List<MongoStore.PriceLine> lines = new ArrayList<>();
        if (!slot.getPriceItem().isEmpty()) {
            lines.add(new MongoStore.PriceLine(
                    BuiltInRegistries.ITEM.getKey(slot.getPriceItem().getItem()).toString(),
                    slot.getPriceItem().getHoverName().getString(),
                    slot.getPriceAmount() * bundles));
        }
        if (slot.hasSecondPrice()) {
            lines.add(new MongoStore.PriceLine(
                    BuiltInRegistries.ITEM.getKey(slot.getPriceItem2().getItem()).toString(),
                    slot.getPriceItem2().getHoverName().getString(),
                    slot.getPriceAmount2() * bundles));
        }
        return lines;
    }

    /** Publica el estado de esta tienda a MongoDB (si está habilitado). */
    public void publishState() {
        if (level == null || level.isClientSide) return;
        if (!MongoDriver.AVAILABLE) return; // sin driver → no se toca MongoStore
        String world = level.dimension().location().toString();
        MongoStore.getInstance().publishShop(world, worldPosition, slots);
    }

    // ── Registro de tiendas cargadas (server) ──────────────────────────────────

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        if (level != null && !level.isClientSide) {
            LOADED.add(this);
            catchUpGlobalRestock();
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        LOADED.remove(this);
    }

    /** Publica en Mongo el estado de todas las tiendas cargadas. Devuelve cuántas. */
    public static int republishAllLoaded() {
        int n = 0;
        for (ShopBlockEntity be : LOADED) {
            be.publishState();
            n++;
        }
        return n;
    }

    public static int loadedShopCount() {
        return LOADED.size();
    }

    /** Resumen de precio/pago de un slot para la fila del registro. */
    private static String buildSlotPriceSummary(ShopSlot slot, int bundles) {
        StringBuilder sb = new StringBuilder();
        if (!slot.getPriceItem().isEmpty()) {
            sb.append(slot.getPriceAmount() * bundles).append("× ")
              .append(slot.getPriceItem().getHoverName().getString());
        }
        if (slot.hasSecondPrice()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(slot.getPriceAmount2() * bundles).append("× ")
              .append(slot.getPriceItem2().getHoverName().getString());
        }
        return sb.toString();
    }

    /**
     * Called when a player interacts with the entrega zone.
     * Enforces the 5-minute buyer-only protection window.
     *
     * @return null on success, or an error message key to show the player
     */
    public String tryPickupDelivery(ServerPlayer player) {
        if (deliveryQueue.isEmpty()) {
            return "no_deliveries";
        }

        DeliveryEntry delivery = deliveryQueue.peek();

        if (delivery.isProtectedFrom(player.getUUID())) {
            long secs = delivery.remainingProtectionSeconds();
            long mins = secs / 60;
            long remSecs = secs % 60;
            return "protected:" + mins + "m " + remSecs + "s";
        }

        deliveryQueue.poll();

        // Start the protection window for the next queued delivery
        DeliveryEntry nextHead = deliveryQueue.peek();
        if (nextHead != null) {
            nextHead.startProtection();
        }

        // Pack purchased items into a Create cardboard box
        List<ItemStack> items = delivery.getItems();
        ItemStack box = createCardboardBox(items);
        if (!player.getInventory().add(box)) {
            dropItemAt(box, Vec3.atCenterOf(worldPosition));
        }

        setChanged();
        syncToClient();
        return null;
    }

    private ItemStack createCardboardBox(List<ItemStack> items) {
        return PackageItem.containing(items);
    }

    private void dropItemAt(ItemStack stack, Vec3 pos) {
        if (level == null || stack.isEmpty()) return;
        ItemEntity entity = new ItemEntity(level, pos.x, pos.y, pos.z, stack.copy());
        entity.setDeltaMovement(0, 0.1, 0);
        entity.setPickUpDelay(10);
        level.addFreshEntity(entity);
    }

    private int countItemInInventory(ServerPlayer player, ItemStack target) {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (ItemStack.isSameItemSameComponents(stack, target)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private void removeItemFromInventory(ServerPlayer player, ItemStack target, int amount) {
        int remaining = amount;
        for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (ItemStack.isSameItemSameComponents(stack, target)) {
                int toRemove = Math.min(remaining, stack.getCount());
                stack.shrink(toRemove);
                if (stack.isEmpty()) {
                    player.getInventory().setItem(i, ItemStack.EMPTY);
                }
                remaining -= toRemove;
            }
        }
    }

    public int getClickedSlot(Vec3 eyePos, Vec3 lookDir, BlockState state) {
        // Guard against a stale render call after the block was broken
        if (!(state.getBlock() instanceof ShopBlock)) return -1;
        // Transform eye position to model space (relative to origin, NORTH-facing)
        double ex = eyePos.x - worldPosition.getX();
        double ey = eyePos.y - worldPosition.getY();
        double ez = eyePos.z - worldPosition.getZ();

        Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);

        // Rotate eye position and look direction into NORTH-facing model space
        double mex, mez, mdx, mdz;
        switch (facing) {
            case SOUTH -> { mex = 1.0 - ex; mez = 1.0 - ez; mdx = -lookDir.x; mdz = -lookDir.z; }
            case WEST  -> { mex = 1.0 - ez; mez = ex;        mdx = -lookDir.z; mdz = lookDir.x; }
            case EAST  -> { mex = ez;        mez = 1.0 - ex;  mdx = lookDir.z;  mdz = -lookDir.x; }
            default    -> { mex = ex;        mez = ez;        mdx = lookDir.x;  mdz = lookDir.z; }
        }
        double mdy = lookDir.y;

        // Check confirmarcompra panel (returns -2): cast ray to center Z plane
        float ccz = (CONFIRMAR_MIN[2] + CONFIRMAR_MAX[2]) / 2f;
        if (Math.abs(mdz) > 1e-6) {
            double t = (ccz - mez) / mdz;
            if (t > 0) {
                double ix = mex + mdx * t;
                double iy = ey + mdy * t;
                if (ix >= CONFIRMAR_MIN[0] && ix <= CONFIRMAR_MAX[0] &&
                        iy >= CONFIRMAR_MIN[1] && iy <= CONFIRMAR_MAX[1]) {
                    return -2;
                }
            }
        }

        // Check entrega zone (returns -3): cast ray to center Y plane (horizontal surface)
        float ecy = (ENTREGA_MIN[1] + ENTREGA_MAX[1]) / 2f;
        if (Math.abs(mdy) > 1e-6) {
            double t = (ecy - ey) / mdy;
            if (t > 0) {
                double ix = mex + mdx * t;
                double iz = mez + mdz * t;
                if (ix >= ENTREGA_MIN[0] && ix <= ENTREGA_MAX[0] &&
                        iz >= ENTREGA_MIN[2] && iz <= ENTREGA_MAX[2]) {
                    return -3;
                }
            }
        }

        double dirDot = mdx * mdx + mdy * mdy + mdz * mdz;
        if (dirDot < 1e-10) return -1;

        int bestSlot = -1;
        double bestDist = Double.MAX_VALUE;
        double maxDist = 3.5 / 16.0;

        for (int i = 0; i < SLOTS_PER_GROUP; i++) {
            double dist = rayToSlotDist(mex, ey, mez, mdx, mdy, mdz, dirDot, GROUP1_POSITIONS[i]);
            if (dist < bestDist) { bestDist = dist; bestSlot = i; }
        }

        for (int i = 0; i < SLOTS_PER_GROUP; i++) {
            double dist = rayToSlotDist(mex, ey, mez, mdx, mdy, mdz, dirDot, GROUP2_POSITIONS[i]);
            if (dist < bestDist) { bestDist = dist; bestSlot = SLOTS_PER_GROUP + i; }
        }

        if (bestSlot >= 0 && bestDist <= maxDist) {
            return bestSlot;
        }

        return -1;
    }

    private static double rayToSlotDist(double ex, double ey, double ez,
                                         double dx, double dy, double dz, double dirDot,
                                         float[] slotPos) {
        double sx = slotPos[0], sy = slotPos[1], sz = slotPos[2];
        double diffX = sx - ex, diffY = sy - ey, diffZ = sz - ez;
        double t = (diffX * dx + diffY * dy + diffZ * dz) / dirDot;
        if (t < 0) return Double.MAX_VALUE;
        double px = ex + dx * t - sx;
        double py = ey + dy * t - sy;
        double pz = ez + dz * t - sz;
        return Math.sqrt(px * px + py * py + pz * pz);
    }

    /**
     * Aplica la configuración completa de un slot en una sola operación
     * (proveniente del panel de administración).
     *
     * El stock actual solo se resetea cuando cambia el máximo — antes, cada
     * "Aplicar" reponía el stock silenciosamente.
     */
    public void applySlotConfig(int index, ShopSlot.Type type, ItemStack saleItem, ItemStack renderOverride,
                                int sellAmount, ItemStack priceItem, int priceAmount,
                                ItemStack priceItem2, int priceAmount2, int maxStock) {
        if (index < 0 || index >= TOTAL_SLOTS) return;
        ShopSlot slot = slots[index];
        slot.setType(type);
        slot.setDisplayItem(saleItem);
        slot.setRenderOverride(renderOverride);
        slot.setSellAmount(sellAmount);
        slot.setPriceItem(priceItem);
        slot.setPriceAmount(priceAmount);
        slot.setPriceItem2(priceItem2);
        slot.setPriceAmount2(priceAmount2);
        // Cambiar el límite reinicia el consumo por jugador; si no cambia, se
        // conserva el progreso de cada jugador.
        if (slot.getMaxStock() != maxStock) {
            slot.setMaxStock(maxStock);
        }
        setChanged();
        syncToClient();
        publishState();
    }

    public void restockSlot(int index) {
        if (index >= 0 && index < TOTAL_SLOTS) {
            slots[index].restock();
            setChanged();
            syncToClient();
            publishState();
        }
    }

    public void clearSlot(int index) {
        if (index >= 0 && index < TOTAL_SLOTS) {
            slots[index].clear();
            setChanged();
            syncToClient();
            publishState();
        }
    }

    /**
     * Intercambia dos slots por completo (ítem, precio, stock por jugador,
     * temporizadores). Sirve para reordenar productos en el estante.
     */
    public void swapSlots(int a, int b) {
        if (a < 0 || a >= TOTAL_SLOTS || b < 0 || b >= TOTAL_SLOTS || a == b) return;
        ShopSlot tmp = slots[a];
        slots[a] = slots[b];
        slots[b] = tmp;
        setChanged();
        syncToClient();
        publishState();
    }

    // ── Restock global ──────────────────────────────────────────────────────

    /** Reabastece todos los slots (para todos los jugadores) y marca el instante. */
    public void applyGlobalRestock(long timestamp) {
        for (ShopSlot slot : slots) {
            slot.restock();
        }
        lastGlobalRestockMs = timestamp;
        setChanged();
        syncToClient();
        publishState();
    }

    /**
     * Si hubo un restock global mientras esta tienda estaba descargada, lo
     * aplica ahora (al cargarse). Llamado desde {@link #clearRemoved()}.
     */
    private void catchUpGlobalRestock() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        long global = GlobalRestockData.get(serverLevel.getServer()).getTimestamp();
        if (global > lastGlobalRestockMs) {
            for (ShopSlot slot : slots) {
                slot.restock();
            }
            lastGlobalRestockMs = global;
            setChanged();
            syncToClient();
        }
    }

    /** Reabastece todas las tiendas cargadas. Devuelve cuántas. */
    public static int globalRestockAllLoaded(long timestamp) {
        int n = 0;
        for (ShopBlockEntity be : LOADED) {
            be.applyGlobalRestock(timestamp);
            n++;
        }
        return n;
    }

    // ===== NBT (1.20.5+ data components migration) =========================

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag slotList = new ListTag();
        for (ShopSlot slot : slots) {
            slotList.add(slot.save(registries));
        }
        tag.put("Slots", slotList);

        ListTag queueTag = new ListTag();
        for (DeliveryEntry entry : deliveryQueue) {
            queueTag.add(entry.save(registries));
        }
        tag.put("DeliveryQueue", queueTag);

        tag.putLong("LastGlobalRestock", lastGlobalRestockMs);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        // Carga defensiva: una entrada corrupta no debe descartar el resto de
        // la tienda (era una de las causas de "la tienda se resetea").
        if (tag.contains("Slots")) {
            ListTag slotList = tag.getList("Slots", Tag.TAG_COMPOUND);
            for (int i = 0; i < Math.min(slotList.size(), TOTAL_SLOTS); i++) {
                try {
                    slots[i].load(registries, slotList.getCompound(i));
                } catch (Exception e) {
                    com.example.betteradminshop.BetterAdminShop.LOGGER
                            .error("[BetterAdminShop] Slot {} corrupto en {}, se omite", i, worldPosition, e);
                    slots[i].clear();
                }
            }
        }

        deliveryQueue.clear();
        if (tag.contains("DeliveryQueue")) {
            ListTag queueTag = tag.getList("DeliveryQueue", Tag.TAG_COMPOUND);
            for (int i = 0; i < queueTag.size(); i++) {
                try {
                    deliveryQueue.addLast(DeliveryEntry.load(queueTag.getCompound(i), registries));
                } catch (Exception e) {
                    com.example.betteradminshop.BetterAdminShop.LOGGER
                            .error("[BetterAdminShop] Entrega corrupta en {}, se omite", worldPosition, e);
                }
            }
        }

        lastGlobalRestockMs = tag.getLong("LastGlobalRestock");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    public void syncToClient() {
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }
}
