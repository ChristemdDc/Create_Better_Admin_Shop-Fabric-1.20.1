package com.example.betteradminshop.network;

import com.example.betteradminshop.BetterAdminShop;
import com.example.betteradminshop.data.PurchaseRecord;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Server → Client: carries a page of purchase records plus pagination metadata.
 * Also used as the initial payload when the player runs /tiendas records.
 */
public record RecordsDataPayload(
        List<PurchaseRecord> records,
        int    totalCount,      // total con los filtros actuales (para paginar)
        int    ventaCount,      // total de ventas (con filtro de jugador)
        int    compraCount,     // total de compras (con filtro de jugador)
        int    page,
        String sortColumn,
        boolean ascending,
        String  playerFilter,
        String  typeFilter      // "venta", "compra" o "" (todas)
) implements CustomPacketPayload {

    public static final int PAGE_SIZE = 10;

    public static final Type<RecordsDataPayload> TYPE =
            new Type<>(BetterAdminShop.id("records_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RecordsDataPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public RecordsDataPayload decode(RegistryFriendlyByteBuf buf) {
                    int size = buf.readVarInt();
                    List<PurchaseRecord> recs = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) recs.add(PurchaseRecord.STREAM_CODEC.decode(buf));
                    int total     = buf.readVarInt();
                    int ventas    = buf.readVarInt();
                    int compras   = buf.readVarInt();
                    int page      = buf.readVarInt();
                    String sort   = readStr(buf);
                    boolean asc   = buf.readBoolean();
                    String filter = readStr(buf);
                    String type   = readStr(buf);
                    return new RecordsDataPayload(recs, total, ventas, compras, page, sort, asc, filter, type);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, RecordsDataPayload p) {
                    buf.writeVarInt(p.records().size());
                    for (PurchaseRecord r : p.records()) PurchaseRecord.STREAM_CODEC.encode(buf, r);
                    buf.writeVarInt(p.totalCount());
                    buf.writeVarInt(p.ventaCount());
                    buf.writeVarInt(p.compraCount());
                    buf.writeVarInt(p.page());
                    writeStr(buf, p.sortColumn());
                    buf.writeBoolean(p.ascending());
                    writeStr(buf, p.playerFilter());
                    writeStr(buf, p.typeFilter());
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

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
