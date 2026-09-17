package dev.sterner.guardvillagers.common.developer;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SaplingBlock;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.Set;

/** Generates real vanilla mature trees only in pre-checked, empty volumes. */
final class DeveloperTreeGenerator {
    private static final int MIN_RADIUS = 8;
    private static final int MAX_RADIUS = 20;
    private static final int CLEARANCE_RADIUS = 3;
    private static final int CLEARANCE_HEIGHT = 9;

    private DeveloperTreeGenerator() {
    }

    static Result generateNext(ServerWorld world, BlockPos origin, Set<BlockPos> attempted) {
        for (int radius = MIN_RADIUS; radius <= MAX_RADIUS; radius += 2) {
            for (int dx = -radius; dx <= radius; dx += 2) {
                for (int dz = -radius; dz <= radius; dz += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    int x = origin.getX() + dx;
                    int z = origin.getZ() + dz;
                    int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos saplingPos = new BlockPos(x, y, z);
                    if (!attempted.add(saplingPos.toImmutable()) || !isSafeTreeSite(world, saplingPos)) {
                        continue;
                    }

                    BlockState saplingState = Blocks.OAK_SAPLING.getDefaultState();
                    if (!world.setBlockState(saplingPos, saplingState, Block.NOTIFY_ALL)) {
                        continue;
                    }
                    ((SaplingBlock) Blocks.OAK_SAPLING).generate(world, saplingPos, saplingState, world.random);
                    if (!world.getBlockState(saplingPos).isOf(Blocks.OAK_SAPLING)) {
                        return Result.GENERATED;
                    }
                    world.removeBlock(saplingPos, false);
                }
            }
        }
        return Result.NO_SAFE_SITE;
    }

    private static boolean isSafeTreeSite(ServerWorld world, BlockPos saplingPos) {
        if (!world.getWorldBorder().contains(saplingPos)) {
            return false;
        }
        BlockState ground = world.getBlockState(saplingPos.down());
        if (!ground.isIn(BlockTags.DIRT)) {
            return false;
        }
        for (int dx = -CLEARANCE_RADIUS; dx <= CLEARANCE_RADIUS; dx++) {
            for (int dz = -CLEARANCE_RADIUS; dz <= CLEARANCE_RADIUS; dz++) {
                for (int dy = 0; dy <= CLEARANCE_HEIGHT; dy++) {
                    if (!world.getBlockState(saplingPos.add(dx, dy, dz)).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    enum Result {
        GENERATED,
        NO_SAFE_SITE
    }
}
