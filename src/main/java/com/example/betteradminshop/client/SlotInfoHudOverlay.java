package com.example.betteradminshop.client;

import com.example.betteradminshop.block.ShopBlock;
import com.example.betteradminshop.block.ShopBlockEntity;
import com.example.betteradminshop.block.ShopPart;
import com.example.betteradminshop.block.ShopSlot;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Panel de información estilo Jade en la esquina inferior derecha cuando el
 * jugador mira un slot de venta de la tienda.
 *
 * Muestra: el ítem en venta (y cuántas unidades entrega cada compra), el
 * precio (uno o dos ítems) y el stock disponible.
 *
 * Comparte la esquina con {@link EntregaHudOverlay}: nunca coinciden porque
 * getClickedSlot devuelve un índice >= 0 para slots y -3 para la entrega.
 */
public class SlotInfoHudOverlay implements LayeredDraw.Layer {

    // ── Paleta (igual a EntregaHudOverlay / ShopAdminScreen) ─────────────────
    private static final int COL_BG         = 0xD4181825;
    private static final int COL_BG_HEADER  = 0xD4222235;
    private static final int COL_GLOW       = 0x556C63FF;
    private static final int COL_BORDER     = 0xFF6C63FF;
    private static final int COL_SEP        = 0xFF3A3A55;
    private static final int COL_TITLE      = 0xFFE0E0E0;
    private static final int COL_LABEL      = 0xFF888899;
    private static final int COL_PRICE      = 0xFFFFDD55;
    private static final int COL_STOCK_OK   = 0xFF55DD55;
    private static final int COL_STOCK_LOW  = 0xFFFFDD55;
    private static final int COL_STOCK_OUT  = 0xFFFF5555;
    private static final int COL_ACCENT     = 0xFF6C63FF;
    private static final int COL_VENTA      = 0xFF66EE66;
    private static final int COL_COMPRA     = 0xFF66BBFF;

    private static final int W      = 190;
    private static final int PAD    = 5;
    private static final int LINE_H = 11;
    private static final int ICON   = 16;

    @Override
    public void render(GuiGraphics gui, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.player.isSpectator()) return; // oculto en modo espectador (cinemáticas)
        if (mc.screen != null) return; // ocultar cuando hay una pantalla abierta
        if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK) return;

        BlockHitResult hit = (BlockHitResult) mc.hitResult;
        BlockPos hitPos = hit.getBlockPos();
        BlockState hitState = mc.level.getBlockState(hitPos);
        if (!(hitState.getBlock() instanceof ShopBlock)) return;

        Direction facing = hitState.getValue(ShopBlock.FACING);
        ShopPart part = hitState.getValue(ShopBlock.PART);
        BlockPos originPos = part.getOriginPos(hitPos, facing);
        if (!(mc.level.getBlockEntity(originPos) instanceof ShopBlockEntity be)) return;

        float pt = delta.getGameTimeDeltaPartialTick(false);
        Vec3 eye  = mc.player.getEyePosition(pt);
        Vec3 look = mc.player.getViewVector(pt);
        int slotIdx = be.getClickedSlot(eye, look, mc.level.getBlockState(originPos));
        if (slotIdx < 0) return;

        ShopSlot slot = be.getSlot(slotIdx);
        if (slot == null || slot.isEmpty()) return;

        drawPanel(mc, gui, slot, mc.player.getUUID());
    }

    // ── Panel ─────────────────────────────────────────────────────────────────

    private void drawPanel(Minecraft mc, GuiGraphics g, ShopSlot slot, java.util.UUID playerId) {
        Font font = mc.font;
        long now = System.currentTimeMillis();

        boolean twoPrices = slot.hasSecondPrice();
        boolean compra = slot.isCompra();
        int typeColor = compra ? COL_COMPRA : COL_VENTA;

        boolean showTimer = !slot.hasInfiniteStock() && slot.hasActiveTimer(playerId, now);

        // Alturas de secciones
        int headerH = PAD + ICON + PAD + LINE_H; // + línea de tipo
        int priceH  = PAD + LINE_H + (ICON + 2) + (twoPrices ? ICON + 2 : 0) + PAD / 2;
        int stockH  = PAD / 2 + LINE_H + (showTimer ? LINE_H : 0) + PAD;
        int totalH  = headerH + 1 + priceH + 1 + stockH;

        int px = mc.getWindow().getGuiScaledWidth() - W - 8;
        int py = mc.getWindow().getGuiScaledHeight() - totalH - 8;

        // Marco (barra lateral con el color del tipo)
        g.fill(px - 1, py - 1, px + W + 1, py + totalH + 1, COL_GLOW);
        g.fill(px, py, px + W, py + totalH, COL_BG);
        g.fill(px, py, px + W, py + headerH, COL_BG_HEADER);
        g.fill(px, py, px + 2, py + totalH, typeColor);
        g.fill(px, py, px + W, py + 1, typeColor);

        int contentX = px + 2 + PAD;

        // ── Encabezado: ítem (con cantidad) + nombre ──────────────────────────
        int iconY = py + PAD;
        ItemStack saleIcon = slot.getDisplayItem().copyWithCount(slot.getSellAmount());
        g.renderItem(saleIcon, contentX, iconY);
        g.renderItemDecorations(font, saleIcon, contentX, iconY);

        int titleX = contentX + ICON + 4;
        int titleY = iconY + (ICON - font.lineHeight) / 2 - 3;
        String title = slot.getSellAmount() > 1
                ? slot.getSellAmount() + "× " + slot.getDisplayItem().getHoverName().getString()
                : slot.getDisplayItem().getHoverName().getString();
        int maxTitleW = W - (titleX - px) - PAD;
        if (font.width(title) > maxTitleW) {
            title = font.plainSubstrByWidth(title, maxTitleW - 6) + "…";
        }
        g.drawString(font, title, titleX, titleY, COL_TITLE, true);
        // Etiqueta de tipo bajo el nombre
        String typeLabel = compra ? "◆ La tienda te lo COMPRA" : "◆ La tienda te lo VENDE";
        g.drawString(font, typeLabel, titleX, titleY + LINE_H, typeColor, false);

        // ── Separador ─────────────────────────────────────────────────────────
        int sep1Y = py + headerH;
        g.fill(px + 2, sep1Y, px + W, sep1Y + 1, COL_SEP);

        // ── Precio / Pago ─────────────────────────────────────────────────────
        int cy = sep1Y + 1 + PAD;
        g.drawString(font, compra ? "★ Te pagan:" : "★ Precio:", contentX, cy, COL_ACCENT, false);
        cy += LINE_H;

        cy = drawPriceLine(g, font, contentX, cy, slot.getPriceItem(), slot.getPriceAmount());
        if (twoPrices) {
            cy = drawPriceLine(g, font, contentX, cy, slot.getPriceItem2(), slot.getPriceAmount2());
        }
        cy += PAD / 2;

        // ── Separador 2 ───────────────────────────────────────────────────────
        g.fill(px + 2, cy, px + W, cy + 1, COL_SEP);
        cy += 1 + PAD / 2;

        // ── Stock / cupo de compra (por jugador) ──────────────────────────────
        String stockLabel = compra ? "Tu cupo: " : "Tu stock: ";
        int labelW = font.width(stockLabel);
        g.drawString(font, stockLabel, contentX, cy, COL_LABEL, false);
        int remaining = slot.getRemaining(playerId, now);
        String stockStr;
        int stockColor;
        if (slot.hasInfiniteStock()) {
            stockStr = compra ? "∞ Ilimitado" : "∞ Infinito";
            stockColor = COL_STOCK_OK;
        } else if (remaining < slot.getSellAmount()) {
            stockStr = compra ? "✖ Cupo lleno" : "✖ Agotado";
            stockColor = COL_STOCK_OUT;
        } else {
            stockStr = remaining + " / " + slot.getMaxStock();
            stockColor = remaining <= slot.getSellAmount() * 3 ? COL_STOCK_LOW : COL_STOCK_OK;
        }
        g.drawString(font, stockStr, contentX + labelW, cy, stockColor, false);

        // ── Temporizador de reabastecimiento (24h) ────────────────────────────
        if (showTimer) {
            cy += LINE_H;
            long secs = slot.getResetRemainingSeconds(playerId, now);
            String timer = "⏱ Reabastece en " + com.example.betteradminshop.block.ShopBlock.formatDuration(secs);
            g.drawString(font, timer, contentX, cy, COL_STOCK_LOW, false);
        }
    }

    private int drawPriceLine(GuiGraphics g, Font font, int x, int cy, ItemStack priceItem, int amount) {
        if (priceItem.isEmpty()) {
            g.drawString(font, "(gratis)", x, cy + 4, COL_LABEL, false);
            return cy + ICON + 2;
        }
        g.fill(x - 1, cy - 1, x + ICON + 1, cy + ICON + 1, 0x552A2A40);
        g.renderItem(priceItem, x, cy);
        String label = amount + "× " + priceItem.getHoverName().getString();
        int maxW = W - 2 - PAD - ICON - 4 - PAD;
        if (font.width(label) > maxW) {
            label = font.plainSubstrByWidth(label, maxW - 6) + "…";
        }
        g.drawString(font, label, x + ICON + 4, cy + (ICON - font.lineHeight) / 2, COL_PRICE, false);
        return cy + ICON + 2;
    }
}
