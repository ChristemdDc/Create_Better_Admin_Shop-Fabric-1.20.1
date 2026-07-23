package com.example.betteradminshop.block;

import com.example.betteradminshop.client.ShopAdminScreen;
import com.example.betteradminshop.registry.ModSounds;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.world.item.context.UseOnContext;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ShopBlock extends BaseEntityBlock implements IWrenchable {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<ShopPart> PART = EnumProperty.create("part", ShopPart.class);

    private static final VoxelShape FULL_BLOCK = Block.box(0, 0, 0, 16, 16, 16);

    // Precomputed outline shapes: OUTLINE_SHAPES[facingIndex][partOrdinal]
    private static final VoxelShape[][] OUTLINE_SHAPES = new VoxelShape[4][4];

    static {
        double[][] modelBoxes = {
                {0, 0, 0, 32, 32, 16},
                {16, 0, -12, 32, 32, 0}
        };

        Direction[] facings = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        for (int fi = 0; fi < 4; fi++) {
            Direction facing = facings[fi];
            for (ShopPart part : ShopPart.values()) {
                BlockPos partOffset = part.getOffsetFromOrigin(facing);
                double ox = partOffset.getX() * 16.0;
                double oy = partOffset.getY() * 16.0;
                double oz = partOffset.getZ() * 16.0;

                VoxelShape shape = Shapes.empty();
                for (double[] box : modelBoxes) {
                    double[] r1 = rotateXZ(box[0], box[2], facing);
                    double[] r2 = rotateXZ(box[3], box[5], facing);

                    double x1 = Math.max(-16, Math.min(r1[0], r2[0]) - ox);
                    double z1 = Math.max(-16, Math.min(r1[1], r2[1]) - oz);
                    double x2 = Math.min(32, Math.max(r1[0], r2[0]) - ox);
                    double z2 = Math.min(32, Math.max(r1[1], r2[1]) - oz);
                    double y1 = Math.max(-16, box[1] - oy);
                    double y2 = Math.min(32, box[4] - oy);

                    if (x2 > x1 && y2 > y1 && z2 > z1) {
                        shape = Shapes.or(shape, Block.box(x1, y1, z1, x2, y2, z2));
                    }
                }
                OUTLINE_SHAPES[fi][part.ordinal()] = shape;
            }
        }
    }

    private static double[] rotateXZ(double x, double z, Direction facing) {
        return switch (facing) {
            case SOUTH -> new double[]{16 - x, 16 - z};
            case EAST -> new double[]{16 - z, x};
            case WEST -> new double[]{z, 16 - x};
            default -> new double[]{x, z};
        };
    }

    private static int facingIndex(Direction facing) {
        return switch (facing) {
            case SOUTH -> 1;
            case EAST -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }

    public ShopBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(PART, ShopPart.ORIGIN));
    }

    /**
     * BaseEntityBlock requires implementations to declare a codec.
     * Vanilla blocks return {@code simpleCodec(...)}; we can do the same.
     */
    @Override
    public com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(ShopBlock::new);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction facing = ctx.getHorizontalDirection().getOpposite();
        BlockPos origin = ctx.getClickedPos();

        BlockPos[] positions = ShopPart.getAllPositions(origin, facing);
        for (BlockPos p : positions) {
            if (!ctx.getLevel().getBlockState(p).canBeReplaced(ctx)) return null;
        }

        return defaultBlockState().setValue(FACING, facing).setValue(PART, ShopPart.ORIGIN);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (!level.isClientSide) {
            Direction facing = state.getValue(FACING);
            ShopPart[] parts = ShopPart.values();
            for (int i = 1; i < parts.length; i++) { // skip ORIGIN
                BlockPos partPos = pos.offset(parts[i].getOffsetFromOrigin(facing));
                level.setBlock(partPos, state.setValue(PART, parts[i]), 3);
            }
            // Fijar la orientación real: a partir de aquí es inmutable y
            // cualquier edición externa del blockstate se revierte.
            if (level.getBlockEntity(pos) instanceof ShopBlockEntity be) {
                be.setLockedFacing(facing);
            }
        }
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Direction facing = state.getValue(FACING);
        ShopPart part = state.getValue(PART);
        return OUTLINE_SHAPES[facingIndex(facing)][part.ordinal()];
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return FULL_BLOCK;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return state.getValue(PART) == ShopPart.ORIGIN ? RenderShape.MODEL : RenderShape.INVISIBLE;
    }

    // ── Bloque inamovible ────────────────────────────────────────────────────
    // No rotar ni reflejar tras la colocación: la tienda es un multibloque cuyo
    // render, detección de clics y stock por jugador dependen de un FACING
    // consistente. Herramientas de rotación (llave de Create, WorldEdit,
    // bloques de estructura, etc.) usan rotate()/mirror(): al ignorarlas, el
    // bloque no puede desajustarse. La orientación se fija solo al colocarlo.

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state;
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state;
    }

    /** No empujable por pistones (vanilla y, en general, mecánicos de Create). */
    @Override
    public net.minecraft.world.level.material.PushReaction getPistonPushReaction(BlockState state) {
        return net.minecraft.world.level.material.PushReaction.BLOCK;
    }

    // La llave de Create usa IWrenchable: consumimos la acción sin rotar ni
    // desmontar, para que no pueda desajustar la tienda.
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.SUCCESS;
    }

    /**
     * Última línea de defensa: si CUALQUIER herramienta edita el FACING/PART del
     * blockstate directamente (editores de propiedades de otros mods, que no
     * pasan por rotate() ni por IWrenchable), programamos una reparación para el
     * tick siguiente. La tienda revierte a su orientación bloqueada.
     */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level.isClientSide) return;
        if (!(oldState.getBlock() instanceof ShopBlock)) return; // colocación nueva, no edición
        if (oldState.getValue(FACING) == state.getValue(FACING)
                && oldState.getValue(PART) == state.getValue(PART)) return; // sin cambio relevante
        level.scheduleTick(pos, this, 1);
    }

    @Override
    protected void tick(BlockState state, net.minecraft.server.level.ServerLevel level, BlockPos pos,
                        net.minecraft.util.RandomSource random) {
        ShopBlockEntity be = findOwningOrigin(level, pos);
        if (be != null) be.validateStructure();
    }

    /**
     * Busca el block entity de la tienda DUEÑA de {@code pos}. No usa
     * getOriginPos porque el FACING pudo quedar corrupto: escanea el cubo 3x3x3
     * (la tienda ocupa 2x2, así que su origen siempre cae dentro) y se queda con
     * la que realmente contiene esa posición.
     *
     * El chequeo coversPosition es imprescindible: con tiendas pegadas, el
     * escaneo puede encontrar primero el origen de una VECINA — repararla a ella
     * dejaría la editada sin arreglar.
     */
    @Nullable
    private static ShopBlockEntity findOwningOrigin(Level level, BlockPos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (level.getBlockEntity(pos.offset(dx, dy, dz)) instanceof ShopBlockEntity be
                            && be.coversPosition(pos)) {
                        return be;
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) == ShopPart.ORIGIN ? new ShopBlockEntity(pos, state) : null;
    }

    @Nullable
    private ShopBlockEntity getOriginEntity(Level level, BlockPos pos, BlockState state) {
        if (state.getValue(PART) == ShopPart.ORIGIN) {
            return level.getBlockEntity(pos) instanceof ShopBlockEntity be ? be : null;
        }
        BlockPos originPos = state.getValue(PART).getOriginPos(pos, state.getValue(FACING));
        return level.getBlockEntity(originPos) instanceof ShopBlockEntity be ? be : null;
    }

    // ===== 1.21 interaction API =============================================
    // The old single `use(...)` method was split into:
    //  - useItemOn(ItemStack, ...)         → main path when the player is
    //                                        holding something. Returns
    //                                        ItemInteractionResult.
    //  - useWithoutItem(...)               → main path when the main hand is
    //                                        empty. Returns InteractionResult.
    // Behavior is identical to the Fabric version; both paths delegate to
    // a shared {@link #handleInteraction(BlockState, Level, BlockPos, Player, InteractionHand)}.

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        return toItemResult(handleInteraction(state, level, pos, player));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        return handleInteraction(state, level, pos, player);
    }

    private static ItemInteractionResult toItemResult(InteractionResult r) {
        return switch (r) {
            case SUCCESS, SUCCESS_NO_ITEM_USED -> ItemInteractionResult.SUCCESS;
            case CONSUME -> ItemInteractionResult.CONSUME;
            case CONSUME_PARTIAL -> ItemInteractionResult.CONSUME_PARTIAL;
            case FAIL -> ItemInteractionResult.FAIL;
            case PASS -> ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        };
    }

    private InteractionResult handleInteraction(BlockState state, Level level, BlockPos pos, Player player) {
        ShopBlockEntity shopBE = getOriginEntity(level, pos, state);
        if (shopBE == null) return InteractionResult.PASS;

        if (player.isShiftKeyDown()) {
            if (player.hasPermissions(4)) {
                if (level.isClientSide) {
                    openAdminScreen(shopBE);
                }
                return InteractionResult.sidedSuccess(level.isClientSide);
            } else {
                if (!level.isClientSide) {
                    player.displayClientMessage(
                            Component.literal("§cNo tienes permisos para administrar esta tienda."), true);
                }
                return InteractionResult.FAIL;
            }
        }

        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            BlockState originState = level.getBlockState(shopBE.getBlockPos());
            Vec3 eyePos = player.getEyePosition();
            Vec3 lookDir = player.getLookAngle();
            int clickedSlot = shopBE.getClickedSlot(eyePos, lookDir, originState);

            if (clickedSlot == -3) {
                // Entrega zone: try to pick up the current delivery
                String result = shopBE.tryPickupDelivery(serverPlayer);
                if (result == null) {
                    player.displayClientMessage(
                            Component.literal("§a¡Paquete recogido!"), true);
                } else if (result.equals("no_deliveries")) {
                    player.displayClientMessage(
                            Component.literal("§eNo hay entregas pendientes."), true);
                } else if (result.startsWith("protected:")) {
                    String timeLeft = result.substring("protected:".length());
                    player.displayClientMessage(
                            Component.literal("§cEste paquete está protegido. Tiempo restante: " + timeLeft), true);
                }
                return InteractionResult.CONSUME;

            } else if (clickedSlot == -2) {
                // Confirmarcompra panel: process current order
                ShopOrder order = shopBE.getOrCreateOrder(player.getUUID());
                if (order.isEmpty()) {
                    player.displayClientMessage(
                            Component.literal("§eNo tienes artículos en tu orden de compra."), true);
                    return InteractionResult.CONSUME;
                }

                String missingItem = shopBE.processPurchase(serverPlayer);
                if (missingItem != null) {
                    player.displayClientMessage(
                            Component.literal("§cNo tienes suficientes '" + missingItem + "'"), true);
                } else {
                    removeShoppingListFromInventory(serverPlayer);
                    player.displayClientMessage(
                            Component.literal("§a¡Compra realizada! Tu paquete está en el área de entrega."), true);
                    level.playSound(null, shopBE.getBlockPos(), ModSounds.DESK_BELL.get(),
                            SoundSource.BLOCKS, 1.0f, 1.0f);
                }

            } else if (clickedSlot >= 0) {
                ShopSlot slot = shopBE.getSlot(clickedSlot);
                if (slot != null && !slot.isEmpty()) {
                    long now = System.currentTimeMillis();
                    if (slot.isOutOfStockFor(player.getUUID(), now)) {
                        long secs = slot.getResetRemainingSeconds(player.getUUID(), now);
                        String msg = slot.isCompra() ? "§c¡Cupo de compra lleno!" : "§c¡Artículo agotado!";
                        if (secs > 0) {
                            msg += " §7Reabastece en " + formatDuration(secs);
                        }
                        player.displayClientMessage(Component.literal(msg), true);
                    } else {
                        if (findShoppingListInInventory(serverPlayer) == null) {
                            shopBE.clearOrder(player.getUUID());
                        }
                        boolean added = shopBE.addToOrder(player.getUUID(), clickedSlot);
                        if (added) {
                            ShopOrder order = shopBE.getOrCreateOrder(player.getUUID());
                            updateShoppingListItem(serverPlayer, shopBE, order);
                        }
                    }
                }
            }
        }

        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Creates or updates the {@code create:shopping_list} item in the player's
     * inventory.
     *
     * In 1.20.5+ item lore is no longer stored under
     * {@code tag.display.Lore}. It now lives in the {@link DataComponents#LORE}
     * data component as a typed {@link ItemLore} value.
     */
    private void updateShoppingListItem(ServerPlayer player, ShopBlockEntity shopBE, ShopOrder order) {
        ItemStack listStack = findShoppingListInInventory(player);
        boolean isNew = listStack == null;

        if (isNew) {
            listStack = AllItems.SHOPPING_LIST.asStack();
        }

        Map<Integer, Integer> items = order.getItems();
        Map<ItemStack, Integer> required = order.computeRequired(shopBE.getSlots());
        Map<ItemStack, Integer> rewards  = order.computeRewards(shopBE.getSlots());

        List<Component> lines = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : items.entrySet()) {
            ShopSlot slot = shopBE.getSlot(entry.getKey());
            if (slot != null && !slot.isEmpty()) {
                int units = entry.getValue() * slot.getSellAmount();
                String tag = slot.isCompra() ? "§b[Compra] §r" : "§a[Venta] §r";
                lines.add(Component.literal(
                        tag + slot.getDisplayItem().getHoverName().getString() + " x" + units)
                        .withStyle(s -> s.withItalic(false)));
            }
        }

        if (!required.isEmpty()) {
            lines.add(Component.literal("").withStyle(s -> s.withItalic(false)));
            lines.add(Component.literal("§7Entregas:").withStyle(s -> s.withItalic(false)));
            for (Map.Entry<ItemStack, Integer> entry : required.entrySet()) {
                lines.add(Component.literal("§c" + entry.getKey().getHoverName().getString()
                        + " x" + entry.getValue()).withStyle(s -> s.withItalic(false)));
            }
        }

        if (!rewards.isEmpty()) {
            lines.add(Component.literal("").withStyle(s -> s.withItalic(false)));
            lines.add(Component.literal("§7Recibes:").withStyle(s -> s.withItalic(false)));
            for (Map.Entry<ItemStack, Integer> entry : rewards.entrySet()) {
                lines.add(Component.literal("§6" + entry.getKey().getHoverName().getString()
                        + " x" + entry.getValue()).withStyle(s -> s.withItalic(false)));
            }
        }

        listStack.set(DataComponents.LORE, new ItemLore(lines));

        if (isNew) {
            if (!player.getInventory().add(listStack)) {
                player.drop(listStack, false);
            }
        }
    }

    private ItemStack findShoppingListInInventory(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(AllItems.SHOPPING_LIST.get())) {
                return stack;
            }
        }
        return null;
    }

    private void removeShoppingListFromInventory(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(AllItems.SHOPPING_LIST.get())) {
                player.getInventory().setItem(i, ItemStack.EMPTY);
                return;
            }
        }
    }

    /** Formatea segundos como "23h 5m" o "4m 12s". */
    public static String formatDuration(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    private void openAdminScreen(ShopBlockEntity shopBE) {
        // Replaces the Fabric `FabricLoader.getEnvironmentType() == CLIENT`
        // check. The method is only called from the client branch already, but
        // we keep the guard so accidentally calling it from common code on a
        // dedicated server would not classload the Screen class.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ShopAdminScreen.open(shopBE);
        }
    }

    /**
     * NeoForge 1.21.1 parchea la firma de {@code playerWillDestroy} para que
     * retorne {@link BlockState} (la nueva state post-destruccion). El cuerpo
     * sigue haciendo lo mismo, solo cambia la firma.
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && state.getBlock() instanceof ShopBlock) {
            Direction facing = state.getValue(FACING);
            ShopPart part = state.getValue(PART);
            BlockPos originPos = part.getOriginPos(pos, facing);

            // Quitar el documento de esta tienda en MongoDB (si el driver está presente)
            if (com.example.betteradminshop.data.MongoDriver.AVAILABLE) {
                com.example.betteradminshop.data.MongoStore.getInstance()
                        .removeShop(level.dimension().location().toString(), originPos);
            }

            for (ShopPart p : ShopPart.values()) {
                BlockPos partPos = originPos.offset(p.getOffsetFromOrigin(facing));
                if (!partPos.equals(pos)) {
                    BlockState partState = level.getBlockState(partPos);
                    if (partState.getBlock() instanceof ShopBlock) {
                        // Flag 18 (SEND_TO_CLIENT | NO_RERENDER) keeps neighbors
                        // from cascading into this same handler.
                        level.setBlock(partPos, Blocks.AIR.defaultBlockState(), 18);
                    }
                }
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            super.onRemove(state, level, pos, newState, isMoving);
        }
    }
}
