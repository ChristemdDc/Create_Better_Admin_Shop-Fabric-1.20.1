package com.example.betteradminshop.client;

import com.example.betteradminshop.block.ShopBlockEntity;
import com.example.betteradminshop.block.ShopSlot;
import com.example.betteradminshop.network.ModNetworking;

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

public class ShopAdminScreen extends Screen {

    private enum Mode { NORMAL, PICKING_DISPLAY_ITEM, PICKING_PRICE_ITEM }

    private final ShopBlockEntity shopBE;
    private final BlockPos shopPos;

    // Layout
    private static final int GUI_WIDTH = 380;
    private static final int GUI_HEIGHT = 270;
    private int leftX, topY;

    // State
    private Mode currentMode = Mode.NORMAL;
    private int selectedSlot = -1;

    // Custom text fields (no Minecraft EditBox)
    private CustomTextField stockField, priceAmountField;

    // Custom buttons (no Minecraft Button)
    private CustomButton applyBtn, restockBtn, clearBtn, changeItemBtn, changePriceBtn;

    // Slot grid
    private final List<SlotWidget> slotWidgets = new ArrayList<>();

    // Item picker
    private static final int PICKER_WIDTH = 220;
    private static final int PICKER_HEIGHT = 200;
    private static final int PICKER_COLS = 9;
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

        allItems.clear();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item != Items.AIR) {
                allItems.add(new ItemStack(item));
            }
        }
        filteredItems.clear();
        filteredItems.addAll(allItems);

        pickerX = (width - PICKER_WIDTH) / 2;
        pickerY = (height - PICKER_HEIGHT) / 2;

        initSlotConfigFields();
        initSlotGrid();
        initPickerSearch();
    }

    private void initSlotConfigFields() {
        int panelX = leftX + 170;
        int panelY = topY + 109;

        stockField = new CustomTextField(8);
        stockField.setBounds(panelX + 80, panelY + 70, 50, 14);
        stockField.setValue("-1");

        priceAmountField = new CustomTextField(8);
        priceAmountField.setBounds(panelX + 80, panelY + 90, 50, 14);
        priceAmountField.setValue("1");

        changeItemBtn = new CustomButton("Cambiar Item", panelX + 5, panelY + 35, 130, 14, () -> {
            if (selectedSlot >= 0) {
                currentMode = Mode.PICKING_DISPLAY_ITEM;
                pickerScrollOffset = 0;
                filterItems("");
                pickerSearchField.setValue("");
                pickerSearchField.setFocused(true);
            }
        });

        changePriceBtn = new CustomButton("Cambiar Precio Item", panelX + 5, panelY + 52, 130, 14, () -> {
            if (selectedSlot >= 0) {
                currentMode = Mode.PICKING_PRICE_ITEM;
                pickerScrollOffset = 0;
                filterItems("");
                pickerSearchField.setValue("");
                pickerSearchField.setFocused(true);
            }
        });

        applyBtn   = new CustomButton("Aplicar",  panelX + 5,  panelY + 110, 42, 14, this::applyStockAndPrice);
        restockBtn = new CustomButton("Restock",  panelX + 50, panelY + 110, 42, 14, this::restockSelected);
        clearBtn   = new CustomButton("Quitar",   panelX + 95, panelY + 110, 42, 14, this::clearSelected);
    }

    private void initSlotGrid() {
        slotWidgets.clear();
        int slotSize = 24;
        int cols = 3;

        int g1x = leftX + 15;
        int g1y = topY + 34;
        for (int i = 0; i < ShopBlockEntity.SLOTS_PER_GROUP; i++) {
            int row = i / cols;
            int col = i % cols;
            slotWidgets.add(new SlotWidget(i, g1x + col * slotSize, g1y + row * slotSize, slotSize));
        }

        int g2x = leftX + 15;
        int g2y = topY + 144;
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

    private void filterItems(String query) {
        filteredItems.clear();
        pickerScrollOffset = 0;
        if (query == null || query.isEmpty()) {
            filteredItems.addAll(allItems);
        } else {
            String lower = query.toLowerCase();
            for (ItemStack stack : allItems) {
                String name = stack.getHoverName().getString().toLowerCase();
                if (name.contains(lower)) {
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

        drawSectionHeader(g, leftX + 10, topY + 22, "▾ Estante Izquierdo");
        drawSectionHeader(g, leftX + 10, topY + 132, "▾ Estante Derecho");

        for (SlotWidget sw : slotWidgets) {
            ShopSlot slot = shopBE.getSlot(sw.slotIndex);
            renderSlotWidget(g, sw, slot, sw.slotIndex == selectedSlot, mouseX, mouseY);
        }

        renderSlotDetails(g, mouseX, mouseY);

        // Draw custom widgets when a slot is selected in NORMAL mode
        if (selectedSlot >= 0 && currentMode == Mode.NORMAL) {
            changeItemBtn.draw(g, font, mouseX, mouseY);
            changePriceBtn.draw(g, font, mouseX, mouseY);
            stockField.draw(g, font);
            priceAmountField.draw(g, font);
            applyBtn.draw(g, font, mouseX, mouseY);
            restockBtn.draw(g, font, mouseX, mouseY);
            clearBtn.draw(g, font, mouseX, mouseY);
        }

        if (currentMode == Mode.NORMAL) {
            for (SlotWidget sw : slotWidgets) {
                if (mouseX >= sw.x && mouseX < sw.x + sw.size && mouseY >= sw.y && mouseY < sw.y + sw.size) {
                    ShopSlot slot = shopBE.getSlot(sw.slotIndex);
                    if (slot != null && !slot.isEmpty()) {
                        g.renderTooltip(font, slot.getDisplayItem(), mouseX, mouseY);
                    }
                }
            }
        }

        if (currentMode != Mode.NORMAL) {
            renderPickerOverlay(g, mouseX, mouseY);
        }
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

        int borderColor = selected ? COL_ACCENT : COL_BORDER;
        g.fill(sw.x, sw.y, sw.x + sw.size, sw.y + sw.size, borderColor);
        g.fill(sw.x + 1, sw.y + 1, sw.x + sw.size - 1, sw.y + sw.size - 1, bg);

        if (slot != null && !slot.isEmpty()) {
            g.renderItem(slot.getDisplayItem(), sw.x + 4, sw.y + 4);

            if (slot.isOutOfStock()) {
                g.fill(sw.x + 2, sw.y + 2, sw.x + sw.size - 2, sw.y + sw.size - 2, 0x66FF0000);
            }
        } else {
            g.drawCenteredString(font, "+", sw.x + sw.size / 2, sw.y + sw.size / 2 - 4, COL_TEXT_DIM);
        }
    }

    private void renderSlotDetails(GuiGraphics g, int mouseX, int mouseY) {
        int panelX = leftX + 110;
        int panelY = topY + 22;
        int panelW = GUI_WIDTH - 115;
        int panelH = GUI_HEIGHT - 27;

        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, COL_BG_INNER);
        g.fill(panelX, panelY, panelX + 1, panelY + panelH, COL_BORDER);

        if (selectedSlot < 0) {
            g.drawCenteredString(font, "Selecciona un slot", panelX + panelW / 2, panelY + panelH / 2 - 8,
                    COL_TEXT_DIM);
            g.drawCenteredString(font, "para configurarlo", panelX + panelW / 2, panelY + panelH / 2 + 4,
                    COL_TEXT_DIM);
            return;
        }

        ShopSlot slot = shopBE.getSlot(selectedSlot);
        int cx = panelX + 10;
        int cy = panelY + 8;

        String slotLabel = "Slot #" + (selectedSlot + 1);
        String groupLabel = selectedSlot < ShopBlockEntity.SLOTS_PER_GROUP ?
                " (Estante Izq.)" : " (Estante Der.)";
        g.drawString(font, slotLabel + groupLabel, cx, cy, COL_ACCENT);
        cy += 14;

        g.fill(cx, cy, panelX + panelW - 10, cy + 1, COL_BORDER);
        cy += 5;

        if (slot != null && !slot.isEmpty()) {
            g.renderItem(slot.getDisplayItem(), cx, cy);
            g.drawString(font, slot.getDisplayItem().getHoverName().getString(), cx + 20, cy + 4, COL_TEXT);
            cy += 22;

            g.drawString(font, "── Precio ──", cx, cy, COL_TEXT_DIM);
            cy += 12;
            if (!slot.getPriceItem().isEmpty()) {
                g.renderItem(slot.getPriceItem(), cx, cy);
                g.drawString(font, " x" + slot.getPriceAmount() + "  " +
                                slot.getPriceItem().getHoverName().getString(),
                        cx + 18, cy + 4, COL_YELLOW);
            } else {
                g.drawString(font, "(Sin precio definido)", cx, cy + 4, COL_RED);
            }
            cy += 22;

            g.drawString(font, "── Stock ──", cx, cy, COL_TEXT_DIM);
            cy += 12;
            if (slot.hasInfiniteStock()) {
                g.drawString(font, "∞ Infinito", cx, cy, COL_GREEN);
            } else {
                int current = slot.getCurrentStock();
                int max = slot.getMaxStock();
                int color = current > 0 ? COL_GREEN : COL_RED;
                g.drawString(font, current + " / " + max, cx, cy, color);
            }
        } else {
            g.drawString(font, "Slot vacío", cx, cy, COL_TEXT_DIM);
            cy += 12;
            g.drawString(font, "Usa 'Cambiar Item' para", cx, cy, COL_TEXT_DIM);
            cy += 10;
            g.drawString(font, "asignar un producto.", cx, cy, COL_TEXT_DIM);
        }

        int fieldLabelX = panelX + 10;
        int fieldBaseY = topY + 109;
        g.drawString(font, "Max Stock:", fieldLabelX, fieldBaseY + 72, COL_TEXT_DIM);
        g.drawString(font, "Cant. Precio:", fieldLabelX, fieldBaseY + 92, COL_TEXT_DIM);
    }

    // ==================== ITEM PICKER ====================

    private void renderPickerOverlay(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(0, 0, width, height, 0xAA000000);

        drawPanel(g, pickerX, pickerY, PICKER_WIDTH, PICKER_HEIGHT);
        drawAccentBar(g, pickerX, pickerY, PICKER_WIDTH, 18);

        String pickerTitle = currentMode == Mode.PICKING_DISPLAY_ITEM ?
                "Seleccionar Item" : "Seleccionar Item de Precio";
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

        // Route clicks to custom buttons and text fields
        if (selectedSlot >= 0) {
            if (changeItemBtn.mouseClicked(mouseX, mouseY)) return true;
            if (changePriceBtn.mouseClicked(mouseX, mouseY)) return true;
            if (applyBtn.mouseClicked(mouseX, mouseY)) return true;
            if (restockBtn.mouseClicked(mouseX, mouseY)) return true;
            if (clearBtn.mouseClicked(mouseX, mouseY)) return true;
            if (stockField.isMouseOver(mouseX, mouseY)) {
                stockField.setFocused(true);
                priceAmountField.setFocused(false);
                return true;
            }
            if (priceAmountField.isMouseOver(mouseX, mouseY)) {
                priceAmountField.setFocused(true);
                stockField.setFocused(false);
                return true;
            }
            // Clicking elsewhere unfocuses both fields
            stockField.setFocused(false);
            priceAmountField.setFocused(false);
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
        if (selectedSlot < 0) {
            closeItemPicker();
            return;
        }

        ShopSlot slot = shopBE.getSlot(selectedSlot);
        if (slot == null) {
            closeItemPicker();
            return;
        }

        if (currentMode == Mode.PICKING_DISPLAY_ITEM) {
            ModNetworking.sendSetSlotItem(shopPos, selectedSlot, item);
            slot.setDisplayItem(item);
            if (slot.getPriceItem().isEmpty()) {
                ItemStack emerald = new ItemStack(Items.EMERALD);
                ModNetworking.sendSetSlotPrice(shopPos, selectedSlot, emerald, 1);
                slot.setPriceItem(emerald);
                slot.setPriceAmount(1);
            }
        } else if (currentMode == Mode.PICKING_PRICE_ITEM) {
            int amount;
            try {
                amount = Integer.parseInt(priceAmountField.getValue().trim());
                if (amount < 1) amount = 1;
            } catch (NumberFormatException e) {
                amount = 1;
            }
            ModNetworking.sendSetSlotPrice(shopPos, selectedSlot, item, amount);
            slot.setPriceItem(item);
            slot.setPriceAmount(amount);
        }

        closeItemPicker();
    }

    private void closeItemPicker() {
        currentMode = Mode.NORMAL;
        pickerSearchField.setValue("");
        pickerSearchField.setFocused(false);
    }

    private void selectSlot(int slotIndex) {
        selectedSlot = slotIndex;
        ShopSlot slot = shopBE.getSlot(slotIndex);
        if (slot != null && !slot.isEmpty()) {
            stockField.setValue(slot.getMaxStock() == ShopSlot.INFINITE_STOCK ?
                    "-1" : String.valueOf(slot.getMaxStock()));
            priceAmountField.setValue(String.valueOf(slot.getPriceAmount()));
        } else {
            stockField.setValue("-1");
            priceAmountField.setValue("1");
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
            if (priceAmountField.keyPressed(keyCode)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (currentMode != Mode.NORMAL) {
            if (pickerSearchField.charTyped(codePoint)) return true;
        } else {
            if (stockField.charTyped(codePoint)) return true;
            if (priceAmountField.charTyped(codePoint)) return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    // ==================== ACTIONS ====================

    private void applyStockAndPrice() {
        if (selectedSlot < 0) return;
        try {
            int maxStock = Integer.parseInt(stockField.getValue().trim());
            ModNetworking.sendSetSlotStock(shopPos, selectedSlot, maxStock);
        } catch (NumberFormatException e) {
            // ignore
        }
        try {
            int priceAmt = Integer.parseInt(priceAmountField.getValue().trim());
            if (priceAmt > 0) {
                ShopSlot slot = shopBE.getSlot(selectedSlot);
                if (slot != null && !slot.getPriceItem().isEmpty()) {
                    ModNetworking.sendSetSlotPrice(shopPos, selectedSlot, slot.getPriceItem(), priceAmt);
                }
            }
        } catch (NumberFormatException e) {
            // ignore
        }
    }

    private void restockSelected() {
        if (selectedSlot < 0) return;
        ModNetworking.sendRestockSlot(shopPos, selectedSlot);
    }

    private void clearSelected() {
        if (selectedSlot < 0) return;
        ModNetworking.sendClearSlot(shopPos, selectedSlot);
        selectedSlot = -1;
        stockField.setFocused(false);
        priceAmountField.setFocused(false);
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
            g.fill(x, y, x + w, y + h, 0xFF111122);
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
