package com.example.betteradminshop.client;

import com.example.betteradminshop.block.PlayerShopBlock;
import com.example.betteradminshop.block.PlayerShopBlockEntity;
import com.example.betteradminshop.block.PlayerShopSlot;
import com.example.betteradminshop.block.ShopPart;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.content.logistics.box.PackageItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Renderer de la TIENDA DE JUGADOR:
 *  - Ítems a la venta sobre las bandejas del tier activo de cada estante.
 *  - ✗ roja sobre los slots sin stock.
 *  - Recuadros de selección (bandeja / zona de pago / entrega) — respetan el
 *    keybind de ocultar y el modo espectador, igual que la tienda admin.
 *  - Cardboard girando FRENTE a "entregaDeCardboardConCompra" si hay entregas.
 */
public class PlayerShopRenderer implements BlockEntityRenderer<PlayerShopBlockEntity> {

    private final ItemRenderer itemRenderer;
    private static final float ITEM_SCALE = 0.24f;
    private static final float SELECT_BOX_HALF = 1.5f / 16f;
    /** Altura del ítem y del recuadro sobre la bandeja (compartida con el raycast). */
    private static final float ITEM_Y_OFFSET = PlayerShopBlockEntity.TRAY_Y_OFFSET;
    private static final ItemStack PACKAGE_DISPLAY_STACK = PackageItem.containing(java.util.List.of());

    public PlayerShopRenderer(BlockEntityRendererProvider.Context context) {
        this.itemRenderer = Minecraft.getInstance().getItemRenderer();
    }

    @Override
    public void render(PlayerShopBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {

        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof PlayerShopBlock)) return;
        Direction facing = state.getValue(PlayerShopBlock.FACING);

        int hovered = getHovered(be, partialTick);
        var localPlayer = Minecraft.getInstance().player;
        boolean spectator = localPlayer != null && localPlayer.isSpectator();
        boolean showBoxes = !ShopKeybinds.areBoxesHidden() && !spectator;

        poseStack.pushPose();
        applyFacingRotation(poseStack, facing);

        // ── Bandejas del estante izquierdo (slots 0..tier-1) ─────────────────
        float[][] left = PlayerShopBlockEntity.LEFT_TRAYS[be.getLeftTier() - 2];
        for (int i = 0; i < left.length; i++) {
            renderTray(be, poseStack, bufferSource, packedLight,
                    be.getSlot(i), left[i], hovered == i, showBoxes, i);
        }
        // ── Bandejas del estante derecho (slots 4..4+tier-1) ─────────────────
        float[][] right = PlayerShopBlockEntity.RIGHT_TRAYS[be.getRightTier() - 2];
        for (int i = 0; i < right.length; i++) {
            int idx = PlayerShopBlockEntity.SLOTS_PER_SHELF + i;
            renderTray(be, poseStack, bufferSource, packedLight,
                    be.getSlot(idx), right[i], hovered == idx, showBoxes, idx);
        }

        // ── Zona de pago (confirmar compra) ──────────────────────────────────
        if (showBoxes && hovered == -2) {
            renderAabbBox(poseStack, bufferSource,
                    PlayerShopBlockEntity.PAGO_MIN, PlayerShopBlockEntity.PAGO_MAX,
                    0.2f, 1f, 0.2f);
        }

        // ── Entrega: cardboard girando frente al depot ───────────────────────
        if (be.hasDelivery()) {
            renderDeliveryPackage(poseStack, bufferSource, packedLight);
        }
        if (showBoxes && hovered == -3) {
            float[] min = {PlayerShopBlockEntity.ENTREGA_MIN[0] - 0.06f,
                    PlayerShopBlockEntity.ENTREGA_MIN[1] - 0.03f,
                    PlayerShopBlockEntity.ENTREGA_MIN[2] - 0.20f};
            float[] max = {PlayerShopBlockEntity.ENTREGA_MAX[0] + 0.06f,
                    PlayerShopBlockEntity.ENTREGA_MAX[1] + 0.08f,
                    PlayerShopBlockEntity.ENTREGA_MAX[2]};
            renderAabbBox(poseStack, bufferSource, min, max, 1f, 0.8f, 0.1f);
        }

        poseStack.popPose();
    }

    private void renderTray(PlayerShopBlockEntity be, PoseStack poseStack, MultiBufferSource bufferSource,
                            int packedLight, PlayerShopSlot slot, float[] pos,
                            boolean hovered, boolean showBoxes, int slotIndex) {
        if (slot != null && !slot.isEmpty()) {
            poseStack.pushPose();
            poseStack.translate(pos[0], pos[1] + ITEM_Y_OFFSET, pos[2]);
            poseStack.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE);
            poseStack.mulPose(Axis.YP.rotationDegrees(180));
            itemRenderer.renderStatic(slot.getSaleItem(), ItemDisplayContext.FIXED, packedLight,
                    OverlayTexture.NO_OVERLAY, poseStack, bufferSource, null, 0);
            if (be.stockFor(slotIndex) < slot.getSellAmount()) {
                poseStack.pushPose();
                poseStack.translate(0, 0, -0.01f);
                renderOutOfStockOverlay(poseStack, bufferSource, packedLight);
                poseStack.popPose();
            }
            poseStack.popPose();
        }
        if (showBoxes && hovered) {
            // El recuadro acompaña al ítem (misma altura), no la bandeja
            float[] boxCenter = {pos[0], pos[1] + ITEM_Y_OFFSET, pos[2]};
            renderCenteredBox(poseStack, bufferSource, boxCenter, SELECT_BOX_HALF, 1f, 1f, 1f);
        }
    }

    /** Cardboard flotando y girando frente a "entregaDeCardboardConCompra". */
    private void renderDeliveryPackage(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        float cx = (PlayerShopBlockEntity.ENTREGA_MIN[0] + PlayerShopBlockEntity.ENTREGA_MAX[0]) / 2f;
        float cy = (PlayerShopBlockEntity.ENTREGA_MIN[1] + PlayerShopBlockEntity.ENTREGA_MAX[1]) / 2f;
        float cz = PlayerShopBlockEntity.ENTREGA_MIN[2] - 0.12f; // delante de la cara frontal

        long gameTime = Objects.requireNonNull(Minecraft.getInstance().level).getGameTime();
        float angle = ((gameTime % 80)
                + Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false)) * (360f / 80f);

        poseStack.pushPose();
        poseStack.translate(cx, cy, cz);
        poseStack.scale(0.45f, 0.45f, 0.45f);
        poseStack.mulPose(Axis.YP.rotationDegrees(angle));
        itemRenderer.renderStatic(PACKAGE_DISPLAY_STACK, ItemDisplayContext.FIXED,
                packedLight, OverlayTexture.NO_OVERLAY, poseStack, bufferSource, null, 0);
        poseStack.popPose();
    }

    // ── Raycast local (jugador local) ─────────────────────────────────────────

    private int getHovered(PlayerShopBlockEntity be, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK) return -1;
        if (mc.level == null || mc.player == null) return -1;
        BlockHitResult hit = (BlockHitResult) mc.hitResult;
        BlockPos hitPos = hit.getBlockPos();
        BlockState hitState = mc.level.getBlockState(hitPos);
        if (!(hitState.getBlock() instanceof PlayerShopBlock)) return -1;

        Direction facing = hitState.getValue(PlayerShopBlock.FACING);
        ShopPart part = hitState.getValue(PlayerShopBlock.PART);
        BlockPos originPos = part.getOriginPos(hitPos, facing);
        if (!originPos.equals(be.getBlockPos())) return -1;

        Vec3 eye = mc.player.getEyePosition(partialTick);
        Vec3 look = mc.player.getViewVector(partialTick);
        BlockState originState = mc.level.getBlockState(originPos);
        if (!(originState.getBlock() instanceof PlayerShopBlock)) return -1;
        return be.getClickedSlot(eye, look, originState);
    }

    // ── Primitivas de dibujo ─────────────────────────────────────────────────

    private void renderCenteredBox(PoseStack poseStack, MultiBufferSource bufferSource,
                                   float[] center, float half, float r, float g, float b) {
        float[] min = {center[0] - half, center[1] - half, center[2] - half};
        float[] max = {center[0] + half, center[1] + half, center[2] + half};
        renderAabbBox(poseStack, bufferSource, min, max, r, g, b);
    }

    private void renderAabbBox(PoseStack poseStack, MultiBufferSource bufferSource,
                               float[] min, float[] max, float r, float g, float b) {
        VertexConsumer vc = bufferSource.getBuffer(RenderType.lines());
        PoseStack.Pose pose = poseStack.last();
        float a = 0.85f;
        float x0 = min[0], y0 = min[1], z0 = min[2];
        float x1 = max[0], y1 = max[1], z1 = max[2];

        line(vc, pose, x0, y0, z0, x1, y0, z0, r, g, b, a);
        line(vc, pose, x1, y0, z0, x1, y0, z1, r, g, b, a);
        line(vc, pose, x1, y0, z1, x0, y0, z1, r, g, b, a);
        line(vc, pose, x0, y0, z1, x0, y0, z0, r, g, b, a);

        line(vc, pose, x0, y1, z0, x1, y1, z0, r, g, b, a);
        line(vc, pose, x1, y1, z0, x1, y1, z1, r, g, b, a);
        line(vc, pose, x1, y1, z1, x0, y1, z1, r, g, b, a);
        line(vc, pose, x0, y1, z1, x0, y1, z0, r, g, b, a);

        line(vc, pose, x0, y0, z0, x0, y1, z0, r, g, b, a);
        line(vc, pose, x1, y0, z0, x1, y1, z0, r, g, b, a);
        line(vc, pose, x1, y0, z1, x1, y1, z1, r, g, b, a);
        line(vc, pose, x0, y0, z1, x0, y1, z1, r, g, b, a);
    }

    private static void line(VertexConsumer vc, PoseStack.Pose pose,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float r, float g, float b, float a) {
        float nx = x1 - x0, ny = y1 - y0, nz = z1 - z0;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 0) { nx /= len; ny /= len; nz /= len; }
        vc.addVertex(pose, x0, y0, z0).setColor(r, g, b, a).setNormal(pose, nx, ny, nz);
        vc.addVertex(pose, x1, y1, z1).setColor(r, g, b, a).setNormal(pose, nx, ny, nz);
    }

    private void renderOutOfStockOverlay(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        Font font = Minecraft.getInstance().font;
        poseStack.pushPose();
        poseStack.scale(0.05f, -0.05f, 0.05f);
        poseStack.translate(-3, -6, 0);
        font.drawInBatch("✗", 0, 0, 0xFF0000, false, poseStack.last().pose(),
                bufferSource, Font.DisplayMode.NORMAL, 0, packedLight);
        poseStack.popPose();
    }

    private void applyFacingRotation(PoseStack poseStack, Direction facing) {
        float rotation = switch (facing) {
            case SOUTH -> 180f;
            case WEST -> 90f;
            case EAST -> -90f;
            default -> 0f;
        };
        if (rotation != 0) {
            poseStack.translate(0.5, 0, 0.5);
            poseStack.mulPose(Axis.YP.rotationDegrees(rotation));
            poseStack.translate(-0.5, 0, -0.5);
        }
    }

    @Override
    public boolean shouldRenderOffScreen(PlayerShopBlockEntity blockEntity) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 64;
    }
}
