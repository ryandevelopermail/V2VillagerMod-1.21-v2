package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.MasonGuardEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MasonMiningStairGoalTest {

    @Test
    void hasRepairSummaryData_onlyReportsWhenThereAreRepairEvents() {
        assertFalse(MasonMiningStairGoal.hasRepairSummaryData(0, 0));
        assertTrue(MasonMiningStairGoal.hasRepairSummaryData(1, 0));
        assertTrue(MasonMiningStairGoal.hasRepairSummaryData(0, 1));
    }

    @Test
    void clearBlockIfNeeded_neverBreaksMasonPairedJobBlock_andSkipsRetries() throws Exception {
        MasonGuardEntity guard = mock(MasonGuardEntity.class);
        ServerWorld world = mock(ServerWorld.class);
        BlockPos protectedPos = new BlockPos(4, 64, 9);
        BlockState protectedState = Blocks.STONECUTTER.getDefaultState();

        when(guard.getPairedJobPos()).thenReturn(protectedPos);
        when(world.getBlockState(protectedPos)).thenReturn(protectedState);

        MasonMiningStairGoal goal = new MasonMiningStairGoal(guard);

        assertFalse(invokeClearBlockIfNeeded(goal, world, protectedPos));
        assertFalse(invokeClearBlockIfNeeded(goal, world, protectedPos));

        verify(world, never()).breakBlock(any(BlockPos.class), anyBoolean(), any());
        verify(world, times(1)).getBlockState(protectedPos);
    }

    @Test
    void clearBlockIfNeeded_neverBreaksAnyProfessionJobBlock() throws Exception {
        MasonGuardEntity guard = mock(MasonGuardEntity.class);
        ServerWorld world = mock(ServerWorld.class);
        BlockPos protectedPos = new BlockPos(7, 65, 3);
        BlockState protectedState = Blocks.LECTERN.getDefaultState();

        when(guard.getPairedJobPos()).thenReturn(new BlockPos(0, 64, 0));
        when(world.getBlockState(protectedPos)).thenReturn(protectedState);

        MasonMiningStairGoal goal = new MasonMiningStairGoal(guard);

        assertFalse(invokeClearBlockIfNeeded(goal, world, protectedPos));

        verify(world, never()).breakBlock(any(BlockPos.class), anyBoolean(), any());
    }

    @Test
    void isBootstrapObstructedAtOrigin_detectsAdjacentChestStructure() throws Exception {
        MasonGuardEntity guard = mock(MasonGuardEntity.class);
        ServerWorld world = mock(ServerWorld.class);
        BlockPos origin = new BlockPos(10, 64, 10);
        BlockPos chestPos = origin.north();
        BlockPos jobPos = new BlockPos(0, 64, 0);

        when(guard.getPairedChestPos()).thenReturn(chestPos);
        when(guard.getPairedJobPos()).thenReturn(jobPos);

        MasonMiningStairGoal goal = new MasonMiningStairGoal(guard);

        assertTrue(invokeIsBootstrapObstructedAtOrigin(goal, world, origin, Direction.NORTH));
    }

    @Test
    void buildBootstrapCandidateOffsets_generatesExpectedRingCount() throws Exception {
        MasonGuardEntity guard = mock(MasonGuardEntity.class);
        MasonMiningStairGoal goal = new MasonMiningStairGoal(guard);
        BlockPos origin = new BlockPos(0, 64, 0);

        List<BlockPos> candidates = invokeBuildBootstrapCandidateOffsets(goal, origin);

        assertEquals(768, candidates.size());
    }

    private static boolean invokeClearBlockIfNeeded(MasonMiningStairGoal goal, ServerWorld world, BlockPos pos) throws Exception {
        Method method = MasonMiningStairGoal.class.getDeclaredMethod("clearBlockIfNeeded", ServerWorld.class, BlockPos.class);
        method.setAccessible(true);
        return (boolean) method.invoke(goal, world, pos);
    }

    private static boolean invokeIsBootstrapObstructedAtOrigin(MasonMiningStairGoal goal, ServerWorld world, BlockPos origin, Direction direction) throws Exception {
        Method method = MasonMiningStairGoal.class.getDeclaredMethod("isBootstrapObstructedAtOrigin", ServerWorld.class, BlockPos.class, Direction.class);
        method.setAccessible(true);
        return (boolean) method.invoke(goal, world, origin, direction);
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> invokeBuildBootstrapCandidateOffsets(MasonMiningStairGoal goal, BlockPos origin) throws Exception {
        Method method = MasonMiningStairGoal.class.getDeclaredMethod("buildBootstrapCandidateOffsets", BlockPos.class);
        method.setAccessible(true);
        return (List<BlockPos>) method.invoke(goal, origin);
    }
}
