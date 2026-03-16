package dev.sterner.guardvillagers.common.util;

import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.HashSet;
import java.util.Set;

public final class PairedStorageHelper {
    private PairedStorageHelper() {
    }

    public static Set<BlockPos> getNormalizedStoragePositions(ServerWorld world, BlockPos storagePos) {
        BlockState state = world.getBlockState(storagePos);
        if (state.getBlock() instanceof BarrelBlock) {
            return Set.of(storagePos.toImmutable());
        }

        if (!(state.getBlock() instanceof ChestBlock)) {
            return Set.of();
        }

        Set<BlockPos> positions = new HashSet<>();
        positions.add(storagePos.toImmutable());

        ChestType chestType = state.get(ChestBlock.CHEST_TYPE);
        if (chestType != ChestType.SINGLE) {
            Direction facing = state.get(ChestBlock.FACING);
            Direction offsetDirection = chestType == ChestType.LEFT
                    ? facing.rotateYClockwise()
                    : facing.rotateYCounterclockwise();
            BlockPos otherHalfPos = storagePos.offset(offsetDirection);
            BlockState otherState = world.getBlockState(otherHalfPos);
            if (otherState.getBlock() instanceof ChestBlock
                    && otherState.get(ChestBlock.FACING) == facing
                    && otherState.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
                positions.add(otherHalfPos.toImmutable());
            }
        }

        return positions;
    }

    public static boolean areEquivalentStoragePositions(ServerWorld world, BlockPos firstPos, BlockPos secondPos) {
        if (firstPos.equals(secondPos)) {
            return true;
        }

        Set<BlockPos> firstNormalized = getNormalizedStoragePositions(world, firstPos);
        if (firstNormalized.isEmpty()) {
            return false;
        }

        Set<BlockPos> secondNormalized = getNormalizedStoragePositions(world, secondPos);
        if (secondNormalized.isEmpty()) {
            return false;
        }

        for (BlockPos pos : firstNormalized) {
            if (secondNormalized.contains(pos)) {
                return true;
            }
        }
        return false;
    }
}
