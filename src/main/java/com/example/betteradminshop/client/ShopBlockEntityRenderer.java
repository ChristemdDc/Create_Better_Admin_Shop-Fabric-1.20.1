package com.example.betteradminshop.client;

import com.example.betteradminshop.block.ShopBlock;
import com.example.betteradminshop.block.ShopBlockEntity;
import com.example.betteradminshop.block.ShopPart;
import com.example.betteradminshop.block.ShopSlot;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class ShopBlockEntityRenderer implements BlockEntityRenderer<ShopBlockEntity> {

    private final ItemRenderer itemRenderer;
    private static final float ITEM_SCALE = 0.22f;
    private static final float SELECT_BOX_HALF = 1.4f / 16f;

    public ShopBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        this.itemRenderer = Minecraft.getInstance().getItemRenderer();
    }

    @Override
    public void render(ShopBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {

        ShopSlot[] slots = be.getSlots();
        BlockState state = be.getBlockState();
        Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);

        int hoveredSlot = getHoveredSlot(be, partialTick);

        poseStack.pushPose();
        applyFacingRotation(poseStack, facing);

        for (int i = 0; i < ShopBlockEntity.SLOTS_PER_GROUP; i++) {
            ShopSlot slot = slots[i];
            float[] pos = ShopBlockEntity.GROUP1_POSITIONS[i];
            if (!slot.isEmpty()) {
                renderShopItem(poseStack, bufferSource, slot, pos, packedLight, packedOverlay);
            }
            if (hoveredSlot == i) {
                renderSelectionBox(poseStack, bufferSource, pos);
            }
        }

        for (int i = 0; i < ShopBlockEntity.SLOTS_PER_GROUP; i++) {
            ShopSlot slot = slots[ShopBlockEntity.SLOTS_PER_GROUP + i];
            float[] pos = ShopBlockEntity.GROUP2_POSITIONS[i];
            if (!slot.isEmpty()) {
                renderShopItem(poseStack, bufferSource, slot, pos, packedLight, packedOverlay);
            }
            if (hoveredSlot == ShopBlockEntity.SLOTS_PER_GROUP + i) {
                renderSelectionBox(poseStack, bufferSource, pos);
            }
        }

        if (hoveredSlot == -2) {
            renderTendederoSelectionBox(poseStack, bufferSource);
        }

        poseStack.popPose();
    }

    private int getHoveredSlot(ShopBlockEntity be, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK) return -1;
        BlockHitResult blockHit = (BlockHitResult) mc.hitResult;

        BlockPos hitBlockPos = blockHit.getBlockPos();
        if (mc.level == null || mc.player == null) return -1;
        BlockState hitState = mc.level.getBlockState(hitBlockPos);
        if (!(hitState.getBlock() instanceof ShopBlock)) return -1;

        Direction facing = hitState.getValue(ShopBlock.FACING);
        ShopPart part = hitState.getValue(ShopBlock.PART);
        BlockPos originPos = part.getOriginPos(hitBlockPos, facing);
        if (!originPos.equals(be.getBlockPos())) return -1;

        // 1.21+: getFrameTime() was replaced by the DeltaTracker. We already
        // have the partialTick in render() so we just pass it through.
        Vec3 eyePos = mc.player.getEyePosition(partialTick);
        Vec3 lookDir = mc.player.getViewVector(partialTick);
        BlockState originState = mc.level.getBlockState(originPos);
        return be.getClickedSlot(eyePos, lookDir, originState);
    }

    private void renderShopItem(PoseStack poseStack, MultiBufferSource bufferSource,
                                ShopSlot slot, float[] pos, int packedLight, int packedOverlay) {
        ItemStack displayItem = slot.getDisplayItem();
        if (displayItem.isEmpty()) return;

        poseStack.pushPose();
        poseStack.translate(pos[0], pos[1], pos[2]);
        poseStack.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE);
        poseStack.mulPose(Axis.YP.rotationDegrees(180));

        itemRenderer.renderStatic(displayItem, ItemDisplayContext.FIXED, packedLight,
                OverlayTexture.NO_OVERLAY, poseStack, bufferSource, null, 0);

        if (slot.isOutOfStock()) {
            poseStack.pushPose();
            poseStack.translate(0, 0, -0.01f);
            renderOutOfStockOverlay(poseStack, bufferSource, packedLight);
            poseStack.popPose();
        }

        poseStack.popPose();
    }

    private void renderSelectionBox(PoseStack poseStack, MultiBufferSource bufferSource, float[] pos) {
        poseStack.pushPose();
        poseStack.translate(pos[0], pos[1], pos[2]);

        VertexConsumer vc = bufferSource.getBuffer(RenderType.lines());
        PoseStack.Pose pose = poseStack.last();

        float h = SELECT_BOX_HALF;
        float x0 = -h, y0 = -h, z0 = -h;
        float x1 = h, y1 = h, z1 = h;
        float r = 1f, g = 1f, b = 1f, a = 0.8f;

        // Bottom face
        line(vc, pose, x0, y0, z0, x1, y0, z0, r, g, b, a);
        line(vc, pose, x1, y0, z0, x1, y0, z1, r, g, b, a);
        line(vc, pose, x1, y0, z1, x0, y0, z1, r, g, b, a);
        line(vc, pose, x0, y0, z1, x0, y0, z0, r, g, b, a);
        // Top face
        line(vc, pose, x0, y1, z0, x1, y1, z0, r, g, b, a);
        line(vc, pose, x1, y1, z0, x1, y1, z1, r, g, b, a);
        line(vc, pose, x1, y1, z1, x0, y1, z1, r, g, b, a);
        line(vc, pose, x0, y1, z1, x0, y1, z0, r, g, b, a);
        // Vertical edges
        line(vc, pose, x0, y0, z0, x0, y1, z0, r, g, b, a);
        line(vc, pose, x1, y0, z0, x1, y1, z0, r, g, b, a);
        line(vc, pose, x1, y0, z1, x1, y1, z1, r, g, b, a);
        line(vc, pose, x0, y0, z1, x0, y1, z1, r, g, b, a);

        poseStack.popPose();
    }

    private void renderTendederoSelectionBox(PoseStack poseStack, MultiBufferSource bufferSource) {
        float[] tMin = ShopBlockEntity.TENDEDERO_MIN;
        float[] tMax = ShopBlockEntity.TENDEDERO_MAX;
        float cx = (tMin[0] + tMax[0]) / 2f;
        float cy = (tMin[1] + tMax[1]) / 2f;
        float cz = (tMin[2] + tMax[2]) / 2f;
        float hx = (tMax[0] - tMin[0]) / 2f;
        float hy = (tMax[1] - tMin[1]) / 2f;
        float hz = (tMax[2] - tMin[2]) / 2f;

        poseStack.pushPose();
        poseStack.translate(cx, cy, cz);

        VertexConsumer vc = bufferSource.getBuffer(RenderType.lines());
        PoseStack.Pose pose = poseStack.last();

        float r = 0.2f, g = 1f, b = 0.2f, a = 0.9f;

        line(vc, pose, -hx, -hy, -hz,  hx, -hy, -hz, r, g, b, a);
        line(vc, pose,  hx, -hy, -hz,  hx, -hy,  hz, r, g, b, a);
        line(vc, pose,  hx, -hy,  hz, -hx, -hy,  hz, r, g, b, a);
        line(vc, pose, -hx, -hy,  hz, -hx, -hy, -hz, r, g, b, a);

        line(vc, pose, -hx,  hy, -hz,  hx,  hy, -hz, r, g, b, a);
        line(vc, pose,  hx,  hy, -hz,  hx,  hy,  hz, r, g, b, a);
        line(vc, pose,  hx,  hy,  hz, -hx,  hy,  hz, r, g, b, a);
        line(vc, pose, -hx,  hy,  hz, -hx,  hy, -hz, r, g, b, a);

        line(vc, pose, -hx, -hy, -hz, -hx,  hy, -hz, r, g, b, a);
        line(vc, pose,  hx, -hy, -hz,  hx,  hy, -hz, r, g, b, a);
        line(vc, pose,  hx, -hy,  hz,  hx,  hy,  hz, r, g, b, a);
        line(vc, pose, -hx, -hy,  hz, -hx,  hy,  hz, r, g, b, a);

        poseStack.popPose();
    }

    /**
     * NeoForge 1.21.1 ya trae la API "nueva" de VertexConsumer:
     * {@code addVertex / setColor / setNormal}, sin {@code endVertex()}.
     * Usamos {@link PoseStack.Pose} en lugar de pasar Matrix4f + Matrix3f
     * separados — la pose ya carga ambos y los aplica internamente.
     */
    private static void line(VertexConsumer vc, PoseStack.Pose pose,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float r, float g, float b, float a) {
        float nx = x1 - x0;
        float ny = y1 - y0;
        float nz = z1 - z0;
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
    public boolean shouldRenderOffScreen(ShopBlockEntity blockEntity) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 64;
    }
}
