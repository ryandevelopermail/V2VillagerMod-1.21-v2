package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.QuartermasterPrerequisiteHelper;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuartermasterGoalPrerequisiteValidationTimingTest {

    @Test
    void structuralValidation_isThrottledBetweenChecks() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity villager = mock(VillagerEntity.class);
        BlockPos jobPos = new BlockPos(0, 64, 0);
        BlockPos chestPos = new BlockPos(1, 64, 0);
        BlockPos secondChestPos = new BlockPos(2, 64, 0);
        QuartermasterGoal goal = new QuartermasterGoal(villager, jobPos, chestPos);

        when(world.getTime()).thenReturn(100L, 110L, 121L);

        QuartermasterPrerequisiteHelper.Result valid =
                new QuartermasterPrerequisiteHelper.Result(true, secondChestPos);
        try (MockedStatic<QuartermasterPrerequisiteHelper> mocked = Mockito.mockStatic(QuartermasterPrerequisiteHelper.class)) {
            mocked.when(() -> QuartermasterPrerequisiteHelper.validate(world, villager, jobPos, chestPos))
                    .thenReturn(valid);

            assertTrue(invokeValidate(goal, world, false));
            assertTrue(invokeValidate(goal, world, false));
            assertTrue(invokeValidate(goal, world, false));

            mocked.verify(() -> QuartermasterPrerequisiteHelper.validate(eq(world), eq(villager), eq(jobPos), eq(chestPos)), Mockito.times(2));
        }
    }

    @Test
    void chestListenerSignal_forcesImmediateStructuralRevalidation() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity villager = mock(VillagerEntity.class);
        BlockPos jobPos = new BlockPos(0, 64, 0);
        BlockPos chestPos = new BlockPos(1, 64, 0);
        BlockPos secondChestPos = new BlockPos(2, 64, 0);
        QuartermasterGoal goal = new QuartermasterGoal(villager, jobPos, chestPos);

        when(world.getTime()).thenReturn(100L, 101L);
        QuartermasterPrerequisiteHelper.Result valid =
                new QuartermasterPrerequisiteHelper.Result(true, secondChestPos);
        try (MockedStatic<QuartermasterPrerequisiteHelper> mocked = Mockito.mockStatic(QuartermasterPrerequisiteHelper.class)) {
            mocked.when(() -> QuartermasterPrerequisiteHelper.validate(world, villager, jobPos, chestPos))
                    .thenReturn(valid);

            assertTrue(invokeValidate(goal, world, false));
            goal.requestImmediatePrerequisiteRevalidation();
            assertTrue(invokeValidate(goal, world, false));

            mocked.verify(() -> QuartermasterPrerequisiteHelper.validate(eq(world), eq(villager), eq(jobPos), eq(chestPos)), Mockito.times(2));
        }
    }

    @Test
    void forcedRevalidation_demotesPromptlyWhenChestPairBreaks() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity villager = mock(VillagerEntity.class);
        BlockPos jobPos = new BlockPos(0, 64, 0);
        BlockPos chestPos = new BlockPos(1, 64, 0);
        BlockPos secondChestPos = new BlockPos(2, 64, 0);
        QuartermasterGoal goal = new QuartermasterGoal(villager, jobPos, chestPos);

        when(world.getTime()).thenReturn(100L, 101L);
        QuartermasterPrerequisiteHelper.Result valid =
                new QuartermasterPrerequisiteHelper.Result(true, secondChestPos);
        QuartermasterPrerequisiteHelper.Result invalid = QuartermasterPrerequisiteHelper.Result.invalid();
        try (MockedStatic<QuartermasterPrerequisiteHelper> mocked = Mockito.mockStatic(QuartermasterPrerequisiteHelper.class)) {
            mocked.when(() -> QuartermasterPrerequisiteHelper.validate(world, villager, jobPos, chestPos))
                    .thenReturn(valid, invalid);

            assertTrue(invokeValidate(goal, world, false));
            goal.requestImmediatePrerequisiteRevalidation();
            assertFalse(invokeValidate(goal, world, false));
        }
    }

    private static boolean invokeValidate(QuartermasterGoal goal, ServerWorld world, boolean forceNow) throws Exception {
        Method method = QuartermasterGoal.class.getDeclaredMethod("validateAndSyncPrerequisites", ServerWorld.class, boolean.class);
        method.setAccessible(true);
        return (boolean) method.invoke(goal, world, forceNow);
    }
}
