package com.example.betteradminshop.client;

import com.example.betteradminshop.block.PlayerShopBlockEntity;
import com.example.betteradminshop.block.PlayerShopSlot;
import com.example.betteradminshop.block.StockInventory;
import com.example.betteradminshop.data.PlayerShopSettings;
import com.example.betteradminshop.network.PlayerShopNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * MENÚ de la tienda de jugador (Fase 4) — dirigido a jugadores, con la paleta
 * del modelo: maderas oscuras, cerezo y el rojo del toldo.
 *
 * Vistas:
 *  - MAIN: los dos estantes (slots según tier + botón de mejora por lado),
 *    popup de opciones por slot, estado de renta, accesos a Stock y Jugadores.
 *  - STOCK: inventario de 16 slots con mejoras de capacidad/vacío y purga.
 *  - PICK_ITEM: elegir ítem del stock para ponerlo a la venta.
 *  - PICK_PRICE: panel compacto (no ocupa toda la pantalla) con buscador @/#
 *    y cantidad para fijar el precio.
 *  - MANAGERS: empleados del negocio (solo el dueño).
 */
public class PlayerShopMenuScreen extends Screen {

    private enum View { MAIN, STOCK, PICK_ITEM, MANAGERS }

    private final PlayerShopBlockEntity shop;
    private final BlockPos shopPos;

    // ── Layout ───────────────────────────────────────────────────────────────
    private static final int W = 480;
    private static final int H = 260;
    private int left, top;

    // ── Paleta (del modelo: toldo rojo + cerezo + roble oscuro) ──────────────
    private static final int COL_BG          = 0xFF261A10;   // roble oscuro
    private static final int COL_PANEL       = 0xFF382718;   // madera media
    private static final int COL_PANEL_LIGHT = 0xFF4A3421;   // cerezo claro
    private static final int COL_INNER       = 0xFF2E2013;
    private static final int COL_ACCENT      = 0xFFB03434;   // rojo del toldo
    private static final int COL_ACCENT_DARK = 0xFF7E2424;
    private static final int COL_BORDER      = 0xFF5C4026;
    private static final int COL_CREAM       = 0xFFEFE2C4;   // texto principal
    private static final int COL_CREAM_DIM   = 0xFFB9A88A;
    private static final int COL_GOLD        = 0xFFE8B84C;
    private static final int COL_GREEN       = 0xFF7BC96F;
    private static final int COL_RED         = 0xFFFF6B5E;
    private static final int COL_SLOT_BG     = 0xFF57422C;
    private static final int COL_SLOT_HOVER  = 0xFF6B5236;
    private static final int COL_SLOT_SEL    = 0xFF8A5C33;

    // ── Estado ───────────────────────────────────────────────────────────────
    private View view = View.MAIN;

    /** Popup de opciones de slot (MAIN). -1 = cerrado. */
    private int optionsSlot = -1;
    private int optionsX, optionsY;

    /** PICK_ITEM: slot destino + selección. */
    private int pickTargetSlot = -1;
    private ItemStack pickSelected = ItemStack.EMPTY;
    private Field sellAmountField;

    /** PICK_PRICE (overlay sobre MAIN): estado del panel. */
    private boolean priceOverlay = false;
    private int priceTargetSlot = -1;
    private ItemStack priceSelected = ItemStack.EMPTY;
    /** Segundo precio, OPCIONAL: si se pone, el comprador paga ambos ítems. */
    private ItemStack priceSelected2 = ItemStack.EMPTY;
    /** Fila de precio (0 o 1) a la que van las selecciones del grid. */
    private int priceEditIndex = 0;
    /**
     * Ítem elegido en PICK_ITEM cuando el slot AÚN no tiene precio: se aplica
     * junto con el precio al confirmar el overlay (nada se vende gratis).
     */
    private ItemStack pendingSale = ItemStack.EMPTY;
    private Field priceSearchField, priceAmountField, priceAmountField2;
    private final List<ItemStack> priceAll = new ArrayList<>();
    private final List<ItemStack> priceFiltered = new ArrayList<>();
    private int priceScroll = 0;

    /** STOCK: slot seleccionado. */
    private int stockSelected = -1;

    /** MANAGERS: campo de nombre. */
    private Field managerField;

    /** Portapapeles de slot (persiste con el juego abierto). */
    private static ItemStack clipSale = ItemStack.EMPTY;
    private static ItemStack clipPrice = ItemStack.EMPTY;
    private static int clipPriceAmount = 1;
    private static ItemStack clipPrice2 = ItemStack.EMPTY;
    private static int clipPriceAmount2 = 1;
    /** La rotación viaja con el producto al copiar/pegar. */
    private static int clipRotation = 0;

    /** Arrastre de slots para reorganizar. */
    private int dragSlot = -1;
    private boolean dragging = false;
    private double pressX, pressY;

    /**
     * Distribución 2D de las bandejas por tier, ESPEJO de la tienda real
     * (col 0 = izquierda, 1 = derecha, 0.5 = centro · fila 0 = arriba, 1 = abajo).
     * Derivada de las posiciones físicas de LEFT_TRAYS / RIGHT_TRAYS.
     */
    private static final float[][][] LEFT_LAYOUT = {
            {{0.5f, 0}, {0.5f, 1}},                          // 2 slots: arriba / abajo
            {{0f, 1}, {0.5f, 0}, {1f, 1}},                   // 3: triángulo (1 arriba, 2 abajo)
            {{0f, 1}, {0f, 0}, {1f, 1}, {1f, 0}}             // 4: cuadrícula 2×2
    };
    private static final float[][][] RIGHT_LAYOUT = {
            {{0.5f, 0}, {0.5f, 1}},
            {{0f, 1}, {0.5f, 0}, {1f, 1}},
            {{0f, 1}, {0f, 0}, {1f, 0}, {1f, 1}}             // 4: espejo del izquierdo
    };

    private static final int SLOT_SIZE = 42;

    private String pendingTooltip;
    private int tooltipX, tooltipY;

    /** Ítems no accesibles en survival (no aparecen en el selector de precio). */
    private static final Set<Item> ADMIN_ONLY = Set.of(
            Items.COMMAND_BLOCK, Items.CHAIN_COMMAND_BLOCK, Items.REPEATING_COMMAND_BLOCK,
            Items.COMMAND_BLOCK_MINECART, Items.BARRIER, Items.STRUCTURE_BLOCK,
            Items.STRUCTURE_VOID, Items.JIGSAW, Items.LIGHT, Items.DEBUG_STICK,
            Items.SPAWNER, Items.TRIAL_SPAWNER, Items.VAULT, Items.BEDROCK,
            Items.END_PORTAL_FRAME, Items.REINFORCED_DEEPSLATE, Items.KNOWLEDGE_BOOK,
            Items.PETRIFIED_OAK_SLAB);

    public PlayerShopMenuScreen(PlayerShopBlockEntity shop) {
        super(Component.literal("Tienda de " + shop.getOwnerName()));
        this.shop = shop;
        this.shopPos = shop.getBlockPos();
    }

    public static void open(PlayerShopBlockEntity shop) {
        Minecraft.getInstance().setScreen(new PlayerShopMenuScreen(shop));
    }

    @Override
    protected void init() {
        super.init();
        left = (width - W) / 2;
        top = (height - H) / 2;

        sellAmountField = new Field(5);
        sellAmountField.set("1");
        priceSearchField = new Field(40);
        priceAmountField = new Field(5);
        priceAmountField.set("1");
        priceAmountField2 = new Field(5);
        priceAmountField2.set("1");
        managerField = new Field(16);

        // Ítems survival para el selector de precio
        priceAll.clear();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR || ADMIN_ONLY.contains(item)) continue;
            if (item instanceof SpawnEggItem) continue;
            priceAll.add(new ItemStack(item));
        }
        priceFiltered.clear();
        priceFiltered.addAll(priceAll);
    }

    // ═══════════════════════ RENDER ═══════════════════════

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0xCC120B06);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        pendingTooltip = null;
        renderBackground(g, mx, my, pt);

        // Marco principal
        g.fill(left + 3, top + 3, left + W + 3, top + H + 3, 0x66000000);
        border(g, left - 1, top - 1, W + 2, H + 2, COL_ACCENT_DARK);
        g.fill(left, top, left + W, top + H, COL_BG);

        // Toldo (barra de título con "flecos" rojos)
        g.fill(left, top, left + W, top + 20, COL_ACCENT);
        g.fill(left, top + 17, left + W, top + 20, COL_ACCENT_DARK);
        for (int x = left; x < left + W; x += 24) {
            g.fill(x + 4, top + 20, x + 20, top + 24, COL_ACCENT_DARK);
        }
        g.drawCenteredString(font, "✦ Tienda de " + shop.getOwnerName() + " ✦",
                left + W / 2, top + 6, COL_CREAM);

        switch (view) {
            case MAIN -> renderMain(g, mx, my);
            case STOCK -> renderStock(g, mx, my);
            case PICK_ITEM -> renderPickItem(g, mx, my);
            case MANAGERS -> renderManagers(g, mx, my);
        }

        if (priceOverlay) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 350);
            renderPriceOverlay(g, mx, my);
            g.pose().popPose();
        } else if (view == View.MAIN && optionsSlot >= 0) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            renderOptionsPopup(g, mx, my);
            g.pose().popPose();
        }

        if (pendingTooltip != null) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            g.renderTooltip(font, Component.literal(pendingTooltip), tooltipX, tooltipY);
            g.pose().popPose();
        }
    }

    // ── Vista MAIN ───────────────────────────────────────────────────────────

    private void renderMain(GuiGraphics g, int mx, int my) {
        renderShelfPanel(g, mx, my, true, left + 10, top + 32);
        renderShelfPanel(g, mx, my, false, left + 245, top + 32);

        // ── Barra de estado (renta) ──────────────────────────────────────────
        long now = System.currentTimeMillis();
        int sy = top + 168;
        g.fill(left + 10, sy, left + W - 10, sy + 26, COL_PANEL);
        border(g, left + 10, sy, W - 20, 26, COL_BORDER);
        if (shop.rentAmountInfo() <= 0) {
            g.drawString(font, "Renta: §agratis §7(no configurada por administración)",
                    left + 18, sy + 9, COL_CREAM_DIM, false);
        } else if (shop.isOperational(now)) {
            long days = Math.max(0, shop.getRentPaidUntilMs() - now) / 86_400_000L;
            long hours = (Math.max(0, shop.getRentPaidUntilMs() - now) % 86_400_000L) / 3_600_000L;
            g.drawString(font, "§aRenta al día §7— vence en §f" + days + "d " + hours + "h",
                    left + 18, sy + 9, COL_CREAM, false);
        } else {
            g.drawString(font, "§c⚠ RENTA PENDIENTE §7— la tienda está cerrada a los compradores",
                    left + 18, sy + 9, COL_CREAM, false);
        }

        // ── Botonera inferior ────────────────────────────────────────────────
        int by = top + H - 40;
        // Stock (icono de cofre)
        boolean hovStock = in(mx, my, left + 10, by, 96, 28);
        panelButton(g, left + 10, by, 96, 28, hovStock);
        g.renderItem(new ItemStack(Items.CHEST), left + 16, by + 6);
        g.drawString(font, "Stock", left + 38, by + 10, hovStock ? 0xFFFFFFFF : COL_CREAM, false);
        if (hovStock) tooltip("Inventario del stock de la tienda", mx, my);

        // Jugadores (solo dueño)
        if (isOwnerLocal()) {
            boolean hovMg = in(mx, my, left + 112, by, 110, 28);
            panelButton(g, left + 112, by, 110, 28, hovMg);
            g.renderItem(new ItemStack(Items.PLAYER_HEAD), left + 118, by + 6);
            g.drawString(font, "Jugadores", left + 140, by + 10, hovMg ? 0xFFFFFFFF : COL_CREAM, false);
            if (hovMg) tooltip("Añadir jugadores al negocio (podrán gestionar la tienda)", mx, my);
        }

        // Pagar renta — solo cuando está PENDIENTE (pagada → desaparece)
        if (shop.rentAmountInfo() > 0 && !shop.isOperational(now)) {
            String label = "Pagar renta: " + shop.rentAmountInfo() + "× "
                    + shop.rentItemInfo().getHoverName().getString();
            int w = font.width(label) + 30;
            int bx = left + W - 10 - w;
            boolean hov = in(mx, my, bx, by, w, 28);
            g.fill(bx, by, bx + w, by + 28, hov ? COL_ACCENT : COL_ACCENT_DARK);
            border(g, bx, by, w, 28, COL_ACCENT);
            g.renderItem(shop.rentItemInfo(), bx + 6, by + 6);
            g.drawString(font, label, bx + 26, by + 10, COL_CREAM, false);
            if (hov) {
                long daysCov = shop.rentPeriodInfo() / 86_400_000L;
                tooltip("Cubre " + daysCov + " días de operación", mx, my);
            }
        }

        // Ítem arrastrado siguiendo al cursor
        if (dragging && dragSlot >= 0) {
            PlayerShopSlot src = shop.getSlot(dragSlot);
            if (src != null && !src.isEmpty()) {
                g.pose().pushPose();
                g.pose().translate(0, 0, 320);
                g.renderItem(src.getSaleItem(), mx - 8, my - 8);
                g.pose().popPose();
            }
        }
    }

    private void renderShelfPanel(GuiGraphics g, int mx, int my, boolean leftShelf, int px, int py) {
        int pw = 225, ph = 130;
        g.fill(px, py, px + pw, py + ph, COL_PANEL);
        border(g, px, py, pw, ph, COL_BORDER);
        g.fill(px, py, px + pw, py + 16, COL_PANEL_LIGHT);

        int tier = leftShelf ? shop.getLeftTier() : shop.getRightTier();
        String title = (leftShelf ? "Estante Izquierdo" : "Estante Derecho") + " · " + tier + "/4";
        g.drawString(font, title, px + 8, py + 4, COL_GOLD, false);

        // Botón mejorar
        if (tier < 4) {
            String up = "Mejorar ➜ " + (tier + 1);
            int bw = font.width(up) + 12;
            int bx = px + pw - bw - 6, byy = py + 2;
            boolean hov = in(mx, my, bx, byy, bw, 12);
            g.fill(bx, byy, bx + bw, byy + 12, hov ? COL_ACCENT : COL_ACCENT_DARK);
            g.drawString(font, up, bx + 6, byy + 2, COL_CREAM, false);
            if (hov) {
                var cost = shop.upgradeCostInfo(tier == 2
                        ? PlayerShopSettings.UP_SHELF3 : PlayerShopSettings.UP_SHELF4);
                tooltip(cost == null ? "Añade 1 slot a este estante"
                        : "Añade 1 slot · Coste: " + cost.amount() + "× "
                        + cost.item().getHoverName().getString(), mx, my);
            }
        } else {
            g.drawString(font, "MÁX", px + pw - 30, py + 4, COL_GREEN, false);
        }

        // Slots en la MISMA distribución que la tienda real (por tier).
        int base = leftShelf ? 0 : PlayerShopBlockEntity.SLOTS_PER_SHELF;
        float[][] layout = (leftShelf ? LEFT_LAYOUT : RIGHT_LAYOUT)[tier - 2];
        for (int i = 0; i < layout.length; i++) {
            int slotIndex = base + i;
            int x = slotX(px, layout[i][0]);
            int y = slotY(py, layout[i][1]);
            PlayerShopSlot slot = shop.getSlot(slotIndex);
            boolean hov = in(mx, my, x, y, SLOT_SIZE, SLOT_SIZE);
            boolean dragTarget = dragging && hov && slotIndex != dragSlot;
            boolean isDragSource = dragging && slotIndex == dragSlot;

            g.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE,
                    optionsSlot == slotIndex ? COL_SLOT_SEL : (hov ? COL_SLOT_HOVER : COL_SLOT_BG));
            border(g, x, y, SLOT_SIZE, SLOT_SIZE,
                    dragTarget || optionsSlot == slotIndex ? COL_GOLD : COL_BORDER);
            if (dragTarget) border(g, x + 1, y + 1, SLOT_SIZE - 2, SLOT_SIZE - 2, COL_GOLD);

            if (slot != null && !slot.isEmpty() && !isDragSource) {
                g.renderItem(slot.getSaleItem(), x + 13, y + 6);
                if (!slot.getPriceItem().isEmpty()) {
                    g.pose().pushPose();
                    g.pose().translate(x + 5, y + 25, 0);
                    g.pose().scale(0.7f, 0.7f, 1f);
                    g.renderItem(slot.getPriceItem(), 0, 0);
                    g.pose().popPose();
                    g.drawString(font, "×" + slot.getPriceAmount(), x + 18, y + 28, COL_GOLD, false);
                    // Con dos precios no cabe el segundo ícono: se marca con "+"
                    // y el detalle completo va en el tooltip.
                    if (slot.hasSecondPrice()) {
                        g.drawString(font, "+", x + SLOT_SIZE - 9, y + 28, COL_GOLD, true);
                    }
                }
                int stockAvail = shop.stockFor(slotIndex);
                if (stockAvail < slot.getSellAmount()) {
                    g.fill(x + 1, y + 1, x + SLOT_SIZE - 1, y + SLOT_SIZE - 1, 0x66FF0000);
                }
                if (hov && !dragging) {
                    StringBuilder tip = new StringBuilder(slot.getSellAmount() + "× "
                            + slot.getSaleItem().getHoverName().getString());
                    if (!slot.getPriceItem().isEmpty()) {
                        tip.append(" · Precio: ").append(slot.getPriceAmount()).append("× ")
                           .append(slot.getPriceItem().getHoverName().getString());
                        if (slot.hasSecondPrice()) {
                            tip.append(" + ").append(slot.getPriceAmount2()).append("× ")
                               .append(slot.getPriceItem2().getHoverName().getString());
                        }
                    }
                    tip.append(" · Stock: ").append(stockAvail).append(" · arrastra para mover");
                    tooltip(tip.toString(), mx, my);
                }
            } else if (slot == null || slot.isEmpty()) {
                g.drawCenteredString(font, "+", x + SLOT_SIZE / 2, y + SLOT_SIZE / 2 - 4, COL_CREAM_DIM);
                if (hov && !dragging) tooltip("Clic: opciones del slot", mx, my);
            }
        }

        // Pie del panel
        g.drawString(font, "Clic: opciones · arrastrar: reorganizar", px + 8, py + ph - 12, COL_CREAM_DIM, false);
    }

    /** X del slot dentro del panel según su columna (0 izq · 0.5 centro · 1 der). */
    private static int slotX(int panelX, float col) {
        int col0 = panelX + 46, col1 = panelX + 137; // 2 columnas dentro de 225px
        return Math.round(col0 + (col1 - col0) * col);
    }

    /** Y del slot según su fila (0 arriba · 1 abajo). */
    private static int slotY(int panelY, float row) {
        return panelY + 22 + Math.round(row * (SLOT_SIZE + 6));
    }

    private static final String[] OPTION_LABELS = {
            "Modificar ítem", "Modificar precio", "Rotar ítem",
            "Limpiar slot", "Copiar slot", "Pegar slot"};

    private void renderOptionsPopup(GuiGraphics g, int mx, int my) {
        int w = 110, rowH = 15;
        int rows = OPTION_LABELS.length;
        int x = Math.min(optionsX, width - w - 4);
        int y = Math.min(optionsY, height - rows * rowH - 8);
        g.fill(x + 2, y + 2, x + w + 2, y + rows * rowH + 6 + 2, 0x88000000);
        g.fill(x, y, x + w, y + rows * rowH + 6, COL_PANEL_LIGHT);
        border(g, x, y, w, rows * rowH + 6, COL_GOLD);
        for (int i = 0; i < rows; i++) {
            int ry = y + 3 + i * rowH;
            boolean disabled = (i == 5 && clipSale.isEmpty());
            boolean hov = !disabled && in(mx, my, x + 2, ry, w - 4, rowH);
            if (hov) g.fill(x + 2, ry, x + w - 2, ry + rowH, COL_ACCENT);
            int col = disabled ? 0xFF80735C : (hov ? 0xFFFFFFFF : COL_CREAM);
            g.drawString(font, OPTION_LABELS[i], x + 8, ry + 3, col, false);
        }
    }

    // ── Vista STOCK ──────────────────────────────────────────────────────────

    private void renderStock(GuiGraphics g, int mx, int my) {
        backButton(g, mx, my);
        g.drawString(font, "📦 Inventario del Stock", left + 60, top + 28, COL_GOLD, false);

        StockInventory stock = shop.getStock();
        int cap = stock.capacityPerSlot();

        // Grid 8×2
        int gx = left + 14, gy = top + 46, size = 42, gap = 8;
        for (int i = 0; i < StockInventory.SLOTS; i++) {
            int col = i % 8, row = i / 8;
            int x = gx + col * (size + gap), y = gy + row * (size + gap + 10);
            boolean hov = in(mx, my, x, y, size, size);
            g.fill(x, y, x + size, y + size,
                    stockSelected == i ? COL_SLOT_SEL : (hov ? COL_SLOT_HOVER : COL_SLOT_BG));
            border(g, x, y, size, size, stockSelected == i ? COL_GOLD : COL_BORDER);

            ItemStack proto = stock.getItem(i);
            int count = stock.getCount(i);
            if (!proto.isEmpty()) {
                g.renderItem(proto, x + 13, y + 6);
                String c = count >= 1000 ? String.format("%.1fk", count / 1000.0) : String.valueOf(count);
                g.drawCenteredString(font, c, x + size / 2, y + size - 12, COL_CREAM);
                // barra de llenado
                int barW = (int) ((size - 6) * Math.min(1f, count / (float) cap));
                g.fill(x + 3, y + size - 3, x + 3 + barW, y + size - 1,
                        count >= cap ? COL_RED : COL_GREEN);
                if (hov) tooltip(proto.getHoverName().getString() + " · " + count + "/" + cap, mx, my);
            } else if (hov) {
                tooltip("Slot libre · entra por el chute de import", mx, my);
            }
        }

        // ── Panel de acciones (abajo) ────────────────────────────────────────
        int ay = top + 172;
        g.fill(left + 10, ay, left + W - 10, ay + 76, COL_PANEL);
        border(g, left + 10, ay, W - 20, 76, COL_BORDER);

        // Mejora de capacidad
        int tier = stock.getCapacityTier();
        int bx = left + 20, byy = ay + 10;
        if (tier < StockInventory.MAX_TIER) {
            var cost = shop.upgradeCostInfo(tier == 0
                    ? PlayerShopSettings.UP_STOCK1 : PlayerShopSettings.UP_STOCK2);
            String label = "Capacidad ➜ " + StockInventory.TIER_STACKS[tier + 1] + " stacks/slot";
            int w = font.width(label) + 14;
            boolean hov = in(mx, my, bx, byy, w, 16);
            g.fill(bx, byy, bx + w, byy + 16, hov ? COL_ACCENT : COL_ACCENT_DARK);
            g.drawString(font, label, bx + 7, byy + 4, COL_CREAM, false);
            if (hov && cost != null) {
                tooltip("Cada slot pasa de " + StockInventory.TIER_STACKS[tier] + " a "
                        + StockInventory.TIER_STACKS[tier + 1] + " stacks · Coste: "
                        + cost.amount() + "× " + cost.item().getHoverName().getString(), mx, my);
            }
        } else {
            g.drawString(font, "Capacidad: §a16 stacks/slot (MÁX)", bx, byy + 4, COL_CREAM, false);
        }

        // Mejora de vacío
        int vy = byy + 24;
        if (!stock.hasVoidUpgrade()) {
            var cost = shop.upgradeCostInfo(PlayerShopSettings.UP_VOID);
            String label = "🗑 Mejora de vacío";
            int w = font.width(label) + 14;
            boolean hov = in(mx, my, bx, vy, w, 16);
            g.fill(bx, vy, bx + w, vy + 16, hov ? COL_ACCENT : COL_ACCENT_DARK);
            g.drawString(font, label, bx + 7, vy + 4, COL_CREAM, false);
            if (hov) {
                tooltip("Descarta los ítems que entren cuando su stock ya está lleno"
                        + (cost == null ? "" : " · Coste: " + cost.amount() + "× "
                        + cost.item().getHoverName().getString()), mx, my);
            }
        } else {
            boolean enabled = stock.isVoidEnabled();
            String label = "🗑 Vacío: " + (enabled ? "§aACTIVA" : "§cINACTIVA");
            int w = font.width(label) + 14;
            boolean hov = in(mx, my, bx, vy, w, 16);
            g.fill(bx, vy, bx + w, vy + 16, hov ? COL_ACCENT : COL_PANEL_LIGHT);
            border(g, bx, vy, w, 16, enabled ? COL_GREEN : COL_BORDER);
            g.drawString(font, label, bx + 7, vy + 4, COL_CREAM, false);
            if (hov) {
                tooltip(enabled
                        ? "Descarta los ítems que entren con su stock lleno · clic para DESACTIVAR"
                        : "Los ítems que no quepan rebotarán al ducto · clic para ACTIVAR", mx, my);
            }
        }

        // Purga del slot seleccionado
        int px = left + W / 2 + 20, pyy = ay + 10;
        if (stockSelected >= 0 && !stock.getItem(stockSelected).isEmpty()) {
            ItemStack sel = stock.getItem(stockSelected);
            g.renderItem(sel, px, pyy);
            g.drawString(font, sel.getHoverName().getString(), px + 20, pyy + 4, COL_CREAM, false);
            String purge = "⚠ Purgar este ítem";
            int w = font.width(purge) + 14;
            boolean hov = in(mx, my, px, pyy + 22, w, 16);
            g.fill(px, pyy + 22, px + w, pyy + 38, hov ? COL_ACCENT : COL_ACCENT_DARK);
            g.drawString(font, purge, px + 7, pyy + 26, COL_CREAM, false);
            if (hov) {
                tooltip("Vacía TODO el stock de este ítem por el chute de export en cardboards. "
                        + "Requiere un ducto/tolva conectado.", mx, my);
            }
        } else {
            g.drawString(font, "Selecciona un slot para purgarlo", px, pyy + 8, COL_CREAM_DIM, false);
        }
    }

    // ── Vista PICK_ITEM ──────────────────────────────────────────────────────

    private void renderPickItem(GuiGraphics g, int mx, int my) {
        backButton(g, mx, my);
        g.drawString(font, "Elegir ítem del stock — Slot #" + (pickTargetSlot + 1),
                left + 60, top + 28, COL_GOLD, false);

        List<ItemStack> distinct = distinctStock();
        if (distinct.isEmpty()) {
            g.drawCenteredString(font, "El stock está vacío.", left + W / 2, top + 100, COL_CREAM_DIM);
            g.drawCenteredString(font, "Importa ítems por el chute de import (ducto/tolva).",
                    left + W / 2, top + 114, COL_CREAM_DIM);
            return;
        }

        int gx = left + 20, gy = top + 50, size = 42, gap = 8;
        for (int i = 0; i < distinct.size(); i++) {
            int col = i % 9, row = i / 9;
            int x = gx + col * (size + gap), y = gy + row * (size + gap);
            ItemStack proto = distinct.get(i);
            boolean sel = !pickSelected.isEmpty()
                    && ItemStack.isSameItemSameComponents(pickSelected, proto);
            boolean hov = in(mx, my, x, y, size, size);
            g.fill(x, y, x + size, y + size, sel ? COL_SLOT_SEL : (hov ? COL_SLOT_HOVER : COL_SLOT_BG));
            border(g, x, y, size, size, sel ? COL_GOLD : COL_BORDER);
            g.renderItem(proto, x + 13, y + 13);
            if (hov) {
                tooltip(proto.getHoverName().getString() + " · en stock: "
                        + shop.getStock().countOf(proto), mx, my);
            }
        }

        // Cantidad por venta + confirmar
        int fy = top + H - 42;
        g.drawString(font, "Cantidad por venta:", left + 20, fy + 5, COL_CREAM, false);
        sellAmountField.draw(g, font, left + 130, fy, 46, 16);
        boolean can = !pickSelected.isEmpty();
        String ok = "✔ Confirmar";
        int w = font.width(ok) + 16;
        boolean hov = can && in(mx, my, left + 200, fy, w, 16);
        g.fill(left + 200, fy, left + 200 + w, fy + 16, !can ? COL_INNER : (hov ? COL_ACCENT : COL_ACCENT_DARK));
        g.drawString(font, ok, left + 208, fy + 4, can ? COL_CREAM : 0xFF80735C, false);
    }

    // ── Overlay PICK_PRICE (compacto, no ocupa toda la pantalla) ─────────────

    private static final int PW = 330, PH = 252;

    private void renderPriceOverlay(GuiGraphics g, int mx, int my) {
        g.fill(0, 0, width, height, 0x99000000);
        int px = (width - PW) / 2, py = (height - PH) / 2;
        g.fill(px + 3, py + 3, px + PW + 3, py + PH + 3, 0x88000000);
        g.fill(px, py, px + PW, py + PH, COL_BG);
        border(g, px, py, PW, PH, COL_ACCENT);
        g.fill(px, py, px + PW, py + 16, COL_ACCENT);
        g.drawCenteredString(font, "Precio del Slot #" + (priceTargetSlot + 1),
                px + PW / 2, py + 4, COL_CREAM);

        // Buscador
        priceSearchField.draw(g, font, px + 10, py + 22, PW - 20, 14);
        if (priceSearchField.get().isEmpty()) {
            g.drawString(font, "Buscar…  §8@mod  #tag", px + 14, py + 25, COL_CREAM_DIM, false);
        }

        // Grid 9×4 con scroll
        int gx = px + 10, gy = py + 42, cell = 20, cols = 9, rows = 4;
        int start = priceScroll * cols;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int idx = start + r * cols + c;
                if (idx >= priceFiltered.size()) break;
                int x = gx + c * cell, y = gy + r * cell;
                ItemStack stack = priceFiltered.get(idx);
                boolean sel = !priceSelected.isEmpty()
                        && ItemStack.isSameItemSameComponents(priceSelected, stack);
                boolean hov = in(mx, my, x, y, cell, cell);
                g.fill(x, y, x + cell, y + cell, sel ? COL_SLOT_SEL : (hov ? COL_SLOT_HOVER : COL_INNER));
                if (sel) border(g, x, y, cell, cell, COL_GOLD);
                g.renderItem(stack, x + 2, y + 2);
                if (hov) tooltip(stack.getHoverName().getString(), mx, my);
            }
        }
        // Scrollbar sencillo
        int totalRows = (priceFiltered.size() + cols - 1) / cols;
        if (totalRows > rows) {
            int sbX = px + PW - 8, sbY = gy, sbH = rows * cell;
            g.fill(sbX, sbY, sbX + 4, sbY + sbH, COL_INNER);
            int thumbH = Math.max(8, sbH * rows / totalRows);
            int maxScroll = totalRows - rows;
            int thumbY = sbY + (sbH - thumbH) * Math.min(priceScroll, maxScroll) / Math.max(1, maxScroll);
            g.fill(sbX, thumbY, sbX + 4, thumbY + thumbH, COL_ACCENT);
        }

        // Pista de uso: el grid rellena la fila activa
        g.drawString(font, "Clic en una fila para elegir qué precio editas.",
                px + 10, py + 128, COL_CREAM_DIM, false);

        // Dos filas de precio: la 2ª es opcional y se cobra ADEMÁS de la 1ª
        int row1Y = priceRowY(py, 0), row2Y = priceRowY(py, 1);
        drawPriceRow(g, px, row1Y, 0, priceSelected, priceAmountField, mx, my);
        drawPriceRow(g, px, row2Y, 1, priceSelected2, priceAmountField2, mx, my);

        // Confirmar / cancelar
        int byy = py + PH - 24;
        boolean can = !priceSelected.isEmpty();
        boolean hovOk = can && in(mx, my, px + PW - 110, byy, 100, 16);
        g.fill(px + PW - 110, byy, px + PW - 10, byy + 16,
                !can ? COL_INNER : (hovOk ? COL_ACCENT : COL_ACCENT_DARK));
        g.drawCenteredString(font, "✔ Confirmar", px + PW - 60, byy + 4, can ? COL_CREAM : 0xFF80735C);
        boolean hovNo = in(mx, my, px + 10, byy, 80, 16);
        g.fill(px + 10, byy, px + 90, byy + 16, hovNo ? COL_ACCENT_DARK : COL_PANEL);
        g.drawCenteredString(font, "Cancelar", px + 50, byy + 4, COL_CREAM);
    }

    // Geometría compartida por el render y los clics de las filas de precio
    private static final int PROW_H = 30;
    private static int priceRowY(int py, int index) { return py + PH - 98 + index * (PROW_H + 4); }
    private static int priceRowX(int px) { return px + 58; }
    private static int priceRowW() { return PW - 66; }

    /**
     * Una fila de precio: etiqueta + ítem + cantidad. La fila ACTIVA
     * ({@link #priceEditIndex}) es la que recibe lo que se elija en el grid.
     * La fila 2 lleva además un botón para quitar el precio.
     */
    private void drawPriceRow(GuiGraphics g, int px, int iy, int index,
                              ItemStack sel, Field amountField, int mx, int my) {
        boolean active = priceEditIndex == index;
        int bx = priceRowX(px), bw = priceRowW();

        g.drawString(font, "Precio " + (index + 1), px + 12, iy + 6,
                active ? COL_GOLD : COL_CREAM_DIM, false);
        if (index == 1) {
            g.drawString(font, "§8opcional", px + 12, iy + 17, COL_CREAM_DIM, false);
        }

        g.fill(bx, iy, bx + bw, iy + PROW_H, COL_PANEL);
        border(g, bx, iy, bw, PROW_H, active ? COL_GOLD : COL_BORDER);

        if (sel.isEmpty()) {
            String hint = index == 0 ? "Elige el ítem que cobrarás"
                                     : "Sin segundo precio";
            g.drawString(font, hint, bx + 8, iy + 11, COL_CREAM_DIM, false);
        } else {
            g.renderItem(sel, bx + 6, iy + 7);
            String name = font.plainSubstrByWidth(sel.getHoverName().getString(), bw - 150);
            g.drawString(font, name, bx + 26, iy + 11, COL_CREAM, false);
        }

        g.drawString(font, "Cant:", px + PW - 140, iy + 11, COL_CREAM_DIM, false);
        amountField.draw(g, font, px + PW - 108, iy + 7, 40, 16);

        if (index == 1 && !sel.isEmpty()) {
            boolean hov = in(mx, my, px + PW - 58, iy + 7, 16, 16);
            g.fill(px + PW - 58, iy + 7, px + PW - 42, iy + 23,
                    hov ? COL_ACCENT : COL_INNER);
            g.drawCenteredString(font, "✖", px + PW - 50, iy + 11, COL_CREAM);
            if (hov) tooltip("Quitar el segundo precio", mx, my);
        }
    }

    // ── Vista MANAGERS ───────────────────────────────────────────────────────

    private void renderManagers(GuiGraphics g, int mx, int my) {
        backButton(g, mx, my);
        g.drawString(font, "👥 Jugadores del negocio", left + 60, top + 28, COL_GOLD, false);
        g.drawString(font, "Podrán gestionar la tienda (no pueden añadir a otros).",
                left + 60, top + 40, COL_CREAM_DIM, false);

        int ly = top + 58;
        var managers = shop.getManagers();
        if (managers.isEmpty()) {
            g.drawString(font, "Nadie más tiene acceso todavía.", left + 20, ly + 6, COL_CREAM_DIM, false);
        }
        int i = 0;
        for (var e : managers.entrySet()) {
            int ry = ly + i * 20;
            g.fill(left + 14, ry, left + W - 14, ry + 18, i % 2 == 0 ? COL_PANEL : COL_INNER);
            g.drawString(font, e.getValue(), left + 24, ry + 5, COL_CREAM, false);
            boolean hov = in(mx, my, left + W - 36, ry + 2, 18, 14);
            g.fill(left + W - 36, ry + 2, left + W - 18, ry + 16, hov ? COL_ACCENT : COL_ACCENT_DARK);
            g.drawCenteredString(font, "✕", left + W - 27, ry + 5, COL_CREAM);
            if (hov) tooltip("Quitar a " + e.getValue() + " del negocio", mx, my);
            i++;
        }

        int fy = top + H - 42;
        g.drawString(font, "Nombre:", left + 20, fy + 5, COL_CREAM, false);
        managerField.draw(g, font, left + 70, fy, 140, 16);
        boolean hov = in(mx, my, left + 220, fy, 70, 16);
        g.fill(left + 220, fy, left + 290, fy + 16, hov ? COL_ACCENT : COL_ACCENT_DARK);
        g.drawCenteredString(font, "Añadir", left + 255, fy + 4, COL_CREAM);
        g.drawString(font, "§8TAB completa · ENTER añade", left + 300, fy + 5, COL_CREAM_DIM, false);

        // Sugerencias mientras escribes (jugadores conectados), hacia arriba
        if (managerField.isFocused()) {
            List<String> sugg = managerSuggestions();
            if (!sugg.isEmpty()) {
                int sw = 140, rowH = 13;
                int sx = left + 70;
                int syTop = fy - sugg.size() * rowH - 2;
                g.pose().pushPose();
                g.pose().translate(0, 0, 320);
                g.fill(sx - 1, syTop - 1, sx + sw + 1, fy - 1, COL_GOLD);
                g.fill(sx, syTop, sx + sw, fy - 2, 0xF01C1209);
                for (int s = 0; s < sugg.size(); s++) {
                    int ry = syTop + s * rowH;
                    boolean rowHov = in(mx, my, sx, ry, sw, rowH);
                    if (rowHov) g.fill(sx, ry, sx + sw, ry + rowH, COL_ACCENT);
                    g.drawString(font, sugg.get(s), sx + 5, ry + 3,
                            rowHov ? 0xFFFFFFFF : COL_CREAM, false);
                }
                g.pose().popPose();
            }
        }
    }

    /**
     * Sugerencias de nombres: jugadores CONECTADOS que empiezan por lo escrito,
     * excluyendo al dueño y a los que ya están en el negocio. Máximo 5.
     */
    private List<String> managerSuggestions() {
        var conn = Minecraft.getInstance().getConnection();
        if (conn == null) return List.of();
        String typed = managerField.get().trim().toLowerCase(java.util.Locale.ROOT);
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (String n : shop.getManagers().values()) taken.add(n.toLowerCase(java.util.Locale.ROOT));
        if (!shop.getOwnerName().isEmpty()) taken.add(shop.getOwnerName().toLowerCase(java.util.Locale.ROOT));
        List<String> out = new ArrayList<>();
        for (var info : conn.getOnlinePlayers()) {
            String name = info.getProfile().getName();
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (taken.contains(lower)) continue;
            if (typed.isEmpty() || lower.startsWith(typed)) out.add(name);
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out.size() > 5 ? out.subList(0, 5) : out;
    }

    /** Envía el alta del jugador escrito en el campo (si hay algo). */
    private void addManagerFromField() {
        String name = managerField.get().trim();
        if (!name.isEmpty()) {
            PlayerShopNetworking.sendManager(shopPos, true, name);
            managerField.set("");
        }
    }

    // ═══════════════════════ INPUT ═══════════════════════

    @Override
    public boolean mouseClicked(double mxD, double myD, int button) {
        int mx = (int) mxD, my = (int) myD;
        if (button != 0) return super.mouseClicked(mxD, myD, button);

        if (priceOverlay) return clickPriceOverlay(mx, my);

        switch (view) {
            case MAIN -> { if (clickMain(mx, my)) return true; }
            case STOCK -> { if (clickStock(mx, my)) return true; }
            case PICK_ITEM -> { if (clickPickItem(mx, my)) return true; }
            case MANAGERS -> { if (clickManagers(mx, my)) return true; }
        }
        return super.mouseClicked(mxD, myD, button);
    }

    private boolean clickMain(int mx, int my) {
        // Popup de opciones abierto
        if (optionsSlot >= 0) {
            int w = 110, rowH = 15, rows = OPTION_LABELS.length;
            int x = Math.min(optionsX, width - w - 4);
            int y = Math.min(optionsY, height - rows * rowH - 8);
            if (in(mx, my, x, y, w, rows * rowH + 6)) {
                int idx = (my - y - 3) / rowH;
                if (idx >= 0 && idx < rows) runSlotOption(idx);
                return true;
            }
            // Clic fuera → cerrar (y quizá abrir en otro slot)
            optionsSlot = -1;
        }

        // Slots de estantes: vacío → opciones directas; con contenido → se "arma"
        // un arrastre (si sueltas sin mover, se abren las opciones).
        Integer hit = shelfSlotAt(mx, my);
        if (hit != null) {
            PlayerShopSlot s = shop.getSlot(hit);
            if (s == null || s.isEmpty()) {
                optionsSlot = hit;
                optionsX = mx + 4;
                optionsY = my + 4;
            } else {
                dragSlot = hit;
                dragging = false;
                pressX = mx;
                pressY = my;
            }
            return true;
        }

        // Botonera inferior
        int by = top + H - 40;
        if (in(mx, my, left + 10, by, 96, 28)) {
            view = View.STOCK;
            stockSelected = -1;
            return true;
        }
        if (isOwnerLocal() && in(mx, my, left + 112, by, 110, 28)) {
            view = View.MANAGERS;
            return true;
        }
        if (shop.rentAmountInfo() > 0 && !shop.isOperational(System.currentTimeMillis())) {
            String label = "Pagar renta: " + shop.rentAmountInfo() + "× "
                    + shop.rentItemInfo().getHoverName().getString();
            int w = font.width(label) + 30;
            if (in(mx, my, left + W - 10 - w, by, w, 28)) {
                PlayerShopNetworking.sendPayRent(shopPos);
                return true;
            }
        }

        // Botones "Mejorar" de estantes
        if (clickShelfUpgrade(mx, my, true, left + 10, top + 32)) return true;
        if (clickShelfUpgrade(mx, my, false, left + 245, top + 32)) return true;

        return false;
    }

    private boolean clickShelfUpgrade(int mx, int my, boolean leftShelf, int px, int py) {
        int tier = leftShelf ? shop.getLeftTier() : shop.getRightTier();
        if (tier >= 4) return false;
        String up = "Mejorar ➜ " + (tier + 1);
        int bw = font.width(up) + 12;
        int bx = px + 225 - bw - 6;
        if (in(mx, my, bx, py + 2, bw, 12)) {
            PlayerShopNetworking.sendUpgrade(shopPos, leftShelf ? 0 : 1);
            return true;
        }
        return false;
    }

    private Integer shelfSlotAt(int mx, int my) {
        for (int side = 0; side < 2; side++) {
            boolean leftShelf = side == 0;
            int px = leftShelf ? left + 10 : left + 245;
            int py = top + 32;
            int tier = leftShelf ? shop.getLeftTier() : shop.getRightTier();
            int base = leftShelf ? 0 : PlayerShopBlockEntity.SLOTS_PER_SHELF;
            float[][] layout = (leftShelf ? LEFT_LAYOUT : RIGHT_LAYOUT)[tier - 2];
            for (int i = 0; i < layout.length; i++) {
                int x = slotX(px, layout[i][0]);
                int y = slotY(py, layout[i][1]);
                if (in(mx, my, x, y, SLOT_SIZE, SLOT_SIZE)) return base + i;
            }
        }
        return null;
    }

    private void runSlotOption(int option) {
        int slot = optionsSlot;
        optionsSlot = -1;
        PlayerShopSlot s = shop.getSlot(slot);
        if (s == null) return;
        switch (option) {
            case 0 -> { // Modificar ítem
                pickTargetSlot = slot;
                pickSelected = s.isEmpty() ? ItemStack.EMPTY : s.getSaleItem().copyWithCount(1);
                sellAmountField.set(String.valueOf(s.isEmpty() ? 1 : s.getSellAmount()));
                view = View.PICK_ITEM;
            }
            case 1 -> { // Modificar precio
                if (s.isEmpty()) {
                    msg("§eAsigna primero un ítem a la venta en este slot.");
                    return;
                }
                priceTargetSlot = slot;
                priceSelected = s.getPriceItem().copyWithCount(1);
                priceAmountField.set(String.valueOf(s.getPriceAmount()));
                priceSelected2 = s.getPriceItem2().copyWithCount(1);
                priceAmountField2.set(String.valueOf(s.getPriceAmount2()));
                priceEditIndex = 0;
                priceSearchField.set("");
                filterPrice("");
                priceScroll = 0;
                priceOverlay = true;
            }
            case 2 -> { // Rotar (giro horizontal, 90° por clic)
                if (!s.isEmpty()) {
                    PlayerShopNetworking.sendRotateSlot(shopPos, slot);
                    s.rotate(); // reflejo inmediato
                } else {
                    msg("§eEste slot no tiene ningún ítem para rotar.");
                }
            }
            case 3 -> { // Limpiar
                PlayerShopNetworking.sendClearSlot(shopPos, slot);
                s.clear(); // reflejo inmediato
            }
            case 4 -> { // Copiar
                if (!s.isEmpty()) {
                    clipSale = s.getSaleItem().copyWithCount(s.getSellAmount());
                    clipPrice = s.getPriceItem().copy();
                    clipPriceAmount = s.getPriceAmount();
                    clipPrice2 = s.getPriceItem2().copy();
                    clipPriceAmount2 = s.getPriceAmount2();
                    clipRotation = s.getRotation();
                    msg("§aSlot copiado.");
                }
            }
            case 5 -> { // Pegar
                if (!clipSale.isEmpty()) {
                    PlayerShopNetworking.sendSetSlot(shopPos, slot, clipSale, clipPrice,
                            clipPriceAmount, clipPrice2, clipPriceAmount2, clipRotation);
                    s.setSaleItem(clipSale);
                    s.setPriceItem(clipPrice);
                    s.setPriceAmount(clipPriceAmount);
                    s.setPriceItem2(clipPrice2);
                    s.setPriceAmount2(clipPriceAmount2);
                    s.setRotation(clipRotation);
                }
            }
        }
    }

    private boolean clickStock(int mx, int my) {
        if (clickBack(mx, my)) return true;
        StockInventory stock = shop.getStock();

        int gx = left + 14, gy = top + 46, size = 42, gap = 8;
        for (int i = 0; i < StockInventory.SLOTS; i++) {
            int col = i % 8, row = i / 8;
            int x = gx + col * (size + gap), y = gy + row * (size + gap + 10);
            if (in(mx, my, x, y, size, size)) {
                stockSelected = (stockSelected == i) ? -1 : i;
                return true;
            }
        }

        int ay = top + 172, bx = left + 20, byy = ay + 10;
        int tier = stock.getCapacityTier();
        if (tier < StockInventory.MAX_TIER) {
            String label = "Capacidad ➜ " + StockInventory.TIER_STACKS[tier + 1] + " stacks/slot";
            if (in(mx, my, bx, byy, font.width(label) + 14, 16)) {
                PlayerShopNetworking.sendUpgrade(shopPos, 2);
                return true;
            }
        }
        int vy = byy + 24;
        if (!stock.hasVoidUpgrade()) {
            String label = "🗑 Mejora de vacío";
            if (in(mx, my, bx, vy, font.width(label) + 14, 16)) {
                PlayerShopNetworking.sendUpgrade(shopPos, 3);
                return true;
            }
        } else {
            String label = "🗑 Vacío: " + (stock.isVoidEnabled() ? "§aACTIVA" : "§cINACTIVA");
            if (in(mx, my, bx, vy, font.width(label) + 14, 16)) {
                PlayerShopNetworking.sendUpgrade(shopPos, 4);
                stock.setVoidEnabled(!stock.isVoidEnabled()); // reflejo inmediato
                return true;
            }
        }
        if (stockSelected >= 0 && !stock.getItem(stockSelected).isEmpty()) {
            int px = left + W / 2 + 20, pyy = ay + 10;
            String purge = "⚠ Purgar este ítem";
            if (in(mx, my, px, pyy + 22, font.width(purge) + 14, 16)) {
                PlayerShopNetworking.sendPurge(shopPos, stockSelected);
                return true;
            }
        }
        return false;
    }

    private boolean clickPickItem(int mx, int my) {
        if (clickBack(mx, my)) return true;
        List<ItemStack> distinct = distinctStock();
        int gx = left + 20, gy = top + 50, size = 42, gap = 8;
        for (int i = 0; i < distinct.size(); i++) {
            int col = i % 9, row = i / 9;
            int x = gx + col * (size + gap), y = gy + row * (size + gap);
            if (in(mx, my, x, y, size, size)) {
                pickSelected = distinct.get(i).copyWithCount(1);
                return true;
            }
        }
        int fy = top + H - 42;
        if (in(mx, my, left + 130, fy, 46, 16)) {
            sellAmountField.focus(true);
            return true;
        }
        sellAmountField.focus(false);
        if (!pickSelected.isEmpty()) {
            int w = font.width("✔ Confirmar") + 16;
            if (in(mx, my, left + 200, fy, w, 16)) {
                int amt = Math.max(1, parseInt(sellAmountField.get(), 1));
                PlayerShopSlot s = shop.getSlot(pickTargetSlot);
                ItemStack sale = pickSelected.copyWithCount(amt);
                if (s != null && !s.getPriceItem().isEmpty()) {
                    // Ya tiene precio(s) → aplicar de una (conserva rotación)
                    PlayerShopNetworking.sendSetSlot(shopPos, pickTargetSlot, sale,
                            s.getPriceItem(), s.getPriceAmount(),
                            s.getPriceItem2(), s.getPriceAmount2(), s.getRotation());
                    s.setSaleItem(sale); // reflejo inmediato
                    view = View.MAIN;
                } else {
                    // Sin precio: nada se vende gratis → encadenar al selector de
                    // precio; el ítem se aplica JUNTO con el precio al confirmar.
                    pendingSale = sale;
                    priceTargetSlot = pickTargetSlot;
                    priceSelected = ItemStack.EMPTY;
                    priceAmountField.set("1");
                    priceSelected2 = ItemStack.EMPTY;
                    priceAmountField2.set("1");
                    priceEditIndex = 0;
                    priceSearchField.set("");
                    filterPrice("");
                    priceScroll = 0;
                    priceOverlay = true;
                    view = View.MAIN;
                    msg("§eFija el precio para ponerlo a la venta.");
                }
                return true;
            }
        }
        return false;
    }

    private boolean clickPriceOverlay(int mx, int my) {
        int px = (width - PW) / 2, py = (height - PH) / 2;
        if (in(mx, my, px + 10, py + 22, PW - 20, 14)) {
            priceSearchField.focus(true);
            return true;
        }
        priceSearchField.focus(false);

        int gx = px + 10, gy = py + 42, cell = 20, cols = 9, rows = 4;
        if (in(mx, my, gx, gy, cols * cell, rows * cell)) {
            int idx = priceScroll * cols + ((my - gy) / cell) * cols + (mx - gx) / cell;
            if (idx >= 0 && idx < priceFiltered.size()) {
                ItemStack chosen = priceFiltered.get(idx).copyWithCount(1);
                // Los dos precios deben ser ítems distintos: si fuera el mismo,
                // sería un único cobro partido en dos.
                ItemStack other = priceEditIndex == 0 ? priceSelected2 : priceSelected;
                if (!other.isEmpty() && ItemStack.isSameItemSameComponents(other, chosen)) {
                    msg("§eLos dos precios deben ser ítems distintos.");
                } else if (priceEditIndex == 0) {
                    priceSelected = chosen;
                } else {
                    priceSelected2 = chosen;
                }
            }
            return true;
        }

        // Filas de precio: seleccionar la activa, enfocar cantidad, quitar la 2ª
        for (int i = 0; i < 2; i++) {
            int iy = priceRowY(py, i);
            Field amountField = i == 0 ? priceAmountField : priceAmountField2;
            if (i == 1 && !priceSelected2.isEmpty()
                    && in(mx, my, px + PW - 58, iy + 7, 16, 16)) {
                priceSelected2 = ItemStack.EMPTY;
                priceAmountField2.set("1");
                priceEditIndex = 1;
                return true;
            }
            if (in(mx, my, px + PW - 108, iy + 7, 40, 16)) {
                priceEditIndex = i;
                amountField.focus(true);
                (i == 0 ? priceAmountField2 : priceAmountField).focus(false);
                return true;
            }
            if (in(mx, my, px + 10, iy, PW - 20, PROW_H)) {
                priceEditIndex = i;
                priceAmountField.focus(false);
                priceAmountField2.focus(false);
                return true;
            }
        }
        priceAmountField.focus(false);
        priceAmountField2.focus(false);

        int byy = py + PH - 24;
        if (!priceSelected.isEmpty() && in(mx, my, px + PW - 110, byy, 100, 16)) {
            int amt = Math.max(1, parseInt(priceAmountField.get(), 1));
            int amt2 = Math.max(1, parseInt(priceAmountField2.get(), 1));
            PlayerShopSlot s = shop.getSlot(priceTargetSlot);
            if (!pendingSale.isEmpty()) {
                // Ítem nuevo + precio: se aplican JUNTOS (nada gratis)
                PlayerShopNetworking.sendSetSlot(shopPos, priceTargetSlot,
                        pendingSale, priceSelected, amt, priceSelected2, amt2,
                        s == null ? 0 : s.getRotation());
                if (s != null) {
                    s.setSaleItem(pendingSale); // reflejo inmediato
                    s.setPriceItem(priceSelected);
                    s.setPriceAmount(amt);
                    s.setPriceItem2(priceSelected2);
                    s.setPriceAmount2(amt2);
                }
                pendingSale = ItemStack.EMPTY;
            } else if (s != null && !s.isEmpty()) {
                PlayerShopNetworking.sendSetSlot(shopPos, priceTargetSlot,
                        s.getSaleItem().copyWithCount(s.getSellAmount()),
                        priceSelected, amt, priceSelected2, amt2, s.getRotation());
                s.setPriceItem(priceSelected); // reflejo inmediato
                s.setPriceAmount(amt);
                s.setPriceItem2(priceSelected2);
                s.setPriceAmount2(amt2);
            }
            priceOverlay = false;
            return true;
        }
        if (in(mx, my, px + 10, byy, 80, 16)) {
            cancelPriceOverlay();
            return true;
        }
        if (!in(mx, my, px, py, PW, PH)) {
            cancelPriceOverlay();
        }
        return true;
    }

    /** Cierra el overlay de precio; si venía un ítem pendiente, NO se pone a la venta. */
    private void cancelPriceOverlay() {
        priceOverlay = false;
        if (!pendingSale.isEmpty()) {
            pendingSale = ItemStack.EMPTY;
            msg("§eNo se puso a la venta: falta fijar el precio.");
        }
    }

    private boolean clickManagers(int mx, int my) {
        int fy = top + H - 42;

        // Clic en una sugerencia → autocompleta el campo
        if (managerField.isFocused()) {
            List<String> sugg = managerSuggestions();
            if (!sugg.isEmpty()) {
                int sw = 140, rowH = 13;
                int sx = left + 70;
                int syTop = fy - sugg.size() * rowH - 2;
                if (in(mx, my, sx, syTop, sw, sugg.size() * rowH)) {
                    int idx = (my - syTop) / rowH;
                    if (idx >= 0 && idx < sugg.size()) {
                        managerField.set(sugg.get(idx));
                    }
                    return true;
                }
            }
        }

        if (clickBack(mx, my)) return true;
        int ly = top + 58, i = 0;
        List<java.util.UUID> ids = new ArrayList<>(shop.getManagers().keySet());
        for (java.util.UUID id : ids) {
            int ry = ly + i * 20;
            if (in(mx, my, left + W - 36, ry + 2, 18, 14)) {
                PlayerShopNetworking.sendManager(shopPos, false, id.toString());
                shop.getManagers().remove(id); // reflejo inmediato
                return true;
            }
            i++;
        }
        if (in(mx, my, left + 70, fy, 140, 16)) {
            managerField.focus(true);
            return true;
        }
        managerField.focus(false);
        if (in(mx, my, left + 220, fy, 70, 16)) {
            addManagerFromField();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button == 0 && dragSlot >= 0 && !dragging
                && (Math.abs(mx - pressX) > 3 || Math.abs(my - pressY) > 3)) {
            dragging = true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mxD, double myD, int button) {
        if (button == 0 && dragSlot >= 0 && view == View.MAIN && !priceOverlay) {
            int mx = (int) mxD, my = (int) myD;
            int source = dragSlot;
            boolean wasDragging = dragging;
            dragSlot = -1;
            dragging = false;

            if (wasDragging) {
                Integer target = shelfSlotAt(mx, my);
                if (target != null && target != source) {
                    PlayerShopNetworking.sendSwapSlots(shopPos, source, target);
                    shop.swapShopSlots(source, target); // reflejo inmediato
                }
            } else {
                // Clic sin arrastre → abrir opciones del slot
                optionsSlot = source;
                optionsX = mx + 4;
                optionsY = my + 4;
            }
            return true;
        }
        dragSlot = -1;
        dragging = false;
        return super.mouseReleased(mxD, myD, button);
    }

    // ── Scroll / teclado ─────────────────────────────────────────────────────

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (priceOverlay) {
            int cols = 9, rows = 4;
            int totalRows = (priceFiltered.size() + cols - 1) / cols;
            int maxScroll = Math.max(0, totalRows - rows);
            priceScroll = Math.max(0, Math.min(maxScroll, priceScroll - (int) Math.signum(sy)));
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 256) { // ESC
            if (priceOverlay) { cancelPriceOverlay(); return true; }
            if (optionsSlot >= 0) { optionsSlot = -1; return true; }
            if (view != View.MAIN) { view = View.MAIN; return true; }
            onClose();
            return true;
        }
        if (priceOverlay) {
            if (priceSearchField.key(key)) { filterPrice(priceSearchField.get()); return true; }
            if (priceAmountField.key(key)) return true;
            if (priceAmountField2.key(key)) return true;
        } else if (view == View.PICK_ITEM && sellAmountField.key(key)) {
            return true;
        } else if (view == View.MANAGERS) {
            if (managerField.isFocused()) {
                if (key == 258) { // TAB → autocompletar con la primera sugerencia
                    List<String> sugg = managerSuggestions();
                    if (!sugg.isEmpty()) managerField.set(sugg.get(0));
                    return true;
                }
                if (key == 257 || key == 335) { // ENTER → añadir
                    addManagerFromField();
                    return true;
                }
            }
            if (managerField.key(key)) return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (priceOverlay) {
            if (priceSearchField.type(c)) { filterPrice(priceSearchField.get()); return true; }
            if (priceAmountField.type(c)) return true;
            if (priceAmountField2.type(c)) return true;
        } else if (view == View.PICK_ITEM && sellAmountField.type(c)) {
            return true;
        } else if (view == View.MANAGERS && managerField.type(c)) {
            return true;
        }
        return super.charTyped(c, mods);
    }

    // ═══════════════════════ HELPERS ═══════════════════════

    /** Filtro del selector de precio: @mod, #tag o subcadena de nombre/id. */
    private void filterPrice(String query) {
        priceFiltered.clear();
        priceScroll = 0;
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        if (q.isEmpty()) {
            priceFiltered.addAll(priceAll);
            return;
        }
        char prefix = q.charAt(0);
        if (prefix == '@') {
            String term = q.substring(1).trim();
            for (ItemStack s : priceAll) {
                if (term.isEmpty() || BuiltInRegistries.ITEM.getKey(s.getItem())
                        .getNamespace().contains(term)) {
                    priceFiltered.add(s);
                }
            }
        } else if (prefix == '#') {
            String term = q.substring(1).trim();
            for (ItemStack s : priceAll) {
                try {
                    if (term.isEmpty() ? s.getTags().findAny().isPresent()
                            : s.getTags().anyMatch(t -> t.location().toString().contains(term))) {
                        priceFiltered.add(s);
                    }
                } catch (Throwable ignored) {}
            }
        } else {
            for (ItemStack s : priceAll) {
                if (s.getHoverName().getString().toLowerCase(java.util.Locale.ROOT).contains(q)
                        || BuiltInRegistries.ITEM.getKey(s.getItem()).toString().contains(q)) {
                    priceFiltered.add(s);
                }
            }
        }
    }

    /** Prototipos distintos presentes en el stock (para PICK_ITEM). */
    private List<ItemStack> distinctStock() {
        List<ItemStack> out = new ArrayList<>();
        StockInventory stock = shop.getStock();
        for (int i = 0; i < StockInventory.SLOTS; i++) {
            ItemStack p = stock.getItem(i);
            if (p.isEmpty() || stock.getCount(i) <= 0) continue;
            boolean dup = false;
            for (ItemStack o : out) {
                if (ItemStack.isSameItemSameComponents(o, p)) { dup = true; break; }
            }
            if (!dup) out.add(p.copyWithCount(1));
        }
        return out;
    }

    private boolean isOwnerLocal() {
        var p = Minecraft.getInstance().player;
        return p != null && (shop.isOwner(p) || p.hasPermissions(2));
    }

    private void backButton(GuiGraphics g, int mx, int my) {
        boolean hov = in(mx, my, left + 10, top + 25, 42, 14);
        g.fill(left + 10, top + 25, left + 52, top + 39, hov ? COL_ACCENT : COL_PANEL);
        border(g, left + 10, top + 25, 42, 14, COL_BORDER);
        g.drawCenteredString(font, "← Volver", left + 31, top + 28, COL_CREAM);
    }

    private boolean clickBack(int mx, int my) {
        if (in(mx, my, left + 10, top + 25, 42, 14)) {
            view = View.MAIN;
            return true;
        }
        return false;
    }

    private void panelButton(GuiGraphics g, int x, int y, int w, int h, boolean hov) {
        g.fill(x, y, x + w, y + h, hov ? COL_PANEL_LIGHT : COL_PANEL);
        border(g, x, y, w, h, hov ? COL_GOLD : COL_BORDER);
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

    private void tooltip(String text, int mx, int my) {
        pendingTooltip = text;
        tooltipX = mx;
        tooltipY = my;
    }

    private void msg(String text) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(Component.literal(text), true);
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ── Campo de texto mínimo ────────────────────────────────────────────────

    private static class Field {
        private final int maxLen;
        private String value = "";
        private boolean focused;
        private int x, y, w, h;

        Field(int maxLen) { this.maxLen = maxLen; }

        void set(String v) { value = v == null ? "" : v; }
        String get() { return value; }
        void focus(boolean f) { focused = f; }
        boolean isFocused() { return focused; }

        boolean key(int key) {
            if (!focused) return false;
            if (key == 259 && !value.isEmpty()) { // backspace
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
            this.x = x; this.y = y; this.w = w; this.h = h;
            g.fill(x, y, x + w, y + h, 0xFF1C1209);
            border(g, x, y, w, h, focused ? COL_GOLD : COL_BORDER);
            String shown = value;
            if (focused && (System.currentTimeMillis() / 500) % 2 == 0) shown += "_";
            g.drawString(font, shown, x + 4, y + (h - font.lineHeight) / 2 + 1, COL_CREAM, false);
        }
    }
}
