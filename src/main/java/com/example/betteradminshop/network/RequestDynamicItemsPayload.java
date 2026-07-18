package com.example.betteradminshop.network;

import com.example.betteradminshop.BetterAdminShop;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client → Server: el cliente pide la lista actual de ítems dinámicos (por
 * ejemplo al abrir la pantalla de administración).
 */
public record RequestDynamicItemsPayload() implements CustomPacketPayload {

    public static final RequestDynamicItemsPayload INSTANCE = new RequestDynamicItemsPayload();

    public static final Type<RequestDynamicItemsPayload> TYPE =
            new Type<>(BetterAdminShop.id("request_dynamic_items"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestDynamicItemsPayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void sendToServer() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(INSTANCE);
    }
}
