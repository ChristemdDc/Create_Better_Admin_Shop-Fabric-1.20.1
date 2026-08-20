package com.example.betteradminshop.client;

import com.example.betteradminshop.item.ShopPackageEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Dibuja el paquete soltado en el mundo como su modelo 3D (el mismo de la
 * cardboard de Create), apoyado en el suelo y orientado según su yaw.
 */
public class ShopPackageEntityRenderer extends EntityRenderer<ShopPackageEntity> {

    public ShopPackageEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.4f;
    }

    @Override
    public void render(ShopPackageEntity entity, float yaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        ItemStack box = entity.getBox();
        if (box.isEmpty()) return;

        poseStack.pushPose();
        // El ItemRenderer YA centra el modelo en el origen (aplica su propio
        // translate(-0.5,-0.5,-0.5)). El origen de la entidad está en sus pies,
        // así que basta subir media caja para que se apoye dentro de su hitbox;
        // un desplazamiento extra en X/Z lo sacaría de ella.
        poseStack.translate(0, ShopPackageEntity.SIZE / 2f, 0);
        poseStack.mulPose(Axis.YP.rotationDegrees(180 - yaw));
        // NONE dibuja a escala 1:1 (FIXED aplica las transformaciones de
        // "display" del modelo y lo encogía).
        Minecraft.getInstance().getItemRenderer().renderStatic(box, ItemDisplayContext.NONE,
                packedLight, OverlayTexture.NO_OVERLAY, poseStack, buffers, entity.level(),
                entity.getId());
        poseStack.popPose();

        super.render(entity, yaw, partialTick, poseStack, buffers, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(ShopPackageEntity entity) {
        // El render va por el modelo del ítem; no se usa una textura de entidad.
        return net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS;
    }
}
