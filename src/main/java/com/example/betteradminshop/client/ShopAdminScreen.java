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

    /** Ítems del inventario del jugador mostrados dentro del selector. */
    private final List<ItemStack> inventoryItems = new ArrayList<>();

    // Botones grandes de elección de tipo (slot vacío)
    private int ventaRectX, compraRectX, bigRectY, bigRectH;
    private int ventaRectW, compraRectW;

    // Botones-icono pequeños (rects calculados al dibujar)
    private static final int ICON_BTN = 14;
    private int typeBadgeX, typeBadgeY, typeBadgeW, typeBadgeH; // badge de tipo (clic = alternar)
    private int copyIconX, copyIconY;   // copiar contenido del slot
    private int pasteIconX, pasteIconY; // pegar en slot vacío
    private boolean copyIconShown, pasteIconShown;

    // Portapapeles de slot (estático: persiste mientras el juego esté abierto)
    private static SlotClipboard clipboard = null;

    /** Portapapeles de TIENDA COMPLETA (plantilla de los 24 slots). */
    private static net.minecraft.nbt.CompoundTag shopClipboard = null;
    private CustomButton copyShopBtn, pasteShopBtn;

    // Drag & drop de slots
    private int dragSourceSlot = -1;   // slot "armado" al presionar
    private boolean dragging = false;  // se activa al mover el mouse
    private double pressX, pressY;

    // Tooltip diferido (se dibuja al final del render para quedar encima)
    private String pendingTooltip;
    private int pendingTooltipX, pendingTooltipY;
    private static final int ICON_COPY = 0, ICON_PASTE = 1;

    // Slot grid
    private final List<SlotWidget> slotWidgets = new ArrayList<>();

    // Item picker
    private static final int PICKER_WIDTH = 240;
    private static final int PICKER_HEIGHT = 210;
    /** Panel IZQUIERDO: inventario completo del jugador (9x4 = 36 slots). */
    private static final int INV_COLS = 9;
    private static final int INV_STORAGE_ROWS = 3;   // 27 de almacenamiento
    private static final int INV_PANEL_W = INV_COLS * 20 + 20;
    private static final int INV_PANEL_H = 18 + 8 + INV_STORAGE_ROWS * 20 + 8 + 20 + 10;
    /** Separación entre el panel de inventario y el del buscador. */
    private static final int PANEL_GAP = 12;
    private int invPanelX, invPanelY;
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
    private static final int COL_BG = 0xFF14141F;
    private static final int COL_BG_INNER = 0xFF1E1E2E;
    private static final int COL_BG_FIELD = 0xFF12121C;
    private static final int COL_ACCENT = 0xFF6C63FF;
    private static final int COL_ACCENT_DIM = 0xFF4A4499;
    private static final int COL_TEXT = 0xFFEDEDF5;
    private static final int COL_TEXT_DIM = 0xFF8E8EA6;
    private static final int COL_GREEN = 0xFF55DD55;
    private static final int COL_RED = 0xFFFF5555;
    private static final int COL_YELLOW = 0xFFFFDD55;
    private static final int COL_SLOT_BG = 0xFF262637;
    private static final int COL_SLOT_HOVER = 0xFF3A3A57;
    private static final int COL_SLOT_SELECTED = 0xFF4A4A77;
    private static final int COL_BORDER = 0xFF3D3D5C;
    /** Realce superior sutil para dar relieve a los paneles. */
    private static final int COL_HIGHLIGHT = 0x22FFFFFF;
    private static final int COL_SHADOW = 0x33000000;
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

        // Dos paneles lado a lado, centrados como conjunto
        int totalW = INV_PANEL_W + PANEL_GAP + PICKER_WIDTH;
        int startX = (width - totalW) / 2;
        invPanelX = startX;
        pickerX = startX + INV_PANEL_W + PANEL_GAP;
        pickerY = (height - PICKER_HEIGHT) / 2;
        invPanelY = pickerY;

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

    /** Reconstruye la lista del selector: dinámicos primero + ítems del registro. */
    private void rebuildAllItems() {
        allItems.clear();

        // 1) Ítems dinámicos, primero para que sean fáciles de encontrar:
        //    - Cretania Recipes (auto-detectados por reflexión, si el mod está)
        //    - registrados manualmente vía /tiendas items (sincronizados del server)
        for (ItemStack s : CretaniaCompat.items()) {
            if (!s.isEmpty()) allItems.add(s.copyWithCount(1));
        }
        for (ItemStack s : ClientDynamicItems.get()) {
            if (!s.isEmpty()) allItems.add(s.copyWithCount(1));
        }

        // 2) Ítems del registro. Si Cretania está, se ocultan sus ítems BASE
        //    (custom / custom_block) porque lo útil son las variantes dinámicas.
        boolean hideCretaniaBase = CretaniaCompat.isPresent();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            if (hideCretaniaBase) {
                String id = BuiltInRegistries.ITEM.getKey(item).toString();
                if (id.equals(CretaniaCompat.BASE_ITEM) || id.equals(CretaniaCompat.BASE_BLOCK)) continue;
            }
            allItems.add(new ItemStack(item));
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

        // Copiar/pegar TIENDA COMPLETA (barra de título, a la derecha)
        copyShopBtn = new CustomButton("Copiar", leftX + GUI_WIDTH - 146, topY + 3, 64, 14,
                this::copyShop);
        pasteShopBtn = new CustomButton("Pegar", leftX + GUI_WIDTH - 74, topY + 3, 64, 14,
                this::pasteShop);

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

    /**
     * Refresca el inventario del jugador para el panel izquierdo, INDEXADO por
     * slot real (0-8 hotbar, 9-35 almacenamiento) para que la disposición en
     * pantalla coincida con la del inventario del jugador.
     */
    private void refreshInventoryItems() {
        inventoryItems.clear();
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        var inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = i < inv.getContainerSize() ? inv.getItem(i) : ItemStack.EMPTY;
            inventoryItems.add(s.isEmpty() ? ItemStack.EMPTY : s.copyWithCount(1));
        }
    }

    /** Ítem del slot de inventario (vacío si fuera de rango). */
    private ItemStack invItem(int slot) {
        return (slot >= 0 && slot < inventoryItems.size()) ? inventoryItems.get(slot) : ItemStack.EMPTY;
    }

    /** Filas visibles de la rejilla del registro (sin contar el inventario). */
    private static int pickerVisibleRows() {
        return (PICKER_HEIGHT - 70) / PICKER_SLOT_SIZE;
    }

    /** Y de la primera fila de almacenamiento del panel de inventario. */
    private int invStorageY() {
        return invPanelY + 18 + 8;
    }

    /** Y de la fila de la hotbar (separada, como en el inventario real). */
    private int invHotbarY() {
        return invStorageY() + INV_STORAGE_ROWS * PICKER_SLOT_SIZE + 8;
    }

    /**
     * Índice del slot de inventario bajo el cursor en el panel izquierdo, o -1.
     * Orden del inventario de MC: 0-8 hotbar, 9-35 almacenamiento. En pantalla
     * mostramos primero el almacenamiento (9-35) y abajo la hotbar (0-8).
     */
    private int invSlotAt(double mx, double my) {
        int gx = invPanelX + 10;
        int col = (int) Math.floor((mx - gx) / PICKER_SLOT_SIZE);
        if (col < 0 || col >= INV_COLS) return -1;
        int storageY = invStorageY();
        if (my >= storageY && my < storageY + INV_STORAGE_ROWS * PICKER_SLOT_SIZE) {
            int row = (int) ((my - storageY) / PICKER_SLOT_SIZE);
            return 9 + row * INV_COLS + col;
        }
        int hotbarY = invHotbarY();
        if (my >= hotbarY && my < hotbarY + PICKER_SLOT_SIZE) {
            return col;
        }
        return -1;
    }

    private void openPicker(Mode mode) {
        if (selectedSlot < 0) return;
        if (mode != Mode.PICKING_SALE_ITEM && cfgSaleItem.isEmpty()) return;
        currentMode = mode;
        pickerScrollOffset = 0;
        refreshInventoryItems();
        filterItems("");
        pickerSearchField.setValue("");
        pickerSearchField.setFocused(true);
    }

    /**
     * Filtra el selector. Prefijos especiales:
     *   @mod   → por namespace (ej. "@create", "@cretania").
     *   #tag   → por etiqueta   (ej. "#planks", "#minecraft:logs").
     * Sin prefijo: por nombre o id de registro (subcadena).
     */
    private void filterItems(String query) {
        filteredItems.clear();
        pickerScrollOffset = 0;
        String q = (query == null) ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        if (q.isEmpty()) {
            filteredItems.addAll(allItems);
            return;
        }

        char prefix = q.charAt(0);
        if (prefix == '@') {
            String term = q.substring(1).trim();
            for (ItemStack stack : allItems) {
                String ns = BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace();
                if (term.isEmpty() || ns.contains(term)) filteredItems.add(stack);
            }
        } else if (prefix == '#') {
            String term = q.substring(1).trim();
            for (ItemStack stack : allItems) {
                if (matchesTag(stack, term)) filteredItems.add(stack);
            }
        } else {
            for (ItemStack stack : allItems) {
                String name = stack.getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
                // También por id de registro ("minecraft:stone") para ítems
                // modded cuyo nombre no está traducido.
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (name.contains(q) || id.contains(q)) filteredItems.add(stack);
            }
        }
    }

    /** ¿El ítem tiene una etiqueta cuyo id/ruta contiene {@code term}? */
    private static boolean matchesTag(ItemStack stack, String term) {
        try {
            if (term.isEmpty()) return stack.getTags().findAny().isPresent();
            return stack.getTags().anyMatch(t ->
                    t.location().toString().contains(term) || t.location().getPath().contains(term));
        } catch (Throwable t) {
            return false;
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
        pendingTooltip = null;
        renderBackground(g, mouseX, mouseY, partialTick);

        drawPanel(g, leftX, topY, GUI_WIDTH, GUI_HEIGHT);

        drawAccentBar(g, leftX, topY, GUI_WIDTH, 20);
        g.drawCenteredString(font, "★ Administración de Tienda ★", leftX + GUI_WIDTH / 2, topY + 6, 0xFFFFFF);

        // Copiar / pegar TIENDA COMPLETA
        copyShopBtn.draw(g, font, mouseX, mouseY);
        if (copyShopBtn.isMouseOver(mouseX, mouseY)) {
            setTooltip("Copiar toda la tienda (productos, precios y stock)", mouseX, mouseY);
        }
        if (shopClipboard != null) {
            pasteShopBtn.draw(g, font, mouseX, mouseY);
            if (pasteShopBtn.isMouseOver(mouseX, mouseY)) {
                setTooltip("Pegar la tienda copiada aquí (reemplaza todos los slots)", mouseX, mouseY);
            }
        }

        // Tarjetas de los estantes (dan estructura a la columna izquierda)
        drawCard(g, leftX + 8, topY + 22, LEFT_PANEL_W - 14, 108);
        drawCard(g, leftX + 8, topY + 132, LEFT_PANEL_W - 14, 108);
        drawSectionHeader(g, leftX + 12, topY + 26, "Estante Izquierdo");
        drawSectionHeader(g, leftX + 12, topY + 136, "Estante Derecho");

        for (SlotWidget sw : slotWidgets) {
            ShopSlot slot = shopBE.getSlot(sw.slotIndex);
            renderSlotWidget(g, sw, slot, sw.slotIndex == selectedSlot, mouseX, mouseY);
        }

        // Resaltar el slot destino mientras se arrastra
        if (dragging) {
            SlotWidget target = slotAt(mouseX, mouseY);
            if (target != null && target.slotIndex != dragSourceSlot) {
                drawBorder(g, target.x, target.y, target.size, target.size, COL_ACCENT);
                drawBorder(g, target.x + 1, target.y + 1, target.size - 2, target.size - 2, COL_ACCENT);
            }
        }

        renderConfigArea(g, mouseX, mouseY);

        if (currentMode == Mode.NORMAL && !dragging) {
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
        } else {
            // Ítem que se está arrastrando (sigue al cursor)
            if (dragging && dragSourceSlot >= 0) {
                ShopSlot src = shopBE.getSlot(dragSourceSlot);
                if (src != null && !src.isEmpty()) {
                    g.pose().pushPose();
                    g.pose().translate(0, 0, 300);
                    g.renderItem(src.getRenderItem(), mouseX - 8, mouseY - 8);
                    g.pose().popPose();
                }
            } else if (pendingTooltip != null) {
                g.renderTooltip(font, Component.literal(pendingTooltip), pendingTooltipX, pendingTooltipY);
            }
        }
    }

    private SlotWidget slotAt(double mx, double my) {
        for (SlotWidget sw : slotWidgets) {
            if (mx >= sw.x && mx < sw.x + sw.size && my >= sw.y && my < sw.y + sw.size) return sw;
        }
        return null;
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
        // Sombra proyectada difusa (dos capas) para separar del mundo
        g.fill(x + 4, y + 4, x + w + 4, y + h + 4, 0x50000000);
        g.fill(x + 2, y + 2, x + w + 2, y + h + 2, 0x70000000);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, COL_BORDER);
        g.fill(x, y, x + w, y + h, COL_BG);
        // Realce superior: da sensación de relieve
        g.fill(x + 1, y + 1, x + w - 1, y + 2, COL_HIGHLIGHT);
    }

    /** Panel interior (tarjeta) con borde y realce, para agrupar contenido. */
    private void drawCard(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, COL_BG_INNER);
        drawBorder(g, x, y, w, h, COL_BORDER);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, COL_HIGHLIGHT);
    }

    private void drawAccentBar(GuiGraphics g, int x, int y, int w, int h) {
        // Degradado vertical simulado en tres bandas + línea de acento
        g.fill(x, y, x + w, y + h, COL_ACCENT_DIM);
        g.fill(x, y, x + w, y + h / 2, COL_ACCENT);
        g.fill(x, y + h / 2, x + w, y + h - 2, COL_ACCENT_DIM);
        g.fill(x, y + h - 2, x + w, y + h, 0xFF2E2A66);
        g.fill(x, y, x + w, y + 1, COL_HIGHLIGHT);
    }

    /** Encabezado de sección con marca de acento a la izquierda. */
    private void drawSectionHeader(GuiGraphics g, int x, int y, String text) {
        g.fill(x, y - 1, x + 2, y + 9, COL_ACCENT);
        g.drawString(font, text, x + 6, y, COL_YELLOW);
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
        // Realce interior superior: aspecto de casilla hundida
        g.fill(sw.x + 1, sw.y + 1, sw.x + sw.size - 1, sw.y + 2, COL_HIGHLIGHT);
        if (selected) {
            // Doble borde de acento para que la selección cante
            drawBorder(g, sw.x - 1, sw.y - 1, sw.size + 2, sw.size + 2, COL_ACCENT);
        }

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
        g.fill(px, py, px + 1, py + ph, COL_ACCENT_DIM);   // separador con acento
        g.fill(px + 1, py, px + pw - 5, py + 1, COL_HIGHLIGHT);

        if (selectedSlot < 0) {
            // Estado vacío ilustrado: recuadro punteado + guía
            int bw = 120, bh = 46;
            int bx = px + (pw - 5 - bw) / 2, by = py + ph / 2 - bh;
            for (int i = 0; i < bw; i += 6) {           // borde punteado
                g.fill(bx + i, by, bx + Math.min(i + 3, bw), by + 1, COL_BORDER);
                g.fill(bx + i, by + bh - 1, bx + Math.min(i + 3, bw), by + bh, COL_BORDER);
            }
            for (int i = 0; i < bh; i += 6) {
                g.fill(bx, by + i, bx + 1, by + Math.min(i + 3, bh), COL_BORDER);
                g.fill(bx + bw - 1, by + i, bx + bw, by + Math.min(i + 3, bh), COL_BORDER);
            }
            g.drawCenteredString(font, "+", bx + bw / 2, by + bh / 2 - 4, COL_BORDER);
            g.drawCenteredString(font, "Selecciona un slot del estante",
                    px + (pw - 5) / 2, by + bh + 12, COL_TEXT);
            g.drawCenteredString(font, "§8para configurar su producto",
                    px + (pw - 5) / 2, by + bh + 24, COL_TEXT_DIM);
            return;
        }

        // Encabezado
        g.fill(px + 1, py + 1, px + pw - 5, py + 20, 0xFF23233A);
        String slotLabel = "Slot #" + (selectedSlot + 1)
                + (selectedSlot < ShopBlockEntity.SLOTS_PER_GROUP ? " (Estante Izq.)" : " (Estante Der.)");
        g.fill(cfgLeftX - 4, py + 5, cfgLeftX - 2, py + 15, COL_ACCENT);
        g.drawString(font, slotLabel, cfgLeftX, py + 8, COL_ACCENT);

        copyIconShown = false;
        pasteIconShown = false;
        int rightEdge = px + pw - 12;

        if (cfgType != null) {
            // Badge de tipo (clic = alternar Venta/Compra)
            drawTypeBadge(g, rightEdge, py + 5, cfgType == ShopSlot.Type.COMPRA, mouseX, mouseY);
            // Icono copiar, a la izquierda del badge
            if (!cfgSaleItem.isEmpty()) {
                copyIconX = typeBadgeX - 5 - ICON_BTN;
                copyIconY = py + 4;
                drawIconButton(g, copyIconX, copyIconY, mouseX, mouseY, ICON_COPY,
                        "Copiar contenido de este slot");
                copyIconShown = true;
            }
            if (isDirty()) {
                String dirty = "● sin guardar";
                int anchorX = copyIconShown ? copyIconX : typeBadgeX;
                g.drawString(font, dirty, anchorX - 6 - font.width(dirty), py + 8, COL_DIRTY);
            }
        } else if (clipboard != null) {
            // Slot vacío + hay algo copiado → icono pegar (arriba a la derecha)
            pasteIconX = rightEdge - ICON_BTN;
            pasteIconY = py + 4;
            drawIconButton(g, pasteIconX, pasteIconY, mouseX, mouseY, ICON_PASTE,
                    "Pegar aquí el slot copiado");
            pasteIconShown = true;
        }
        g.fill(cfgLeftX, py + 19, px + pw - 14, py + 20, COL_BORDER);

        if (cfgType == null) {
            renderChoiceButtons(g, mouseX, mouseY);
        } else {
            renderConfigColumns(g, mouseX, mouseY);
        }
    }

    /** Badge de tipo clicable (alterna Venta/Compra). Guarda su rect para el hit-test. */
    private void drawTypeBadge(GuiGraphics g, int rightX, int y, boolean compra, int mouseX, int mouseY) {
        String label = compra ? "COMPRA" : "VENTA";
        int w = font.width(label) + 10;
        int h = 12;
        int x = rightX - w;
        typeBadgeX = x; typeBadgeY = y; typeBadgeW = w; typeBadgeH = h;
        int accent = compra ? COL_COMPRA : COL_VENTA;
        boolean hov = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        g.fill(x, y, x + w, y + h, hov ? (compra ? COL_COMPRA_DIM : COL_VENTA_DIM)
                : (compra ? COL_COMPRA_BG : COL_VENTA_BG));
        drawBorder(g, x, y, w, h, accent);
        g.drawString(font, label, x + 5, y + 2, hov ? 0xFFFFFFFF : accent, false);
        if (hov) setTooltip(compra ? "Clic: cambiar a Venta" : "Clic: cambiar a Compra", mouseX, mouseY);
    }

    private void setTooltip(String text, int mouseX, int mouseY) {
        pendingTooltip = text;
        pendingTooltipX = mouseX;
        pendingTooltipY = mouseY;
    }

    /** Botón-icono pequeño (copiar / pegar) dibujado con GuiGraphics. */
    private boolean drawIconButton(GuiGraphics g, int x, int y, int mouseX, int mouseY, int kind, String tooltip) {
        boolean hov = mouseX >= x && mouseX < x + ICON_BTN && mouseY >= y && mouseY < y + ICON_BTN;
        int bg = hov ? COL_ACCENT_DIM : 0xFF1A1A2E;
        int border = hov ? COL_ACCENT : COL_BORDER;
        g.fill(x, y, x + ICON_BTN, y + ICON_BTN, border);
        g.fill(x + 1, y + 1, x + ICON_BTN - 1, y + ICON_BTN - 1, bg);
        int col = hov ? 0xFFFFFFFF : COL_TEXT;
        if (kind == ICON_COPY) drawCopyGlyph(g, x, y, col, bg);
        else drawPasteGlyph(g, x, y, col);
        if (hov && tooltip != null) setTooltip(tooltip, mouseX, mouseY);
        return hov;
    }

    /** Icono de copiar: dos recuadros superpuestos. */
    private static void drawCopyGlyph(GuiGraphics g, int bx, int by, int col, int bg) {
        int sz = 6;
        int backX = bx + 6, backY = by + 3;   // recuadro de atrás (arriba-derecha)
        int frontX = bx + 3, frontY = by + 5;  // recuadro de adelante (abajo-izq)
        drawBorder(g, backX, backY, sz, sz, col);
        // Borrar la parte del de atrás que queda dentro del de adelante
        g.fill(frontX, frontY, frontX + sz, frontY + sz, bg);
        drawBorder(g, frontX, frontY, sz, sz, col);
    }

    /** Icono de pegar: portapapeles. */
    private static void drawPasteGlyph(GuiGraphics g, int bx, int by, int col) {
        int x0 = bx + 3, y0 = by + 4, w = 8, h = 8;
        drawBorder(g, x0, y0, w, h, col);
        g.fill(x0 + 2, y0 + 3, x0 + w - 2, y0 + 4, col); // línea de texto
        g.fill(x0 + 2, y0 + 5, x0 + w - 2, y0 + 6, col);
        g.fill(bx + 6, by + 2, bx + 6 + 3, by + 5, col); // pinza superior
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

    /** Panel IZQUIERDO: inventario completo del jugador (ítems reales con NBT). */
    private void renderInventoryPanel(GuiGraphics g, int mouseX, int mouseY) {
        drawPanel(g, invPanelX, invPanelY, INV_PANEL_W, INV_PANEL_H);
        drawAccentBar(g, invPanelX, invPanelY, INV_PANEL_W, 18);
        g.drawCenteredString(font, "Tu inventario", invPanelX + INV_PANEL_W / 2, invPanelY + 5, 0xFFFFFF);

        int gx = invPanelX + 10;
        ItemStack hoveredStack = ItemStack.EMPTY;

        // Almacenamiento (slots 9-35) y hotbar (0-8), como en el inventario real
        for (int i = 0; i < 36; i++) {
            boolean hotbar = i < INV_COLS;
            int col = hotbar ? i : (i - 9) % INV_COLS;
            int row = hotbar ? 0 : (i - 9) / INV_COLS;
            int ix = gx + col * PICKER_SLOT_SIZE;
            int iy = hotbar ? invHotbarY() : invStorageY() + row * PICKER_SLOT_SIZE;

            boolean hovered = mouseX >= ix && mouseX < ix + PICKER_SLOT_SIZE
                    && mouseY >= iy && mouseY < iy + PICKER_SLOT_SIZE;
            g.fill(ix, iy, ix + PICKER_SLOT_SIZE, iy + PICKER_SLOT_SIZE,
                    hovered ? COL_SLOT_HOVER : COL_SLOT_BG);
            g.fill(ix + 1, iy + 1, ix + PICKER_SLOT_SIZE - 1, iy + PICKER_SLOT_SIZE - 1,
                    hovered ? 0xFF333350 : 0xFF1A1A2A);

            ItemStack stack = invItem(i);
            if (!stack.isEmpty()) {
                g.renderItem(stack, ix + 2, iy + 2);
                if (hovered) hoveredStack = stack;
            }
        }

        g.drawString(font, "§8Conserva encantamientos y NBT",
                invPanelX + 10, invPanelY + INV_PANEL_H - 12, COL_TEXT_DIM, false);

        if (!hoveredStack.isEmpty()) {
            g.renderTooltip(font, hoveredStack, mouseX, mouseY);
        }
    }

    private void renderPickerOverlay(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(0, 0, width, height, 0xE0000000);

        renderInventoryPanel(g, mouseX, mouseY);

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
        // Pista de sintaxis cuando el buscador está vacío
        if (pickerSearchField.getValue().isEmpty()) {
            g.drawString(font, "Buscar…   §8@mod  #tag", pickerX + 13, pickerY + 25, COL_TEXT_DIM, false);
        }

        int gridX = pickerX + 10;
        int gridY = pickerY + 42;
        int visibleRows = pickerVisibleRows();
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

        g.drawString(font, filteredItems.size() + " items", pickerX + 10, pickerY + PICKER_HEIGHT - 12,
                COL_TEXT_DIM);

        g.drawString(font, "[ESC] Cancelar", pickerX + PICKER_WIDTH - 80, pickerY + PICKER_HEIGHT - 12,
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

        // Copiar / pegar tienda completa (barra de título)
        if (copyShopBtn.mouseClicked(mouseX, mouseY)) return true;
        if (shopClipboard != null && pasteShopBtn.mouseClicked(mouseX, mouseY)) return true;

        if (selectedSlot >= 0) {
            if (cfgType == null) {
                // Icono pegar (slot vacío + portapapeles)
                if (pasteIconShown && inRect(mouseX, mouseY, pasteIconX, pasteIconY, ICON_BTN, ICON_BTN)) {
                    pasteClipboard();
                    return true;
                }
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
                // Badge de tipo → alternar Venta/Compra
                if (inRect(mouseX, mouseY, typeBadgeX, typeBadgeY, typeBadgeW, typeBadgeH)) {
                    cfgType = (cfgType == ShopSlot.Type.VENTA) ? ShopSlot.Type.COMPRA : ShopSlot.Type.VENTA;
                    return true;
                }
                // Icono copiar
                if (copyIconShown && inRect(mouseX, mouseY, copyIconX, copyIconY, ICON_BTN, ICON_BTN)) {
                    copyToClipboard();
                    return true;
                }
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

        // Slots del estante: un slot con contenido "arma" un drag; uno vacío
        // se selecciona directo. La selección de un slot con contenido ocurre
        // al soltar sin arrastrar (ver mouseReleased).
        SlotWidget sw = slotAt(mouseX, mouseY);
        if (sw != null) {
            ShopSlot slot = shopBE.getSlot(sw.slotIndex);
            if (slot != null && !slot.isEmpty()) {
                dragSourceSlot = sw.slotIndex;
                dragging = false;
                pressX = mouseX;
                pressY = mouseY;
            } else {
                selectSlot(sw.slotIndex);
            }
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (button == 0 && dragSourceSlot >= 0 && !dragging) {
            if (Math.abs(mouseX - pressX) > 3 || Math.abs(mouseY - pressY) > 3) {
                dragging = true;
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragSourceSlot >= 0) {
            int source = dragSourceSlot;
            boolean wasDragging = dragging;
            dragSourceSlot = -1;
            dragging = false;

            SlotWidget target = slotAt(mouseX, mouseY);
            if (wasDragging) {
                // Arrastre completado: intercambiar con el slot destino
                if (target != null && target.slotIndex != source) {
                    ModNetworking.sendSwapSlots(shopPos, source, target.slotIndex);
                    shopBE.swapSlots(source, target.slotIndex); // feedback inmediato
                    selectSlot(target.slotIndex); // el contenido quedó en destino
                }
                return true;
            } else {
                // Fue un clic normal (sin arrastrar) → seleccionar el slot
                selectSlot(source);
                return true;
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
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

        // Panel IZQUIERDO: toma el ítem REAL del inventario (con sus componentes)
        int invSlot = invSlotAt(mouseX, mouseY);
        if (invSlot >= 0) {
            ItemStack stack = invItem(invSlot);
            if (!stack.isEmpty()) onItemPicked(stack.copyWithCount(1));
            return true;
        }

        int gridX = pickerX + 10;
        int gridY = pickerY + 42;
        int visibleRows = pickerVisibleRows();
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

        boolean inPicker = mouseX >= pickerX && mouseX <= pickerX + PICKER_WIDTH
                && mouseY >= pickerY && mouseY <= pickerY + PICKER_HEIGHT;
        boolean inInventory = mouseX >= invPanelX && mouseX <= invPanelX + INV_PANEL_W
                && mouseY >= invPanelY && mouseY <= invPanelY + INV_PANEL_H;
        if (!inPicker && !inInventory) {
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
            int visibleRows = pickerVisibleRows();
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

    // ── Copiar / pegar TIENDA COMPLETA ────────────────────────────────────────

    /** Copia la configuración de los 24 slots al portapapeles de tienda. */
    private void copyShop() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        shopClipboard = shopBE.saveTemplate(mc.level.registryAccess());
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(
                    "§a[Tienda] Configuración copiada (productos, precios y stock)."), true);
        }
    }

    /** Aplica la plantilla copiada a ESTA tienda (reemplaza todos los slots). */
    private void pasteShop() {
        Minecraft mc = Minecraft.getInstance();
        if (shopClipboard == null || mc.level == null) return;
        ModNetworking.sendApplyShopTemplate(shopPos, shopClipboard);
        // Feedback inmediato en el cliente
        shopBE.applyTemplate(mc.level.registryAccess(), shopClipboard);
        selectedSlot = -1;
        cfgType = null;
        unfocusAllFields();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(
                    "§a[Tienda] Configuración pegada en esta tienda."), true);
        }
    }

    // ── Copiar / pegar (portapapeles de slot) ─────────────────────────────────

    /** Copia el contenido actual del editor al portapapeles. */
    private void copyToClipboard() {
        if (cfgType == null || cfgSaleItem.isEmpty()) return;
        clipboard = new SlotClipboard(
                cfgType, cfgSaleItem, cfgRenderOverride,
                Math.max(1, parseInt(sellAmountField.getValue(), 1)),
                cfgPriceItem, Math.max(1, parseInt(price1AmountField.getValue(), 1)),
                cfgPriceItem2, Math.max(1, parseInt(price2AmountField.getValue(), 1)),
                Math.max(-1, parseInt(stockField.getValue(), ShopSlot.INFINITE_STOCK)));
    }

    /** Pega el portapapeles en el slot vacío seleccionado y lo guarda. */
    private void pasteClipboard() {
        if (clipboard == null || selectedSlot < 0) return;
        cfgType = clipboard.type;
        cfgSaleItem = clipboard.saleItem.copy();
        cfgRenderOverride = clipboard.renderOverride.copy();
        cfgPriceItem = clipboard.priceItem.copy();
        cfgPriceItem2 = clipboard.priceItem2.copy();
        sellAmountField.setValue(String.valueOf(clipboard.sellAmount));
        price1AmountField.setValue(String.valueOf(clipboard.priceAmount));
        price2AmountField.setValue(String.valueOf(clipboard.priceAmount2));
        stockField.setValue(clipboard.maxStock == ShopSlot.INFINITE_STOCK ?
                "-1" : String.valueOf(clipboard.maxStock));
        saveConfig(); // aplicar de inmediato (aparece el producto)
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
            // Fondo hundido (sombra interior arriba)
            g.fill(x, y, x + w, y + h, COL_BG_FIELD);
            g.fill(x + 1, y + 1, x + w - 1, y + 2, 0x30000000);
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
            int bg     = hovered ? COL_ACCENT_DIM : 0xFF23233A;
            int border = hovered ? COL_ACCENT      : COL_BORDER;
            g.fill(x + 1, y + 1, x + w + 1, y + h + 1, COL_SHADOW);   // sombra
            g.fill(x,     y,     x + w,     y + h,     border);
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg);
            g.fill(x + 1, y + 1, x + w - 1, y + 2,     COL_HIGHLIGHT); // realce
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

    /** Snapshot del contenido de un slot para copiar/pegar. */
    private static class SlotClipboard {
        final ShopSlot.Type type;
        final ItemStack saleItem, renderOverride, priceItem, priceItem2;
        final int sellAmount, priceAmount, priceAmount2, maxStock;

        SlotClipboard(ShopSlot.Type type, ItemStack saleItem, ItemStack renderOverride, int sellAmount,
                      ItemStack priceItem, int priceAmount, ItemStack priceItem2, int priceAmount2, int maxStock) {
            this.type = type;
            this.saleItem = saleItem.copy();
            this.renderOverride = renderOverride.copy();
            this.sellAmount = sellAmount;
            this.priceItem = priceItem.copy();
            this.priceAmount = priceAmount;
            this.priceItem2 = priceItem2.copy();
            this.priceAmount2 = priceAmount2;
            this.maxStock = maxStock;
        }
    }
}
