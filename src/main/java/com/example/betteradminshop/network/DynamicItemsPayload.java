package com.example.betteradminshop.network;

import com.example.betteradminshop.BetterAdminShop;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → Client: la lista actual de ítems dinámicos auxiliares que el
 * selector de la tienda debe incluir además de los del registro.
 */
public record DynamicItemsPayload(List<ItemStack> items) implements CustomPacketPayload {

    public static final Type<DynamicItemsPayload> TYPE =
            new Type<>(BetterAdminShop.id("dynamic_items"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DynamicItemsPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public DynamicItemsPayload decode(RegistryFriendlyByteBuf buf) {
                    int n = buf.readVarInt();
                    List<ItemStack> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        list.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
                    }
                    return new DynamicItemsPayload(list);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, DynamicItemsPayload p) {
                    buf.writeVarInt(p.items().size());
                    for (ItemStack s : p.items()) {
                        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, s);
                    }
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
