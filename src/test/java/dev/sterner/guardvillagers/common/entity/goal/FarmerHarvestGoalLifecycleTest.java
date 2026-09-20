package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Deque;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmerHarvestGoalLifecycleTest {
    private static final BlockPos JOB = new BlockPos(10, 64, 10);
    private static final BlockPos CHEST = new BlockPos(11, 64, 10);
    private static final BlockPos OTHER_JOB = new BlockPos(30, 64, 30);
    private static final BlockPos OTHER_CHEST = new BlockPos(31, 64, 30);

    @Test
    void sameTargetSetTargetsDoesNotResetActiveFarmerStage() throws Exception {
        FarmerHarvestGoal goal = newGoal();
        setStage(goal, FarmerHarvestGoal.Stage.HOE_GROUND);

        boolean changed = goal.setTargets(JOB, CHEST);

        assertFalse(changed);
        assertEquals(FarmerHarvestGoal.Stage.HOE_GROUND, stage(goal));
        assertTrue(FarmerHarvestGoal.shouldContinueForState(true, true, stage(goal)));
    }

    @Test
    void samePairRefreshCannotStrandActiveFarmerInIdle() throws Exception {
        FarmerHarvestGoal goal = newGoal();
        setStage(goal, FarmerHarvestGoal.Stage.HARVEST);

        for (int refresh = 0; refresh < 5; refresh++) {
            assertFalse(goal.setTargets(JOB, CHEST));
        }

        assertEquals(FarmerHarvestGoal.Stage.HARVEST, stage(goal));
        assertTrue(FarmerHarvestGoal.shouldContinueForState(true, true, stage(goal)));
    }

    @Test
    void identicalPeriodicRefreshPreservesTerritoryScheduledWorkAndHoeTargets() throws Exception {
        FarmerHarvestGoal goal = newGoal();
        setStage(goal, FarmerHarvestGoal.Stage.HOE_GROUND);
        eligibleTerritory(goal).add(new BlockPos(12, 64, 10));
        hoeTargets(goal).add(new BlockPos(13, 64, 10));
        setField(goal, "currentHoeTarget", new BlockPos(13, 64, 10));
        setField(goal, "hasUnseededFarmlandObligation", true);
        setField(goal, "wheatSeedForagingRequested", true);
        FarmerWorkCheckSchedule schedule = schedule(goal);
        schedule.reset();
        schedule.requestNoSoonerThan(700L);

        goal.setTargets(JOB, CHEST);

        assertEquals(FarmerHarvestGoal.Stage.HOE_GROUND, stage(goal));
        assertEquals(1, eligibleTerritory(goal).size());
        assertEquals(1, hoeTargets(goal).size());
        assertEquals(new BlockPos(13, 64, 10), field(goal, "currentHoeTarget", BlockPos.class));
        assertTrue(field(goal, "hasUnseededFarmlandObligation", Boolean.class));
        assertTrue(field(goal, "wheatSeedForagingRequested", Boolean.class));
        assertEquals(700L, schedule.nextCheckTime());
        assertTrue(schedule.consumePriorityScanRequest());
    }

    @Test
    void activeGoalCannotRemainIndefinitelyInIdle() throws Exception {
        FarmerHarvestGoal goal = newGoal();
        setStage(goal, FarmerHarvestGoal.Stage.IDLE);

        assertFalse(FarmerHarvestGoal.shouldContinueForState(true, true, stage(goal)));
        assertFalse(FarmerHarvestGoal.isContinuableStage(FarmerHarvestGoal.Stage.IDLE));
    }

    @Test
    void shouldContinueIsFalseForEveryStageWithoutMeaningfulTickBehavior() throws Exception {
        FarmerHarvestGoal goal = newGoal();

        for (FarmerHarvestGoal.Stage stage : new FarmerHarvestGoal.Stage[]{
                FarmerHarvestGoal.Stage.IDLE, FarmerHarvestGoal.Stage.DONE}) {
            setStage(goal, stage);
            assertFalse(FarmerHarvestGoal.shouldContinueForState(true, true, stage), stage.name());
            assertFalse(FarmerHarvestGoal.isContinuableStage(stage), stage.name());
        }
    }

    @Test
    void genuineTargetChangeSafelyTerminatesAndRebindsOldRun() throws Exception {
        FarmerHarvestGoal goal = newGoal();
        setStage(goal, FarmerHarvestGoal.Stage.HOE_GROUND);
        eligibleTerritory(goal).add(new BlockPos(12, 64, 10));
        hoeTargets(goal).add(new BlockPos(13, 64, 10));
        setField(goal, "currentHoeTarget", new BlockPos(13, 64, 10));

        boolean changed = goal.setTargets(OTHER_JOB, OTHER_CHEST);

        assertTrue(changed);
        assertEquals(FarmerHarvestGoal.Stage.DONE, stage(goal));
        assertFalse(FarmerHarvestGoal.shouldContinueForState(true, true, stage(goal)));
        assertEquals(OTHER_JOB, field(goal, "jobPos", BlockPos.class));
        assertEquals(OTHER_CHEST, field(goal, "chestPos", BlockPos.class));
        assertTrue(eligibleTerritory(goal).isEmpty());
        assertTrue(hoeTargets(goal).isEmpty());
        assertNull(field(goal, "currentHoeTarget", BlockPos.class));
    }

    @Test
    void genuineTargetChangeRequestsFreshWorkCheck() throws Exception {
        FarmerHarvestGoal goal = newGoal();
        FarmerWorkCheckSchedule schedule = schedule(goal);
        schedule.reset();
        schedule.scheduleAt(900L);

        goal.setTargets(OTHER_JOB, OTHER_CHEST);

        assertEquals(0L, schedule.nextCheckTime());
        assertTrue(schedule.consumeImmediateRequest());
        assertTrue(schedule.consumePriorityScanRequest());
    }

    @Test
    void hoeMutationStillStartsWorkAfterPeriodicPairRefresh() throws Exception {
        FarmerHarvestGoal goal = newGoal();
        setStage(goal, FarmerHarvestGoal.Stage.HOE_GROUND);
        FarmerWorkCheckSchedule schedule = schedule(goal);
        schedule.reset();
        schedule.scheduleAt(900L);

        goal.setTargets(JOB, CHEST);
        goal.setTargets(JOB, CHEST);
        goal.requestImmediateWorkCheck();

        assertEquals(FarmerHarvestGoal.Stage.HOE_GROUND, stage(goal));
        assertFalse(schedule.shouldWait(1L));
        assertTrue(schedule.consumeImmediateRequest());
        assertTrue(schedule.consumePriorityScanRequest());
    }

    @Test
    void hoeInsertionAfterPriorCompletedFarmerRunStartsNewRun() throws Exception {
        FarmerHarvestGoal goal = newGoal();
        setStage(goal, FarmerHarvestGoal.Stage.DONE);
        FarmerWorkCheckSchedule schedule = schedule(goal);
        schedule.reset();
        schedule.scheduleAt(900L);

        goal.requestImmediateWorkCheck();
        assertFalse(schedule.shouldWait(1L));
        invokeAuthorizeStart(goal);
        goal.beginRunLifecycle();

        assertTrue(field(goal, "startFollowingChestWake", Boolean.class));
        assertEquals(FarmerHarvestGoal.Stage.GO_TO_JOB, stage(goal));
        assertTrue(FarmerHarvestGoal.shouldContinueForState(true, true, stage(goal)));
    }

    @Test
    void hoeInsertionWhileIdleBackedOffClearsDelayAndAllowsWork() throws Exception {
        FarmerHarvestGoal goal = newGoal();
        setStage(goal, FarmerHarvestGoal.Stage.DONE);
        FarmerWorkCheckSchedule schedule = schedule(goal);
        schedule.reset();
        schedule.scheduleAt(600L);

        goal.requestImmediateWorkCheck();

        assertEquals(0L, schedule.nextCheckTime());
        assertFalse(schedule.shouldWait(1L));
        assertTrue(schedule.consumeImmediateRequest());
        assertTrue(schedule.consumePriorityScanRequest());
    }

    private static FarmerHarvestGoal newGoal() {
        return new FarmerHarvestGoal(null, JOB, CHEST);
    }

    private static FarmerHarvestGoal.Stage stage(FarmerHarvestGoal goal) throws Exception {
        return field(goal, "stage", FarmerHarvestGoal.Stage.class);
    }

    private static void setStage(FarmerHarvestGoal goal, FarmerHarvestGoal.Stage stage) throws Exception {
        setField(goal, "stage", stage);
    }

    @SuppressWarnings("unchecked")
    private static Set<BlockPos> eligibleTerritory(FarmerHarvestGoal goal) throws Exception {
        return (Set<BlockPos>) field(goal, "eligibleTerritory", Set.class);
    }

    @SuppressWarnings("unchecked")
    private static Deque<BlockPos> hoeTargets(FarmerHarvestGoal goal) throws Exception {
        return (Deque<BlockPos>) field(goal, "hoeTargets", Deque.class);
    }

    private static FarmerWorkCheckSchedule schedule(FarmerHarvestGoal goal) throws Exception {
        return field(goal, "workCheckSchedule", FarmerWorkCheckSchedule.class);
    }

    private static void invokeAuthorizeStart(FarmerHarvestGoal goal) throws Exception {
        Method method = FarmerHarvestGoal.class.getDeclaredMethod("authorizeStart");
        method.setAccessible(true);
        assertTrue((boolean) method.invoke(goal));
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
