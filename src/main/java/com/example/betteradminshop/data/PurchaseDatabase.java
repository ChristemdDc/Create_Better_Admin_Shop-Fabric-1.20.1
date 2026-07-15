package com.example.betteradminshop.data;

import com.example.betteradminshop.BetterAdminShop;

import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Server-side singleton that owns the SQLite connection and all CRUD operations.
 *
 * All public methods are thread-safe (guarded by a lock) so they can be called
 * from the server thread without worrying about concurrent access.
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
            BetterAdminShop.LOGGER.info("[BetterAdminShop] SQLite database opened at {}", dbPath.toAbsolutePath());
        } catch (Exception e) {
            BetterAdminShop.LOGGER.error("[BetterAdminShop] Failed to open SQLite database", e);
        } finally {
            lock.unlock();
        }
    }

    public void close() {
        lock.lock();
        try {
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
                    transaction_type      TEXT    NOT NULL DEFAULT 'venta'
                )
                """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_player    ON purchase_records(player_uuid)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_timestamp ON purchase_records(purchase_timestamp_utc)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_item      ON purchase_records(item_id)");
            migrateAddTypeColumn(st);
            st.execute("CREATE INDEX IF NOT EXISTS idx_type      ON purchase_records(transaction_type)");
        }
    }

    /** Migración: bases de datos anteriores no tienen transaction_type. */
    private void migrateAddTypeColumn(Statement st) throws SQLException {
        boolean hasColumn = false;
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(purchase_records)")) {
            while (rs.next()) {
                if ("transaction_type".equalsIgnoreCase(rs.getString("name"))) {
                    hasColumn = true;
                    break;
                }
            }
        }
        if (!hasColumn) {
            st.execute("ALTER TABLE purchase_records ADD COLUMN transaction_type TEXT NOT NULL DEFAULT 'venta'");
            BetterAdminShop.LOGGER.info("[BetterAdminShop] Migración: columna transaction_type añadida.");
        }
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Registra una transacción de cualquier tipo ({@link #TYPE_VENTA} o
     * {@link #TYPE_COMPRA}). Cada ítem de la orden produce una fila.
     */
    public void logTransaction(String type, String transactionId, String playerUuid, String playerName,
                               String itemId, String itemDisplayName, int quantity,
                               String priceSummary, long timestampUtc, BlockPos shopPos, String shopWorld) {
        lock.lock();
        try {
            if (conn == null) return;
            try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO purchase_records
                    (transaction_id, player_uuid, player_name, item_id, item_display_name,
                     quantity, price_summary, purchase_timestamp_utc, shop_x, shop_y, shop_z, shop_world,
                     transaction_type)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
                ps.setString(1,  transactionId);
                ps.setString(2,  playerUuid);
                ps.setString(3,  playerName);
                ps.setString(4,  itemId);
                ps.setString(5,  itemDisplayName);
                ps.setInt(6,     quantity);
                ps.setString(7,  priceSummary);
                ps.setLong(8,    timestampUtc);
                ps.setInt(9,     shopPos.getX());
                ps.setInt(10,    shopPos.getY());
                ps.setInt(11,    shopPos.getZ());
                ps.setString(12, shopWorld);
                ps.setString(13, TYPE_COMPRA.equals(type) ? TYPE_COMPRA : TYPE_VENTA);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            BetterAdminShop.LOGGER.error("[BetterAdminShop] Failed to log transaction", e);
        } finally {
            lock.unlock();
        }
    }

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
        lock.lock();
        List<PurchaseRecord> results = new ArrayList<>();
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
                        rs.getString("transaction_type")
                ));
            }
        } catch (SQLException e) {
            BetterAdminShop.LOGGER.error("[BetterAdminShop] Error reading records", e);
        } finally {
            lock.unlock();
        }
        return results;
    }

    public int getTotalCount(String playerFilter, String typeFilter) {
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
