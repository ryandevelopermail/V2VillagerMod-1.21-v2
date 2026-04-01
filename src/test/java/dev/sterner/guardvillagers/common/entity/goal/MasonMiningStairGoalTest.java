package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.MasonGuardEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

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

    private static boolean invokeClearBlockIfNeeded(MasonMiningStairGoal goal, ServerWorld world, BlockPos pos) throws Exception {
        Method method = MasonMiningStairGoal.class.getDeclaredMethod("clearBlockIfNeeded", ServerWorld.class, BlockPos.class);
        method.setAccessible(true);
        return (boolean) method.invoke(goal, world, pos);
    }
}
