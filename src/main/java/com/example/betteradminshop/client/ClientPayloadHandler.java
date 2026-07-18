package com.example.betteradminshop.client;

import com.example.betteradminshop.network.DynamicItemsPayload;
import com.example.betteradminshop.network.RecordsDataPayload;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client-only payload handlers.
 *
 * <p>Kept in a separate class (never referenced by name from common code) so
 * that a dedicated server never loads client-only classes like {@code Screen}.
 * {@link com.example.betteradminshop.network.ModNetworking} registers these
 * through a deferring lambda, so this class is only classloaded when a packet
 * is actually handled on the client.
 */
public final class ClientPayloadHandler {

    private ClientPayloadHandler() {}

    /** Runs client-side — opens or refreshes the AdminRecordsScreen. */
    public static void handleRecordsData(RecordsDataPayload msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            if (mc.screen instanceof AdminRecordsScreen existing) {
                existing.updateData(msg);
            } else {
                mc.setScreen(new AdminRecordsScreen(msg));
            }
        });
    }

    /** Runs client-side — actualiza la caché de ítems dinámicos y refresca el selector. */
    public static void handleDynamicItems(DynamicItemsPayload msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ClientDynamicItems.set(msg.items());
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.screen instanceof ShopAdminScreen screen) {
                screen.refreshItemList();
            }
        });
    }
}
