package com.example.betteradminshop.client;

import com.example.betteradminshop.block.DeliveryEntry;
import com.example.betteradminshop.block.PlayerShopBlock;
import com.example.betteradminshop.block.PlayerShopBlockEntity;
import com.example.betteradminshop.block.PlayerShopSlot;
import com.example.betteradminshop.block.ShopPart;

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
 * Panel HUD (esquina inferior derecha) para la TIENDA DE JUGADOR.
 *
 *  - Mirando una bandeja: ítem, precio, stock real disponible y dueño.
 *  - Mirando la zona de pago: resumen "confirmar compra".
 *  - Mirando la entrega: comprador, protección y nº de ítems.
 *  - Si la renta está vencida: aviso de tienda cerrada.
 */
public class PlayerShopHudOverlay implements LayeredDraw.Layer {

    private static final int COL_BG        = 0xD4181825;
    private static final int COL_BG_HEADER = 0xD4222235;
    private static final int COL_GLOW      = 0x556C63FF;
    private static final int COL_SEP       = 0xFF3A3A55;
    private static final int COL_TITLE     = 0xFFE0E0E0;
    private static final int COL_LABEL     = 0xFF888899;
    private static final int COL_PRICE     = 0xFFFFDD55;
    private static final int COL_OK        = 0xFF55DD55;
    private static final int COL_LOW       = 0xFFFFDD55;
    private static final int COL_OUT       = 0xFFFF5555;
    private static final int COL_OWNER     = 0xFFADD8FF;
    private static final int COL_ACCENT    = 0xFF55DD88;   // verde tienda de jugador

    private static final int W = 190;
    private static final int PAD = 5;
    private static final int LINE_H = 11;
    private static final int ICON = 16;

    @Override
    public void render(GuiGraphics gui, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.player.isSpectator()) return;
        if (mc.screen != null) return;
        if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK) return;

        BlockHitResult hit = (BlockHitResult) mc.hitResult;
        BlockPos hitPos = hit.getBlockPos();
        BlockState hitState = mc.level.getBlockState(hitPos);
        if (!(hitState.getBlock() instanceof PlayerShopBlock)) return;

        Direction facing = hitState.getValue(PlayerShopBlock.FACING);
        ShopPart part = hitState.getValue(PlayerShopBlock.PART);
        BlockPos originPos = part.getOriginPos(hitPos, facing);
        if (!(mc.level.getBlockEntity(originPos) instanceof PlayerShopBlockEntity be)) return;

        float pt = delta.getGameTimeDeltaPartialTick(false);
        Vec3 eye = mc.player.getEyePosition(pt);
        Vec3 look = mc.player.getViewVector(pt);
        int target = be.getClickedSlot(eye, look, mc.level.getBlockState(originPos));

        if (target == -3 && be.hasDelivery()) {
            drawDeliveryPanel(mc, gui, be);
        } else if (target >= 0) {
            PlayerShopSlot slot = be.getSlot(target);
            if (slot != null && !slot.isEmpty()) {
                drawSlotPanel(mc, gui, be, slot, target);
            }
        }
    }

    // ── Panel de slot ────────────────────────────────────────────────────────

    private void drawSlotPanel(Minecraft mc, GuiGraphics g, PlayerShopBlockEntity be,
                               PlayerShopSlot slot, int slotIndex) {
        Font font = mc.font;
        long now = System.currentTimeMillis();
        boolean closed = !be.isOperational(now);

        int headerH = PAD + ICON + PAD + LINE_H;         // ítem + dueño
        int priceH  = PAD + LINE_H + (ICON + 2) + PAD / 2;
        int stockH  = PAD / 2 + LINE_H + (closed ? LINE_H : 0) + PAD;
        int totalH  = headerH + 1 + priceH + 1 + stockH;

        int px = mc.getWindow().getGuiScaledWidth() - W - 8;
        int py = mc.getWindow().getGuiScaledHeight() - totalH - 8;

        g.fill(px - 1, py - 1, px + W + 1, py + totalH + 1, COL_GLOW);
        g.fill(px, py, px + W, py + totalH, COL_BG);
        g.fill(px, py, px + W, py + headerH, COL_BG_HEADER);
        g.fill(px, py, px + 2, py + totalH, COL_ACCENT);
        g.fill(px, py, px + W, py + 1, COL_ACCENT);

        int contentX = px + 2 + PAD;

        // Encabezado: ítem (con cantidad por compra) + nombre + dueño
        int iconY = py + PAD;
        ItemStack icon = slot.getSaleItem().copyWithCount(slot.getSellAmount());
        g.renderItem(icon, contentX, iconY);
        g.renderItemDecorations(font, icon, contentX, iconY);

        int titleX = contentX + ICON + 4;
        int titleY = iconY + (ICON - font.lineHeight) / 2 - 3;
        String title = slot.getSellAmount() > 1
                ? slot.getSellAmount() + "× " + slot.getSaleItem().getHoverName().getString()
                : slot.getSaleItem().getHoverName().getString();
        title = clip(font, title, W - (titleX - px) - PAD);
        g.drawString(font, title, titleX, titleY, COL_TITLE, true);
        g.drawString(font, "◆ Tienda de " + be.getOwnerName(), titleX, titleY + LINE_H, COL_OWNER, false);

        int sepY = py + headerH;
        g.fill(px + 2, sepY, px + W, sepY + 1, COL_SEP);

        // Precio
        int cy = sepY + 1 + PAD;
        g.drawString(font, "★ Precio:", contentX, cy, COL_ACCENT, false);
        cy += LINE_H;
        ItemStack price = slot.getPriceItem();
        if (price.isEmpty()) {
            g.drawString(font, "✖ Sin precio — no disponible", contentX, cy + 4, COL_OUT, false);
        } else {
            g.fill(contentX - 1, cy - 1, contentX + ICON + 1, cy + ICON + 1, 0x552A2A40);
            g.renderItem(price, contentX, cy);
            String label = clip(font, slot.getPriceAmount() + "× " + price.getHoverName().getString(),
                    W - 2 - PAD - ICON - 4 - PAD);
            g.drawString(font, label, contentX + ICON + 4,
                    cy + (ICON - font.lineHeight) / 2, COL_PRICE, false);
        }
        cy += ICON + 2 + PAD / 2;

        g.fill(px + 2, cy, px + W, cy + 1, COL_SEP);
        cy += 1 + PAD / 2;

        // Stock disponible (real, del inventario de la tienda)
        int available = be.stockFor(slotIndex);
        String stockLabel = "Stock: ";
        g.drawString(font, stockLabel, contentX, cy, COL_LABEL, false);
        String stockStr;
        int stockColor;
        if (available < slot.getSellAmount()) {
            stockStr = "✖ Agotado";
            stockColor = COL_OUT;
        } else {
            stockStr = available + " unidades";
            stockColor = available <= slot.getSellAmount() * 3 ? COL_LOW : COL_OK;
        }
        g.drawString(font, stockStr, contentX + font.width(stockLabel), cy, stockColor, false);

        if (closed) {
            cy += LINE_H;
            g.drawString(font, "⚠ CERRADA — renta pendiente", contentX, cy, COL_OUT, false);
        }
    }

    // ── Panel de entrega ─────────────────────────────────────────────────────

    private void drawDeliveryPanel(Minecraft mc, GuiGraphics g, PlayerShopBlockEntity be) {
        Font font = mc.font;
        DeliveryEntry delivery = be.peekDelivery();
        if (delivery == null) return;

        int headerH = PAD + ICON + PAD;
        int infoH = PAD + LINE_H + LINE_H + PAD;
        int totalH = headerH + 1 + infoH;

        int px = mc.getWindow().getGuiScaledWidth() - W - 8;
        int py = mc.getWindow().getGuiScaledHeight() - totalH - 8;

        g.fill(px - 1, py - 1, px + W + 1, py + totalH + 1, COL_GLOW);
        g.fill(px, py, px + W, py + totalH, COL_BG);
        g.fill(px, py, px + W, py + headerH, COL_BG_HEADER);
        g.fill(px, py, px + 2, py + totalH, COL_ACCENT);
        g.fill(px, py, px + W, py + 1, COL_ACCENT);

        int contentX = px + 2 + PAD;
        int iconY = py + PAD;
        g.renderItem(PlayerShopRendererIcons.PACKAGE_ICON, contentX, iconY);
        int titleY = iconY + (ICON - font.lineHeight) / 2;
        g.drawString(font, "Retiro de compra", contentX + ICON + 4, titleY, COL_TITLE, true);
        String count = delivery.getItems().size() + (delivery.getItems().size() == 1 ? " ítem" : " ítems");
        g.drawString(font, count, px + W - PAD - font.width(count), titleY, COL_LABEL, false);

        int sepY = py + headerH;
        g.fill(px + 2, sepY, px + W, sepY + 1, COL_SEP);

        int cy = sepY + 1 + PAD;
        g.drawString(font, "★ Comprador: ", contentX, cy, COL_ACCENT, false);
        g.drawString(font, delivery.getBuyerName(),
                contentX + font.width("★ Comprador: "), cy, COL_OWNER, true);
        cy += LINE_H;

        long remSecs = delivery.remainingProtectionSeconds();
        if (remSecs > 0) {
            g.drawString(font, String.format("⏱ Disponible en %d:%02d", remSecs / 60, remSecs % 60),
                    contentX, cy, remSecs > 60 ? COL_OK : COL_LOW, false);
        } else {
            g.drawString(font, "✔ Listo para recoger", contentX, cy, COL_OK, false);
        }
    }

    private static String clip(Font font, String s, int maxW) {
        if (font.width(s) <= maxW) return s;
        return font.plainSubstrByWidth(s, Math.max(0, maxW - 6)) + "…";
    }

    /** Icono de cardboard compartido (cacheado, no alocar cada frame). */
    static final class PlayerShopRendererIcons {
        static final ItemStack PACKAGE_ICON =
                com.simibubi.create.content.logistics.box.PackageItem.containing(java.util.List.of());
    }
}
