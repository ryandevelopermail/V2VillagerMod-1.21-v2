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

/** Generates real vanilla mature trees only in pre-checked, non-destructive volumes. */
final class DeveloperTreeGenerator {
    private static final int MIN_RADIUS = 8;
    private static final int MAX_RADIUS = 20;
    private static final int MAX_GROWTH_INVOCATIONS = 3;
    private static final int TRUNK_CLEARANCE_HEIGHT = 6;
    private static final int CORE_CANOPY_RADIUS = 1;
    private static final int CORE_CANOPY_MIN_Y = 3;
    private static final int CORE_CANOPY_MAX_Y = 7;

    private DeveloperTreeGenerator() {
    }

    static Result generateNext(ServerWorld world, BlockPos origin, Set<BlockPos> attempted) {
        int preflightRejections = 0;
        int vanillaGrowthFailures = 0;
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
                    if (!attempted.add(saplingPos.toImmutable())) {
                        continue;
                    }
                    CandidateStatus candidateStatus = validateCandidate(world, saplingPos);
                    if (candidateStatus == CandidateStatus.UNUSABLE) {
                        continue;
                    }
                    if (candidateStatus == CandidateStatus.PREFLIGHT_REJECTED) {
                        preflightRejections++;
                        continue;
                    }

                    BlockState saplingState = Blocks.OAK_SAPLING.getDefaultState();
                    if (!world.setBlockState(saplingPos, saplingState, Block.NOTIFY_ALL)) {
                        vanillaGrowthFailures++;
                        continue;
                    }
                    boolean generated = DeveloperTreeGrowthDriver.growToMaturity(
                            MAX_GROWTH_INVOCATIONS,
                            () -> world.getBlockState(saplingPos).isOf(Blocks.OAK_SAPLING),
                            () -> isMatureOakTree(world, saplingPos),
                            () -> invokeVanillaGrowth(world, saplingPos),
                            () -> world.removeBlock(saplingPos, false)
                    );
                    if (generated) {
                        return Result.GENERATED;
                    }
                    vanillaGrowthFailures++;
                }
            }
        }
        return switch (DeveloperTreeSitePolicy.classifyFailure(preflightRejections, vanillaGrowthFailures)) {
            case NO_USABLE_CANDIDATE -> Result.NO_USABLE_CANDIDATE;
            case PREFLIGHT_REJECTED -> Result.PREFLIGHT_REJECTED;
            case VANILLA_GROWTH_FAILED -> Result.VANILLA_GROWTH_FAILED;
        };
    }

    private static CandidateStatus validateCandidate(ServerWorld world, BlockPos saplingPos) {
        if (!world.getWorldBorder().contains(saplingPos)) {
            return CandidateStatus.UNUSABLE;
        }
        BlockState ground = world.getBlockState(saplingPos.down());
        if (!ground.isIn(BlockTags.DIRT)) {
            return CandidateStatus.UNUSABLE;
        }
        if (!allowsVegetationOrAir(world.getBlockState(saplingPos))) {
            return CandidateStatus.UNUSABLE;
        }
        for (int dy = 1; dy <= TRUNK_CLEARANCE_HEIGHT; dy++) {
            if (!allowsVegetationOrAir(world.getBlockState(saplingPos.up(dy)))) {
                return CandidateStatus.PREFLIGHT_REJECTED;
            }
        }
        for (int dx = -CORE_CANOPY_RADIUS; dx <= CORE_CANOPY_RADIUS; dx++) {
            for (int dz = -CORE_CANOPY_RADIUS; dz <= CORE_CANOPY_RADIUS; dz++) {
                for (int dy = CORE_CANOPY_MIN_Y; dy <= CORE_CANOPY_MAX_Y; dy++) {
                    BlockState state = world.getBlockState(saplingPos.add(dx, dy, dz));
                    if (!allowsVegetationOrAir(state)) {
                        return CandidateStatus.PREFLIGHT_REJECTED;
                    }
                }
            }
        }
        return CandidateStatus.VALID;
    }

    private static boolean allowsVegetationOrAir(BlockState state) {
        return DeveloperTreeSitePolicy.allowsClearanceBlock(
                state.isAir(),
                state.isReplaceable(),
                state.getFluidState().isEmpty());
    }

    private static void invokeVanillaGrowth(ServerWorld world, BlockPos saplingPos) {
        BlockState currentState = world.getBlockState(saplingPos);
        if (currentState.isOf(Blocks.OAK_SAPLING)) {
            ((SaplingBlock) Blocks.OAK_SAPLING).generate(world, saplingPos, currentState, world.random);
        }
    }

    private static boolean isMatureOakTree(ServerWorld world, BlockPos saplingPos) {
        if (world.getBlockState(saplingPos).isOf(Blocks.OAK_SAPLING)) {
            return false;
        }
        for (int dy = 0; dy <= TRUNK_CLEARANCE_HEIGHT; dy++) {
            if (world.getBlockState(saplingPos.up(dy)).isIn(BlockTags.OAK_LOGS)) {
                return true;
            }
        }
        return false;
    }

    enum Result {
        GENERATED,
        NO_USABLE_CANDIDATE,
        PREFLIGHT_REJECTED,
        VANILLA_GROWTH_FAILED
    }

    private enum CandidateStatus {
        VALID,
        UNUSABLE,
        PREFLIGHT_REJECTED
    }
}
