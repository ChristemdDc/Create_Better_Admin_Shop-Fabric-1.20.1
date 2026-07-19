package com.example.betteradminshop.data;

import com.example.betteradminshop.BetterAdminShop;
import com.example.betteradminshop.block.ShopSlot;
import com.example.betteradminshop.config.BetterAdminShopConfig;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import org.bson.Document;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Espejo opcional a MongoDB. SQLite ({@link PurchaseDatabase}) sigue siendo la
 * base local autoritativa; cuando Mongo está habilitado, este store:
 *
 *  - Espeja cada transacción (venta/compra) a la colección de transacciones.
 *  - Publica el estado de cada tienda (ítems, precios, stock) a la colección de
 *    tiendas, para gestión externa y precios dinámicos con IA (a futuro).
 *
 * Diseño defensivo: es el ÚNICO escritor (evita drift), toda la E/S corre en un
 * hilo daemon aparte (nunca bloquea el hilo del servidor), y cualquier fallo se
 * registra sin afectar al juego. Si el driver no carga o Mongo no responde, el
 * mod sigue funcionando solo con SQLite.
 */
/*
 * ═══════════════════════════════════════════════════════════════════════════
 *  REFERENCIA: gestión de la base de datos (por SSH / mongosh)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  Config (en el host):  <carpeta_servidor>/config/betteradminshop-common.toml
 *    [mongodb]
 *      enabled  = true
 *      uri      = "mongodb://usuario:clave@localhost:27017"
 *      database = "betteradminshop"           (por defecto)
 *      transactionsCollection = "transactions"
 *      shopsCollection        = "shops"
 *      publishShopState       = true
 *
 *  Nombres por defecto:
 *    - Base de datos:  betteradminshop
 *    - Colección "transactions": una fila por línea de venta/compra.
 *        _id            = "<transactionId>:<slotIndex>"  (idempotente)
 *        transactionId, type("venta"|"compra"), playerUuid, playerName,
 *        itemId, itemName, quantity,
 *        price: [ { itemId, itemName, amount } ],  priceSummary,
 *        timestamp (BSON Date),  shop: { world, x, y, z }
 *    - Colección "shops": un documento por tienda (estado actual).
 *        _id  = "<world>:<x>:<y>:<z>"
 *        world, x, y, z, updatedAt,
 *        slots: [ { index, type, itemId, itemName, sellAmount,
 *                   maxStockPerPlayer, renderItemId?,
 *                   price: [ { itemId, itemName, amount } ] } ]
 *
 *  Comandos útiles (mongosh):
 *    mongosh "mongodb://usuario:clave@localhost:27017"
 *    use betteradminshop
 *    show collections
 *    db.transactions.find().sort({ timestamp: -1 }).limit(10)
 *    db.transactions.find({ type: "compra" })
 *    db.transactions.aggregate([
 *      { $match: { type: "venta" } },
 *      { $group: { _id: "$itemName", total: { $sum: "$quantity" } } },
 *      { $sort: { total: -1 } } ])
 *    db.shops.find({ world: "minecraft:overworld" }).pretty()
 *    // Índices recomendados (consultas rápidas / IA a futuro):
 *    db.transactions.createIndex({ timestamp: -1 })
 *    db.transactions.createIndex({ type: 1, itemId: 1 })
 *
 *  Notas:
 *    - El mod SOLO escribe (único escritor). Leer por SSH es seguro.
 *    - Para que la IA a futuro pueda MODIFICAR precios/stock, hará falta un
 *      paso extra donde el mod LEA de vuelta la colección "shops" y aplique
 *      los cambios al NBT del bloque (aún no implementado).
 *    - Verifica la conexión en el juego con: /tiendas mongo status
 * ═══════════════════════════════════════════════════════════════════════════
 */
public final class MongoStore {

    private static final MongoStore INSTANCE = new MongoStore();

    public static MongoStore getInstance() {
        return INSTANCE;
    }

    private MongoStore() {}

    // ── Estado ────────────────────────────────────────────────────────────────

    private volatile boolean enabled = false;
    private MongoClient client;
    private MongoCollection<Document> transactions;
    private MongoCollection<Document> shops;
    private ExecutorService executor;

    public boolean isEnabled() {
        return enabled;
    }

    // ── Ciclo de vida ───────────────────────────────────────────────────────

    /** Llamado al arrancar el servidor. No bloquea: la conexión real es perezosa. */
    public void initialize() {
        try {
            if (!BetterAdminShopConfig.MONGO_ENABLED.get()) {
                BetterAdminShop.LOGGER.info("[BetterAdminShop] MongoDB deshabilitado (solo SQLite).");
                return;
            }
            String uri = BetterAdminShopConfig.MONGO_URI.get();
            String dbName = BetterAdminShopConfig.MONGO_DATABASE.get();

            // create(...) NO abre la conexión todavía; se conecta en la primera
            // operación (que corre en el executor, no en el hilo del servidor).
            client = MongoClients.create(uri);
            MongoDatabase db = client.getDatabase(dbName);
            transactions = db.getCollection(BetterAdminShopConfig.MONGO_TX_COLLECTION.get());
            shops = db.getCollection(BetterAdminShopConfig.MONGO_SHOP_COLLECTION.get());

            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "BetterAdminShop-Mongo");
                t.setDaemon(true);
                return t;
            });
            enabled = true;
            BetterAdminShop.LOGGER.info("[BetterAdminShop] MongoDB habilitado → base '{}' (colecciones '{}' / '{}').",
                    dbName,
                    BetterAdminShopConfig.MONGO_TX_COLLECTION.get(),
                    BetterAdminShopConfig.MONGO_SHOP_COLLECTION.get());
        } catch (Throwable t) {
            enabled = false;
            BetterAdminShop.LOGGER.error(
                    "[BetterAdminShop] No se pudo inicializar MongoDB; la integración queda deshabilitada.", t);
        }
    }

    public void close() {
        enabled = false;
        ExecutorService ex = executor;
        if (ex != null) {
            ex.shutdown();
            try {
                if (!ex.awaitTermination(5, TimeUnit.SECONDS)) ex.shutdownNow();
            } catch (InterruptedException e) {
                ex.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        try {
            if (client != null) client.close();
        } catch (Throwable ignored) {
        }
        client = null;
        transactions = null;
        shops = null;
        executor = null;
    }

    private void submit(Runnable task) {
        ExecutorService ex = executor;
        if (!enabled || ex == null) return;
        try {
            ex.submit(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    BetterAdminShop.LOGGER.error("[BetterAdminShop] Operación de MongoDB falló", t);
                }
            });
        } catch (Throwable ignored) {
            // executor apagándose
        }
    }

    // ── Transacciones ───────────────────────────────────────────────────────

    /** Una línea de precio/pago dentro de una transacción. */
    public record PriceLine(String itemId, String itemName, int amount) {}

    /**
     * Espeja una línea de transacción a Mongo. {@code _id} determinista
     * (transacción + slot) para que la escritura sea idempotente.
     */
    public void logTransaction(String type, String transactionId, int slotIndex,
                               String playerUuid, String playerName,
                               String itemId, String itemName, int quantity,
                               List<PriceLine> price, String priceSummary,
                               long timestampMs, String world, BlockPos pos) {
        if (!enabled) return;
        submit(() -> {
            List<Document> priceDocs = new ArrayList<>();
            if (price != null) {
                for (PriceLine p : price) {
                    priceDocs.add(new Document("itemId", p.itemId())
                            .append("itemName", p.itemName())
                            .append("amount", p.amount()));
                }
            }
            Document doc = new Document("_id", transactionId + ":" + slotIndex)
                    .append("transactionId", transactionId)
                    .append("type", type)
                    .append("playerUuid", playerUuid)
                    .append("playerName", playerName)
                    .append("itemId", itemId)
                    .append("itemName", itemName)
                    .append("quantity", quantity)
                    .append("price", priceDocs)
                    .append("priceSummary", priceSummary)
                    .append("timestamp", new Date(timestampMs))
                    .append("shop", shopLocation(world, pos));
            transactions.replaceOne(Filters.eq("_id", doc.get("_id")), doc,
                    new ReplaceOptions().upsert(true));
        });
    }

    // ── Estado de tiendas ───────────────────────────────────────────────────

    /**
     * Publica (upsert) el estado completo de una tienda: sus slots con ítem,
     * tipo, precios y stock. Es lo que un sistema externo / la IA leerá (y a
     * futuro escribirá) para gestionar precios y existencias.
     */
    public void publishShop(String world, BlockPos pos, ShopSlot[] slots) {
        if (!enabled || !BetterAdminShopConfig.MONGO_PUBLISH_STATE.get()) return;
        // Copiar los datos AHORA (en el hilo del servidor) para no leer los
        // slots desde el hilo de Mongo mientras el juego los modifica.
        List<Document> slotDocs = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            ShopSlot slot = slots[i];
            if (slot == null || slot.isEmpty()) continue;
            slotDocs.add(slotDocument(i, slot));
        }
        String id = shopId(world, pos);
        long now = System.currentTimeMillis();
        submit(() -> {
            Document doc = new Document("_id", id)
                    .append("world", world)
                    .append("x", pos.getX())
                    .append("y", pos.getY())
                    .append("z", pos.getZ())
                    .append("updatedAt", new Date(now))
                    .append("slots", slotDocs);
            shops.replaceOne(Filters.eq("_id", id), doc, new ReplaceOptions().upsert(true));
        });
    }

    /** Elimina el documento de una tienda (al romper el bloque). */
    public void removeShop(String world, BlockPos pos) {
        if (!enabled) return;
        String id = shopId(world, pos);
        submit(() -> shops.deleteOne(Filters.eq("_id", id)));
    }

    // ── Helpers de construcción de documentos ─────────────────────────────────

    private static Document slotDocument(int index, ShopSlot slot) {
        Document doc = new Document("index", index)
                .append("type", slot.isCompra() ? "compra" : "venta")
                .append("itemId", itemId(slot.getDisplayItem()))
                .append("itemName", slot.getDisplayItem().getHoverName().getString())
                .append("sellAmount", slot.getSellAmount())
                .append("maxStockPerPlayer", slot.getMaxStock());

        if (slot.hasRenderOverride()) {
            doc.append("renderItemId", itemId(slot.getRenderOverride()));
        }

        List<Document> price = new ArrayList<>();
        if (!slot.getPriceItem().isEmpty()) {
            price.add(priceDoc(slot.getPriceItem(), slot.getPriceAmount()));
        }
        if (slot.hasSecondPrice()) {
            price.add(priceDoc(slot.getPriceItem2(), slot.getPriceAmount2()));
        }
        doc.append("price", price);
        return doc;
    }

    private static Document priceDoc(ItemStack stack, int amount) {
        return new Document("itemId", itemId(stack))
                .append("itemName", stack.getHoverName().getString())
                .append("amount", amount);
    }

    private static Document shopLocation(String world, BlockPos pos) {
        return new Document("world", world)
                .append("x", pos.getX())
                .append("y", pos.getY())
                .append("z", pos.getZ());
    }

    private static String shopId(String world, BlockPos pos) {
        return world + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
