package com.example.betteradminshop.item;

import com.example.betteradminshop.registry.ModEntities;

import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;

import java.util.List;

/**
 * Paquete de tienda soltado en el mundo. Replica el comportamiento de la
 * cardboard de Create ({@code PackageEntity}):
 *
 *  - Al tirarlo con Q se convierte en esta entidad, heredando la posición y el
 *    impulso del ítem soltado (×1.5), sin empuje propio.
 *  - Clic derecho con la mano vacía: lo recoges con todo su contenido.
 *  - Golpearlo: revienta y suelta lo que llevaba dentro.
 *  - Se puede ATRAVESAR ({@code canBeCollidedWith} false) pero EMPUJAR
 *    ({@code isPushable} true), igual que la de Create.
 *
 * Hereda de LivingEntity como Create para tener vida (5 = 3 corazones) y su
 * misma física; se desactivan pociones y fuego.
 */
public class ShopPackageEntity extends LivingEntity implements IEntityWithComplexSpawn {

    /** Tamaño de la caja 12×12 del modelo (12/16 de bloque), como en Create. */
    public static final float SIZE = 12f / 16f;

    private static final EntityDataAccessor<ItemStack> DATA_BOX =
            SynchedEntityData.defineId(ShopPackageEntity.class, EntityDataSerializers.ITEM_STACK);

    public ShopPackageEntity(EntityType<? extends ShopPackageEntity> type, Level level) {
        super(type, level);
        this.blocksBuilding = true;
    }

    /** Atributos: 5 de vida (3 corazones), sin movimiento propio. */
    public static AttributeSupplier.Builder createAttributes() {
        return LivingEntity.createLivingAttributes()
                .add(Attributes.MAX_HEALTH, 5.0)
                .add(Attributes.MOVEMENT_SPEED, 0.0);
    }

    /**
     * Crea la entidad desde el ítem soltado. {@code original} es el ItemEntity
     * que vanilla ya generó al tirarlo: se heredan SU posición y SU velocidad
     * (×1.5), exactamente como hace Create.
     */
    public static ShopPackageEntity fromDroppedItem(Level level, Entity original, ItemStack box) {
        ShopPackageEntity entity = new ShopPackageEntity(ModEntities.SHOP_PACKAGE.get(), level);
        entity.setPos(original.position());
        entity.setBox(box.copyWithCount(1));
        entity.setDeltaMovement(original.getDeltaMovement().scale(1.5));
        return entity;
    }

    // ── Datos ────────────────────────────────────────────────────────────────

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_BOX, ItemStack.EMPTY);
    }

    public ItemStack getBox() {
        return entityData.get(DATA_BOX);
    }

    public void setBox(ItemStack box) {
        entityData.set(DATA_BOX, box);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("Box")) {
            setBox(ItemStack.parseOptional(level().registryAccess(), tag.getCompound("Box")));
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        ItemStack box = getBox();
        if (!box.isEmpty()) tag.put("Box", box.save(level().registryAccess()));
    }

    /** El contenido puede ser grande: viaja aparte al aparecer en el cliente. */
    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buf) {
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, getBox());
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buf) {
        setBox(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
    }

    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        return EntityDimensions.fixed(SIZE, SIZE);
    }

    // ── Física / colisión (idéntica a Create) ────────────────────────────────

    /**
     * Suaviza las correcciones de velocidad del servidor promediándolas con la
     * actual (como Create) en vez de reemplazarlas de golpe: sin esto, empujar
     * la caja se ve a tirones.
     */
    @Override
    public void lerpMotion(double x, double y, double z) {
        setDeltaMovement(getDeltaMovement().add(x, y, z).scale(0.5));
    }

    @Override
    public void travel(Vec3 movementInput) {
        // Sin IA: solo cae y roza contra el suelo. Gravedad igual a la de Create.
        if (!isNoGravity()) {
            setDeltaMovement(getDeltaMovement().add(0, -0.06, 0));
        }
        move(MoverType.SELF, getDeltaMovement());
        Vec3 motion = getDeltaMovement();
        double drag = onGround() ? 0.6 : 0.91;
        setDeltaMovement(motion.x * drag, onGround() ? 0.0 : motion.y * 0.98, motion.z * drag);
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && getBox().isEmpty()) {
            discard(); // paquete sin datos: no debe quedar flotando
        }
    }

    /** Empujable: chocarla la mueve. */
    @Override
    public boolean isPushable() {
        return true;
    }

    /** ...pero atravesable: no bloquea el paso. */
    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isAffectedByPotions() {
        return false;
    }

    @Override
    public boolean fireImmune() {
        return true;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.WOOL_HIT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.WOOL_BREAK;
    }

    @Override
    public Fallsounds getFallSounds() {
        return new Fallsounds(SoundEvents.WOOL_STEP, SoundEvents.WOOL_STEP);
    }

    // ── Equipamiento: obligatorio en LivingEntity, sin uso aquí ──────────────

    @Override
    public Iterable<ItemStack> getArmorSlots() {
        return List.of();
    }

    @Override
    public ItemStack getItemBySlot(EquipmentSlot slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
        // el paquete no lleva equipo
    }

    @Override
    public HumanoidArm getMainArm() {
        return HumanoidArm.RIGHT;
    }

    // ── Interacción: clic derecho recoge ─────────────────────────────────────

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (!player.getItemInHand(hand).isEmpty()) {
            return super.interact(player, hand);
        }
        if (level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        player.setItemInHand(hand, getBox().copy());
        level().playSound(null, blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS,
                0.2f, 0.75f + level().random.nextFloat());
        discard();
        return InteractionResult.SUCCESS;
    }

    @Override
    public ItemStack getPickResult() {
        return getBox().copy();
    }

    // ── Daño: golpearla revienta el paquete ──────────────────────────────────

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide || isRemoved()) return false;
        if (source.getEntity() instanceof Player player
                && !net.neoforged.neoforge.common.CommonHooks.onPlayerAttackTarget(player, this)) {
            return false;
        }
        burst();
        return true;
    }

    /** Revienta el paquete: efectos y suelta su contenido al mundo. */
    private void burst() {
        ItemStack box = getBox();
        List<ItemStack> contents = ShopPackageItem.getContents(box, level().registryAccess());

        if (level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, box),
                    getX(), getY() + 0.3, getZ(), 12, 0.2, 0.2, 0.2, 0.05);
        }
        level().playSound(null, blockPosition(), SoundEvents.WOOL_BREAK, SoundSource.BLOCKS,
                0.9f, 1.1f + level().random.nextFloat() * 0.2f);

        for (ItemStack stack : contents) {
            if (stack.isEmpty()) continue;
            ItemEntity item = new ItemEntity(level(), getX(), getY() + 0.25, getZ(), stack.copy());
            item.setDeltaMovement(
                    (level().random.nextDouble() - 0.5) * 0.15, 0.2,
                    (level().random.nextDouble() - 0.5) * 0.15);
            item.setPickUpDelay(10);
            level().addFreshEntity(item);
        }
        discard();
    }
}
