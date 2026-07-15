package com.example.betteradminshop.network;

import com.example.betteradminshop.BetterAdminShop;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.nio.charset.StandardCharsets;

/**
 * Client → Server: the client requests a new page or applies new sort/filter
 * settings. The server re-queries the DB and responds with a {@link RecordsDataPayload}.
 */
public record RequestRecordsPayload(
        int     page,
        String  sortColumn,
        boolean ascending,
        String  playerFilter,
        String  typeFilter      // "venta", "compra" o "" (todas)
) implements CustomPacketPayload {

    public static final Type<RequestRecordsPayload> TYPE =
            new Type<>(BetterAdminShop.id("request_records"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestRecordsPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public RequestRecordsPayload decode(RegistryFriendlyByteBuf buf) {
                    int     page   = buf.readVarInt();
                    String  sort   = readStr(buf);
                    boolean asc    = buf.readBoolean();
                    String  filter = readStr(buf);
                    String  type   = readStr(buf);
                    return new RequestRecordsPayload(page, sort, asc, filter, type);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, RequestRecordsPayload p) {
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

    // ── Client-side send helper ───────────────────────────────────────────────

    public static void sendToServer(int page, String sortColumn, boolean ascending,
                                    String filter, String typeFilter) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new RequestRecordsPayload(page, sortColumn, ascending, filter, typeFilter));
    }
}
