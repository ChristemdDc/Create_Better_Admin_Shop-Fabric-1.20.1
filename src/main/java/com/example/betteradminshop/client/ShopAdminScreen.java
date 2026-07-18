package com.example.betteradminshop.client;

import com.example.betteradminshop.block.ShopBlockEntity;
import com.example.betteradminshop.block.ShopSlot;
import com.example.betteradminshop.network.ModNetworking;
import com.example.betteradminshop.network.RequestDynamicItemsPayload;

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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Panel de administración de la tienda.
 *
 * Al seleccionar un slot vacío se muestran dos botones grandes: VENTA (la
 * tienda vende al jugador) y COMPRA (la tienda le compra al jugador). Al elegir
 * uno se abre el selector de ítem y luego el panel de configuración de dos
 * columnas, con etiquetas adaptadas al tipo:
 *   - VENTA:  "En venta" / "Precio" (lo que paga el jugador)
 *   - COMPRA: "Se compra" / "Paga" (lo que la tienda le paga al jugador)
 *
 * La configuración se envía al servidor con "Guardar" en un único paquete.
 */
public class ShopAdminScreen extends Screen {

    private enum Mode { NORMAL, PICKING_SALE_ITEM, PICKING_RENDER_ITEM, PICKING_PRICE_ITEM, PICKING_PRICE_ITEM_2 }

    private final ShopBlockEntity shopBE;
    private final BlockPos shopPos;

    // Layout
    private static final int GUI_WIDTH = 470;
    private static final int GUI_HEIGHT = 256;
    private static final int LEFT_PANEL_W = 110;
    private int leftX, topY;

    // Geometría del panel de configuración (calculada en init)
    private int cfgTopY, cfgLeftX, cfgRightX, cfgDivX, cfgLeftW, cfgRightW;

    // State
    private Mode currentMode = Mode.NORMAL;
    private int selectedSlot = -1;
    /** Tipo elegido para el slot actual; null = mostrando los botones Venta/Compra. */
    private ShopSlot.Type cfgType = null;

    // ── Configuración editable (copia local; se envía con "Guardar") ─────────
    private ItemStack cfgSaleItem = ItemStack.EMPTY;
    private ItemStack cfgRenderOverride = ItemStack.EMPTY;
    private ItemStack cfgPriceItem = ItemStack.EMPTY;
    private ItemStack cfgPriceItem2 = ItemStack.EMPTY;

    // Custom text fields (no Minecraft EditBox)
    private CustomTextField stockField, sellAmountField, price1AmountField, price2AmountField;

    // Custom buttons (no Minecraft Button)
    private CustomButton saleItemBtn, renderItemBtn, renderResetBtn;
    private CustomButton price1Btn, price2Btn, price2RemoveBtn;
    private CustomButton saveBtn, restockBtn, clearBtn;

    // Botones grandes de elección de tipo (slot vacío)
    private int ventaRectX, compraRectX, bigRectY, bigRectH;
    private int ventaRectW, compraRectW;

    // Slot grid
    private final List<SlotWidget> slotWidgets = new ArrayList<>();

    // Item picker
    private static final int PICKER_WIDTH = 240;
    private static final int PICKER_HEIGHT = 210;
    private static final int PICKER_COLS = 10;
    private static final int PICKER_SLOT_SIZE = 20;
    private CustomTextField pickerSearchField;
    private final List<ItemStack> allItems = new ArrayList<>();
    private final List<ItemStack> filteredItems = new ArrayList<>();
    private int pickerScrollOffset = 0;
    private int pickerX, pickerY;

    // Colors
    // NOTA: en 1.21+ Screen.renderBackground aplica un blur al mundo. Si los
    // colores de panel usan alpha < 0xFF, el blur del mundo se cuela y todo
    // el contenido del menu se ve "borroso". Por eso 0xFF en los fondos.
    private static final int COL_BG = 0xFF181825;
    private static final int COL_BG_INNER = 0xFF222235;
    private static final int COL_BG_FIELD = 0xFF1A1A2C;
    private static final int COL_ACCENT = 0xFF6C63FF;
    private static final int COL_ACCENT_DIM = 0xFF4A4499;
    private static final int COL_TEXT = 0xFFE0E0E0;
    private static final int COL_TEXT_DIM = 0xFF888899;
    private static final int COL_GREEN = 0xFF55DD55;
    private static final int COL_RED = 0xFFFF5555;
    private static final int COL_YELLOW = 0xFFFFDD55;
    private static final int COL_SLOT_BG = 0xFF2A2A40;
    private static final int COL_SLOT_HOVER = 0xFF3A3A55;
    private static final int COL_SLOT_SELECTED = 0xFF4A4A77;
    private static final int COL_BORDER = 0xFF444466;
    private static final int COL_DIRTY = 0xFFFFAA33;
    // Colores por tipo (coinciden con records y HUD)
    private static final int COL_VENTA     = 0xFF55DD55;
    private static final int COL_VENTA_BG   = 0xFF1E3A24;
    private static final int COL_VENTA_DIM  = 0xFF2C5233;
    private static final int COL_COMPRA     = 0xFF66BBFF;
    private static final int COL_COMPRA_BG  = 0xFF1D2E44;
    private static final int COL_COMPRA_DIM = 0xFF294863;

    public ShopAdminScreen(ShopBlockEntity shopBE) {
        super(Component.literal("Administración de Tienda"));
        this.shopBE = shopBE;
        this.shopPos = shopBE.getBlockPos();
    }

    public static void open(ShopBlockEntity shopBE) {
        Minecraft.getInstance().setScreen(new ShopAdminScreen(shopBE));
    }

    @Override
    protected void init() {
        super.init();
        leftX = (width - GUI_WIDTH) / 2;
        topY = (height - GUI_HEIGHT) / 2;

        rebuildAllItems();
        filteredItems.clear();
        filteredItems.addAll(allItems);

        pickerX = (width - PICKER_WIDTH) / 2;
        pickerY = (height - PICKER_HEIGHT) / 2;

        initConfigWidgets();
        initSlotGrid();
        initPickerSearch();

        // Pedir al servidor la lista actual de ítems dinámicos (los creados en
        // juego por otros mods). La respuesta llega por DynamicItemsPayload y
        // dispara refreshItemList().
        RequestDynamicItemsPayload.sendToServer();

        // Mantener la selección al redimensionar
        if (selectedSlot >= 0) {
            loadConfigFromSlot(selectedSlot);
        }
    }

    /** Reconstruye la lista del selector: ítems del registro + dinámicos. */
    private void rebuildAllItems() {
        allItems.clear();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item != Items.AIR) {
                allItems.add(new ItemStack(item));
            }
        }
        // Ítems dinámicos (creados en juego por otros mods), sincronizados desde
        // el servidor. Se muestran primero para que sean fáciles de encontrar.
        List<ItemStack> dynamic = ClientDynamicItems.get();
        if (!dynamic.isEmpty()) {
            List<ItemStack> merged = new ArrayList<>(dynamic.size() + allItems.size());
            for (ItemStack s : dynamic) {
                if (!s.isEmpty()) merged.add(s.copyWithCount(1));
            }
            merged.addAll(allItems);
            allItems.clear();
            allItems.addAll(merged);
        }
    }

    /** Llamado cuando llega la lista de ítems dinámicos desde el servidor. */
    public void refreshItemList() {
        rebuildAllItems();
        filterItems(pickerSearchField != null ? pickerSearchField.getValue() : "");
    }

    // ── Layout del panel derecho ──────────────────────────────────────────────

    private int panelX() { return leftX + LEFT_PANEL_W; }
    private int panelW() { return GUI_WIDTH - LEFT_PANEL_W; }

    private int lRow(int i) { return cfgTopY + 30 + i * 30; }
    private int rRow(int i) { return cfgTopY + 30 + i * 40; }

    private void initConfigWidgets() {
        cfgTopY   = topY + 22;
        cfgDivX   = panelX() + 186;
        cfgLeftX  = panelX() + 10;
        cfgLeftW  = cfgDivX - 6 - cfgLeftX;               // ~170
        cfgRightX = cfgDivX + 10;
        cfgRightW = panelX() + panelW() - 12 - cfgRightX; // ~152

        // Botones grandes (slot vacío) — mismo ancho, centrados y simétricos
        bigRectY = cfgTopY + 34;
        bigRectH = (GUI_HEIGHT - 27) - 34 - 8;
        int areaLeft = cfgLeftX;
        int areaRight = panelX() + panelW() - 12;
        int gap = 14;
        int btnW = (areaRight - areaLeft - gap) / 2;
        ventaRectX  = areaLeft;
        ventaRectW  = btnW;
        compraRectX = areaLeft + btnW + gap;
        compraRectW = btnW;

        // ── Columna izquierda: ítem / render / cantidad / stock ──────────────
        saleItemBtn = new CustomButton("Cambiar", cfgLeftX + cfgLeftW - 52, lRow(0) + 9, 52, 16,
                () -> openPicker(Mode.PICKING_SALE_ITEM));

        renderItemBtn = new CustomButton("Elegir", cfgLeftX + cfgLeftW - 96, lRow(1) + 9, 44, 16,
                () -> openPicker(Mode.PICKING_RENDER_ITEM));
        renderResetBtn = new CustomButton("Real", cfgLeftX + cfgLeftW - 48, lRow(1) + 9, 48, 16,
                () -> cfgRenderOverride = ItemStack.EMPTY);

        sellAmountField = new CustomTextField(6);
        sellAmountField.setBounds(cfgLeftX, lRow(2) + 9, 44, 16);
        sellAmountField.setValue("1");

        stockField = new CustomTextField(8);
        stockField.setBounds(cfgLeftX, lRow(3) + 9, 44, 16);
        stockField.setValue("-1");

        // ── Columna derecha: precios / acciones ──────────────────────────────
        int pAmtW = 34;
        price1Btn = new CustomButton("Cambiar", cfgRightX + cfgRightW - 52, rRow(0) + 9, 52, 16,
                () -> openPicker(Mode.PICKING_PRICE_ITEM));
        price1AmountField = new CustomTextField(6);
        price1AmountField.setBounds(cfgRightX + cfgRightW - 52 - pAmtW - 4, rRow(0) + 10, pAmtW, 14);
        price1AmountField.setValue("1");

        price2Btn = new CustomButton("Elegir", cfgRightX + cfgRightW - 52, rRow(1) + 9, 52, 16,
                () -> openPicker(Mode.PICKING_PRICE_ITEM_2));
        price2AmountField = new CustomTextField(6);
        price2AmountField.setBounds(cfgRightX + cfgRightW - 52 - pAmtW - 4, rRow(1) + 10, pAmtW, 14);
        price2AmountField.setValue("1");
        // Botón para quitar el 2º precio (en la línea de la etiqueta)
        price2RemoveBtn = new CustomButton("✕ quitar", cfgRightX + cfgRightW - 50, rRow(1), 50, 9,
                () -> cfgPriceItem2 = ItemStack.EMPTY);

        int actY = rRow(2) + 6;
        int halfW = (cfgRightW - 4) / 2;
        saveBtn    = new CustomButton("✔ Guardar", cfgRightX, actY, cfgRightW, 18, this::saveConfig);
        restockBtn = new CustomButton("Restock", cfgRightX, actY + 22, halfW, 16, this::restockSelected);
        clearBtn   = new CustomButton("Vaciar", cfgRightX + halfW + 4, actY + 22, halfW, 16, this::clearSelected);
    }

    private void initSlotGrid() {
        slotWidgets.clear();
        int slotSize = 24;
        int cols = 3;

        int g1x = leftX + 16;
        int g1y = topY + 36;
        for (int i = 0; i < ShopBlockEntity.SLOTS_PER_GROUP; i++) {
            int row = i / cols;
            int col = i % cols;
            slotWidgets.add(new SlotWidget(i, g1x + col * slotSize, g1y + row * slotSize, slotSize));
        }

        int g2x = leftX + 16;
        int g2y = topY + 146;
        for (int i = 0; i < ShopBlockEntity.SLOTS_PER_GROUP; i++) {
            int idx = ShopBlockEntity.SLOTS_PER_GROUP + i;
            int row = i / cols;
            int col = i % cols;
            slotWidgets.add(new SlotWidget(idx, g2x + col * slotSize, g2y + row * slotSize, slotSize));
        }
    }

    private void initPickerSearch() {
        pickerSearchField = new CustomTextField(50);
        pickerSearchField.setBounds(pickerX + 10, pickerY + 22, PICKER_WIDTH - 20, 14);
        pickerSearchField.setResponder(this::filterItems);
    }

    private void openPicker(Mode mode) {
        if (selectedSlot < 0) return;
        if (mode != Mode.PICKING_SALE_ITEM && cfgSaleItem.isEmpty()) return;
        currentMode = mode;
        pickerScrollOffset = 0;
        filterItems("");
        pickerSearchField.setValue("");
        pickerSearchField.setFocused(true);
    }

    private void filterItems(String query) {
        filteredItems.clear();
        pickerScrollOffset = 0;
        if (query == null || query.isEmpty()) {
            filteredItems.addAll(allItems);
        } else {
            String lower = query.toLowerCase();
            for (ItemStack stack : allItems) {
                String name = stack.getHoverName().getString().toLowerCase();
                // También busca por id de registro ("minecraft:stone") para
                // ítems modded cuyo nombre no está traducido.
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (name.contains(lower) || id.contains(lower)) {
                    filteredItems.add(stack);
                }
            }
        }
    }

    // ==================== RENDERING ====================

    /**
     * Override renderBackground to prevent the 1.21+ Gaussian blur effect.
     * Instead of calling super (which calls renderBlurredBackground), we draw a
     * solid translucent overlay so the world is darkened without blur.
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xCC000000);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);

        drawPanel(g, leftX, topY, GUI_WIDTH, GUI_HEIGHT);

        drawAccentBar(g, leftX, topY, GUI_WIDTH, 20);
        g.drawCenteredString(font, "★ Administración de Tienda ★", leftX + GUI_WIDTH / 2, topY + 6, 0xFFFFFF);

        drawSectionHeader(g, leftX + 12, topY + 25, "▾ Estante Izquierdo");
        drawSectionHeader(g, leftX + 12, topY + 135, "▾ Estante Derecho");

        for (SlotWidget sw : slotWidgets) {
            ShopSlot slot = shopBE.getSlot(sw.slotIndex);
            renderSlotWidget(g, sw, slot, sw.slotIndex == selectedSlot, mouseX, mouseY);
        }

        renderConfigArea(g, mouseX, mouseY);

        if (currentMode == Mode.NORMAL) {
            for (SlotWidget sw : slotWidgets) {
                if (mouseX >= sw.x && mouseX < sw.x + sw.size && mouseY >= sw.y && mouseY < sw.y + sw.size) {
                    ShopSlot slot = shopBE.getSlot(sw.slotIndex);
                    if (slot != null && !slot.isEmpty()) {
                        renderSlotSummaryTooltip(g, slot, mouseX, mouseY);
                    }
                }
            }
        }

        if (currentMode != Mode.NORMAL) {
            // Elevar el selector en Z: en 1.21 el texto/ítems se dibujan en
            // lotes diferidos, así que sin esto el contenido del panel de
            // configuración (texto) se cuela por encima del fondo del selector.
            g.pose().pushPose();
            g.pose().translate(0, 0, 350);
            renderPickerOverlay(g, mouseX, mouseY);
            g.pose().popPose();
        }
    }

    /** Tooltip resumido al pasar el mouse por un slot del estante. */
    private void renderSlotSummaryTooltip(GuiGraphics g, ShopSlot slot, int mouseX, int mouseY) {
        List<Component> lines = new ArrayList<>();
        boolean compra = slot.isCompra();
        lines.add(Component.literal((compra ? "§b● Compra §r" : "§a● Venta §r")
                + slot.getDisplayItem().getHoverName().getString()));
        lines.add(Component.literal("§7" + (compra ? "Pide: §fx" : "Vende: §fx") + slot.getSellAmount()));
        if (!slot.getPriceItem().isEmpty()) {
            lines.add(Component.literal("§7" + (compra ? "Paga: §e" : "Precio: §e")
                    + slot.getPriceAmount() + "× " + slot.getPriceItem().getHoverName().getString()));
        }
        if (slot.hasSecondPrice()) {
            lines.add(Component.literal("§7        + §e" + slot.getPriceAmount2() + "× "
                    + slot.getPriceItem2().getHoverName().getString()));
        }
        if (slot.hasInfiniteStock()) {
            lines.add(Component.literal("§7" + (compra ? "Cupo: §a∞" : "Stock: §a∞")));
        } else {
            lines.add(Component.literal("§7" + (compra ? "Cupo: §a" : "Stock: §a")
                    + slot.getMaxStock() + " §7por jugador §8(24h)"));
        }
        if (slot.hasRenderOverride()) {
            lines.add(Component.literal("§8Se muestra como: "
                    + slot.getRenderOverride().getHoverName().getString()));
        }
        g.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    private void drawPanel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x + 2, y + 2, x + w + 2, y + h + 2, 0x80000000);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, COL_BORDER);
        g.fill(x, y, x + w, y + h, COL_BG);
    }

    private void drawAccentBar(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, COL_ACCENT_DIM);
        g.fill(x, y + h - 1, x + w, y + h, COL_ACCENT);
    }

    private void drawSectionHeader(GuiGraphics g, int x, int y, String text) {
        g.drawString(font, text, x, y, COL_YELLOW);
    }

    private void renderSlotWidget(GuiGraphics g, SlotWidget sw, ShopSlot slot,
                                  boolean selected, int mouseX, int mouseY) {
        boolean hovered = mouseX >= sw.x && mouseX < sw.x + sw.size &&
                mouseY >= sw.y && mouseY < sw.y + sw.size && currentMode == Mode.NORMAL;

        int bg = selected ? COL_SLOT_SELECTED : (hovered ? COL_SLOT_HOVER : COL_SLOT_BG);

        // Borde del color del tipo si el slot está configurado
        int borderColor = COL_BORDER;
        if (slot != null && !slot.isEmpty()) borderColor = slot.isCompra() ? COL_COMPRA : COL_VENTA;
        if (selected) borderColor = COL_ACCENT;
        g.fill(sw.x, sw.y, sw.x + sw.size, sw.y + sw.size, borderColor);
        g.fill(sw.x + 1, sw.y + 1, sw.x + sw.size - 1, sw.y + sw.size - 1, bg);

        if (slot != null && !slot.isEmpty()) {
            g.renderItem(slot.getRenderItem(), sw.x + 4, sw.y + 4);
            // Punto indicador de tipo (esquina superior izquierda)
            int dot = slot.isCompra() ? COL_COMPRA : COL_VENTA;
            g.fill(sw.x + 2, sw.y + 2, sw.x + 5, sw.y + 5, dot);
        } else {
            g.drawCenteredString(font, "+", sw.x + sw.size / 2, sw.y + sw.size / 2 - 4, COL_TEXT_DIM);
        }
    }

    // ── Área de configuración (derecha) ───────────────────────────────────────

    private void renderConfigArea(GuiGraphics g, int mouseX, int mouseY) {
        int px = panelX();
        int py = cfgTopY;
        int pw = panelW();
        int ph = GUI_HEIGHT - 27;

        g.fill(px, py, px + pw - 5, py + ph, COL_BG_INNER);
        g.fill(px, py, px + 1, py + ph, COL_BORDER);

        if (selectedSlot < 0) {
            g.drawCenteredString(font, "Selecciona un slot del estante", px + pw / 2, py + ph / 2 - 8, COL_TEXT_DIM);
            g.drawCenteredString(font, "para configurarlo", px + pw / 2, py + ph / 2 + 4, COL_TEXT_DIM);
            return;
        }

        // Encabezado
        String slotLabel = "Slot #" + (selectedSlot + 1)
                + (selectedSlot < ShopBlockEntity.SLOTS_PER_GROUP ? " (Estante Izq.)" : " (Estante Der.)");
        g.drawString(font, slotLabel, cfgLeftX, py + 8, COL_ACCENT);
        if (cfgType != null) {
            drawTypeBadge(g, px + pw - 12, py + 7, cfgType == ShopSlot.Type.COMPRA);
            if (isDirty()) {
                String dirty = "● sin guardar";
                g.drawString(font, dirty, px + pw - 70 - font.width(dirty), py + 8, COL_DIRTY);
            }
        }
        g.fill(cfgLeftX, py + 19, px + pw - 14, py + 20, COL_BORDER);

        if (cfgType == null) {
            renderChoiceButtons(g, mouseX, mouseY);
        } else {
            renderConfigColumns(g, mouseX, mouseY);
        }
    }

    /** Badge de tipo alineado a la derecha, terminando en {@code rightX}. */
    private void drawTypeBadge(GuiGraphics g, int rightX, int y, boolean compra) {
        String label = compra ? "COMPRA" : "VENTA";
        int w = font.width(label) + 8;
        int x = rightX - w;
        g.fill(x, y, x + w, y + 11, compra ? COL_COMPRA_BG : COL_VENTA_BG);
        g.fill(x, y, x + w, y + 1, compra ? COL_COMPRA : COL_VENTA);
        g.fill(x, y + 10, x + w, y + 11, compra ? COL_COMPRA : COL_VENTA);
        g.drawString(font, label, x + 4, y + 2, compra ? COL_COMPRA : COL_VENTA, false);
    }

    // ── Botones grandes de elección de tipo ───────────────────────────────────

    private void renderChoiceButtons(GuiGraphics g, int mouseX, int mouseY) {
        drawChoiceButton(g, ventaRectX, ventaRectW, false, mouseX, mouseY,
                "VENTA", new ItemStack(Items.EMERALD),
                new String[]{"La tienda VENDE", "un ítem al jugador.", "", "El jugador paga", "el precio indicado."});
        drawChoiceButton(g, compraRectX, compraRectW, true, mouseX, mouseY,
                "COMPRA", new ItemStack(Items.CHEST),
                new String[]{"La tienda COMPRA", "un ítem al jugador.", "", "El jugador recibe", "el pago indicado."});
    }

    private void drawChoiceButton(GuiGraphics g, int x, int w, boolean compra, int mouseX, int mouseY,
                                  String title, ItemStack icon, String[] desc) {
        int y = bigRectY, h = bigRectH;
        boolean hov = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        int accent = compra ? COL_COMPRA : COL_VENTA;
        int bg = hov ? (compra ? COL_COMPRA_DIM : COL_VENTA_DIM) : (compra ? COL_COMPRA_BG : COL_VENTA_BG);

        // Fondo + borde
        g.fill(x, y, x + w, y + h, bg);
        drawBorder(g, x, y, w, h, accent);
        if (hov) drawBorder(g, x + 1, y + 1, w - 2, h - 2, accent);

        // Icono grande centrado arriba
        int cx = x + w / 2;
        int iconY = y + 22;
        g.pose().pushPose();
        g.pose().translate(cx - 16, iconY, 0);
        g.pose().scale(2f, 2f, 1f);
        g.renderItem(icon, 0, 0);
        g.pose().popPose();

        // Título grande
        int titleY = iconY + 40;
        g.pose().pushPose();
        g.pose().translate(cx, titleY, 0);
        g.pose().scale(1.6f, 1.6f, 1f);
        g.drawCenteredString(font, title, 0, 0, accent);
        g.pose().popPose();

        // Descripción
        int dy = titleY + 24;
        for (String line : desc) {
            if (!line.isEmpty()) {
                g.drawCenteredString(font, line, cx, dy, COL_TEXT_DIM);
            }
            dy += 11;
        }

        // Pista de clic al final
        g.drawCenteredString(font, hov ? "▶ Clic para elegir" : "", cx, y + h - 14, accent);
    }

    // ── Panel de configuración (dos columnas) ─────────────────────────────────

    private void renderConfigColumns(GuiGraphics g, int mouseX, int mouseY) {
        boolean compra = cfgType == ShopSlot.Type.COMPRA;

        // Divisor entre columnas
        g.fill(cfgDivX, cfgTopY + 26, cfgDivX + 1, cfgTopY + (GUI_HEIGHT - 27) - 6, COL_BORDER);

        // ── Columna izquierda ────────────────────────────────────────────────
        // Ítem
        drawColLabel(g, cfgLeftX, lRow(0), compra ? "Se compra:" : "En venta:");
        drawItemValue(g, cfgLeftX, lRow(0) + 9, cfgSaleItem, saleItemBtn.x - 4, COL_TEXT, "(elige un ítem)", COL_TEXT_DIM);
        saleItemBtn.draw(g, font, mouseX, mouseY);

        // Render override
        drawColLabel(g, cfgLeftX, lRow(1), "Mostrar como:");
        drawItemValue(g, cfgLeftX, lRow(1) + 9, cfgRenderOverride, renderItemBtn.x - 4, COL_TEXT,
                "(el mismo ítem)", COL_TEXT_DIM);
        renderItemBtn.draw(g, font, mouseX, mouseY);
        if (!cfgRenderOverride.isEmpty()) renderResetBtn.draw(g, font, mouseX, mouseY);

        // Cantidad por transacción
        drawColLabel(g, cfgLeftX, lRow(2), compra ? "Cant. por compra:" : "Cant. por venta:");
        sellAmountField.draw(g, font);
        g.drawString(font, compra ? "u. que pide" : "u. por compra", cfgLeftX + 50, lRow(2) + 13, COL_TEXT_DIM);

        // Stock / cupo (por jugador, reabastece cada 24h)
        drawColLabel(g, cfgLeftX, lRow(3), compra ? "Cupo/jugador:" : "Stock/jugador:");
        stockField.draw(g, font);
        int maxStockVal = parseInt(stockField.getValue(), ShopSlot.INFINITE_STOCK);
        String stockInfo;
        int stockColor;
        if (maxStockVal < 0) {
            stockInfo = compra ? "∞ ilimitado" : "∞ infinito";
            stockColor = COL_GREEN;
        } else {
            stockInfo = "por jugador · 24h";
            stockColor = COL_TEXT_DIM;
        }
        g.drawString(font, stockInfo, cfgLeftX + 50, lRow(3) + 13, stockColor);

        // ── Columna derecha ──────────────────────────────────────────────────
        // Precio 1
        drawColLabel(g, cfgRightX, rRow(0), compra ? "Paga 1:" : "Precio 1:");
        drawItemValue(g, cfgRightX, rRow(0) + 9, cfgPriceItem, price1AmountField.x - 4, COL_YELLOW,
                "(sin definir)", COL_RED);
        price1Btn.draw(g, font, mouseX, mouseY);
        price1AmountField.draw(g, font);

        // Precio 2
        drawColLabel(g, cfgRightX, rRow(1), compra ? "Paga 2:" : "Precio 2:");
        drawItemValue(g, cfgRightX, rRow(1) + 9, cfgPriceItem2, price2AmountField.x - 4, COL_YELLOW,
                "(opcional)", COL_TEXT_DIM);
        price2Btn.draw(g, font, mouseX, mouseY);
        if (!cfgPriceItem2.isEmpty()) {
            price2AmountField.draw(g, font);
            price2RemoveBtn.draw(g, font, mouseX, mouseY);
        }

        // Nota de doble precio
        if (!cfgPriceItem2.isEmpty()) {
            String note = compra ? "Paga ambos ítems por compra." : "Cobra ambos ítems por compra.";
            g.drawString(font, note, cfgRightX, rRow(2) - 4, COL_TEXT_DIM);
        }

        // Acciones
        saveBtn.draw(g, font, mouseX, mouseY);
        restockBtn.draw(g, font, mouseX, mouseY);
        clearBtn.draw(g, font, mouseX, mouseY);
    }

    private void drawColLabel(GuiGraphics g, int x, int y, String label) {
        g.drawString(font, label, x, y, COL_TEXT_DIM);
    }

    /** Dibuja [icono] nombre (clip) o un placeholder si el ítem está vacío. */
    private void drawItemValue(GuiGraphics g, int x, int y, ItemStack stack, int rightLimit,
                               int nameColor, String emptyText, int emptyColor) {
        if (stack.isEmpty()) {
            g.drawString(font, emptyText, x, y + 4, emptyColor);
            return;
        }
        g.renderItem(stack, x, y);
        int nameX = x + 20;
        drawClipped(g, stack.getHoverName().getString(), nameX, y + 4, rightLimit - nameX, nameColor);
    }

    private void drawClipped(GuiGraphics g, String text, int x, int y, int maxW, int color) {
        if (maxW < 8) return;
        if (font.width(text) > maxW) {
            text = font.plainSubstrByWidth(text, Math.max(0, maxW - 6)) + "…";
        }
        g.drawString(font, text, x, y, color);
    }

    private static void drawBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    // ==================== ITEM PICKER ====================

    private void renderPickerOverlay(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(0, 0, width, height, 0xE0000000);

        drawPanel(g, pickerX, pickerY, PICKER_WIDTH, PICKER_HEIGHT);
        drawAccentBar(g, pickerX, pickerY, PICKER_WIDTH, 18);

        String pickerTitle = switch (currentMode) {
            case PICKING_SALE_ITEM -> cfgType == ShopSlot.Type.COMPRA ? "Ítem que la tienda compra" : "Ítem en venta";
            case PICKING_RENDER_ITEM -> "Ítem a mostrar (solo visual)";
            case PICKING_PRICE_ITEM -> cfgType == ShopSlot.Type.COMPRA ? "Ítem de pago 1" : "Ítem de Precio 1";
            case PICKING_PRICE_ITEM_2 -> cfgType == ShopSlot.Type.COMPRA ? "Ítem de pago 2" : "Ítem de Precio 2";
            default -> "Seleccionar Item";
        };
        g.drawCenteredString(font, pickerTitle, pickerX + PICKER_WIDTH / 2, pickerY + 5, 0xFFFFFF);

        pickerSearchField.draw(g, font);

        int gridX = pickerX + 10;
        int gridY = pickerY + 42;
        int visibleRows = (PICKER_HEIGHT - 70) / PICKER_SLOT_SIZE;
        int startIdx = pickerScrollOffset * PICKER_COLS;

        for (int row = 0; row < visibleRows; row++) {
            for (int col = 0; col < PICKER_COLS; col++) {
                int idx = startIdx + row * PICKER_COLS + col;
                if (idx >= filteredItems.size()) break;
                ItemStack stack = filteredItems.get(idx);

                int ix = gridX + col * PICKER_SLOT_SIZE;
                int iy = gridY + row * PICKER_SLOT_SIZE;

                boolean hovered = mouseX >= ix && mouseX < ix + PICKER_SLOT_SIZE &&
                        mouseY >= iy && mouseY < iy + PICKER_SLOT_SIZE;

                g.fill(ix, iy, ix + PICKER_SLOT_SIZE, iy + PICKER_SLOT_SIZE,
                        hovered ? COL_SLOT_HOVER : COL_SLOT_BG);
                g.fill(ix + 1, iy + 1, ix + PICKER_SLOT_SIZE - 1, iy + PICKER_SLOT_SIZE - 1,
                        hovered ? 0xFF333350 : 0xFF1A1A2A);

                g.renderItem(stack, ix + 2, iy + 2);
            }
        }

        int totalRows = (filteredItems.size() + PICKER_COLS - 1) / PICKER_COLS;
        if (totalRows > visibleRows) {
            int scrollBarX = pickerX + PICKER_WIDTH - 8;
            int scrollBarY = gridY;
            int scrollBarH = visibleRows * PICKER_SLOT_SIZE;
            g.fill(scrollBarX, scrollBarY, scrollBarX + 4, scrollBarY + scrollBarH, 0xFF333344);

            int thumbH = Math.max(10, scrollBarH * visibleRows / totalRows);
            int maxScrollRows = Math.max(1, totalRows - visibleRows);
            int thumbY = scrollBarY + (scrollBarH - thumbH) * pickerScrollOffset / maxScrollRows;
            g.fill(scrollBarX, thumbY, scrollBarX + 4, thumbY + thumbH, COL_ACCENT);
        }

        g.drawString(font, filteredItems.size() + " items", pickerX + 10, pickerY + PICKER_HEIGHT - 14,
                COL_TEXT_DIM);

        g.drawString(font, "[ESC] Cancelar", pickerX + PICKER_WIDTH - 80, pickerY + PICKER_HEIGHT - 14,
                COL_TEXT_DIM);

        int gridW = PICKER_COLS * PICKER_SLOT_SIZE;
        int gridEndY = gridY + visibleRows * PICKER_SLOT_SIZE;
        if (mouseX >= gridX && mouseX < gridX + gridW && mouseY >= gridY && mouseY < gridEndY) {
            int col = (mouseX - gridX) / PICKER_SLOT_SIZE;
            int row = (mouseY - gridY) / PICKER_SLOT_SIZE;
            int idx = startIdx + row * PICKER_COLS + col;
            if (col < PICKER_COLS && idx >= 0 && idx < filteredItems.size()) {
                g.renderTooltip(font, filteredItems.get(idx), mouseX, mouseY);
            }
        }
    }

    // ==================== INPUT HANDLING ====================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        if (currentMode != Mode.NORMAL) {
            return handlePickerClick(mouseX, mouseY);
        }

        if (selectedSlot >= 0) {
            if (cfgType == null) {
                // Botones grandes de elección
                if (inRect(mouseX, mouseY, ventaRectX, bigRectY, ventaRectW, bigRectH)) {
                    cfgType = ShopSlot.Type.VENTA;
                    openPicker(Mode.PICKING_SALE_ITEM);
                    return true;
                }
                if (inRect(mouseX, mouseY, compraRectX, bigRectY, compraRectW, bigRectH)) {
                    cfgType = ShopSlot.Type.COMPRA;
                    openPicker(Mode.PICKING_SALE_ITEM);
                    return true;
                }
            } else {
                if (saleItemBtn.mouseClicked(mouseX, mouseY)) return true;
                if (renderItemBtn.mouseClicked(mouseX, mouseY)) return true;
                if (!cfgRenderOverride.isEmpty() && renderResetBtn.mouseClicked(mouseX, mouseY)) return true;
                if (price1Btn.mouseClicked(mouseX, mouseY)) return true;
                if (price2Btn.mouseClicked(mouseX, mouseY)) return true;
                if (!cfgPriceItem2.isEmpty() && price2RemoveBtn.mouseClicked(mouseX, mouseY)) return true;
                if (saveBtn.mouseClicked(mouseX, mouseY)) return true;
                if (restockBtn.mouseClicked(mouseX, mouseY)) return true;
                if (clearBtn.mouseClicked(mouseX, mouseY)) return true;

                if (focusField(sellAmountField, mouseX, mouseY)) return true;
                if (focusField(stockField, mouseX, mouseY)) return true;
                if (focusField(price1AmountField, mouseX, mouseY)) return true;
                if (!cfgPriceItem2.isEmpty() && focusField(price2AmountField, mouseX, mouseY)) return true;
                unfocusAllFields();
            }
        }

        for (SlotWidget sw : slotWidgets) {
            if (mouseX >= sw.x && mouseX < sw.x + sw.size &&
                    mouseY >= sw.y && mouseY < sw.y + sw.size) {
                selectSlot(sw.slotIndex);
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private boolean focusField(CustomTextField field, double mouseX, double mouseY) {
        if (field.isMouseOver(mouseX, mouseY)) {
            unfocusAllFields();
            field.setFocused(true);
            return true;
        }
        return false;
    }

    private void unfocusAllFields() {
        stockField.setFocused(false);
        sellAmountField.setFocused(false);
        price1AmountField.setFocused(false);
        price2AmountField.setFocused(false);
    }

    private boolean handlePickerClick(double mouseX, double mouseY) {
        if (pickerSearchField.isMouseOver(mouseX, mouseY)) {
            pickerSearchField.setFocused(true);
            return true;
        }

        int gridX = pickerX + 10;
        int gridY = pickerY + 42;
        int visibleRows = (PICKER_HEIGHT - 70) / PICKER_SLOT_SIZE;
        int startIdx = pickerScrollOffset * PICKER_COLS;

        if (mouseX >= gridX && mouseX < gridX + PICKER_COLS * PICKER_SLOT_SIZE &&
                mouseY >= gridY && mouseY < gridY + visibleRows * PICKER_SLOT_SIZE) {

            int col = (int) (mouseX - gridX) / PICKER_SLOT_SIZE;
            int row = (int) (mouseY - gridY) / PICKER_SLOT_SIZE;
            int idx = startIdx + row * PICKER_COLS + col;

            if (col < PICKER_COLS && idx >= 0 && idx < filteredItems.size()) {
                ItemStack selected = filteredItems.get(idx).copy();
                selected.setCount(1);
                onItemPicked(selected);
                return true;
            }
        }

        if (mouseX < pickerX || mouseX > pickerX + PICKER_WIDTH ||
                mouseY < pickerY || mouseY > pickerY + PICKER_HEIGHT) {
            closeItemPicker();
            return true;
        }

        return true;
    }

    private void onItemPicked(ItemStack item) {
        switch (currentMode) {
            case PICKING_SALE_ITEM -> {
                cfgSaleItem = item;
                if (cfgPriceItem.isEmpty()) {
                    cfgPriceItem = new ItemStack(Items.EMERALD);
                    price1AmountField.setValue("1");
                }
            }
            case PICKING_RENDER_ITEM -> cfgRenderOverride = item;
            case PICKING_PRICE_ITEM -> cfgPriceItem = item;
            case PICKING_PRICE_ITEM_2 -> cfgPriceItem2 = item;
            default -> {}
        }
        closeItemPicker();
    }

    private void closeItemPicker() {
        currentMode = Mode.NORMAL;
        pickerSearchField.setValue("");
        pickerSearchField.setFocused(false);
        // Si canceló la elección del ítem principal, volver a los botones
        if (cfgSaleItem.isEmpty()) {
            cfgType = null;
        }
    }

    private void selectSlot(int slotIndex) {
        selectedSlot = slotIndex;
        unfocusAllFields();
        loadConfigFromSlot(slotIndex);
    }

    private void loadConfigFromSlot(int slotIndex) {
        ShopSlot slot = shopBE.getSlot(slotIndex);
        if (slot != null && !slot.isEmpty()) {
            cfgType = slot.getType();
            cfgSaleItem = slot.getDisplayItem().copy();
            cfgRenderOverride = slot.getRenderOverride().copy();
            cfgPriceItem = slot.getPriceItem().copy();
            cfgPriceItem2 = slot.getPriceItem2().copy();
            sellAmountField.setValue(String.valueOf(slot.getSellAmount()));
            price1AmountField.setValue(String.valueOf(slot.getPriceAmount()));
            price2AmountField.setValue(String.valueOf(slot.getPriceAmount2()));
            stockField.setValue(slot.getMaxStock() == ShopSlot.INFINITE_STOCK ?
                    "-1" : String.valueOf(slot.getMaxStock()));
        } else {
            cfgType = null; // slot vacío → mostrar botones Venta/Compra
            cfgSaleItem = ItemStack.EMPTY;
            cfgRenderOverride = ItemStack.EMPTY;
            cfgPriceItem = ItemStack.EMPTY;
            cfgPriceItem2 = ItemStack.EMPTY;
            sellAmountField.setValue("1");
            price1AmountField.setValue("1");
            price2AmountField.setValue("1");
            stockField.setValue("-1");
        }
    }

    /** ¿Hay cambios locales sin enviar al servidor? */
    private boolean isDirty() {
        ShopSlot slot = shopBE.getSlot(selectedSlot);
        if (slot == null) return false;
        if (slot.isEmpty()) return !cfgSaleItem.isEmpty();
        return slot.getType() != cfgType
                || !ItemStack.isSameItemSameComponents(cfgSaleItem, slot.getDisplayItem())
                || !ItemStack.isSameItemSameComponents(cfgRenderOverride, slot.getRenderOverride())
                || !ItemStack.isSameItemSameComponents(cfgPriceItem, slot.getPriceItem())
                || !ItemStack.isSameItemSameComponents(cfgPriceItem2, slot.getPriceItem2())
                || parseInt(sellAmountField.getValue(), 1) != slot.getSellAmount()
                || parseInt(price1AmountField.getValue(), 1) != slot.getPriceAmount()
                || (!cfgPriceItem2.isEmpty() && parseInt(price2AmountField.getValue(), 1) != slot.getPriceAmount2())
                || parseInt(stockField.getValue(), ShopSlot.INFINITE_STOCK) != slot.getMaxStock();
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Signature changed in 1.20.5+ from {@code (mouseX, mouseY, delta)} to
     * {@code (mouseX, mouseY, scrollX, scrollY)}. We use scrollY (vertical).
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (currentMode != Mode.NORMAL) {
            int totalRows = (filteredItems.size() + PICKER_COLS - 1) / PICKER_COLS;
            int visibleRows = (PICKER_HEIGHT - 70) / PICKER_SLOT_SIZE;
            int maxScroll = Math.max(0, totalRows - visibleRows);
            pickerScrollOffset = Math.max(0, Math.min(maxScroll,
                    pickerScrollOffset - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (currentMode != Mode.NORMAL) {
            if (keyCode == 256) { // ESC
                closeItemPicker();
                return true;
            }
            if (pickerSearchField.keyPressed(keyCode)) return true;
        } else {
            if (stockField.keyPressed(keyCode)) return true;
            if (sellAmountField.keyPressed(keyCode)) return true;
            if (price1AmountField.keyPressed(keyCode)) return true;
            if (price2AmountField.keyPressed(keyCode)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (currentMode != Mode.NORMAL) {
            if (pickerSearchField.charTyped(codePoint)) return true;
        } else {
            if (stockField.charTyped(codePoint)) return true;
            if (sellAmountField.charTyped(codePoint)) return true;
            if (price1AmountField.charTyped(codePoint)) return true;
            if (price2AmountField.charTyped(codePoint)) return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    // ==================== ACTIONS ====================

    /** Envía la configuración completa del slot al servidor en un solo paquete. */
    private void saveConfig() {
        if (selectedSlot < 0 || cfgType == null || cfgSaleItem.isEmpty()) return;

        int sellAmount = Math.max(1, parseInt(sellAmountField.getValue(), 1));
        int price1Amt = Math.max(1, parseInt(price1AmountField.getValue(), 1));
        int price2Amt = Math.max(1, parseInt(price2AmountField.getValue(), 1));
        int maxStock = parseInt(stockField.getValue(), ShopSlot.INFINITE_STOCK);
        if (maxStock < 0) maxStock = ShopSlot.INFINITE_STOCK;

        if (cfgPriceItem.isEmpty()) {
            cfgPriceItem = new ItemStack(Items.EMERALD);
            price1Amt = 1;
        }

        boolean isCompra = cfgType == ShopSlot.Type.COMPRA;
        ModNetworking.sendSetSlotConfig(shopPos, selectedSlot, isCompra,
                cfgSaleItem, cfgRenderOverride, sellAmount,
                cfgPriceItem, price1Amt, cfgPriceItem2, price2Amt, maxStock);

        // Aplicar también en el BE del cliente para feedback inmediato
        shopBE.applySlotConfig(selectedSlot, cfgType, cfgSaleItem, cfgRenderOverride, sellAmount,
                cfgPriceItem, price1Amt, cfgPriceItem2, price2Amt, maxStock);

        // Normalizar los campos con los valores efectivos
        sellAmountField.setValue(String.valueOf(sellAmount));
        price1AmountField.setValue(String.valueOf(price1Amt));
        price2AmountField.setValue(String.valueOf(price2Amt));
        stockField.setValue(String.valueOf(maxStock));
    }

    private void restockSelected() {
        if (selectedSlot < 0) return;
        ModNetworking.sendRestockSlot(shopPos, selectedSlot);
    }

    private void clearSelected() {
        if (selectedSlot < 0) return;
        ModNetworking.sendClearSlot(shopPos, selectedSlot);
        ShopSlot slot = shopBE.getSlot(selectedSlot);
        if (slot != null) slot.clear();
        loadConfigFromSlot(selectedSlot); // vuelve a los botones Venta/Compra
        unfocusAllFields();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ==================== CUSTOM WIDGETS ====================

    /**
     * Text input field drawn entirely with GuiGraphics — replaces Minecraft's EditBox.
     */
    private static class CustomTextField {
        int x, y, w, h;
        final int maxLength;
        private String value = "";
        private int cursorPos = 0;
        boolean focused = false;
        private Consumer<String> responder;

        CustomTextField(int maxLength) {
            this.maxLength = maxLength;
        }

        void setBounds(int x, int y, int w, int h) {
            this.x = x; this.y = y; this.w = w; this.h = h;
        }

        void setResponder(Consumer<String> r) { this.responder = r; }

        void setValue(String v) {
            value = (v == null) ? "" : v;
            cursorPos = Math.min(cursorPos, value.length());
        }

        String getValue() { return value; }

        void setFocused(boolean f) {
            focused = f;
            if (f) cursorPos = value.length();
        }

        boolean isMouseOver(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        boolean charTyped(char c) {
            if (!focused || c < ' ' || value.length() >= maxLength) return false;
            value = value.substring(0, cursorPos) + c + value.substring(cursorPos);
            cursorPos++;
            if (responder != null) responder.accept(value);
            return true;
        }

        boolean keyPressed(int keyCode) {
            if (!focused) return false;
            return switch (keyCode) {
                case 259 -> { // BACKSPACE
                    if (cursorPos > 0) {
                        value = value.substring(0, cursorPos - 1) + value.substring(cursorPos);
                        cursorPos--;
                        if (responder != null) responder.accept(value);
                        yield true;
                    }
                    yield false;
                }
                case 261 -> { // DELETE
                    if (cursorPos < value.length()) {
                        value = value.substring(0, cursorPos) + value.substring(cursorPos + 1);
                        if (responder != null) responder.accept(value);
                        yield true;
                    }
                    yield false;
                }
                case 263 -> { if (cursorPos > 0) cursorPos--; yield true; }            // LEFT
                case 262 -> { if (cursorPos < value.length()) cursorPos++; yield true; } // RIGHT
                case 268 -> { cursorPos = 0; yield true; }                               // HOME
                case 269 -> { cursorPos = value.length(); yield true; }                  // END
                default -> false;
            };
        }

        void draw(GuiGraphics g, Font font) {
            // Background
            g.fill(x, y, x + w, y + h, COL_BG_FIELD);
            // Border: accent when focused, dim otherwise
            int bc = focused ? COL_ACCENT : COL_BORDER;
            g.fill(x,         y,         x + w,     y + 1,     bc);
            g.fill(x,         y + h - 1, x + w,     y + h,     bc);
            g.fill(x,         y,         x + 1,     y + h,     bc);
            g.fill(x + w - 1, y,         x + w,     y + h,     bc);
            // Text
            int ty = y + (h - font.lineHeight) / 2;
            g.drawString(font, value, x + 3, ty, COL_TEXT, false);
            // Blinking cursor (530 ms half-period)
            if (focused && (System.currentTimeMillis() / 530) % 2 == 0) {
                int cx = x + 3 + font.width(value.substring(0, cursorPos));
                g.fill(cx, y + 2, cx + 1, y + h - 2, COL_TEXT);
            }
        }
    }

    /**
     * Button drawn entirely with GuiGraphics — replaces Minecraft's Button component.
     */
    private static class CustomButton {
        int x, y, w, h;
        final String label;
        private final Runnable action;

        CustomButton(String label, int x, int y, int w, int h, Runnable action) {
            this.label = label;
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.action = action;
        }

        boolean isMouseOver(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        boolean mouseClicked(double mx, double my) {
            if (isMouseOver(mx, my)) { action.run(); return true; }
            return false;
        }

        void draw(GuiGraphics g, Font font, int mouseX, int mouseY) {
            boolean hovered = isMouseOver(mouseX, mouseY);
            int bg     = hovered ? COL_ACCENT_DIM : 0xFF1A1A2E;
            int border = hovered ? COL_ACCENT      : COL_BORDER;
            g.fill(x,     y,     x + w,     y + h,     border);
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg);
            int tc = hovered ? 0xFFFFFFFF : COL_TEXT;
            g.drawCenteredString(font, label, x + w / 2, y + (h - font.lineHeight) / 2, tc);
        }
    }

    // ==================== INNER CLASSES ====================

    private static class SlotWidget {
        final int slotIndex;
        final int x, y, size;

        SlotWidget(int slotIndex, int x, int y, int size) {
            this.slotIndex = slotIndex;
            this.x = x;
            this.y = y;
            this.size = size;
        }
    }
}
