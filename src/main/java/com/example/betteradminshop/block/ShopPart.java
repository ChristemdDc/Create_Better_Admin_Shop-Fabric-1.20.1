package com.example.betteradminshop.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;

public enum ShopPart implements StringRepresentable {
    ORIGIN("origin"),
    RIGHT("right"),
    TOP("top"),
    TOP_RIGHT("top_right");

    private final String name;

    ShopPart(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    /**
     * Returns the offset from origin for this part, given the block's facing.
     * The model extends +X (2 blocks) and +Y (2 blocks) for NORTH facing.
     * Blockstate rotation maps +X to other horizontal directions.
     */
    public BlockPos getOffsetFromOrigin(Direction facing) {
        int dx = (this == RIGHT || this == TOP_RIGHT) ? 1 : 0;
        int dy = (this == TOP || this == TOP_RIGHT) ? 1 : 0;

        // Rotate the horizontal offset based on facing
        // NORTH: +X, SOUTH: -X, EAST: +Z, WEST: -Z
        return switch (facing) {
            case SOUTH -> new BlockPos(-dx, dy, 0);
            case EAST -> new BlockPos(0, dy, dx);
            case WEST -> new BlockPos(0, dy, -dx);
            default -> new BlockPos(dx, dy, 0); // NORTH
        };
    }

    /**
     * Returns the origin block position given this part's position and facing.
     */
    public BlockPos getOriginPos(BlockPos partPos, Direction facing) {
        BlockPos offset = getOffsetFromOrigin(facing);
        return partPos.subtract(offset);
    }

    /**
     * Returns positions for all 4 parts relative to the origin.
     */
    public static BlockPos[] getAllPositions(BlockPos origin, Direction facing) {
        BlockPos[] positions = new BlockPos[4];
        ShopPart[] parts = values();
        for (int i = 0; i < parts.length; i++) {
            BlockPos offset = parts[i].getOffsetFromOrigin(facing);
            positions[i] = origin.offset(offset);
        }
        return positions;
    }
}
