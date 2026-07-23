package com.example.betteradminshop.client;

import com.example.betteradminshop.block.DeliveryEntry;
import com.example.betteradminshop.block.ShopBlock;
import com.example.betteradminshop.block.ShopBlockEntity;
import com.example.betteradminshop.block.ShopPart;
import com.simibubi.create.content.logistics.box.PackageItem;

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

import java.util.List;

/**
 * Jade-style HUD panel rendered in the bottom-left corner when the player
 * looks at the entrega (delivery pickup) zone of a BetterAdminShop block.
 *
 * Displays:
 *  - A cardboard-box icon + "Zona de Entrega" title
 *  - Who purchased (buyer name)
 *  - Countdown timer (or "ready to pick up")
 *  - List of items inside the package
 */
public class EntregaHudOverlay implements LayeredDraw.Layer {

    // Cached package icon – same approach as ShopBlockEntityRenderer
    static final ItemStack PACKAGE_ICON = PackageItem.containing(java.util.List.of());

    // ── Panel colors (same palette as ShopAdminScreen) ────────────────────────
    private static final int COL_BG         = 0xD4181825;
    private static final int COL_BG_HEADER  = 0xD4222235;
    private static final int COL_GLOW       = 0x556C63FF;  // subtle border glow
    private static final int COL_BORDER     = 0xFF6C63FF;  // accent purple (left bar + top)
    private static final int COL_SEP        = 0xFF3A3A55;  // dim separator line
    private static final int COL_TITLE      = 0xFFE0E0E0;
    private static final int COL_LABEL      = 0xFF888899;  // muted labels
    private static final int COL_TEXT       = 0xFFD0D0D8;
    private static final int COL_BUYER      = 0xFFADD8FF;  // light blue for player name
    private static final int COL_TIMER_OK   = 0xFF55DD55;  // green  – time remaining
    private static final int COL_TIMER_WARN = 0xFFFFDD55;  // yellow – <1 min
    private static final int COL_AVAILABLE  = 0xFF88EE88;  // light green – ready to pick up
    private static final int COL_ACCENT     = 0xFF6C63FF;  // purple accent for icons

    private static final int W       = 190; // panel width
    private static final int PAD     = 5;
    private static final int LINE_H  = 11;  // font height (9) + 2 gap
    private static final int ICON    = 16;
    private static final int MAX_ITEMS = 6; // items shown before truncation

    @Override
    public void render(GuiGraphics gui, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.player.isSpectator()) return; // oculto en modo espectador (cinemáticas)
        if (mc.screen != null) return; // hide when a screen is open
        if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK) return;

        BlockHitResult hit = (BlockHitResult) mc.hitResult;
        BlockPos hitPos = hit.getBlockPos();
        BlockState hitState = mc.level.getBlockState(hitPos);
        if (!(hitState.getBlock() instanceof ShopBlock)) return;

        Direction facing = hitState.getValue(ShopBlock.FACING);
        ShopPart part = hitState.getValue(ShopBlock.PART);
        BlockPos originPos = part.getOriginPos(hitPos, facing);
        if (!(mc.level.getBlockEntity(originPos) instanceof ShopBlockEntity be)) return;
        if (!be.hasDelivery()) return;

        // Confirm the player is actually aiming at the entrega zone
        float pt = delta.getGameTimeDeltaPartialTick(false);
        Vec3 eye  = mc.player.getEyePosition(pt);
        Vec3 look = mc.player.getViewVector(pt);
        if (be.getClickedSlot(eye, look, mc.level.getBlockState(originPos)) != -3) return;

        DeliveryEntry delivery = be.peekDelivery();
        if (delivery == null) return;

        drawPanel(mc, gui, delivery);
    }

    // ── Panel rendering ───────────────────────────────────────────────────────

    private void drawPanel(Minecraft mc, GuiGraphics g, DeliveryEntry delivery) {
        Font font = mc.font;
        List<ItemStack> items = delivery.getItems();
        int shown    = Math.min(items.size(), MAX_ITEMS);
        boolean more = items.size() > MAX_ITEMS;

        // ── Calculate heights ─────────────────────────────────────────────────
        int headerH = PAD + ICON + PAD;          // 26
        int infoH   = PAD + LINE_H + LINE_H + PAD; // 32  (buyer + timer)
        int itemsH  = PAD / 2 + shown * (ICON + 2) + (more ? LINE_H : 0) + PAD;
        int totalH  = headerH + 1 + infoH + 1 + itemsH;

        // ── Panel position: bottom-right corner ───────────────────────────────
        int px = mc.getWindow().getGuiScaledWidth() - W - 8;
        int py = mc.getWindow().getGuiScaledHeight() - totalH - 8;

        // ── Outer glow ────────────────────────────────────────────────────────
        g.fill(px - 1, py - 1, px + W + 1, py + totalH + 1, COL_GLOW);

        // ── Background ────────────────────────────────────────────────────────
        g.fill(px, py, px + W, py + totalH, COL_BG);
        g.fill(px, py, px + W, py + headerH, COL_BG_HEADER);

        // ── Left accent bar ───────────────────────────────────────────────────
        g.fill(px, py, px + 2, py + totalH, COL_BORDER);
        // ── Top border ────────────────────────────────────────────────────────
        g.fill(px, py, px + W, py + 1, COL_BORDER);

        // ── Header: package icon + title + item count ─────────────────────────
        int contentX = px + 2 + PAD; // left edge of content (after accent bar + pad)
        int iconY    = py + PAD;

        g.renderItem(PACKAGE_ICON, contentX, iconY);

        int titleX = contentX + ICON + 4;
        int titleY = iconY + (ICON - font.lineHeight) / 2;
        g.drawString(font, "Zona de Entrega", titleX, titleY, COL_TITLE, true);

        // Item count badge – top-right corner of header
        String countBadge = items.size() + (items.size() == 1 ? " \u00edtem" : " \u00edtems");
        g.drawString(font, countBadge, px + W - PAD - font.width(countBadge), titleY, COL_LABEL, false);

        // ── Separator 1 ───────────────────────────────────────────────────────
        int sep1Y = py + headerH;
        g.fill(px + 2, sep1Y, px + W, sep1Y + 1, COL_SEP);

        // ── Info section ──────────────────────────────────────────────────────
        int cy = sep1Y + 1 + PAD;

        // Buyer row  ★ icon + label + name
        String buyerLabel = "\u2605 Comprador: ";
        int buyerLabelW = font.width(buyerLabel);
        g.drawString(font, buyerLabel, contentX, cy, COL_ACCENT, false);
        g.drawString(font, delivery.getBuyerName(), contentX + buyerLabelW, cy, COL_BUYER, true);
        cy += LINE_H;

        // Timer row  ⏱ / ✔
        long remSecs = delivery.remainingProtectionSeconds();
        String timerStr;
        int timerColor;
        if (remSecs > 0) {
            long m = remSecs / 60, s = remSecs % 60;
            timerStr  = String.format("\u23F1 Disponible en %d:%02d", m, s);
            timerColor = remSecs > 60 ? COL_TIMER_OK : COL_TIMER_WARN;
        } else {
            timerStr  = "\u2714 Listo para recoger";
            timerColor = COL_AVAILABLE;
        }
        g.drawString(font, timerStr, contentX, cy, timerColor, false);
        cy += LINE_H + PAD;

        // ── Separator 2 ───────────────────────────────────────────────────────
        g.fill(px + 2, cy, px + W, cy + 1, COL_SEP);
        cy += 1 + PAD / 2 + 1;

        // ── Items list ────────────────────────────────────────────────────────
        int maxNameW = W - 2 - PAD - ICON - 4 - PAD - 4; // available pixels for item name
        for (int i = 0; i < shown; i++) {
            ItemStack stack = items.get(i);

            // Slot background (subtle)
            g.fill(contentX - 1, cy - 1, contentX + ICON + 1, cy + ICON + 1, 0x552A2A40);

            g.renderItem(stack, contentX, cy);
            g.renderItemDecorations(font, stack, contentX, cy);

            // Name
            String raw  = stack.getHoverName().getString();
            String name = font.plainSubstrByWidth(raw, maxNameW);
            int nameY   = cy + (ICON - font.lineHeight) / 2;
            g.drawString(font, name, contentX + ICON + 4, nameY, COL_TEXT, false);

            cy += ICON + 2;
        }

        if (more) {
            int extra = items.size() - MAX_ITEMS;
            g.drawString(font, "  \u2026 y " + extra + " m\u00e1s", contentX, cy, COL_LABEL, false);
        }
    }
}
