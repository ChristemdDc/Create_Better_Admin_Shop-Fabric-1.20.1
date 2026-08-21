package com.example.betteradminshop.client;

import com.example.betteradminshop.network.PlayerShopNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Panel VISUAL de administración de las tiendas de jugador (Fase 5).
 * Abierto con /tiendas config (OP nivel 4). Edita, para TODAS las tiendas:
 *  - La renta: ítem, cantidad y cada cuántos días se abona (cantidad 0 = off).
 *  - El precio de cada mejora: estante a 3/4 slots, capacidad de stock a
 *    8/16 stacks, y la mejora de vacío.
 * Cada fila muestra qué mejora es, el ítem que cuesta y su cantidad.
 */
public class PlayerShopConfigScreen extends Screen {

    private static final int W = 430;
    private static final int H = 240;

    // Paleta admin (morada, como las pantallas de administración)
    private static final int COL_BG     = 0xFF181825;
    private static final int COL_PANEL  = 0xFF222235;
    private static final int COL_ACCENT = 0xFF6C63FF;
    private static final int COL_ACCENT_DIM = 0xFF4A4499;
    private static final int COL_BORDER = 0xFF444466;
    private static final int COL_TEXT   = 0xFFE0E0E0;
    private static final int COL_DIM    = 0xFF888899;
    private static final int COL_GOLD   = 0xFFFFDD55;

    private int left, top;

    private static final String[] ROW_LABELS = {
            "Renta (cuota de operación)",
            "Mejora: estante ➜ 3 slots",
            "Mejora: estante ➜ 4 slots",
            "Mejora: stock ➜ 8 stacks/slot",
            "Mejora: stock ➜ 16 stacks/slot",
            "Mejora: vacío (descarta excedente)"};

    /** items[0] = renta; 1..5 = mejoras (orden de UPGRADE_KEYS). */
    private final ItemStack[] items = new ItemStack[6];
    private final Field[] amounts = new Field[6];
    private Field daysField;

    // Selector de ítem (overlay compacto)
    private boolean pickerOpen = false;
    private int pickerRow = -1;
    private Field searchField;
    private final List<ItemStack> allItems = new ArrayList<>();
    private final List<ItemStack> filtered = new ArrayList<>();
    private int scroll = 0;

    private String pendingTooltip;
    private int tooltipX, tooltipY;

    public PlayerShopConfigScreen(PlayerShopNetworking.ShopConfig config) {
        super(Component.literal("Configuración de Tiendas de Jugador"));
        items[0] = config.rentItem().isEmpty() ? new ItemStack(Items.EMERALD) : config.rentItem().copy();
        for (int i = 0; i < 5; i++) {
            items[i + 1] = i < config.costItems().size() && !config.costItems().get(i).isEmpty()
                    ? config.costItems().get(i).copy() : new ItemStack(Items.DIAMOND);
        }
        for (int i = 0; i < 6; i++) {
            amounts[i] = new Field(6);
        }
        amounts[0].set(String.valueOf(config.rentAmount()));
        for (int i = 0; i < 5; i++) {
            amounts[i + 1].set(String.valueOf(
                    i < config.costAmounts().size() ? config.costAmounts().get(i) : 0));
        }
        daysField = new Field(6);
        daysField.set(String.format("%.1f", config.rentPeriodMs() / 86_400_000.0));
    }

    @Override
    protected void init() {
        super.init();
        left = (width - W) / 2;
        top = (height - H) / 2;

        searchField = new Field(40);
        allItems.clear();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item != Items.AIR) allItems.add(new ItemStack(item));
        }
        filtered.clear();
        filtered.addAll(allItems);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0xCC000000);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        pendingTooltip = null;
        renderBackground(g, mx, my, pt);

        g.fill(left + 2, top + 2, left + W + 2, top + H + 2, 0x88000000);
        g.fill(left, top, left + W, top + H, COL_BG);
        border(g, left, top, W, H, COL_BORDER);
        g.fill(left, top, left + W, top + 18, COL_ACCENT_DIM);
        g.drawCenteredString(font, "⚙ Configuración de Tiendas de Jugador",
                left + W / 2, top + 5, 0xFFFFFFFF);

        int rowY = top + 26;
        for (int i = 0; i < 6; i++) {
            int y = rowY + i * 26;
            g.fill(left + 8, y, left + W - 8, y + 24, i % 2 == 0 ? COL_PANEL : COL_BG);

            g.drawString(font, ROW_LABELS[i], left + 14, y + 8, COL_TEXT, false);

            // Botón de ítem
            int ix = left + 252;
            boolean hovItem = in(mx, my, ix, y + 3, 20, 20);
            g.fill(ix, y + 3, ix + 20, y + 23 - 2, hovItem ? COL_ACCENT_DIM : 0xFF2A2A40);
            border(g, ix, y + 3, 20, 18, hovItem ? COL_ACCENT : COL_BORDER);
            g.renderItem(items[i], ix + 2, y + 4);
            if (hovItem) {
                tooltip("Ítem del coste: " + items[i].getHoverName().getString()
                        + " · clic para cambiar", mx, my);
            }

            // Cantidad
            g.drawString(font, "×", left + 278, y + 8, COL_DIM, false);
            amounts[i].draw(g, font, left + 288, y + 4, 40, 16);

            // Renta: días
            if (i == 0) {
                g.drawString(font, "cada", left + 334, y + 8, COL_DIM, false);
                daysField.draw(g, font, left + 360, y + 4, 36, 16);
                g.drawString(font, "días", left + 399, y + 8, COL_DIM, false);
            }
        }
        g.drawString(font, "§8Renta con cantidad 0 = deshabilitada (tiendas gratis).",
                left + 14, rowY + 6 * 26 + 2, COL_DIM, false);

        // Guardar / cancelar
        int by = top + H - 26;
        boolean hovSave = in(mx, my, left + W - 110, by, 100, 18);
        g.fill(left + W - 110, by, left + W - 10, by + 18, hovSave ? COL_ACCENT : COL_ACCENT_DIM);
        g.drawCenteredString(font, "✔ Guardar", left + W - 60, by + 5, 0xFFFFFFFF);
        boolean hovNo = in(mx, my, left + 10, by, 80, 18);
        g.fill(left + 10, by, left + 90, by + 18, hovNo ? COL_ACCENT_DIM : COL_PANEL);
        g.drawCenteredString(font, "Cancelar", left + 50, by + 5, COL_TEXT);

        if (pickerOpen) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 350);
            renderPicker(g, mx, my);
            g.pose().popPose();
        }

        if (pendingTooltip != null) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            g.renderTooltip(font, Component.literal(pendingTooltip), tooltipX, tooltipY);
            g.pose().popPose();
        }
    }

    private static final int PW = 260, PH = 190;

    private void renderPicker(GuiGraphics g, int mx, int my) {
        g.fill(0, 0, width, height, 0xB0000000);
        int px = (width - PW) / 2, py = (height - PH) / 2;
        g.fill(px, py, px + PW, py + PH, COL_BG);
        border(g, px, py, PW, PH, COL_ACCENT);
        g.fill(px, py, px + PW, py + 14, COL_ACCENT_DIM);
        g.drawCenteredString(font, "Elegir ítem del coste", px + PW / 2, py + 3, 0xFFFFFFFF);

        searchField.draw(g, font, px + 8, py + 18, PW - 16, 13);
        if (searchField.get().isEmpty()) {
            g.drawString(font, "Buscar…  §8@mod  #tag", px + 12, py + 21, COL_DIM, false);
        }

        int gx = px + 8, gy = py + 36, cell = 20, cols = 12, rows = 7;
        int start = scroll * cols;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int idx = start + r * cols + c;
                if (idx >= filtered.size()) break;
                int x = gx + c * cell, y = gy + r * cell;
                boolean hov = in(mx, my, x, y, cell, cell);
                g.fill(x, y, x + cell, y + cell, hov ? 0xFF3A3A55 : 0xFF20202f);
                g.renderItem(filtered.get(idx), x + 2, y + 2);
                if (hov) tooltip(filtered.get(idx).getHoverName().getString(), mx, my);
            }
        }
        g.drawString(font, filtered.size() + " ítems · [ESC] cerrar", px + 8, py + PH - 12, COL_DIM, false);
    }

    // ── Input ────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mxD, double myD, int button) {
        int mx = (int) mxD, my = (int) myD;
        if (button != 0) return super.mouseClicked(mxD, myD, button);

        if (pickerOpen) {
            int px = (width - PW) / 2, py = (height - PH) / 2;
            if (in(mx, my, px + 8, py + 18, PW - 16, 13)) {
                searchField.focus(true);
                return true;
            }
            searchField.focus(false);
            int gx = px + 8, gy = py + 36, cell = 20, cols = 12, rows = 7;
            if (in(mx, my, gx, gy, cols * cell, rows * cell)) {
                int idx = scroll * cols + ((my - gy) / cell) * cols + (mx - gx) / cell;
                if (idx >= 0 && idx < filtered.size() && pickerRow >= 0) {
                    items[pickerRow] = filtered.get(idx).copyWithCount(1);
                    pickerOpen = false;
                }
                return true;
            }
            if (!in(mx, my, px, py, PW, PH)) pickerOpen = false;
            return true;
        }

        // Filas
        int rowY = top + 26;
        for (int i = 0; i < 6; i++) {
            int y = rowY + i * 26;
            if (in(mx, my, left + 252, y + 3, 20, 20)) {
                pickerOpen = true;
                pickerRow = i;
                searchField.set("");
                filter("");
                scroll = 0;
                return true;
            }
            if (in(mx, my, left + 288, y + 4, 40, 16)) {
                unfocusAll();
                amounts[i].focus(true);
                return true;
            }
            if (i == 0 && in(mx, my, left + 360, y + 4, 36, 16)) {
                unfocusAll();
                daysField.focus(true);
                return true;
            }
        }
        unfocusAll();

        int by = top + H - 26;
        if (in(mx, my, left + W - 110, by, 100, 18)) {
            save();
            return true;
        }
        if (in(mx, my, left + 10, by, 80, 18)) {
            onClose();
            return true;
        }
        return super.mouseClicked(mxD, myD, button);
    }

    private void save() {
        List<ItemStack> costItems = new ArrayList<>();
        List<Integer> costAmounts = new ArrayList<>();
        for (int i = 1; i < 6; i++) {
            costItems.add(items[i]);
            costAmounts.add(Math.max(0, parseInt(amounts[i].get(), 0)));
        }
        double days = parseDouble(daysField.get(), 7.0);
        long periodMs = (long) (Math.max(0.01, days) * 86_400_000.0);
        int rentAmount = Math.max(0, parseInt(amounts[0].get(), 0));

        PacketDistributor.sendToServer(new PlayerShopNetworking.SaveShopConfig(
                new PlayerShopNetworking.ShopConfig(
                        rentAmount <= 0 ? ItemStack.EMPTY : items[0], rentAmount, periodMs,
                        costItems, costAmounts)));
        onClose();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (pickerOpen) {
            int cols = 12, rows = 7;
            int totalRows = (filtered.size() + cols - 1) / cols;
            int maxScroll = Math.max(0, totalRows - rows);
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(sy)));
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 256) {
            if (pickerOpen) { pickerOpen = false; return true; }
            onClose();
            return true;
        }
        if (pickerOpen) {
            if (searchField.key(key)) { filter(searchField.get()); return true; }
        } else {
            for (Field f : amounts) if (f.key(key)) return true;
            if (daysField.key(key)) return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (pickerOpen) {
            if (searchField.type(c)) { filter(searchField.get()); return true; }
        } else {
            for (Field f : amounts) if (f.type(c)) return true;
            if (daysField.type(c)) return true;
        }
        return super.charTyped(c, mods);
    }

    private void filter(String query) {
        filtered.clear();
        scroll = 0;
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        if (q.isEmpty()) {
            filtered.addAll(allItems);
            return;
        }
        char prefix = q.charAt(0);
        if (prefix == '@') {
            String term = q.substring(1).trim();
            for (ItemStack s : allItems) {
                if (term.isEmpty() || BuiltInRegistries.ITEM.getKey(s.getItem())
                        .getNamespace().contains(term)) {
                    filtered.add(s);
                }
            }
        } else if (prefix == '#') {
            String term = q.substring(1).trim();
            for (ItemStack s : allItems) {
                try {
                    if (term.isEmpty() ? s.getTags().findAny().isPresent()
                            : s.getTags().anyMatch(t -> t.location().toString().contains(term))) {
                        filtered.add(s);
                    }
                } catch (Throwable ignored) {}
            }
        } else {
            for (ItemStack s : allItems) {
                if (s.getHoverName().getString().toLowerCase(java.util.Locale.ROOT).contains(q)
                        || BuiltInRegistries.ITEM.getKey(s.getItem()).toString().contains(q)) {
                    filtered.add(s);
                }
            }
        }
    }

    private void unfocusAll() {
        for (Field f : amounts) f.focus(false);
        daysField.focus(false);
    }

    private void tooltip(String text, int mx, int my) {
        pendingTooltip = text;
        tooltipX = mx;
        tooltipY = my;
    }

    private static void border(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    private static boolean in(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String s, double fallback) {
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // Campo de texto mínimo (mismo patrón que el menú de jugador)
    private static class Field {
        private final int maxLen;
        private String value = "";
        private boolean focused;

        Field(int maxLen) { this.maxLen = maxLen; }

        void set(String v) { value = v == null ? "" : v; }
        String get() { return value; }
        void focus(boolean f) { focused = f; }

        boolean key(int key) {
            if (!focused) return false;
            if (key == 259 && !value.isEmpty()) {
                value = value.substring(0, value.length() - 1);
                return true;
            }
            return key == 259;
        }

        boolean type(char c) {
            if (!focused || c < ' ' || value.length() >= maxLen) return false;
            value += c;
            return true;
        }

        void draw(GuiGraphics g, Font font, int x, int y, int w, int h) {
            g.fill(x, y, x + w, y + h, 0xFF111122);
            border(g, x, y, w, h, focused ? COL_GOLD : COL_BORDER);
            String shown = value;
            if (focused && (System.currentTimeMillis() / 500) % 2 == 0) shown += "_";
            g.drawString(font, shown, x + 4, y + (h - font.lineHeight) / 2 + 1, COL_TEXT, false);
        }
    }
}
