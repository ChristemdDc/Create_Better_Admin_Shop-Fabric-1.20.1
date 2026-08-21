package com.example.betteradminshop.data;

import com.example.betteradminshop.BetterAdminShop;

import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Server-side singleton that owns the SQLite connection and all CRUD operations.
 *
 * All public methods are thread-safe (guarded by a lock) so they can be called
 * from the server thread without worrying about concurrent access.
 *
 * ESCRITURAS ASÍNCRONAS: {@link #logTransaction} NUNCA toca el disco en el hilo
 * llamante — encola la fila y un hilo daemon la vuelca en LOTES dentro de una
 * sola transacción. Con decenas de jugadores comprando a la vez esto convierte
 * N transacciones SQLite por compra en una cada ~200 ms, y ningún tick del
 * servidor espera a la E/S. Si la cola se llena (base caída o disco saturado)
 * se descartan filas y se avisa por log: el juego jamás se bloquea por esto.
 */
public class PurchaseDatabase {

    // ── Singleton ─────────────────────────────────────────────────────────────

    private static final PurchaseDatabase INSTANCE = new PurchaseDatabase();

    public static PurchaseDatabase getInstance() {
        return INSTANCE;
    }

    private PurchaseDatabase() {}

    // ── State ─────────────────────────────────────────────────────────────────

    private Connection conn = null;
    private final ReentrantLock lock = new ReentrantLock();

    // ── Cola de escritura asíncrona ──────────────────────────────────────────

    /** Fila pendiente de insertar. Inmutable: viaja entre hilos sin copias. */
    private record PendingRow(String type, String transactionId, String playerUuid, String playerName,
                              String itemId, String itemDisplayName, int quantity,
                              String priceSummary, long timestampUtc,
                              int shopX, int shopY, int shopZ, String shopWorld,
                              String shopOwner) {}

    /** Techo de la cola. Al ritmo real (unas pocas filas por compra) sobra. */
    private static final int QUEUE_CAPACITY = 8192;
    /** Filas por transacción SQLite. */
    private static final int BATCH_SIZE = 256;
    /** Espera del hilo escritor entre sondeos (ms). */
    private static final long POLL_MS = 200L;

    private final BlockingQueue<PendingRow> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private volatile boolean running = false;
    private Thread writerThread = null;
    private final AtomicLong droppedRows = new AtomicLong();
    private volatile long lastDropWarnMs = 0L;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void initialize(Path dbPath) {
        lock.lock();
        try {
            if (conn != null) return; // already open

            // Ensure parent directory exists
            Path parent = dbPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            // Explicitly load the driver bundled via JarJar
            Class.forName("org.sqlite.JDBC");

            conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
            conn.setAutoCommit(true);

            // Optimisations for embedded use
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
            }

            createTables();
            startWriter();
            BetterAdminShop.LOGGER.info("[BetterAdminShop] SQLite database opened at {}", dbPath.toAbsolutePath());
        } catch (Exception e) {
            BetterAdminShop.LOGGER.error("[BetterAdminShop] Failed to open SQLite database", e);
        } finally {
            lock.unlock();
        }
    }

    public void close() {
        // Fuera del lock: hay que dejar que el escritor termine su lote actual.
        stopWriter();
        lock.lock();
        try {
            drainOnce(); // por si quedó algo tras el join
            if (conn != null) {
                conn.close();
                conn = null;
                BetterAdminShop.LOGGER.info("[BetterAdminShop] SQLite database closed.");
            }
        } catch (SQLException e) {
            BetterAdminShop.LOGGER.error("[BetterAdminShop] Error closing SQLite", e);
        } finally {
            lock.unlock();
        }
    }

    private void startWriter() {
        if (writerThread != null) return;
        running = true;
        writerThread = new Thread(this::writerLoop, "BetterAdminShop-SQLite");
        writerThread.setDaemon(true);
        // Por debajo de la prioridad normal: el hilo del servidor manda.
        writerThread.setPriority(Thread.NORM_PRIORITY - 1);
        writerThread.start();
    }

    private void stopWriter() {
        running = false;
        Thread t = writerThread;
        writerThread = null;
        if (t == null) return;
        try {
            // Sin interrupt: se le da margen para vaciar lo que quede en cola.
            t.join(5_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Bucle del hilo escritor: sondea, agrupa y vuelca en lotes. */
    private void writerLoop() {
        while (running || !queue.isEmpty()) {
            try {
                PendingRow first = queue.poll(POLL_MS, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                List<PendingRow> batch = new ArrayList<>(BATCH_SIZE);
                batch.add(first);
                queue.drainTo(batch, BATCH_SIZE - 1);
                writeBatch(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                BetterAdminShop.LOGGER.error("[BetterAdminShop] Error en el hilo de escritura SQLite", e);
            }
        }
    }

    /**
     * Vuelca hasta un lote en el hilo llamante. Lo usan las lecturas (para no
     * mostrar registros obsoletos) y el cierre del servidor.
     */
    private void drainOnce() {
        if (queue.isEmpty()) return;
        List<PendingRow> batch = new ArrayList<>(BATCH_SIZE);
        queue.drainTo(batch, BATCH_SIZE);
        if (!batch.isEmpty()) writeBatch(batch);
    }

    /**
     * Vacía la cola. Solo desde lecturas/cierre, nunca desde el tick.
     *
     * Acotado a propósito: si otro hilo sigue encolando más rápido de lo que
     * escribimos, se corta en vez de girar indefinidamente.
     */
    private void flushPending() {
        int maxBatches = (QUEUE_CAPACITY / BATCH_SIZE) + 2;
        while (!queue.isEmpty() && maxBatches-- > 0) drainOnce();
    }

    /** Inserta un lote en UNA sola transacción. */
    private void writeBatch(List<PendingRow> batch) {
        lock.lock();
        try {
            if (conn == null) return;
            boolean prevAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
                for (PendingRow r : batch) {
                    ps.setString(1,  r.transactionId());
                    ps.setString(2,  r.playerUuid());
                    ps.setString(3,  r.playerName());
                    ps.setString(4,  r.itemId());
                    ps.setString(5,  r.itemDisplayName());
                    ps.setInt(6,     r.quantity());
                    ps.setString(7,  r.priceSummary());
                    ps.setLong(8,    r.timestampUtc());
                    ps.setInt(9,     r.shopX());
                    ps.setInt(10,    r.shopY());
                    ps.setInt(11,    r.shopZ());
                    ps.setString(12, r.shopWorld());
                    ps.setString(13, TYPE_COMPRA.equals(r.type()) ? TYPE_COMPRA : TYPE_VENTA);
                    ps.setString(14, r.shopOwner() == null ? "" : r.shopOwner());
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                BetterAdminShop.LOGGER.error(
                        "[BetterAdminShop] Falló el lote de {} transacciones", batch.size(), e);
            } finally {
                try { conn.setAutoCommit(prevAutoCommit); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            BetterAdminShop.LOGGER.error("[BetterAdminShop] Error preparando el lote SQLite", e);
        } finally {
            lock.unlock();
        }
    }

    // ── Schema ────────────────────────────────────────────────────────────────

    /** Tipo de transacción: la tienda VENDE al jugador. */
    public static final String TYPE_VENTA = "venta";
    /** Tipo de transacción: la tienda COMPRA al jugador. */
    public static final String TYPE_COMPRA = "compra";

    private void createTables() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS purchase_records (
                    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
                    transaction_id        TEXT    NOT NULL,
                    player_uuid           TEXT    NOT NULL,
                    player_name           TEXT    NOT NULL,
                    item_id               TEXT    NOT NULL,
                    item_display_name     TEXT    NOT NULL,
                    quantity              INTEGER NOT NULL,
                    price_summary         TEXT    NOT NULL,
                    purchase_timestamp_utc INTEGER NOT NULL,
                    shop_x                INTEGER,
                    shop_y                INTEGER,
                    shop_z                INTEGER,
                    shop_world            TEXT,
                    transaction_type      TEXT    NOT NULL DEFAULT 'venta',
                    shop_owner            TEXT    NOT NULL DEFAULT ''
                )
                """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_player    ON purchase_records(player_uuid)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_timestamp ON purchase_records(purchase_timestamp_utc)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_item      ON purchase_records(item_id)");
            migrateAddColumn(st, "transaction_type", "TEXT NOT NULL DEFAULT 'venta'");
            migrateAddColumn(st, "shop_owner", "TEXT NOT NULL DEFAULT ''");
            st.execute("CREATE INDEX IF NOT EXISTS idx_type      ON purchase_records(transaction_type)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_owner     ON purchase_records(shop_owner)");
        }
    }

    /** Migración genérica: añade una columna si la base es de una versión anterior. */
    private void migrateAddColumn(Statement st, String column, String definition) throws SQLException {
        boolean hasColumn = false;
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(purchase_records)")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    hasColumn = true;
                    break;
                }
            }
        }
        if (!hasColumn) {
            st.execute("ALTER TABLE purchase_records ADD COLUMN " + column + " " + definition);
            BetterAdminShop.LOGGER.info("[BetterAdminShop] Migración: columna {} añadida.", column);
        }
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    private static final String INSERT_SQL = """
        INSERT INTO purchase_records
            (transaction_id, player_uuid, player_name, item_id, item_display_name,
             quantity, price_summary, purchase_timestamp_utc, shop_x, shop_y, shop_z, shop_world,
             transaction_type, shop_owner)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """;

    /**
     * Registra una transacción de cualquier tipo ({@link #TYPE_VENTA} o
     * {@link #TYPE_COMPRA}). Cada ítem de la orden produce una fila.
     *
     * NO bloquea: solo encola. Es seguro llamarlo desde el hilo del servidor
     * en mitad de una compra.
     */
    public void logTransaction(String type, String transactionId, String playerUuid, String playerName,
                               String itemId, String itemDisplayName, int quantity,
                               String priceSummary, long timestampUtc, BlockPos shopPos, String shopWorld) {
        logTransaction(type, transactionId, playerUuid, playerName, itemId, itemDisplayName,
                quantity, priceSummary, timestampUtc, shopPos, shopWorld, "");
    }

    /**
     * Igual que {@link #logTransaction}, indicando el DUEÑO de la tienda de
     * jugador donde ocurrió. Cadena vacía = tienda de administrador.
     */
    public void logTransaction(String type, String transactionId, String playerUuid, String playerName,
                               String itemId, String itemDisplayName, int quantity,
                               String priceSummary, long timestampUtc, BlockPos shopPos, String shopWorld,
                               String shopOwner) {
        if (!running) return; // base sin abrir o servidor parando
        PendingRow row = new PendingRow(type, transactionId, playerUuid, playerName,
                itemId, itemDisplayName, quantity, priceSummary, timestampUtc,
                shopPos.getX(), shopPos.getY(), shopPos.getZ(), shopWorld,
                shopOwner == null ? "" : shopOwner);
        if (!queue.offer(row)) {
            // Cola saturada: preferimos perder registros antes que frenar el tick.
            long n = droppedRows.incrementAndGet();
            long now = System.currentTimeMillis();
            if (now - lastDropWarnMs > 60_000L) {
                lastDropWarnMs = now;
                BetterAdminShop.LOGGER.warn(
                        "[BetterAdminShop] Cola de registros llena: {} filas descartadas. "
                        + "¿La base de datos va lenta?", n);
            }
        }
    }

    /** Filas descartadas por saturación desde el arranque (diagnóstico). */
    public long getDroppedRows() { return droppedRows.get(); }

    /** Filas esperando a escribirse (diagnóstico). */
    public int getPendingRows() { return queue.size(); }

    // ── Read ──────────────────────────────────────────────────────────────────

    private static final java.util.Set<String> ALLOWED_SORT_COLS = java.util.Set.of(
            "player_name", "item_display_name", "quantity", "price_summary",
            "purchase_timestamp_utc", "transaction_type"
    );

    /**
     * Returns one page of records with optional player-name / type filter and
     * column sort.
     *
     * @param page         0-based page number
     * @param pageSize     rows per page
     * @param sortColumn   DB column name (validated against allow-list)
     * @param ascending    true = ASC
     * @param playerFilter substring filter on player_name (empty = all)
     * @param typeFilter   "venta", "compra" or "" (todas)
     */
    public List<PurchaseRecord> getRecords(int page, int pageSize,
                                           String sortColumn, boolean ascending,
                                           String playerFilter, String typeFilter) {
        flushPending(); // que el panel no muestre registros a medio escribir
        List<PurchaseRecord> results = new ArrayList<>();
        lock.lock();
        try {
            if (conn == null) return results;

            // Validate sort column (prevent SQL injection even though input is internal)
            if (!ALLOWED_SORT_COLS.contains(sortColumn)) sortColumn = "purchase_timestamp_utc";
            String order = ascending ? "ASC" : "DESC";

            String where = buildWhere(playerFilter, typeFilter);
            String sql = "SELECT * FROM purchase_records" + where
                    + " ORDER BY " + sortColumn + " " + order + " LIMIT ? OFFSET ?";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int idx = bindWhere(ps, 1, playerFilter, typeFilter);
                ps.setInt(idx, pageSize);
                ps.setInt(idx + 1, page * pageSize);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    results.add(new PurchaseRecord(
                            rs.getLong("id"),
                            rs.getString("transaction_id"),
                            rs.getString("player_uuid"),
                            rs.getString("player_name"),
                            rs.getString("item_id"),
                            rs.getString("item_display_name"),
                            rs.getInt("quantity"),
                            rs.getString("price_summary"),
                            rs.getLong("purchase_timestamp_utc"),
                            rs.getString("transaction_type"),
                            rs.getString("shop_owner")
                    ));
                }
            } catch (SQLException e) {
                BetterAdminShop.LOGGER.error("[BetterAdminShop] Error reading records", e);
            }
        } finally {
            lock.unlock();
        }
        return results;
    }

    public int getTotalCount(String playerFilter, String typeFilter) {
        flushPending();
        lock.lock();
        try {
            if (conn == null) return 0;
            String sql = "SELECT COUNT(*) FROM purchase_records" + buildWhere(playerFilter, typeFilter);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bindWhere(ps, 1, playerFilter, typeFilter);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            BetterAdminShop.LOGGER.error("[BetterAdminShop] Error counting records", e);
        } finally {
            lock.unlock();
        }
        return 0;
    }

    private static String buildWhere(String playerFilter, String typeFilter) {
        List<String> conds = new ArrayList<>();
        if (!playerFilter.isBlank()) conds.add("LOWER(player_name) LIKE ?");
        if (!typeFilter.isBlank()) conds.add("transaction_type = ?");
        return conds.isEmpty() ? "" : " WHERE " + String.join(" AND ", conds);
    }

    /** Binds the WHERE params starting at {@code startIdx}; returns the next free index. */
    private static int bindWhere(PreparedStatement ps, int startIdx,
                                 String playerFilter, String typeFilter) throws SQLException {
        int idx = startIdx;
        if (!playerFilter.isBlank()) ps.setString(idx++, "%" + playerFilter.toLowerCase() + "%");
        if (!typeFilter.isBlank()) ps.setString(idx++, typeFilter);
        return idx;
    }

    public boolean isReady() {
        lock.lock();
        try { return conn != null; } finally { lock.unlock(); }
    }
}
