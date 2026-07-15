package com.example.betteradminshop.data;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.nio.charset.StandardCharsets;

/**
 * One row in the purchase_records table.
 * Each item bought in a transaction produces one row; rows sharing the same
 * {@code transactionId} belong to the same player order.
 */
public record PurchaseRecord(
        long id,
        String transactionId,
        String playerUuid,
        String playerName,
        String itemId,           // e.g. "minecraft:cherry_planks"
        String itemDisplayName,  // e.g. "Cherry Planks"
        int    quantity,
        String priceSummary,     // e.g. "5× Diamond, 2× Emerald"
        long   purchaseTimestampUtc,  // System.currentTimeMillis()
        String transactionType   // "venta" (la tienda vende) o "compra" (la tienda compra)
) {

    public boolean isCompra() {
        return "compra".equals(transactionType);
    }

    // ── Network codec (manual to handle >6 fields) ───────────────────────────

    public static final StreamCodec<RegistryFriendlyByteBuf, PurchaseRecord> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PurchaseRecord decode(RegistryFriendlyByteBuf buf) {
                    return new PurchaseRecord(
                            buf.readLong(),
                            readStr(buf),
                            readStr(buf),
                            readStr(buf),
                            readStr(buf),
                            readStr(buf),
                            buf.readVarInt(),
                            readStr(buf),
                            buf.readLong(),
                            readStr(buf)
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PurchaseRecord r) {
                    buf.writeLong(r.id());
                    writeStr(buf, r.transactionId());
                    writeStr(buf, r.playerUuid());
                    writeStr(buf, r.playerName());
                    writeStr(buf, r.itemId());
                    writeStr(buf, r.itemDisplayName());
                    buf.writeVarInt(r.quantity());
                    writeStr(buf, r.priceSummary());
                    buf.writeLong(r.purchaseTimestampUtc());
                    writeStr(buf, r.transactionType() == null ? "venta" : r.transactionType());
                }

                private static String readStr(RegistryFriendlyByteBuf buf) {
                    int len = buf.readUnsignedShort();
                    byte[] bytes = new byte[len];
                    buf.readBytes(bytes);
                    return new String(bytes, StandardCharsets.UTF_8);
                }

                private static void writeStr(RegistryFriendlyByteBuf buf, String s) {
                    byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                    buf.writeShort(bytes.length);
                    buf.writeBytes(bytes);
                }
            };
}
