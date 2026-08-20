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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tienda de JUGADOR (multibloque 2×2 con voladizo trasero).
 *
 * Diferencias con la tienda de administrador:
 *  - Tiene DUEÑO (quien la coloca) + empleados invitados; solo ellos gestionan.
 *  - Solo VENDE, con un único ítem de precio por slot.
 *  - El stock NO es infinito: sale del {@link StockInventory} (16 slots con
 *    mejoras de capacidad), que se alimenta por el chute de import.
 *  - Lo recaudado va al buffer de EXPORT (se extrae por el chute de export).
 *  - Estantes mejorables: 2→3→4 slots por lado (propiedades del blockstate
 *    left_tier / right_tier controlan qué subgrupo del modelo se renderiza).
 *  - Renta: la tienda solo opera si la cuota (configurada por administración)
 *    está al día.
 */
public class PlayerShopBlockEntity extends BlockEntity {

    public static final int SLOTS_PER_SHELF = 4;   // máximo con mejoras
    public static final int TOTAL_SLOTS = SLOTS_PER_SHELF * 2; // 0-3 izq, 4-7 der

    // ── Posiciones de render/raycast (unidades de bloque, modelo NORTH) ─────
    // Centros de las bandejas (ya aplicada su rotación de -22.5°), calculados
    // del Blockbench: cada tier tiene su PROPIA disposición de bandejas.
    public static final float[][][] LEFT_TRAYS = {
            { {23.75f/16, 15.53f/16, 11.49f/16}, {23.75f/16, 12.47f/16, 4.09f/16} },                                            // 2 slots
            { {27.45f/16, 12.66f/16, 4.56f/16}, {23.75f/16, 15.15f/16, 10.56f/16}, {20.05f/16, 12.66f/16, 4.56f/16} },          // 3 slots
            { {27.45f/16, 12.66f/16, 4.56f/16}, {27.45f/16, 15.15f/16, 10.56f/16},
              {20.05f/16, 12.66f/16, 4.56f/16}, {20.05f/16, 15.15f/16, 10.56f/16} }                                              // 4 slots
    };
    public static final float[][][] RIGHT_TRAYS = {
            { {8.25f/16, 15.53f/16, 11.49f/16}, {8.25f/16, 12.47f/16, 4.09f/16} },
            { {11.95f/16, 12.66f/16, 4.56f/16}, {8.25f/16, 15.15f/16, 10.56f/16}, {4.55f/16, 12.66f/16, 4.56f/16} },
            { {11.95f/16, 12.66f/16, 4.56f/16}, {11.95f/16, 14.96f/16, 10.10f/16},
              {4.55f/16, 14.96f/16, 10.10f/16}, {4.55f/16, 12.66f/16, 4.56f/16} }
    };

    /**
     * Altura a la que se muestran el ítem y su recuadro sobre la bandeja.
     * Compartida por el render y por el raycast para que lo que ves sea
     * exactamente lo que apuntas.
     */
    public static final float TRAY_Y_OFFSET = 3.2f / 16f;

    /**
     * zonaDePago: AABB del overlay de confirmar compra. Arranca en el asiento
     * (Y 20) y se eleva con el volumen de un peluche sentado, para que el
     * recuadro se vea como una figura y no como una lámina.
     */
    public static final float[] PAGO_MIN = {17.5f/16, 20f/16, 20f/16};
    public static final float[] PAGO_MAX = {23.5f/16, 32f/16, 25.3f/16};

    /** Cara frontal de "entregaDeCardboardConCompra": la cardboard gira delante. */
    public static final float[] ENTREGA_MIN = {9.55f/16, 21f/16, 20.55f/16};
    public static final float[] ENTREGA_MAX = {13.65f/16, 25f/16, 21.05f/16};

    // ── Estado ────────────────────────────────────────────────────────────────

    private UUID ownerId = null;
    private String ownerName = "";
    /** Empleados con acceso al menú (uuid → nombre para mostrar). */
    private final Map<UUID, String> managers = new LinkedHashMap<>();

    private final PlayerShopSlot[] slots = new PlayerShopSlot[TOTAL_SLOTS];
    private final StockInventory stock = new StockInventory();

    /** Recaudación pendiente de extracción por el chute de export. */
    private final List<ItemStack> exportBuffer = new ArrayList<>();

    /** Entregas pendientes en el depot (cardboards) — misma protección que admin. */
    private final Deque<DeliveryEntry> deliveryQueue = new ArrayDeque<>();
    private final Map<UUID, ShopOrder> playerOrders = new LinkedHashMap<>();

    /** Renta pagada hasta este instante (ms). 0 = nunca pagada. */
    private long rentPaidUntilMs = 0L;

    /**
     * Cachés para el CLIENTE de la configuración global (el SavedData vive solo
     * en el servidor): renta y costos de mejoras. El servidor las escribe en
     * cada sync para que el menú muestre precios reales.
     */
    private boolean rentFreeCache = true;
    private ItemStack rentItemCache = ItemStack.EMPTY;
    private int rentAmountCache = 0;
    private long rentPeriodCache = 0L;
    private final Map<String, com.example.betteradminshop.data.PlayerShopSettings.UpgradeCost>
            upgradeCostsCache = new LinkedHashMap<>();

    public ItemStack rentItemInfo() { return rentItemCache; }
    public int rentAmountInfo() { return rentAmountCache; }
    public long rentPeriodInfo() { return rentPeriodCache; }

    public com.example.betteradminshop.data.PlayerShopSettings.UpgradeCost upgradeCostInfo(String key) {
        if (level instanceof ServerLevel serverLevel && serverLevel.getServer() != null) {
            return com.example.betteradminshop.data.PlayerShopSettings
                    .get(serverLevel.getServer()).getUpgradeCost(key);
        }
        return upgradeCostsCache.get(key);
    }

    /** Orientación real (inmutable tras colocar); misma defensa que admin. */
    private Direction lockedFacing = null;

    /**
     * Copia de los tiers de estante. Los tiers viven en el BLOCKSTATE, que se
     * pierde al romper el bloque; guardarlos aquí permite restaurarlos cuando
     * la tienda se vuelve a colocar desde su ítem.
     */
    private int storedLeftTier = 2;
    private int storedRightTier = 2;

    public int getStoredLeftTier() { return storedLeftTier; }
    public int getStoredRightTier() { return storedRightTier; }

    /** Tiendas de jugador cargadas (server): para re-sincronizar la config global. */
    private static final java.util.Set<PlayerShopBlockEntity> LOADED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public PlayerShopBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PLAYER_SHOP_BLOCK_ENTITY.get(), pos, state);
        for (int i = 0; i < TOTAL_SLOTS; i++) slots[i] = new PlayerShopSlot();
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        if (level != null && !level.isClientSide) {
            LOADED.add(this);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        LOADED.remove(this);
    }

    /**
     * Re-sincroniza todas las tiendas cargadas al cliente. Llamar cuando cambia
     * la configuración GLOBAL (renta/costos de mejoras): las cachés que el menú
     * y el HUD muestran viven en el update tag del BE, y sin esto quedarían
     * mostrando valores viejos hasta la próxima mutación de cada tienda.
     */
    public static int resyncAllLoaded() {
        int n = 0;
        for (PlayerShopBlockEntity be : LOADED) {
            be.syncToClient();
            n++;
        }
        return n;
    }

    // ── Dueño / empleados ────────────────────────────────────────────────────

    public void setOwner(UUID id, String name) {
        this.ownerId = id;
        this.ownerName = name == null ? "" : name;
        setChanged();
    }

    public UUID getOwnerId() { return ownerId; }
    public String getOwnerName() { return ownerName; }
    public Map<UUID, String> getManagers() { return managers; }

    public boolean isOwner(Player player) {
        return ownerId != null && ownerId.equals(player.getUUID());
    }

    /** ¿Puede gestionar la tienda? Dueño, empleado invitado, o admin (nivel 2+). */
    public boolean canManage(Player player) {
        return isOwner(player) || managers.containsKey(player.getUUID())
                || player.hasPermissions(2);
    }

    public void addManager(UUID id, String name) {
        managers.put(id, name == null ? "" : name);
        setChanged();
        syncToClient();
    }

    public void removeManager(UUID id) {
        managers.remove(id);
        setChanged();
        syncToClient();
    }

    // ── Slots / stock / export ───────────────────────────────────────────────

    public PlayerShopSlot[] getSlots() { return slots; }

    public PlayerShopSlot getSlot(int index) {
        return (index < 0 || index >= TOTAL_SLOTS) ? null : slots[index];
    }

    public StockInventory getStock() { return stock; }

    /** Unidades del ítem en venta disponibles en el stock (0 si slot vacío). */
    public int stockFor(int slotIndex) {
        PlayerShopSlot slot = getSlot(slotIndex);
        if (slot == null || slot.isEmpty()) return 0;
        return stock.countOf(slot.getSaleItem());
    }

    public List<ItemStack> getExportBuffer() { return exportBuffer; }

    /** Encola ítems para salir por el chute de export (recaudación, purgas). */
    public void enqueueExport(ItemStack stack) {
        if (stack.isEmpty()) return;
        // dividir ≤ maxStackSize para no romper el codec de ItemStack al guardar
        int remaining = stack.getCount();
        int max = Math.max(1, Math.min(stack.getMaxStackSize(), 99));
        while (remaining > 0) {
            int n = Math.min(remaining, max);
            exportBuffer.add(stack.copyWithCount(n));
            remaining -= n;
        }
        setChanged();
    }

    // ── Renta ────────────────────────────────────────────────────────────────

    public long getRentPaidUntilMs() { return rentPaidUntilMs; }

    public void setRentPaidUntilMs(long ms) {
        this.rentPaidUntilMs = ms;
        setChanged();
        syncToClient();
    }

    /**
     * ¿La tienda está operativa? Si la administración no configuró ninguna
     * cuota de renta, las tiendas operan gratis; con cuota configurada, solo
     * si la renta está al día.
     */
    public boolean isOperational(long now) {
        if (level instanceof ServerLevel serverLevel && serverLevel.getServer() != null) {
            if (!com.example.betteradminshop.data.PlayerShopSettings
                    .get(serverLevel.getServer()).isRentConfigured()) {
                return true;
            }
            return rentPaidUntilMs > now;
        }
        return rentFreeCache || rentPaidUntilMs > now;
    }

    // ── Tiers de estante (blockstate del bloque origen) ──────────────────────

    public int getLeftTier() {
        BlockState st = getBlockState();
        return st.hasProperty(PlayerShopBlock.LEFT_TIER) ? st.getValue(PlayerShopBlock.LEFT_TIER) : 2;
    }

    public int getRightTier() {
        BlockState st = getBlockState();
        return st.hasProperty(PlayerShopBlock.RIGHT_TIER) ? st.getValue(PlayerShopBlock.RIGHT_TIER) : 2;
    }

    /** Sube el tier de un estante (izquierdo=true) si no está al máximo. */
    public boolean upgradeShelf(boolean left) {
        if (level == null || level.isClientSide) return false;
        BlockState st = getBlockState();
        var prop = left ? PlayerShopBlock.LEFT_TIER : PlayerShopBlock.RIGHT_TIER;
        int tier = st.getValue(prop);
        if (tier >= 4) return false;
        level.setBlock(worldPosition, st.setValue(prop, tier + 1), 3);
        setChanged();
        return true;
    }

    // ── Órdenes / compra (Fase 2) ────────────────────────────────────────────

    public ShopOrder getOrCreateOrder(UUID playerId) {
        return playerOrders.computeIfAbsent(playerId, k -> new ShopOrder());
    }

    public void clearOrder(UUID playerId) {
        playerOrders.remove(playerId);
    }

    public Deque<DeliveryEntry> getDeliveryQueue() { return deliveryQueue; }

    public boolean hasDelivery() { return !deliveryQueue.isEmpty(); }

    public DeliveryEntry peekDelivery() { return deliveryQueue.peek(); }

    /**
     * Añade una unidad de compra del slot a la orden del jugador, validando
     * contra el stock REAL de la tienda (no infinito).
     */
    public boolean addToOrder(UUID playerId, int slotIndex) {
        PlayerShopSlot slot = getSlot(slotIndex);
        if (slot == null || slot.isEmpty()) return false;
        // Legacy: slots sin precio (de antes de exigirlo) no son comprables
        if (slot.getPriceItem().isEmpty()) return false;
        ShopOrder order = getOrCreateOrder(playerId);
        int wanted = (order.getQuantity(slotIndex) + 1) * slot.getSellAmount();
        if (stockFor(slotIndex) < wanted) return false;
        order.addItem(slotIndex);
        return true;
    }

    /**
     * Confirma la orden del jugador.
     *
     * @return null si OK; "empty" sin orden; "stock:<ítem>" si el stock ya no
     *         alcanza; "pay:<ítem>" si al comprador le falta el pago.
     */
    public String processPurchase(ServerPlayer player) {
        UUID playerId = player.getUUID();
        ShopOrder order = playerOrders.get(playerId);
        if (order == null || order.isEmpty()) return "empty";

        // Revalidar stock en el momento de confirmar
        for (Map.Entry<Integer, Integer> e : order.getItems().entrySet()) {
            PlayerShopSlot slot = getSlot(e.getKey());
            if (slot == null || slot.isEmpty()) return "empty";
            if (stockFor(e.getKey()) < e.getValue() * slot.getSellAmount()) {
                return "stock:" + slot.getSaleItem().getHoverName().getString();
            }
        }

        // Pago requerido (merge por tipo de ítem)
        Map<ItemStack, Integer> required = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> e : order.getItems().entrySet()) {
            PlayerShopSlot slot = slots[e.getKey()];
            addMerged(required, slot.getPriceItem(), slot.getPriceAmount() * e.getValue());
            if (slot.hasSecondPrice()) {
                addMerged(required, slot.getPriceItem2(), slot.getPriceAmount2() * e.getValue());
            }
        }
        for (Map.Entry<ItemStack, Integer> e : required.entrySet()) {
            if (countItemInInventory(player, e.getKey()) < e.getValue()) {
                return "pay:" + e.getKey().getHoverName().getString();
            }
        }
        for (Map.Entry<ItemStack, Integer> e : required.entrySet()) {
            removeItemFromInventory(player, e.getKey(), e.getValue());
        }

        // Retirar del stock y armar la entrega (stacks ≤ maxStackSize)
        List<ItemStack> delivered = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : order.getItems().entrySet()) {
            PlayerShopSlot slot = slots[e.getKey()];
            int units = e.getValue() * slot.getSellAmount();
            stock.remove(slot.getSaleItem(), units);
            int remaining = units;
            int max = Math.max(1, Math.min(slot.getSaleItem().getMaxStackSize(), 99));
            while (remaining > 0) {
                int n = Math.min(remaining, max);
                delivered.add(slot.getSaleItem().copyWithCount(n));
                remaining -= n;
            }
        }

        DeliveryEntry entry = new DeliveryEntry(delivered, playerId,
                System.currentTimeMillis(), player.getName().getString());
        if (deliveryQueue.isEmpty()) entry.startProtection();
        deliveryQueue.addLast(entry);

        // La recaudación sale TAL CUAL por el chute de export (sin cardboard)
        for (Map.Entry<ItemStack, Integer> e : required.entrySet()) {
            enqueueExportUnits(e.getKey(), e.getValue());
        }

        clearOrder(playerId);
        setChanged();
        syncToClient();
        return null;
    }

    /**
     * Recoge la entrega al frente de la cola (misma protección de 5 min por
     * comprador que la tienda de administrador).
     *
     * @return null OK; "no_deliveries"; "protected:<m>m <s>s".
     */
    public String tryPickupDelivery(ServerPlayer player) {
        if (deliveryQueue.isEmpty()) return "no_deliveries";
        DeliveryEntry delivery = deliveryQueue.peek();
        if (delivery.isProtectedFrom(player.getUUID())) {
            long secs = delivery.remainingProtectionSeconds();
            return "protected:" + (secs / 60) + "m " + (secs % 60) + "s";
        }
        deliveryQueue.poll();
        DeliveryEntry next = deliveryQueue.peek();
        if (next != null) next.startProtection();

        // Paquete PROPIO: sin el límite de 9 stacks de las cajas de Create
        ItemStack box = com.example.betteradminshop.item.ShopPackageItem.containing(
                delivery.getItems(), level.registryAccess());
        if (!player.getInventory().add(box)) {
            ItemEntity ent = new ItemEntity(level, worldPosition.getX() + 0.5,
                    worldPosition.getY() + 1.0, worldPosition.getZ() + 0.5, box);
            ent.setPickUpDelay(10);
            level.addFreshEntity(ent);
        }
        setChanged();
        syncToClient();
        return null;
    }

    private static void addMerged(Map<ItemStack, Integer> map, ItemStack item, int amount) {
        if (item.isEmpty() || amount <= 0) return;
        for (Map.Entry<ItemStack, Integer> e : map.entrySet()) {
            if (ItemStack.isSameItemSameComponents(e.getKey(), item)) {
                e.setValue(e.getValue() + amount);
                return;
            }
        }
        map.put(item.copyWithCount(1), amount);
    }

    private static int countItemInInventory(ServerPlayer player, ItemStack target) {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (ItemStack.isSameItemSameComponents(stack, target)) count += stack.getCount();
        }
        return count;
    }

    private static void removeItemFromInventory(ServerPlayer player, ItemStack target, int amount) {
        int remaining = amount;
        for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (ItemStack.isSameItemSameComponents(stack, target)) {
                int toRemove = Math.min(remaining, stack.getCount());
                stack.shrink(toRemove);
                if (stack.isEmpty()) player.getInventory().setItem(i, ItemStack.EMPTY);
                remaining -= toRemove;
            }
        }
    }

    /** Encola unidades para el export dividiendo en stacks válidos. */
    public void enqueueExportUnits(ItemStack proto, int units) {
        if (proto.isEmpty() || units <= 0) return;
        int remaining = units;
        int max = Math.max(1, Math.min(proto.getMaxStackSize(), 99));
        while (remaining > 0) {
            int n = Math.min(remaining, max);
            exportBuffer.add(proto.copyWithCount(n));
            remaining -= n;
        }
        setChanged();
    }

    // ── Raycast (bandejas por tier + zona de pago + entrega) ─────────────────

    /**
     * Qué apunta el jugador: 0..7 = slot de bandeja (0-3 izq, 4-7 der),
     * -2 = zona de pago (confirmar), -3 = entrega (cardboard), -1 = nada.
     * Misma transformación de espacio modelo que la tienda de administrador.
     */
    public int getClickedSlot(Vec3 eyePos, Vec3 lookDir, BlockState state) {
        if (!(state.getBlock() instanceof PlayerShopBlock)) return -1;
        double ex = eyePos.x - worldPosition.getX();
        double ey = eyePos.y - worldPosition.getY();
        double ez = eyePos.z - worldPosition.getZ();

        Direction facing = state.getValue(PlayerShopBlock.FACING);
        double mex, mez, mdx, mdz;
        switch (facing) {
            case SOUTH -> { mex = 1.0 - ex; mez = 1.0 - ez; mdx = -lookDir.x; mdz = -lookDir.z; }
            case WEST  -> { mex = 1.0 - ez; mez = ex;        mdx = -lookDir.z; mdz = lookDir.x; }
            case EAST  -> { mex = ez;        mez = 1.0 - ex;  mdx = lookDir.z;  mdz = -lookDir.x; }
            default    -> { mex = ex;        mez = ez;        mdx = lookDir.x;  mdz = lookDir.z; }
        }
        double mdy = lookDir.y;

        // ── Candidatos, cada uno con su distancia a lo largo del rayo (t) ──
        // Se elige el MÁS CERCANO: la zona de pago es un volumen alto detrás de
        // las bandejas, así que sin comparar distancias se "comería" los clics
        // de las bandejas que quedan delante.

        // Zona de pago: intersección con la caja completa (volumen del peluche)
        double payT = rayBoxDistance(mex, ey, mez, mdx, mdy, mdz, PAGO_MIN, PAGO_MAX);

        // Entrega: plano frontal del cubo, con margen para la cardboard flotante
        double deliveryT = -1;
        float frontZ = ENTREGA_MIN[2];
        if (Math.abs(mdz) > 1e-6) {
            double t = (frontZ - mez) / mdz;
            if (t > 0) {
                double ix = mex + mdx * t;
                double iy = ey + mdy * t;
                if (ix >= ENTREGA_MIN[0] - 0.08 && ix <= ENTREGA_MAX[0] + 0.08
                        && iy >= ENTREGA_MIN[1] - 0.05 && iy <= ENTREGA_MAX[1] + 0.10) {
                    deliveryT = t;
                }
            }
        }

        double dirDot = mdx * mdx + mdy * mdy + mdz * mdz;
        if (dirDot < 1e-10) return -1;

        int bestSlot = -1;
        double bestDist = Double.MAX_VALUE;
        double bestSlotT = Double.MAX_VALUE;
        double maxDist = 3.4 / 16.0;

        float[][] left = LEFT_TRAYS[getLeftTier() - 2];
        for (int i = 0; i < left.length; i++) {
            double[] r = rayToTray(mex, ey, mez, mdx, mdy, mdz, dirDot, left[i]);
            if (r[0] < bestDist) { bestDist = r[0]; bestSlotT = r[1]; bestSlot = i; }
        }
        float[][] right = RIGHT_TRAYS[getRightTier() - 2];
        for (int i = 0; i < right.length; i++) {
            double[] r = rayToTray(mex, ey, mez, mdx, mdy, mdz, dirDot, right[i]);
            if (r[0] < bestDist) { bestDist = r[0]; bestSlotT = r[1]; bestSlot = SLOTS_PER_SHELF + i; }
        }
        boolean slotValid = bestSlot >= 0 && bestDist <= maxDist;

        // Ganador = el candidato válido con menor t
        double bestT = Double.MAX_VALUE;
        int result = -1;
        if (slotValid && bestSlotT < bestT) { bestT = bestSlotT; result = bestSlot; }
        if (payT >= 0 && payT < bestT) { bestT = payT; result = -2; }
        if (deliveryT >= 0 && deliveryT < bestT) { result = -3; }
        return result;
    }

    /**
     * Distancia (t) a la que el rayo entra en la caja, o -1 si no la toca.
     * Método de las "slabs": funciona mires de frente, de lado o desde arriba.
     */
    private static double rayBoxDistance(double ox, double oy, double oz,
                                         double dx, double dy, double dz,
                                         float[] min, float[] max) {
        double tmin = 0.0;
        double tmax = Double.MAX_VALUE;
        double[] o = {ox, oy, oz};
        double[] d = {dx, dy, dz};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-8) {
                if (o[a] < min[a] || o[a] > max[a]) return -1;
            } else {
                double t1 = (min[a] - o[a]) / d[a];
                double t2 = (max[a] - o[a]) / d[a];
                if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
                tmin = Math.max(tmin, t1);
                tmax = Math.min(tmax, t2);
                if (tmax < tmin) return -1;
            }
        }
        return tmin;
    }

    /** @return {distancia perpendicular al centro de la bandeja, t sobre el rayo}. */
    private static double[] rayToTray(double ex, double ey, double ez,
                                      double dx, double dy, double dz, double dirDot,
                                      float[] tray) {
        // El punto de mira es donde se DIBUJA el ítem (bandeja + offset)
        double diffX = tray[0] - ex, diffY = (tray[1] + TRAY_Y_OFFSET) - ey, diffZ = tray[2] - ez;
        double t = (diffX * dx + diffY * dy + diffZ * dz) / dirDot;
        if (t < 0) return new double[]{Double.MAX_VALUE, Double.MAX_VALUE};
        double px = ex + dx * t - tray[0];
        double py = ey + dy * t - (tray[1] + TRAY_Y_OFFSET);
        double pz = ez + dz * t - tray[2];
        return new double[]{Math.sqrt(px * px + py * py + pz * pz), t};
    }

    // ── Operaciones del menú (Fase 4, validadas en servidor) ─────────────────

    /**
     * Configura un slot de venta. El ítem debe existir en el stock de la tienda
     * (o estar vacío el slot). count del saleItem = unidades por compra.
     */
    public String applySlotConfig(int slotIndex, ItemStack saleItem, ItemStack priceItem,
                                  int priceAmount, ItemStack priceItem2, int priceAmount2,
                                  int rotation) {
        PlayerShopSlot slot = getSlot(slotIndex);
        if (slot == null) return "invalid";
        if (!saleItem.isEmpty() && stock.countOf(saleItem.copyWithCount(1)) <= 0) {
            return "not_in_stock";
        }
        // Nada gratis: un slot a la venta SIEMPRE necesita precio válido.
        if (!saleItem.isEmpty() && (priceItem.isEmpty() || priceAmount < 1)) {
            return "no_price";
        }
        // El segundo precio es opcional, pero si se pone debe ser un ítem
        // DISTINTO del primero (si no, sería el mismo cobro partido en dos).
        if (!priceItem2.isEmpty() && ItemStack.isSameItemSameComponents(priceItem, priceItem2)) {
            return "dup_price";
        }
        slot.setSaleItem(saleItem);
        slot.setPriceItem(priceItem);
        slot.setPriceAmount(priceAmount);
        slot.setPriceItem2(priceItem2);
        slot.setPriceAmount2(priceAmount2);
        slot.setRotation(rotation);
        setChanged();
        syncToClient();
        return null;
    }

    public void clearSlotConfig(int slotIndex) {
        PlayerShopSlot slot = getSlot(slotIndex);
        if (slot == null) return;
        slot.clear();
        setChanged();
        syncToClient();
    }

    /** Gira el ítem del slot un cuarto de vuelta (horizontal). */
    public void rotateSlot(int slotIndex) {
        PlayerShopSlot slot = getSlot(slotIndex);
        if (slot == null || slot.isEmpty()) return;
        slot.rotate();
        setChanged();
        syncToClient();
    }

    /** ¿El slot lógico está desbloqueado según el tier de su estante? */
    public boolean isSlotUnlocked(int index) {
        if (index < 0 || index >= TOTAL_SLOTS) return false;
        return index < SLOTS_PER_SHELF ? index < getLeftTier()
                : (index - SLOTS_PER_SHELF) < getRightTier();
    }

    /** Intercambia dos slots de venta (para reorganizar arrastrando en el menú). */
    public void swapShopSlots(int a, int b) {
        if (a == b || !isSlotUnlocked(a) || !isSlotUnlocked(b)) return;
        PlayerShopSlot tmp = slots[a];
        slots[a] = slots[b];
        slots[b] = tmp;
        setChanged();
        syncToClient();
    }

    /**
     * Compra de mejora. kind: 0 = estante izq, 1 = estante der, 2 = capacidad
     * de stock, 3 = vacío. Cobra el precio configurado al jugador.
     *
     * @return null OK; "max" ya al máximo; "pay:<ítem xN>" falta pago.
     */
    public String purchaseUpgrade(ServerPlayer player, int kind) {
        if (!(level instanceof ServerLevel serverLevel)) return "invalid";
        var settings = com.example.betteradminshop.data.PlayerShopSettings.get(serverLevel.getServer());

        String key;
        switch (kind) {
            case 0 -> {
                int tier = getLeftTier();
                if (tier >= 4) return "max";
                key = tier == 2 ? com.example.betteradminshop.data.PlayerShopSettings.UP_SHELF3
                                : com.example.betteradminshop.data.PlayerShopSettings.UP_SHELF4;
            }
            case 1 -> {
                int tier = getRightTier();
                if (tier >= 4) return "max";
                key = tier == 2 ? com.example.betteradminshop.data.PlayerShopSettings.UP_SHELF3
                                : com.example.betteradminshop.data.PlayerShopSettings.UP_SHELF4;
            }
            case 2 -> {
                if (stock.getCapacityTier() >= StockInventory.MAX_TIER) return "max";
                key = stock.getCapacityTier() == 0
                        ? com.example.betteradminshop.data.PlayerShopSettings.UP_STOCK1
                        : com.example.betteradminshop.data.PlayerShopSettings.UP_STOCK2;
            }
            case 3 -> {
                if (stock.hasVoidUpgrade()) return "max";
                key = com.example.betteradminshop.data.PlayerShopSettings.UP_VOID;
            }
            case 4 -> {
                // Toggle de la mejora de vacío (ya comprada): gratis
                if (!stock.hasVoidUpgrade()) return "invalid";
                stock.setVoidEnabled(!stock.isVoidEnabled());
                setChanged();
                syncToClient();
                return null;
            }
            default -> { return "invalid"; }
        }

        var cost = settings.getUpgradeCost(key);
        if (cost.amount() > 0 && !cost.item().isEmpty()) {
            if (countItemInInventory(player, cost.item()) < cost.amount()) {
                return "pay:" + cost.amount() + "× " + cost.item().getHoverName().getString();
            }
            removeItemFromInventory(player, cost.item(), cost.amount());
        }

        switch (kind) {
            case 0 -> upgradeShelf(true);
            case 1 -> upgradeShelf(false);
            case 2 -> stock.setCapacityTier(stock.getCapacityTier() + 1);
            case 3 -> stock.setVoidUpgrade(true);
        }
        setChanged();
        syncToClient();
        return null;
    }

    /** ¿Hay un ducto/tolva conectado al chute de export (cara inferior del origen)? */
    public boolean hasExportConduit() {
        if (level == null) return false;
        BlockPos below = worldPosition.below();
        if (level.getBlockState(below).getBlock() instanceof net.minecraft.world.level.block.HopperBlock) {
            return true;
        }
        return level instanceof ServerLevel serverLevel
                && serverLevel.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                        below, Direction.UP) != null;
    }

    /**
     * Purga TODO el stock del ítem del slot seleccionado: sale por el export en
     * cardboards llenas al máximo (9 stacks por caja; tantas como haga falta).
     *
     * @return null OK; "no_conduit" sin ducto; "empty" slot vacío.
     */
    public String purgeStock(int stockSlot) {
        if (stockSlot < 0 || stockSlot >= StockInventory.SLOTS) return "empty";
        ItemStack proto = stock.getItem(stockSlot);
        if (proto.isEmpty()) return "empty";
        if (!hasExportConduit()) return "no_conduit";

        int units = stock.remove(proto, stock.countOf(proto));
        if (units <= 0) return "empty";

        int stackSize = Math.max(1, Math.min(proto.getMaxStackSize(), 99));
        List<ItemStack> pending = new ArrayList<>();
        int remaining = units;
        while (remaining > 0) {
            int n = Math.min(remaining, stackSize);
            pending.add(proto.copyWithCount(n));
            remaining -= n;
            // 9 stacks por cardboard (capacidad de un paquete de Create)
            if (pending.size() == 9 || remaining <= 0) {
                // La purga SÍ usa cajas de Create: se emiten tantas como haga falta
                exportBuffer.add(PackageItem.containing(pending));
                pending = new ArrayList<>();
            }
        }
        setChanged();
        syncToClient();
        return null;
    }

    /**
     * Paga un periodo de renta con ítems del inventario del jugador.
     *
     * @return null OK; "free" sin renta configurada; "pay:<ítem xN>" falta pago.
     */
    public String payRent(ServerPlayer player) {
        if (!(level instanceof ServerLevel serverLevel)) return "invalid";
        var settings = com.example.betteradminshop.data.PlayerShopSettings.get(serverLevel.getServer());
        if (!settings.isRentConfigured()) return "free";

        if (countItemInInventory(player, settings.getRentItem()) < settings.getRentAmount()) {
            return "pay:" + settings.getRentAmount() + "× "
                    + settings.getRentItem().getHoverName().getString();
        }
        removeItemFromInventory(player, settings.getRentItem(), settings.getRentAmount());
        // La renta va al FONDO COMÚN (solo administración puede retirarlo)
        com.example.betteradminshop.data.RentFund.get(serverLevel.getServer())
                .deposit(settings.getRentItem(), settings.getRentAmount());
        long base = Math.max(System.currentTimeMillis(), rentPaidUntilMs);
        rentPaidUntilMs = base + settings.getRentPeriodMs();
        setChanged();
        syncToClient();
        return null;
    }

    // ── Import / export por chutes, funnels y tolvas (Fase 3) ────────────────

    /** Handler del chute de IMPORT: solo inserción, alimenta el stock. */
    private final IItemHandler importHandler = new IItemHandler() {
        @Override public int getSlots() { return StockInventory.SLOTS; }

        @Override public ItemStack getStackInSlot(int i) {
            ItemStack p = stock.getItem(i);
            if (p.isEmpty()) return ItemStack.EMPTY;
            return p.copyWithCount(Math.max(1, Math.min(stock.getCount(i), p.getMaxStackSize())));
        }

        @Override public ItemStack insertItem(int slotIgnored, ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return stack;
            int remainder = simulate
                    ? stock.simulateInsert(stack, stack.getCount())
                    : stock.insert(stack, stack.getCount());
            if (!simulate && remainder != stack.getCount()) {
                setChanged();
                syncToClient();
            }
            return remainder <= 0 ? ItemStack.EMPTY : stack.copyWithCount(remainder);
        }

        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY; // por import no se extrae
        }

        @Override public int getSlotLimit(int slot) { return stock.capacityPerSlot(); }

        @Override public boolean isItemValid(int slot, ItemStack stack) { return true; }
    };

    /** Handler del chute de EXPORT: solo extracción de la recaudación/purgas. */
    private final IItemHandler exportHandler = new IItemHandler() {
        private static final int VIRTUAL_SLOTS = 27;

        @Override public int getSlots() { return VIRTUAL_SLOTS; }

        @Override public ItemStack getStackInSlot(int i) {
            return i < exportBuffer.size() ? exportBuffer.get(i) : ItemStack.EMPTY;
        }

        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return stack; // por export no se inserta
        }

        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot >= exportBuffer.size() || amount <= 0) return ItemStack.EMPTY;
            ItemStack s = exportBuffer.get(slot);
            int n = Math.min(amount, s.getCount());
            ItemStack out = s.copyWithCount(n);
            if (!simulate) {
                s.shrink(n);
                if (s.isEmpty()) exportBuffer.remove(slot);
                setChanged();
            }
            return out;
        }

        @Override public int getSlotLimit(int slot) { return 64; }

        @Override public boolean isItemValid(int slot, ItemStack stack) { return false; }
    };

    public IItemHandler getImportHandler() { return importHandler; }
    public IItemHandler getExportHandler() { return exportHandler; }

    /**
     * Construye el ítem de la tienda llevándose TODO su contenido: productos,
     * precios, stock, mejoras (estantes, capacidad, vacío), socios, renta y
     * entregas pendientes. Al colocarlo, vanilla aplica BLOCK_ENTITY_DATA antes
     * de {@code setPlacedBy}, así que la tienda revive tal cual estaba.
     */
    public ItemStack createShopItem(HolderLookup.Provider registries) {
        ItemStack stack = new ItemStack(
                com.example.betteradminshop.registry.ModBlocks.PLAYER_SHOP_ITEM.get());
        CompoundTag data = saveWithoutMetadata(registries);
        stack.set(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA,
                net.minecraft.world.item.component.CustomData.of(data));

        // Resumen legible para saber, mirando el ítem, qué lleva dentro
        List<net.minecraft.network.chat.Component> lore = new ArrayList<>();
        int products = 0;
        for (PlayerShopSlot slot : slots) if (!slot.isEmpty()) products++;
        int stockUnits = 0;
        for (int i = 0; i < StockInventory.SLOTS; i++) stockUnits += stock.getCount(i);

        lore.add(net.minecraft.network.chat.Component.literal(
                "§7Tienda de §f" + (ownerName.isEmpty() ? "?" : ownerName))
                .withStyle(st -> st.withItalic(false)));
        lore.add(net.minecraft.network.chat.Component.literal(
                "§7Estantes: §f" + getLeftTier() + " §7/ §f" + getRightTier())
                .withStyle(st -> st.withItalic(false)));
        lore.add(net.minecraft.network.chat.Component.literal(
                "§7Productos: §f" + products + "  §7Stock: §f" + stockUnits + " ítems")
                .withStyle(st -> st.withItalic(false)));
        lore.add(net.minecraft.network.chat.Component.literal(
                "§7Capacidad: §f" + StockInventory.TIER_STACKS[stock.getCapacityTier()]
                + " stacks/slot" + (stock.hasVoidUpgrade() ? " §7· §fvacío" : ""))
                .withStyle(st -> st.withItalic(false)));
        if (!managers.isEmpty()) {
            lore.add(net.minecraft.network.chat.Component.literal(
                    "§7Socios: §f" + managers.size()).withStyle(st -> st.withItalic(false)));
        }
        stack.set(net.minecraft.core.component.DataComponents.LORE,
                new net.minecraft.world.item.component.ItemLore(lore));
        return stack;
    }

    // ── Bloqueo de orientación (misma defensa que la tienda admin) ───────────

    public void setLockedFacing(Direction facing) {
        this.lockedFacing = facing;
        setChanged();
    }

    public boolean coversPosition(BlockPos p) {
        Direction facing = lockedFacing;
        if (facing == null) {
            if (level == null) return false;
            BlockState st = level.getBlockState(worldPosition);
            if (!(st.getBlock() instanceof PlayerShopBlock)) return false;
            facing = st.getValue(PlayerShopBlock.FACING);
        }
        for (ShopPart part : ShopPart.values()) {
            if (worldPosition.offset(part.getOffsetFromOrigin(facing)).equals(p)) return true;
        }
        return false;
    }

    private boolean coveredByAnotherShop(BlockPos p) {
        if (level == null) return false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (level.getBlockEntity(p.offset(dx, dy, dz)) instanceof PlayerShopBlockEntity other
                            && other != this && other.coversPosition(p)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Fuerza FACING/PART correctos en los 4 bloques (revierte ediciones externas). */
    public void validateStructure() {
        if (!(level instanceof ServerLevel)) return;
        BlockState originState = level.getBlockState(worldPosition);
        if (!(originState.getBlock() instanceof PlayerShopBlock)) return;

        if (lockedFacing == null) {
            lockedFacing = originState.getValue(PlayerShopBlock.FACING);
            setChanged();
        }
        Direction facing = lockedFacing;

        if (originState.getValue(PlayerShopBlock.FACING) != facing
                || originState.getValue(PlayerShopBlock.PART) != ShopPart.ORIGIN) {
            level.setBlock(worldPosition, originState
                    .setValue(PlayerShopBlock.FACING, facing)
                    .setValue(PlayerShopBlock.PART, ShopPart.ORIGIN), 3);
        }

        for (ShopPart part : ShopPart.values()) {
            if (part == ShopPart.ORIGIN) continue;
            BlockPos partPos = worldPosition.offset(part.getOffsetFromOrigin(facing));
            BlockState st = level.getBlockState(partPos);
            if (!(st.getBlock() instanceof PlayerShopBlock)) continue;
            if (coveredByAnotherShop(partPos)) continue;
            if (st.getValue(PlayerShopBlock.FACING) != facing
                    || st.getValue(PlayerShopBlock.PART) != part) {
                level.setBlock(partPos, st
                        .setValue(PlayerShopBlock.FACING, facing)
                        .setValue(PlayerShopBlock.PART, part), 3);
            }
        }
    }

    // ── NBT ───────────────────────────────────────────────────────────────────

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        if (ownerId != null) {
            tag.putUUID("Owner", ownerId);
            tag.putString("OwnerName", ownerName);
        }
        ListTag managerList = new ListTag();
        for (var e : managers.entrySet()) {
            CompoundTag m = new CompoundTag();
            m.putUUID("UUID", e.getKey());
            m.putString("Name", e.getValue());
            managerList.add(m);
        }
        tag.put("Managers", managerList);

        ListTag slotList = new ListTag();
        for (PlayerShopSlot slot : slots) slotList.add(slot.save(registries));
        tag.put("Slots", slotList);

        tag.put("Stock", stock.save(registries));

        // Export buffer: prototipo + count (los counts ya vienen divididos ≤99)
        ListTag exportList = new ListTag();
        for (ItemStack s : exportBuffer) {
            if (!s.isEmpty()) exportList.add(s.save(registries));
        }
        tag.put("Export", exportList);

        ListTag queueTag = new ListTag();
        for (DeliveryEntry entry : deliveryQueue) queueTag.add(entry.save(registries));
        tag.put("DeliveryQueue", queueTag);

        tag.putLong("RentPaidUntil", rentPaidUntilMs);
        // Tiers actuales del blockstate (para sobrevivir a romper/recolocar)
        tag.putInt("LeftTier", getLeftTier());
        tag.putInt("RightTier", getRightTier());
        // Cachés de config global para el cliente (HUD/render/menú)
        if (level instanceof ServerLevel serverLevel && serverLevel.getServer() != null) {
            var settings = com.example.betteradminshop.data.PlayerShopSettings.get(serverLevel.getServer());
            tag.putBoolean("RentFree", !settings.isRentConfigured());
            if (settings.isRentConfigured()) {
                tag.put("RentInfoItem", settings.getRentItem().save(registries));
                tag.putInt("RentInfoAmount", settings.getRentAmount());
                tag.putLong("RentInfoPeriod", settings.getRentPeriodMs());
            }
            CompoundTag costs = new CompoundTag();
            for (String key : com.example.betteradminshop.data.PlayerShopSettings.UPGRADE_KEYS) {
                var cost = settings.getUpgradeCost(key);
                CompoundTag c = new CompoundTag();
                if (!cost.item().isEmpty()) c.put("Item", cost.item().save(registries));
                c.putInt("Amount", cost.amount());
                costs.put(key, c);
            }
            tag.put("UpgradeCosts", costs);
        } else {
            tag.putBoolean("RentFree", rentFreeCache);
        }
        if (lockedFacing != null) {
            tag.putString("LockedFacing", lockedFacing.getSerializedName());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        ownerId = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        ownerName = tag.getString("OwnerName");

        managers.clear();
        ListTag managerList = tag.getList("Managers", Tag.TAG_COMPOUND);
        for (int i = 0; i < managerList.size(); i++) {
            CompoundTag m = managerList.getCompound(i);
            if (m.hasUUID("UUID")) managers.put(m.getUUID("UUID"), m.getString("Name"));
        }

        // Carga defensiva (una entrada corrupta no tira el resto)
        if (tag.contains("Slots")) {
            ListTag slotList = tag.getList("Slots", Tag.TAG_COMPOUND);
            for (int i = 0; i < Math.min(slotList.size(), TOTAL_SLOTS); i++) {
                try {
                    slots[i].load(registries, slotList.getCompound(i));
                } catch (Exception e) {
                    com.example.betteradminshop.BetterAdminShop.LOGGER
                            .error("[BetterAdminShop] Slot {} corrupto en tienda de jugador {}", i, worldPosition, e);
                    slots[i].clear();
                }
            }
        }

        if (tag.contains("Stock")) {
            try {
                stock.load(registries, tag.getCompound("Stock"));
            } catch (Exception e) {
                com.example.betteradminshop.BetterAdminShop.LOGGER
                        .error("[BetterAdminShop] Stock corrupto en tienda de jugador {}", worldPosition, e);
            }
        }

        exportBuffer.clear();
        ListTag exportList = tag.getList("Export", Tag.TAG_COMPOUND);
        for (int i = 0; i < exportList.size(); i++) {
            ItemStack s = ItemStack.parseOptional(registries, exportList.getCompound(i));
            if (!s.isEmpty()) exportBuffer.add(s);
        }

        deliveryQueue.clear();
        if (tag.contains("DeliveryQueue")) {
            ListTag queueTag = tag.getList("DeliveryQueue", Tag.TAG_COMPOUND);
            for (int i = 0; i < queueTag.size(); i++) {
                try {
                    deliveryQueue.addLast(DeliveryEntry.load(queueTag.getCompound(i), registries));
                } catch (Exception e) {
                    com.example.betteradminshop.BetterAdminShop.LOGGER
                            .error("[BetterAdminShop] Entrega corrupta en tienda de jugador {}", worldPosition, e);
                }
            }
        }

        rentPaidUntilMs = tag.getLong("RentPaidUntil");
        storedLeftTier = tag.contains("LeftTier") ? Math.max(2, Math.min(4, tag.getInt("LeftTier"))) : 2;
        storedRightTier = tag.contains("RightTier") ? Math.max(2, Math.min(4, tag.getInt("RightTier"))) : 2;
        rentFreeCache = !tag.contains("RentFree") || tag.getBoolean("RentFree");
        rentItemCache = tag.contains("RentInfoItem")
                ? ItemStack.parseOptional(registries, tag.getCompound("RentInfoItem")) : ItemStack.EMPTY;
        rentAmountCache = tag.getInt("RentInfoAmount");
        rentPeriodCache = tag.getLong("RentInfoPeriod");
        upgradeCostsCache.clear();
        if (tag.contains("UpgradeCosts")) {
            CompoundTag costs = tag.getCompound("UpgradeCosts");
            for (String key : com.example.betteradminshop.data.PlayerShopSettings.UPGRADE_KEYS) {
                if (costs.contains(key)) {
                    CompoundTag c = costs.getCompound(key);
                    ItemStack item = ItemStack.parseOptional(registries, c.getCompound("Item"));
                    upgradeCostsCache.put(key,
                            new com.example.betteradminshop.data.PlayerShopSettings.UpgradeCost(
                                    item, c.getInt("Amount")));
                }
            }
        }
        lockedFacing = tag.contains("LockedFacing")
                ? Direction.byName(tag.getString("LockedFacing")) : null;
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
