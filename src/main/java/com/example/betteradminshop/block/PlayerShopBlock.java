package com.example.betteradminshop.block;

import com.example.betteradminshop.registry.ModSounds;
import com.simibubi.create.content.equipment.wrench.IWrenchable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
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
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.jetbrains.annotations.Nullable;

/**
 * Bloque de la TIENDA DE JUGADOR.
 *
 * Multibloque 2×2 (mismo esquema de partes que la tienda admin) con voladizo
 * trasero (silla + depot, z 16..26.5, sin colisión — igual que el voladizo de
 * la tienda admin). Los estantes se renderizan por blockstate: las propiedades
 * {@code left_tier}/{@code right_tier} (2..4) eligen qué subgrupo del modelo
 * aparece (multipart en blockstates/player_shop.json).
 *
 * Inamovible con las mismas defensas que la tienda admin: rotate/mirror no-op,
 * IWrenchable no-op, no empujable, y auto-reparación si una herramienta edita
 * FACING/PART directamente.
 */
public class PlayerShopBlock extends BaseEntityBlock implements IWrenchable {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<ShopPart> PART = EnumProperty.create("part", ShopPart.class);
    /** Slots visibles del estante izquierdo/derecho (2..4). Solo relevante en el origen. */
    public static final IntegerProperty LEFT_TIER = IntegerProperty.create("left_tier", 2, 4);
    public static final IntegerProperty RIGHT_TIER = IntegerProperty.create("right_tier", 2, 4);

    private static final VoxelShape FULL_BLOCK = Block.box(0, 0, 0, 16, 16, 16);

    // Outline shapes precomputadas: OUTLINE_SHAPES[facingIndex][partOrdinal]
    private static final VoxelShape[][] OUTLINE_SHAPES = new VoxelShape[4][4];

    static {
        double[][] modelBoxes = {
                {0, 0, 0, 32, 15, 16},          // mostrador frontal (2 bloques de ancho)
                {7.5, 0, 16, 24.5, 26, 26.5}    // estructura trasera: silla + depot (voladizo)
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

    public PlayerShopBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(PART, ShopPart.ORIGIN)
                .setValue(LEFT_TIER, 2)
                .setValue(RIGHT_TIER, 2));
    }

    @Override
    public com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(PlayerShopBlock::new);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART, LEFT_TIER, RIGHT_TIER);
    }

    // ── Colocación / rotura ───────────────────────────────────────────────────

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction facing = ctx.getHorizontalDirection().getOpposite();
        BlockPos origin = ctx.getClickedPos();
        for (BlockPos p : ShopPart.getAllPositions(origin, facing)) {
            if (!ctx.getLevel().getBlockState(p).canBeReplaced(ctx)) return null;
        }
        return defaultBlockState().setValue(FACING, facing);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (!level.isClientSide) {
            Direction facing = state.getValue(FACING);
            BlockState placedState = state;

            // Vanilla ya aplicó BLOCK_ENTITY_DATA del ítem antes de este punto,
            // así que el BE puede venir con una tienda entera dentro.
            if (level.getBlockEntity(pos) instanceof PlayerShopBlockEntity be) {
                be.setLockedFacing(facing);
                // Solo asignar dueño si la tienda es nueva: al recolocar una
                // tienda guardada, su dueño original se conserva.
                if (be.getOwnerId() == null && placer instanceof Player player) {
                    be.setOwner(player.getUUID(), player.getName().getString());
                }
                // Restaurar las mejoras de estante (viven en el blockstate)
                int lt = be.getStoredLeftTier();
                int rt = be.getStoredRightTier();
                if (lt != state.getValue(LEFT_TIER) || rt != state.getValue(RIGHT_TIER)) {
                    placedState = state.setValue(LEFT_TIER, lt).setValue(RIGHT_TIER, rt);
                    level.setBlock(pos, placedState, 3);
                }
            }

            ShopPart[] parts = ShopPart.values();
            for (int i = 1; i < parts.length; i++) { // skip ORIGIN
                BlockPos partPos = pos.offset(parts[i].getOffsetFromOrigin(facing));
                level.setBlock(partPos, placedState.setValue(PART, parts[i]), 3);
            }
        }
    }

    /** Solo el dueño (o un admin nivel 2+) puede romper la tienda. */
    @Override
    public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        PlayerShopBlockEntity be = getOriginEntity(level, pos, state);
        if (be != null && be.getOwnerId() != null
                && !be.isOwner(player) && !player.hasPermissions(2)) {
            return 0.0F; // irrompible para los demás
        }
        return super.getDestroyProgress(state, player, level, pos);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && state.getBlock() instanceof PlayerShopBlock) {
            Direction facing = state.getValue(FACING);
            ShopPart part = state.getValue(PART);
            BlockPos originPos = part.getOriginPos(pos, facing);

            // Dropear la tienda CON su contenido (la loot table no dropea nada:
            // el ítem se construye aquí para poder incluir el block entity).
            if (!player.isCreative()
                    && level.getBlockEntity(originPos) instanceof PlayerShopBlockEntity be) {
                popResource(level, pos, be.createShopItem(level.registryAccess()));
            }

            for (ShopPart p : ShopPart.values()) {
                BlockPos partPos = originPos.offset(p.getOffsetFromOrigin(facing));
                if (!partPos.equals(pos)) {
                    BlockState partState = level.getBlockState(partPos);
                    if (partState.getBlock() instanceof PlayerShopBlock) {
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

    // ── Formas / render ───────────────────────────────────────────────────────

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return OUTLINE_SHAPES[facingIndex(state.getValue(FACING))][state.getValue(PART).ordinal()];
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return FULL_BLOCK;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return state.getValue(PART) == ShopPart.ORIGIN ? RenderShape.MODEL : RenderShape.INVISIBLE;
    }

    // ── Inamovible (idéntico a la tienda admin) ──────────────────────────────

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state;
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state;
    }

    @Override
    public net.minecraft.world.level.material.PushReaction getPistonPushReaction(BlockState state) {
        return net.minecraft.world.level.material.PushReaction.BLOCK;
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level.isClientSide) return;
        if (!(oldState.getBlock() instanceof PlayerShopBlock)) return;
        if (oldState.getValue(FACING) == state.getValue(FACING)
                && oldState.getValue(PART) == state.getValue(PART)) return;
        level.scheduleTick(pos, this, 1);
    }

    @Override
    protected void tick(BlockState state, net.minecraft.server.level.ServerLevel level, BlockPos pos,
                        net.minecraft.util.RandomSource random) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (level.getBlockEntity(pos.offset(dx, dy, dz)) instanceof PlayerShopBlockEntity be
                            && be.coversPosition(pos)) {
                        be.validateStructure();
                        return;
                    }
                }
            }
        }
    }

    // ── Interacción (Fase 1: stub de propiedad; Fase 2: compra; Fase 4: menú) ─

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) == ShopPart.ORIGIN ? new PlayerShopBlockEntity(pos, state) : null;
    }

    @Nullable
    private PlayerShopBlockEntity getOriginEntity(BlockGetter level, BlockPos pos, BlockState state) {
        if (state.getValue(PART) == ShopPart.ORIGIN) {
            return level.getBlockEntity(pos) instanceof PlayerShopBlockEntity be ? be : null;
        }
        BlockPos originPos = state.getValue(PART).getOriginPos(pos, state.getValue(FACING));
        return level.getBlockEntity(originPos) instanceof PlayerShopBlockEntity be ? be : null;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        return switch (handleInteraction(state, level, pos, player)) {
            case SUCCESS, SUCCESS_NO_ITEM_USED -> ItemInteractionResult.SUCCESS;
            case CONSUME -> ItemInteractionResult.CONSUME;
            case CONSUME_PARTIAL -> ItemInteractionResult.CONSUME_PARTIAL;
            case FAIL -> ItemInteractionResult.FAIL;
            case PASS -> ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        return handleInteraction(state, level, pos, player);
    }

    private InteractionResult handleInteraction(BlockState state, Level level, BlockPos pos, Player player) {
        PlayerShopBlockEntity be = getOriginEntity(level, pos, state);
        if (be == null) return InteractionResult.PASS;

        if (player.isShiftKeyDown()) {
            // Menú de gestión: solo dueño / empleados / admin
            if (be.canManage(player)) {
                if (level.isClientSide) {
                    openMenu(be);
                }
                return InteractionResult.sidedSuccess(level.isClientSide);
            } else {
                if (!level.isClientSide) {
                    player.displayClientMessage(Component.literal(
                            "§cEsta tienda pertenece a §f" + be.getOwnerName() + "§c."), true);
                }
                return InteractionResult.FAIL;
            }
        }

        // ── Compra (Fase 2) ──────────────────────────────────────────────────
        if (!level.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            BlockState originState = level.getBlockState(be.getBlockPos());
            int clicked = be.getClickedSlot(player.getEyePosition(), player.getLookAngle(), originState);
            long now = System.currentTimeMillis();

            if (clicked == -3) {
                // Recoger la cardboard del depot (permitido aunque la tienda esté cerrada)
                String result = be.tryPickupDelivery(serverPlayer);
                if (result == null) {
                    player.displayClientMessage(Component.literal("§a¡Paquete recogido!"), true);
                } else if (result.equals("no_deliveries")) {
                    player.displayClientMessage(Component.literal("§eNo hay entregas pendientes."), true);
                } else if (result.startsWith("protected:")) {
                    player.displayClientMessage(Component.literal(
                            "§cEste paquete está protegido. Tiempo restante: "
                            + result.substring("protected:".length())), true);
                }
                return InteractionResult.CONSUME;
            }

            if (!be.isOperational(now)) {
                if (clicked >= 0 || clicked == -2) {
                    player.displayClientMessage(Component.literal(
                            "§cTienda de §f" + be.getOwnerName() + "§c cerrada (renta pendiente)."), true);
                }
                return InteractionResult.CONSUME;
            }

            if (clicked == -2) {
                // Zona de pago: confirmar la orden
                ShopOrder order = be.getOrCreateOrder(player.getUUID());
                if (order.isEmpty()) {
                    player.displayClientMessage(Component.literal(
                            "§eNo tienes artículos en tu orden de compra."), true);
                    return InteractionResult.CONSUME;
                }
                String result = be.processPurchase(serverPlayer);
                if (result == null) {
                    removeShoppingListFromInventory(serverPlayer);
                    player.displayClientMessage(Component.literal(
                            "§a¡Compra realizada! Recoge tu paquete en el depot."), true);
                    level.playSound(null, be.getBlockPos(), ModSounds.DESK_BELL.get(),
                            net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.0f);
                } else if (result.startsWith("stock:")) {
                    player.displayClientMessage(Component.literal(
                            "§cStock insuficiente de '" + result.substring(6) + "'."), true);
                } else if (result.startsWith("pay:")) {
                    player.displayClientMessage(Component.literal(
                            "§cNo tienes suficientes '" + result.substring(4) + "'."), true);
                } else if (result.equals("full")) {
                    player.displayClientMessage(Component.literal(
                            "§cEsta tienda no puede cobrar: su recaudación está llena."), true);
                }
                return InteractionResult.CONSUME;
            }

            if (clicked >= 0) {
                PlayerShopSlot slot = be.getSlot(clicked);
                if (slot != null && !slot.isEmpty()) {
                    if (findShoppingListInInventory(serverPlayer) == null) {
                        be.clearOrder(player.getUUID());
                    }
                    if (be.addToOrder(player.getUUID(), clicked)) {
                        updateShoppingListItem(serverPlayer, be, be.getOrCreateOrder(player.getUUID()));
                    } else {
                        player.displayClientMessage(Component.literal("§c¡Artículo agotado!"), true);
                    }
                }
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Abre el menú SOLO en cliente (guard para no classloadear Screen en server). */
    private void openMenu(PlayerShopBlockEntity be) {
        if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) {
            com.example.betteradminshop.client.PlayerShopMenuScreen.open(be);
        }
    }

    // ── Shopping list de Create (misma UX que la tienda de administrador) ────

    private void updateShoppingListItem(net.minecraft.server.level.ServerPlayer player,
                                        PlayerShopBlockEntity be, ShopOrder order) {
        ItemStack listStack = findShoppingListInInventory(player);
        boolean isNew = listStack == null;
        if (isNew) listStack = com.simibubi.create.AllItems.SHOPPING_LIST.asStack();

        java.util.List<Component> lines = new java.util.ArrayList<>();
        java.util.Map<ItemStack, Integer> totalCost = new java.util.LinkedHashMap<>();
        for (var e : order.getItems().entrySet()) {
            PlayerShopSlot slot = be.getSlot(e.getKey());
            if (slot == null || slot.isEmpty()) continue;
            int units = e.getValue() * slot.getSellAmount();
            lines.add(Component.literal(
                    slot.getSaleItem().getHoverName().getString() + " x" + units)
                    .withStyle(s -> s.withItalic(false)));
            mergeCost(totalCost, slot.getPriceItem(), slot.getPriceAmount() * e.getValue());
            if (slot.hasSecondPrice()) {
                mergeCost(totalCost, slot.getPriceItem2(), slot.getPriceAmount2() * e.getValue());
            }
        }
        if (!totalCost.isEmpty()) {
            lines.add(Component.literal("").withStyle(s -> s.withItalic(false)));
            lines.add(Component.literal("§7Coste total:").withStyle(s -> s.withItalic(false)));
            for (var e : totalCost.entrySet()) {
                lines.add(Component.literal("§6" + e.getKey().getHoverName().getString()
                        + " x" + e.getValue()).withStyle(s -> s.withItalic(false)));
            }
        }
        listStack.set(net.minecraft.core.component.DataComponents.LORE,
                new net.minecraft.world.item.component.ItemLore(lines));
        if (isNew && !player.getInventory().add(listStack)) {
            player.drop(listStack, false);
        }
    }

    private static void mergeCost(java.util.Map<ItemStack, Integer> map, ItemStack item, int amount) {
        if (item.isEmpty() || amount <= 0) return;
        for (var e : map.entrySet()) {
            if (ItemStack.isSameItemSameComponents(e.getKey(), item)) {
                e.setValue(e.getValue() + amount);
                return;
            }
        }
        map.put(item.copyWithCount(1), amount);
    }

    @Nullable
    private ItemStack findShoppingListInInventory(net.minecraft.server.level.ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(com.simibubi.create.AllItems.SHOPPING_LIST.get())) {
                return stack;
            }
        }
        return null;
    }

    private void removeShoppingListFromInventory(net.minecraft.server.level.ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(com.simibubi.create.AllItems.SHOPPING_LIST.get())) {
                player.getInventory().setItem(i, ItemStack.EMPTY);
                return;
            }
        }
    }
}
