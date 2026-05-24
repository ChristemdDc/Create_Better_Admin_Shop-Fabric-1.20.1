package com.example.betteradminshop.client;

import com.example.betteradminshop.data.PurchaseRecord;
import com.example.betteradminshop.network.RecordsDataPayload;
import com.example.betteradminshop.network.RequestRecordsPayload;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Admin-only screen that shows a paginated, sortable, filterable table of all
 * purchases recorded in the SQLite database.
 *
 * Opened automatically when the server responds to /adminishop records or a
 * RequestRecordsPayload. Refreshed in-place when the player sorts/filters/pages.
 */
public class AdminRecordsScreen extends Screen {

    // ── Palette (matches ShopAdminScreen) ─────────────────────────────────────
    private static final int COL_BG         = 0xFF1E1E2E;
    private static final int COL_BG_INNER   = 0xFF282840;
    private static final int COL_ACCENT     = 0xFF6C63FF;
    private static final int COL_ACCENT_DIM = 0xFF3A3470;
    private static final int COL_TEXT       = 0xFFE8E8E8;
    private static final int COL_TEXT_DIM   = 0xFF9898AA;
    private static final int COL_BORDER     = 0xFF555580;
    private static final int COL_ROW_EVEN   = 0xFF242438;
    private static final int COL_ROW_ODD    = 0xFF202034;
    private static final int COL_ROW_HOVER  = 0xFF30304A;
    private static final int COL_HEADER_BG  = 0xFF2E2E48;
    private static final int COL_SORT_ARROW = 0xFFAAA0FF;
    private static final int COL_GREEN      = 0xFF66EE66;

    // ── Layout constants ──────────────────────────────────────────────────────
    /** Total GUI panel width. */
    private static final int W = 370;
    /** Header bar height (title + filter). */
    private static final int HEADER_H = 32;
    /** Column-header bar height. */
    private static final int COL_HEADER_H = 12;
    /** Each data row height. */
    private static final int ROW_H = 18;
    /** Footer bar height (pagination). */
    private static final int FOOTER_H = 16;
    /** Side & top/bottom margin. */
    private static final int MARGIN = 8;

    /** Number of visible rows. */
    private static final int VISIBLE_ROWS = RecordsDataPayload.PAGE_SIZE;

    private static final int TOTAL_H =
            HEADER_H + 1 + COL_HEADER_H + 1 + VISIBLE_ROWS * ROW_H + 1 + FOOTER_H + MARGIN;

    // ── Column definitions ────────────────────────────────────────────────────
    private static final int[] COL_X    = { 0,   102, 194, 226, 296 };  // relative to content left
    private static final int[] COL_W    = { 102,  92,  32,  70,  80 };  // widths
    private static final String[] COL_LABELS  = { "Jugador", "Ítem", "Cant.", "Precio", "Fecha" };
    private static final String[] COL_DB_KEYS = {
            "player_name", "item_display_name", "quantity", "price_summary", "purchase_timestamp_utc"
    };

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd/MM HH:mm");

    // ── State ─────────────────────────────────────────────────────────────────
    private List<PurchaseRecord> records   = new ArrayList<>();
    private int   totalCount   = 0;
    private int   currentPage  = 0;
    private String sortColumn  = "purchase_timestamp_utc";
    private boolean sortAsc    = false;
    private String playerFilter = "";

    // Derived layout (set in init)
    private int panelX, panelY;
    private int contentX;  // left edge of content columns
    private int rowsY;     // top of first data row
    private int footerY;   // top of footer bar
    private int colHdrY;   // top of column header bar

    // Filter field state
    private String filterText = "";
    private boolean filterFocused = false;
    private int filterX, filterY, filterW = 100, filterH = 10;

    // Hover / input
    private int hoveredRow = -1;
    private int hoveredCol = -1;

    // ── Constructor ───────────────────────────────────────────────────────────

    public AdminRecordsScreen(RecordsDataPayload payload) {
        super(Component.literal("Registros de Compras"));
        applyPayload(payload);
    }

    /** Called from ModNetworking to refresh data while the screen is open. */
    public void updateData(RecordsDataPayload payload) {
        applyPayload(payload);
    }

    private void applyPayload(RecordsDataPayload p) {
        records      = new ArrayList<>(p.records());
        totalCount   = p.totalCount();
        currentPage  = p.page();
        sortColumn   = p.sortColumn();
        sortAsc      = p.ascending();
        playerFilter = p.playerFilter();
        filterText   = p.playerFilter();
    }

    // ── Screen init ───────────────────────────────────────────────────────────

    @Override
    protected void init() {
        panelX   = (width  - W) / 2;
        panelY   = (height - TOTAL_H) / 2;
        contentX = panelX + MARGIN;
        colHdrY  = panelY + HEADER_H + 1;
        rowsY    = colHdrY + COL_HEADER_H + 1;
        footerY  = rowsY + VISIBLE_ROWS * ROW_H + 1;

        // Filter field area (in the header)
        filterW = 110;
        filterH = 10;
        filterX = panelX + W - MARGIN - filterW;
        filterY = panelY + (HEADER_H - filterH) / 2 + 2;
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        // Dim background
        renderBackground(g, mx, my, pt);

        drawPanel(g, mx, my);

        super.render(g, mx, my, pt);
    }

    private void drawPanel(GuiGraphics g, int mx, int my) {
        // ---- Outer frame -------------------------------------------------
        g.fill(panelX, panelY, panelX + W, panelY + TOTAL_H, COL_BG);
        // Accent border (1 px glow outside, then inner border)
        drawBorder(g, panelX - 1, panelY - 1, W + 2, TOTAL_H + 2, COL_ACCENT);
        drawBorder(g, panelX, panelY, W, TOTAL_H, COL_BORDER);

        // ---- Header bar --------------------------------------------------
        g.fill(panelX, panelY, panelX + W, panelY + HEADER_H, COL_BG_INNER);
        // Title
        g.drawString(font, "§5★ §7Registros de Compras", contentX, panelY + 5, COL_TEXT, false);
        // Total badge
        String badge = totalCount + " compras";
        int bw = font.width(badge) + 6;
        int bx = panelX + W - MARGIN - filterW - 8 - bw;
        int by = panelY + (HEADER_H - 10) / 2 + 2;
        g.fill(bx, by, bx + bw, by + 10, COL_ACCENT_DIM);
        g.drawString(font, badge, bx + 3, by + 1, COL_SORT_ARROW, false);

        // Filter field
        boolean filterHov = mx >= filterX && mx < filterX + filterW
                && my >= filterY && my < filterY + filterH;
        g.fill(filterX - 1, filterY - 1, filterX + filterW + 1, filterY + filterH + 1,
                filterFocused ? COL_ACCENT : COL_BORDER);
        g.fill(filterX, filterY, filterX + filterW, filterY + filterH, 0xFF111120);
        String displayFilter = filterText.isEmpty() ? "§8Filtrar jugador..." : "§f" + filterText;
        if (filterFocused && (System.currentTimeMillis() / 500) % 2 == 0) displayFilter += "§f|";
        g.drawString(font, displayFilter, filterX + 2, filterY + 1, COL_TEXT, false);

        // Separator
        g.fill(panelX, panelY + HEADER_H, panelX + W, panelY + HEADER_H + 1, COL_BORDER);

        // ---- Column headers ----------------------------------------------
        g.fill(panelX, colHdrY, panelX + W, colHdrY + COL_HEADER_H, COL_HEADER_BG);
        for (int c = 0; c < COL_LABELS.length; c++) {
            int cx = contentX + COL_X[c];
            boolean isSorted = COL_DB_KEYS[c].equals(sortColumn);
            String label = COL_LABELS[c] + (isSorted ? (sortAsc ? " ▲" : " ▼") : "");
            int col = isSorted ? COL_SORT_ARROW : COL_TEXT_DIM;
            // Clipped draw
            g.drawString(font, label, cx + 2, colHdrY + 2, col, false);
        }
        // Separator
        g.fill(panelX, colHdrY + COL_HEADER_H, panelX + W, colHdrY + COL_HEADER_H + 1, COL_BORDER);

        // ---- Data rows ---------------------------------------------------
        hoveredRow = -1;
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int ry = rowsY + i * ROW_H;
            boolean rowHov = mx >= panelX && mx < panelX + W && my >= ry && my < ry + ROW_H;
            if (rowHov) hoveredRow = i;
            int rowBg = rowHov ? COL_ROW_HOVER : (i % 2 == 0 ? COL_ROW_EVEN : COL_ROW_ODD);
            g.fill(panelX, ry, panelX + W, ry + ROW_H, rowBg);

            if (i < records.size()) {
                drawRow(g, records.get(i), ry, mx, my);
            }
        }
        // Separator
        g.fill(panelX, footerY, panelX + W, footerY + 1, COL_BORDER);

        // ---- Footer (pagination) ----------------------------------------
        g.fill(panelX, footerY, panelX + W, footerY + FOOTER_H, COL_BG_INNER);
        drawPagination(g, mx, my);
    }

    private void drawRow(GuiGraphics g, PurchaseRecord r, int ry, int mx, int my) {
        int cy = ry + (ROW_H - font.lineHeight) / 2;
        int iconY = ry + (ROW_H - 12) / 2;

        // ── Col 0: Player head + name ──────────────────────────────────────
        int col0x = contentX + COL_X[0];
        ResourceLocation skin = getSkin(r.playerUuid());
        // Render inner face layer
        g.blit(skin, col0x, iconY, 12, 12, 8.0f, 8.0f, 8, 8, 64, 64);
        // Render outer (hat) layer  
        g.blit(skin, col0x, iconY, 12, 12, 40.0f, 8.0f, 8, 8, 64, 64);
        String pname = r.playerName();
        if (font.width(pname) > COL_W[0] - 18) {
            pname = font.plainSubstrByWidth(pname, COL_W[0] - 20) + "…";
        }
        g.drawString(font, pname, col0x + 14, cy, COL_TEXT, false);

        // ── Col 1: Item icon + name ─────────────────────────────────────────
        int col1x = contentX + COL_X[1];
        ItemStack stack = itemFromId(r.itemId());
        if (!stack.isEmpty()) {
            g.renderItem(stack, col1x, iconY);
        }
        String iname = r.itemDisplayName();
        if (font.width(iname) > COL_W[1] - 18) {
            iname = font.plainSubstrByWidth(iname, COL_W[1] - 20) + "…";
        }
        g.drawString(font, iname, col1x + 17, cy, COL_TEXT, false);

        // ── Col 2: Quantity ─────────────────────────────────────────────────
        int col2x = contentX + COL_X[2];
        String qty = String.valueOf(r.quantity());
        g.drawString(font, qty, col2x + 2, cy, COL_GREEN, false);

        // ── Col 3: Price summary ────────────────────────────────────────────
        int col3x = contentX + COL_X[3];
        String price = r.priceSummary();
        if (font.width(price) > COL_W[3] - 4) {
            price = font.plainSubstrByWidth(price, COL_W[3] - 8) + "…";
        }
        g.drawString(font, price, col3x + 2, cy, COL_TEXT_DIM, false);

        // ── Col 4: Date/time (local) ────────────────────────────────────────
        int col4x = contentX + COL_X[4];
        String date = toLocalDate(r.purchaseTimestampUtc());
        g.drawString(font, date, col4x + 2, cy, COL_TEXT_DIM, false);
    }

    private void drawPagination(GuiGraphics g, int mx, int my) {
        int totalPages = Math.max(1, (int) Math.ceil((double) totalCount / RecordsDataPayload.PAGE_SIZE));
        int fy = footerY + (FOOTER_H - font.lineHeight) / 2;

        // Prev button
        boolean canPrev = currentPage > 0;
        String prev = "◄ Anterior";
        int prevX = contentX;
        drawButton(g, prev, prevX, footerY + 2, font.width(prev) + 6, FOOTER_H - 4,
                canPrev, mx, my);

        // Page indicator
        String pageStr = "Pág. " + (currentPage + 1) + "/" + totalPages;
        int centerX = panelX + W / 2 - font.width(pageStr) / 2;
        g.drawString(font, pageStr, centerX, fy, COL_TEXT, false);

        // Next button
        boolean canNext = currentPage < totalPages - 1;
        String next = "Siguiente ►";
        int nextW = font.width(next) + 6;
        int nextX = panelX + W - MARGIN - nextW;
        drawButton(g, next, nextX, footerY + 2, nextW, FOOTER_H - 4,
                canNext, mx, my);
    }

    private void drawButton(GuiGraphics g, String label, int bx, int by, int bw, int bh,
                            boolean enabled, int mx, int my) {
        boolean hov = enabled && mx >= bx && mx < bx + bw && my >= by && my < by + bh;
        int bg  = hov    ? COL_ACCENT     : COL_ACCENT_DIM;
        int col = enabled ? COL_TEXT      : COL_TEXT_DIM;
        if (!enabled) bg = COL_BG_INNER;
        g.fill(bx, by, bx + bw, by + bh, bg);
        drawBorder(g, bx, by, bw, bh, enabled ? COL_ACCENT : COL_BORDER);
        g.drawString(font, label, bx + (bw - font.width(label)) / 2, by + (bh - font.lineHeight) / 2, col, false);
    }

    // ── Mouse events ──────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int imx = (int) mx, imy = (int) my;

        // ---- Filter field ------------------------------------------------
        if (imx >= filterX && imx < filterX + filterW
                && imy >= filterY && imy < filterY + filterH) {
            filterFocused = true;
            return true;
        }
        filterFocused = false;

        // ---- Column header click (sort) ----------------------------------
        if (imy >= colHdrY && imy < colHdrY + COL_HEADER_H) {
            for (int c = 0; c < COL_LABELS.length; c++) {
                int cx = contentX + COL_X[c];
                if (imx >= cx && imx < cx + COL_W[c]) {
                    if (COL_DB_KEYS[c].equals(sortColumn)) {
                        sortAsc = !sortAsc;
                    } else {
                        sortColumn = COL_DB_KEYS[c];
                        sortAsc = c == 2; // qty ascending by default; others descending
                    }
                    sendRequest(0);
                    return true;
                }
            }
        }

        // ---- Footer buttons ----------------------------------------------
        int totalPages = Math.max(1, (int) Math.ceil((double) totalCount / RecordsDataPayload.PAGE_SIZE));
        String prev = "◄ Anterior";
        int prevW = font.width(prev) + 6;
        int prevX = contentX;
        if (currentPage > 0 && imx >= prevX && imx < prevX + prevW
                && imy >= footerY + 2 && imy < footerY + FOOTER_H - 2) {
            sendRequest(currentPage - 1);
            return true;
        }

        String next = "Siguiente ►";
        int nextW = font.width(next) + 6;
        int nextX = panelX + W - MARGIN - nextW;
        if (currentPage < totalPages - 1 && imx >= nextX && imx < nextX + nextW
                && imy >= footerY + 2 && imy < footerY + FOOTER_H - 2) {
            sendRequest(currentPage + 1);
            return true;
        }

        // Close if outside panel
        if (imx < panelX || imx >= panelX + W || imy < panelY || imy >= panelY + TOTAL_H) {
            onClose();
        }

        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (filterFocused) {
            if (keyCode == 257 || keyCode == 335) { // Enter / numpad Enter
                filterText  = filterText.strip();
                playerFilter = filterText;
                sendRequest(0);
                filterFocused = false;
                return true;
            }
            if (keyCode == 259) { // Backspace
                if (!filterText.isEmpty()) filterText = filterText.substring(0, filterText.length() - 1);
                return true;
            }
            if (keyCode == 256) { // Escape
                filterFocused = false;
                return true;
            }
            return true; // absorb other keys while focused
        }
        if (keyCode == 256) { onClose(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (filterFocused && c >= 32 && filterText.length() < 40) {
            filterText += c;
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void sendRequest(int page) {
        RequestRecordsPayload.sendToServer(page, sortColumn, sortAsc, playerFilter);
    }

    private ResourceLocation getSkin(String uuidStr) {
        try {
            UUID uuid = UUID.fromString(uuidStr);
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() != null) {
                PlayerInfo info = mc.getConnection().getPlayerInfo(uuid);
                if (info != null) return info.getSkin().texture();
            }
            return DefaultPlayerSkin.getDefaultTexture();
        } catch (Exception e) {
            return DefaultPlayerSkin.getDefaultTexture();
        }
    }

    private static final java.util.Map<String, ItemStack> ITEM_CACHE = new java.util.HashMap<>();

    private static ItemStack itemFromId(String itemId) {
        return ITEM_CACHE.computeIfAbsent(itemId, id -> {
            try {
                ResourceLocation rl = ResourceLocation.tryParse(id);
                if (rl == null) return ItemStack.EMPTY;
                var opt = BuiltInRegistries.ITEM.getHolder(rl);
                return opt.map(h -> new ItemStack(h.value())).orElse(ItemStack.EMPTY);
            } catch (Exception e) {
                return ItemStack.EMPTY;
            }
        });
    }

    private static String toLocalDate(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
                .format(DATE_FMT);
    }

    private static void drawBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x,         y,         x + w, y + 1,     color); // top
        g.fill(x,         y + h - 1, x + w, y + h,     color); // bottom
        g.fill(x,         y,         x + 1, y + h,     color); // left
        g.fill(x + w - 1, y,         x + w, y + h,     color); // right
    }

    /** Skip the Gaussian blur shader — use a plain semi-transparent dark overlay instead. */
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, this.width, this.height, 0x60000000);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
