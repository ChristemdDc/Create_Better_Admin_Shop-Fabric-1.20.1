package com.example.betteradminshop.block;

import com.example.betteradminshop.client.ShopAdminScreen;
import com.simibubi.create.AllItems;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public class ShopBlock extends BaseEntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    private static final VoxelShape SHAPE_NORTH;
    private static final VoxelShape SHAPE_SOUTH;
    private static final VoxelShape SHAPE_WEST;
    private static final VoxelShape SHAPE_EAST;

    static {
        // 1 block wide (X), 2 blocks tall (Y), 2 blocks long (Z) per facing
        SHAPE_NORTH = Block.box(0, 0,  0, 16, 32, 32);
        SHAPE_SOUTH = Block.box(0, 0,-16, 16, 32, 16);
        SHAPE_WEST  = Block.box(-16, 0, 0, 16, 32, 16);
        SHAPE_EAST  = Block.box(0, 0,  0, 32, 32, 16);
    }

    public ShopBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SHAPE_SOUTH;
            case WEST -> SHAPE_WEST;
            case EAST -> SHAPE_EAST;
            default -> SHAPE_NORTH;
        };
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ShopBlockEntity(pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof ShopBlockEntity shopBE)) return InteractionResult.PASS;

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
            if (!shopBE.hasDepot()) {
                player.displayClientMessage(
                        Component.literal("§eLa tienda no está vinculada a un Depot."), true);
                return InteractionResult.CONSUME;
            }

            Vec3 hitVec = hit.getLocation();
            int clickedSlot = shopBE.getClickedSlot(hitVec, state);

            if (clickedSlot == -2) {
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
                            Component.literal("§a¡Compra realizada! Tu paquete fue depositado en el Depot."), true);
                }
            } else if (clickedSlot >= 0) {
                ShopSlot slot = shopBE.getSlot(clickedSlot);
                if (slot != null && !slot.isEmpty()) {
                    if (slot.isOutOfStock()) {
                        player.displayClientMessage(
                                Component.literal("§c¡Artículo agotado!"), true);
                    } else {
                        // If the player discarded their shopping list, reset order
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

    /** Creates or updates the create:shopping_list item in the player's inventory. */
    private void updateShoppingListItem(ServerPlayer player, ShopBlockEntity shopBE, ShopOrder order) {
        ItemStack listStack = findShoppingListInInventory(player);
        boolean isNew = listStack == null;

        if (isNew) {
            listStack = AllItems.SHOPPING_LIST.asStack();
        }

        // Build lore lines showing the ordered items and their total cost
        Map<Integer, Integer> items = order.getItems();
        Map<ItemStack, Integer> totalCost = order.calculateTotalPrice(shopBE.getSlots());

        ListTag loreTag = new ListTag();
        for (Map.Entry<Integer, Integer> entry : items.entrySet()) {
            ShopSlot slot = shopBE.getSlot(entry.getKey());
            if (slot != null && !slot.isEmpty()) {
                String line = Component.Serializer.toJson(
                        Component.literal(slot.getDisplayItem().getHoverName().getString()
                                + " x" + entry.getValue()).withStyle(style -> style.withItalic(false)));
                loreTag.add(StringTag.valueOf(line));
            }
        }

        if (!totalCost.isEmpty()) {
            loreTag.add(StringTag.valueOf(Component.Serializer.toJson(
                    Component.literal("").withStyle(style -> style.withItalic(false)))));
            loreTag.add(StringTag.valueOf(Component.Serializer.toJson(
                    Component.literal("§7Coste total:").withStyle(style -> style.withItalic(false)))));
            for (Map.Entry<ItemStack, Integer> entry : totalCost.entrySet()) {
                String costLine = Component.Serializer.toJson(
                        Component.literal("§6" + entry.getKey().getHoverName().getString()
                                + " x" + entry.getValue()).withStyle(style -> style.withItalic(false)));
                loreTag.add(StringTag.valueOf(costLine));
            }
        }

        CompoundTag display = listStack.getOrCreateTagElement("display");
        display.put("Lore", loreTag);

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

    private void openAdminScreen(ShopBlockEntity shopBE) {
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            ShopAdminScreen.open(shopBE);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            super.onRemove(state, level, pos, newState, isMoving);
        }
    }
}
