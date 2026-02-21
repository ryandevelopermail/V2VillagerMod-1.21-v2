package dev.sterner.guardvillagers.common.util;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import net.minecraft.block.BellBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.ServerWorldAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class VillageBellChestPlacementHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(VillageBellChestPlacementHelper.class);
    private static final int SEARCH_RADIUS = 2;

    private VillageBellChestPlacementHelper() {
    }

    public static void tryPlaceChestForVillageBell(ServerWorldAccess world, BlockPos bellPos, BlockState bellState, StructurePlacementData placementData) {
        if (!bellState.isOf(Blocks.BELL)) {
            LOGGER.debug("Skipping chest placement at {}: not a bell block", bellPos.toShortString());
            return;
        }

        if (!isVillagePlacementContext(world, bellPos, placementData)) {
            LOGGER.debug("Skipping chest placement near bell {}: village placement context failed", bellPos.toShortString());
            return;
        }

        if (hasNearbyChest(world, bellPos)) {
            LOGGER.debug("Skipping chest placement near bell {}: nearby chest already exists", bellPos.toShortString());
            return;
        }

        Optional<BlockPos> chestPos = findNearestAvailableChestPlacement(world, bellPos, bellState);
        if (chestPos.isEmpty()) {
            LOGGER.info("Skipping chest placement near bell {}: no valid chest position found", bellPos.toShortString());
            return;
        }

        if (world.setBlockState(chestPos.get(), Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, getChestFacing(bellState)), 2)) {
            LOGGER.info("Placed generated village chest at {} for bell {}", chestPos.get().toShortString(), bellPos.toShortString());
        } else {
            LOGGER.debug("Failed to set generated village chest at {} for bell {}", chestPos.get().toShortString(), bellPos.toShortString());
        }
    }

    public static Optional<BlockPos> findNearestAvailableChestPlacement(ServerWorldAccess world, BlockPos bellPos, BlockState bellState) {
        Direction leftDirection = getLeftDirection(bellState);
        BlockPos preferredPos = bellPos.offset(leftDirection);
        if (isValidChestPosition(world, preferredPos)) {
            return Optional.of(preferredPos.toImmutable());
        }

        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }

                    BlockPos candidate = bellPos.add(dx, dy, dz);
                    if (candidate.equals(preferredPos) || !isValidChestPosition(world, candidate)) {
                        continue;
                    }

                    candidates.add(candidate.toImmutable());
                }
            }
        }

        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> pos.getSquaredDistance(bellPos))
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));

        return candidates.stream().findFirst();
    }

    private static Direction getLeftDirection(BlockState bellState) {
        if (bellState.contains(BellBlock.FACING)) {
            Direction facing = bellState.get(BellBlock.FACING);
            if (facing.getAxis().isHorizontal()) {
                return facing.rotateYCounterclockwise();
            }
        }

        if (bellState.contains(BellBlock.ATTACHMENT)) {
            var attachment = bellState.get(BellBlock.ATTACHMENT);
            return switch (attachment) {
                case FLOOR -> Direction.WEST;
                case CEILING -> Direction.EAST;
                case SINGLE_WALL -> Direction.NORTH;
                case DOUBLE_WALL -> Direction.SOUTH;
            };
        }

        return Direction.NORTH;
    }

    private static Direction getChestFacing(BlockState bellState) {
        if (bellState.contains(BellBlock.FACING) && bellState.get(BellBlock.FACING).getAxis().isHorizontal()) {
            return bellState.get(BellBlock.FACING);
        }
        return Direction.NORTH;
    }

    private static boolean isVillagePlacementContext(ServerWorldAccess world, BlockPos bellPos, StructurePlacementData placementData) {
        if (placementData == null) {
            LOGGER.debug("Village context check failed at {}: missing structure placement data", bellPos.toShortString());
            return false;
        }

        if (!GuardVillagersConfig.bellChestRequireNearbyBed) {
            return true;
        }

        int radius = Math.max(0, GuardVillagersConfig.bellChestBedSearchRadius);
        for (BlockPos checkPos : BlockPos.iterate(bellPos.add(-radius, -2, -radius), bellPos.add(radius, 2, radius))) {
            if (world.getBlockState(checkPos).isIn(BlockTags.BEDS)) {
                return true;
            }
        }

        LOGGER.debug("Village context check failed at {}: no nearby bed within radius {}", bellPos.toShortString(), radius);
        return false;
    }

    private static boolean hasNearbyChest(ServerWorldAccess world, BlockPos bellPos) {
        for (BlockPos checkPos : BlockPos.iterate(bellPos.add(-2, -1, -2), bellPos.add(2, 1, 2))) {
            if (world.getBlockState(checkPos).isOf(Blocks.CHEST)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isValidChestPosition(ServerWorldAccess world, BlockPos pos) {
        BlockState stateAtPos = world.getBlockState(pos);
        if (!stateAtPos.isAir() && !stateAtPos.isReplaceable()) {
            return false;
        }

        return Blocks.CHEST.getDefaultState().canPlaceAt(world, pos);
    }
}
