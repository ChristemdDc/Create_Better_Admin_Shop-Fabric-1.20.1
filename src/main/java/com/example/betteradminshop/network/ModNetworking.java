package com.example.betteradminshop.network;

import com.example.betteradminshop.BetterAdminShop;
import com.example.betteradminshop.block.ShopBlockEntity;
import com.example.betteradminshop.block.ShopSlot;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

public class ModNetworking {

    public static final ResourceLocation SET_SLOT_ITEM = BetterAdminShop.id("set_slot_item");
    public static final ResourceLocation SET_SLOT_PRICE = BetterAdminShop.id("set_slot_price");
    public static final ResourceLocation SET_SLOT_STOCK = BetterAdminShop.id("set_slot_stock");
    public static final ResourceLocation RESTOCK_SLOT = BetterAdminShop.id("restock_slot");
    public static final ResourceLocation CLEAR_SLOT = BetterAdminShop.id("clear_slot");
    public static final ResourceLocation LINK_DEPOT = BetterAdminShop.id("link_depot");

    public static void registerServerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(SET_SLOT_ITEM, (server, player, handler, buf, sender) -> {
            BlockPos pos = buf.readBlockPos();
            int slotIndex = buf.readVarInt();
            ItemStack item = buf.readItem();
            server.execute(() -> {
                if (!player.hasPermissions(4)) return;
                ShopBlockEntity shop = getShop(player, pos);
                if (shop != null) shop.setSlotItem(slotIndex, item);
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(SET_SLOT_PRICE, (server, player, handler, buf, sender) -> {
            BlockPos pos = buf.readBlockPos();
            int slotIndex = buf.readVarInt();
            ItemStack priceItem = buf.readItem();
            int priceAmount = buf.readVarInt();
            server.execute(() -> {
                if (!player.hasPermissions(4)) return;
                ShopBlockEntity shop = getShop(player, pos);
                if (shop != null) shop.setSlotPrice(slotIndex, priceItem, priceAmount);
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(SET_SLOT_STOCK, (server, player, handler, buf, sender) -> {
            BlockPos pos = buf.readBlockPos();
            int slotIndex = buf.readVarInt();
            int maxStock = buf.readVarInt();
            server.execute(() -> {
                if (!player.hasPermissions(4)) return;
                ShopBlockEntity shop = getShop(player, pos);
                if (shop != null) shop.setSlotMaxStock(slotIndex, maxStock);
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(RESTOCK_SLOT, (server, player, handler, buf, sender) -> {
            BlockPos pos = buf.readBlockPos();
            int slotIndex = buf.readVarInt();
            server.execute(() -> {
                if (!player.hasPermissions(4)) return;
                ShopBlockEntity shop = getShop(player, pos);
                if (shop != null) shop.restockSlot(slotIndex);
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(CLEAR_SLOT, (server, player, handler, buf, sender) -> {
            BlockPos pos = buf.readBlockPos();
            int slotIndex = buf.readVarInt();
            server.execute(() -> {
                if (!player.hasPermissions(4)) return;
                ShopBlockEntity shop = getShop(player, pos);
                if (shop != null) shop.clearSlot(slotIndex);
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(LINK_DEPOT, (server, player, handler, buf, sender) -> {
            BlockPos shopPos = buf.readBlockPos();
            BlockPos depotPos = buf.readBlockPos();
            server.execute(() -> {
                if (!player.hasPermissions(4)) return;
                ShopBlockEntity shop = getShop(player, shopPos);
                if (shop != null) {
                    shop.setDepotPos(depotPos);
                    player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal("§aDepot vinculado en " +
                                    depotPos.getX() + ", " + depotPos.getY() + ", " + depotPos.getZ()), true);
                }
            });
        });
    }

    private static ShopBlockEntity getShop(ServerPlayer player, BlockPos pos) {
        if (player.level().isLoaded(pos)) {
            BlockEntity be = player.level().getBlockEntity(pos);
            if (be instanceof ShopBlockEntity shop) return shop;
        }
        return null;
    }

    public static void sendSetSlotItem(BlockPos pos, int slotIndex, ItemStack item) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeVarInt(slotIndex);
        buf.writeItem(item);
        ClientPlayNetworking.send(SET_SLOT_ITEM, buf);
    }

    public static void sendSetSlotPrice(BlockPos pos, int slotIndex, ItemStack priceItem, int priceAmount) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeVarInt(slotIndex);
        buf.writeItem(priceItem);
        buf.writeVarInt(priceAmount);
        ClientPlayNetworking.send(SET_SLOT_PRICE, buf);
    }

    public static void sendSetSlotStock(BlockPos pos, int slotIndex, int maxStock) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeVarInt(slotIndex);
        buf.writeVarInt(maxStock);
        ClientPlayNetworking.send(SET_SLOT_STOCK, buf);
    }

    public static void sendRestockSlot(BlockPos pos, int slotIndex) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeVarInt(slotIndex);
        ClientPlayNetworking.send(RESTOCK_SLOT, buf);
    }

    public static void sendClearSlot(BlockPos pos, int slotIndex) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeVarInt(slotIndex);
        ClientPlayNetworking.send(CLEAR_SLOT, buf);
    }

    public static void sendLinkDepot(BlockPos shopPos, BlockPos depotPos) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(shopPos);
        buf.writeBlockPos(depotPos);
        ClientPlayNetworking.send(LINK_DEPOT, buf);
    }
}
