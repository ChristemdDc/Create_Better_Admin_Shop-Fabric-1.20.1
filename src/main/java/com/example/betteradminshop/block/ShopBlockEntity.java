package com.example.betteradminshop.block;

import com.example.betteradminshop.BetterAdminShop;
import com.example.betteradminshop.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class ShopBlockEntity extends BlockEntity {

    public static final int SLOTS_PER_GROUP = 12;
    public static final int TOTAL_SLOTS = SLOTS_PER_GROUP * 2;

    private final ShopSlot[] slots = new ShopSlot[TOTAL_SLOTS];
    private BlockPos depotPos = null;
    private final Map<UUID, ShopOrder> playerOrders = new HashMap<>();

    // Render positions for group 1 (elements 37-48)
    // X/Z = center of each shelf element; Y = shelf top + 0.175 (half item visual height at scale 0.35)
    public static final float[][] GROUP1_POSITIONS = {
            {3f/16, 13.8f/16, 2.9f/16},
            {6f/16, 13.8f/16, 2.9f/16},
            {9f/16, 13.8f/16, 2.9f/16},
            {3f/16, 12.3f/16, 5.9f/16},
            {6f/16, 12.3f/16, 5.9f/16},
            {9f/16, 12.3f/16, 5.9f/16},
            {9f/16, 10.8f/16, 8.9f/16},
            {3f/16, 10.8f/16, 9f/16},
            {6f/16, 10.8f/16, 9f/16},
            {3f/16, 9.4f/16, 12f/16},
            {6f/16, 9.4f/16, 12f/16},
            {9f/16, 9.4f/16, 12f/16}
    };

    // Render positions for group 2 (elements 49-60)
    public static final float[][] GROUP2_POSITIONS = {
            {14f/16, 13.8f/16, 2.9f/16},
            {17f/16, 13.8f/16, 2.9f/16},
            {20f/16, 13.8f/16, 2.9f/16},
            {14f/16, 12.3f/16, 5.9f/16},
            {17f/16, 12.3f/16, 5.9f/16},
            {20f/16, 12.3f/16, 5.9f/16},
            {20f/16, 10.8f/16, 8.9f/16},
            {14f/16, 10.8f/16, 9f/16},
            {17f/16, 10.8f/16, 9f/16},
            {14f/16, 9.4f/16, 12f/16},
            {17f/16, 9.4f/16, 12f/16},
            {20f/16, 9.4f/16, 12f/16}
    };

    // Size of each render slot (in block units) for hit detection
    public static final float SLOT_HIT_SIZE = 2.5f / 16f;

    // Tendedero bounding box (elements 70-84), approximate
    public static final float[] TENDEDERO_MIN = {24f/16, 13f/16, 0f/16};
    public static final float[] TENDEDERO_MAX = {32f/16, 27f/16, 6f/16};

    public ShopBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SHOP_BLOCK_ENTITY, pos, state);
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

    public BlockPos getDepotPos() {
        return depotPos;
    }

    public void setDepotPos(BlockPos pos) {
        this.depotPos = pos;
        setChanged();
        syncToClient();
    }

    public boolean hasDepot() {
        return depotPos != null;
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
        if (slot.isEmpty() || slot.isOutOfStock()) return false;

        ShopOrder order = getOrCreateOrder(playerId);
        int currentInOrder = order.getQuantity(slotIndex);

        if (!slot.canPurchase(currentInOrder + 1)) return false;

        order.addItem(slotIndex);
        return true;
    }

    public String processPurchase(ServerPlayer player) {
        UUID playerId = player.getUUID();
        ShopOrder order = playerOrders.get(playerId);
        if (order == null || order.isEmpty()) return null;

        Map<ItemStack, Integer> totalPrice = order.calculateTotalPrice(slots);

        for (Map.Entry<ItemStack, Integer> entry : totalPrice.entrySet()) {
            ItemStack required = entry.getKey();
            int needed = entry.getValue();
            int found = countItemInInventory(player, required);
            if (found < needed) {
                return required.getHoverName().getString();
            }
        }

        for (Map.Entry<ItemStack, Integer> entry : totalPrice.entrySet()) {
            removeItemFromInventory(player, entry.getKey(), entry.getValue());
        }

        List<ItemStack> purchasedItems = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : order.getItems().entrySet()) {
            int slotIdx = entry.getKey();
            int qty = entry.getValue();
            ShopSlot slot = slots[slotIdx];
            if (!slot.isEmpty()) {
                ItemStack item = slot.getDisplayItem().copy();
                int remaining = qty;
                while (remaining > 0) {
                    int batch = Math.min(remaining, item.getMaxStackSize());
                    ItemStack stack = item.copy();
                    stack.setCount(batch);
                    purchasedItems.add(stack);
                    remaining -= batch;
                }
                slot.reduceStock(qty);
            }
        }

        depositToDepot(purchasedItems);

        clearOrder(playerId);
        setChanged();
        syncToClient();

        return null;
    }

    private void depositToDepot(List<ItemStack> items) {
        if (level == null || depotPos == null) {
            dropItemsAtBlock(items);
            return;
        }

        BlockEntity depotBE = level.getBlockEntity(depotPos);
        if (depotBE instanceof Container container) {
            for (ItemStack stack : items) {
                ItemStack remaining = insertIntoContainer(container, stack);
                if (!remaining.isEmpty()) {
                    dropItemAt(remaining, Vec3.atCenterOf(depotPos));
                }
            }
        } else {
            dropItemsAtDepot(items);
        }
    }

    private ItemStack insertIntoContainer(Container container, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (remaining.isEmpty()) break;
            ItemStack existing = container.getItem(i);
            if (existing.isEmpty()) {
                container.setItem(i, remaining.copy());
                remaining = ItemStack.EMPTY;
            } else if (ItemStack.isSameItemSameTags(existing, remaining)) {
                int canFit = existing.getMaxStackSize() - existing.getCount();
                if (canFit > 0) {
                    int toAdd = Math.min(canFit, remaining.getCount());
                    existing.grow(toAdd);
                    remaining.shrink(toAdd);
                }
            }
        }
        return remaining;
    }

    private void dropItemsAtBlock(List<ItemStack> items) {
        if (level == null) return;
        Vec3 pos = Vec3.atCenterOf(worldPosition);
        for (ItemStack item : items) {
            dropItemAt(item, pos);
        }
    }

    private void dropItemsAtDepot(List<ItemStack> items) {
        if (level == null || depotPos == null) {
            dropItemsAtBlock(items);
            return;
        }
        Vec3 pos = Vec3.atCenterOf(depotPos).add(0, 0.5, 0);
        for (ItemStack item : items) {
            dropItemAt(item, pos);
        }
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
            if (ItemStack.isSameItemSameTags(stack, target)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private void removeItemFromInventory(ServerPlayer player, ItemStack target, int amount) {
        int remaining = amount;
        for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (ItemStack.isSameItemSameTags(stack, target)) {
                int toRemove = Math.min(remaining, stack.getCount());
                stack.shrink(toRemove);
                if (stack.isEmpty()) {
                    player.getInventory().setItem(i, ItemStack.EMPTY);
                }
                remaining -= toRemove;
            }
        }
    }

    public int getClickedSlot(Vec3 hitPos, BlockState state) {
        double hx = hitPos.x - worldPosition.getX();
        double hy = hitPos.y - worldPosition.getY();
        double hz = hitPos.z - worldPosition.getZ();

        // Rotate hit coordinates into NORTH-facing model space
        Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        double mx, mz;
        switch (facing) {
            case SOUTH -> { mx = 1.0 - hx; mz = 1.0 - hz; }
            case WEST -> { mx = hz; mz = 1.0 - hx; }
            case EAST -> { mx = 1.0 - hz; mz = hx; }
            default -> { mx = hx; mz = hz; }
        }

        // Check tendedero area (register/checkout)
        if (mx >= TENDEDERO_MIN[0] && mx <= TENDEDERO_MAX[0] &&
                hy >= TENDEDERO_MIN[1] && hy <= TENDEDERO_MAX[1] &&
                mz >= TENDEDERO_MIN[2] && mz <= TENDEDERO_MAX[2]) {
            return -2;
        }

        // Find nearest shelf slot using 2D distance (model X and world Y)
        int bestSlot = -1;
        double bestDist = Double.MAX_VALUE;
        double maxDist = 4.0 / 16.0;

        for (int i = 0; i < SLOTS_PER_GROUP; i++) {
            float[] pos = GROUP1_POSITIONS[i];
            double dx = mx - pos[0];
            double dy = hy - pos[1];
            double dist = dx * dx + dy * dy;
            if (dist < bestDist) {
                bestDist = dist;
                bestSlot = i;
            }
        }

        for (int i = 0; i < SLOTS_PER_GROUP; i++) {
            float[] pos = GROUP2_POSITIONS[i];
            double dx = mx - pos[0];
            double dy = hy - pos[1];
            double dist = dx * dx + dy * dy;
            if (dist < bestDist) {
                bestDist = dist;
                bestSlot = SLOTS_PER_GROUP + i;
            }
        }

        if (bestSlot >= 0 && bestDist <= maxDist * maxDist) {
            return bestSlot;
        }

        return -1;
    }

    public void setSlotItem(int index, ItemStack item) {
        if (index >= 0 && index < TOTAL_SLOTS) {
            slots[index].setDisplayItem(item);
            setChanged();
            syncToClient();
        }
    }

    public void setSlotPrice(int index, ItemStack priceItem, int priceAmount) {
        if (index >= 0 && index < TOTAL_SLOTS) {
            slots[index].setPriceItem(priceItem);
            slots[index].setPriceAmount(priceAmount);
            setChanged();
            syncToClient();
        }
    }

    public void setSlotMaxStock(int index, int maxStock) {
        if (index >= 0 && index < TOTAL_SLOTS) {
            slots[index].setMaxStock(maxStock);
            if (maxStock != ShopSlot.INFINITE_STOCK) {
                slots[index].setCurrentStock(maxStock);
            }
            setChanged();
            syncToClient();
        }
    }

    public void restockSlot(int index) {
        if (index >= 0 && index < TOTAL_SLOTS) {
            slots[index].restock();
            setChanged();
            syncToClient();
        }
    }

    public void clearSlot(int index) {
        if (index >= 0 && index < TOTAL_SLOTS) {
            slots[index].clear();
            setChanged();
            syncToClient();
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ListTag slotList = new ListTag();
        for (ShopSlot slot : slots) {
            slotList.add(slot.save());
        }
        tag.put("Slots", slotList);

        if (depotPos != null) {
            tag.putInt("DepotX", depotPos.getX());
            tag.putInt("DepotY", depotPos.getY());
            tag.putInt("DepotZ", depotPos.getZ());
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);

        if (tag.contains("Slots")) {
            ListTag slotList = tag.getList("Slots", Tag.TAG_COMPOUND);
            for (int i = 0; i < Math.min(slotList.size(), TOTAL_SLOTS); i++) {
                slots[i].load(slotList.getCompound(i));
            }
        }

        if (tag.contains("DepotX")) {
            depotPos = new BlockPos(tag.getInt("DepotX"), tag.getInt("DepotY"), tag.getInt("DepotZ"));
        } else {
            depotPos = null;
        }
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag);
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
