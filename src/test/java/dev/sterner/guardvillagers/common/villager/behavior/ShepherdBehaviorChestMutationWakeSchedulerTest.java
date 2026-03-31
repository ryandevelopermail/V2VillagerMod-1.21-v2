package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.entity.goal.ShepherdBedPlacerGoal;
import dev.sterner.guardvillagers.common.entity.goal.ShepherdCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.ShepherdFencePlacerGoal;
import dev.sterner.guardvillagers.common.entity.goal.ShepherdSpecialGoal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShepherdBehaviorChestMutationWakeSchedulerTest {

    @AfterEach
    void clearStaticState() throws Exception {
        map("SPECIAL_GOALS").clear();
        map("CRAFTING_GOALS").clear();
        map("BED_PLACER_GOALS").clear();
        map("FENCE_PLACER_GOALS").clear();
        map("LAST_WAKE_TICKS").clear();
        map("QUEUED_WAKE_TICKS").clear();
        map("DIRTY_WAKE_FLAGS").clear();
        map("LAST_CHEST_SNAPSHOTS").clear();
    }

    @Test
    void burstMutationsWithinDebounce_onlyOneImmediateWakeupPerWindow() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity villager = mock(VillagerEntity.class);
        ShepherdSpecialGoal specialGoal = mock(ShepherdSpecialGoal.class);
        ShepherdCraftingGoal craftingGoal = mock(ShepherdCraftingGoal.class);

        map("SPECIAL_GOALS").put(villager, specialGoal);
        map("CRAFTING_GOALS").put(villager, craftingGoal);

        int dirtySpecial = getDirtyFlag("DIRTY_SPECIAL");
        int dirtyCrafting = getDirtyFlag("DIRTY_CRAFTING");

        when(world.getTime()).thenReturn(100L, 103L, 107L, 150L);

        invokeScheduleWake(world, villager, dirtySpecial | dirtyCrafting);
        invokeScheduleWake(world, villager, dirtySpecial | dirtyCrafting);
        invokeScheduleWake(world, villager, dirtySpecial | dirtyCrafting);
        invokeScheduleWake(world, villager, dirtySpecial | dirtyCrafting);

        verify(specialGoal, times(2)).requestImmediateCheck();
        verify(craftingGoal, times(2)).requestImmediateCraft(world);
        verify(specialGoal, times(2)).onChestInventoryChanged(world);
        verify(craftingGoal, times(2)).requestCraftNoSoonerThan(140L);
    }

    @Test
    void categoryDirtyFlags_preventUnrelatedBedAndFenceWakeups() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity villager = mock(VillagerEntity.class);
        ShepherdSpecialGoal specialGoal = mock(ShepherdSpecialGoal.class);
        ShepherdBedPlacerGoal bedPlacerGoal = mock(ShepherdBedPlacerGoal.class);
        ShepherdFencePlacerGoal fencePlacerGoal = mock(ShepherdFencePlacerGoal.class);

        map("SPECIAL_GOALS").put(villager, specialGoal);
        map("BED_PLACER_GOALS").put(villager, bedPlacerGoal);
        map("FENCE_PLACER_GOALS").put(villager, fencePlacerGoal);

        when(world.getTime()).thenReturn(200L);

        int dirtySpecial = getDirtyFlag("DIRTY_SPECIAL");
        invokeScheduleWake(world, villager, dirtySpecial);

        verify(specialGoal).requestImmediateCheck();
        verify(bedPlacerGoal, never()).requestImmediateCheck();
        verify(fencePlacerGoal, never()).requestImmediateCheck();
    }

    private static void invokeScheduleWake(ServerWorld world, VillagerEntity villager, int dirtyFlags) throws Exception {
        Method method = ShepherdBehavior.class.getDeclaredMethod(
                "scheduleVillagerWakeup",
                ServerWorld.class,
                VillagerEntity.class,
                int.class
        );
        method.setAccessible(true);
        method.invoke(null, world, villager, dirtyFlags);
    }

    private static int getDirtyFlag(String fieldName) throws Exception {
        Field field = ShepherdBehavior.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> map(String fieldName) throws Exception {
        Field field = ShepherdBehavior.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<Object, Object>) field.get(null);
    }
}
