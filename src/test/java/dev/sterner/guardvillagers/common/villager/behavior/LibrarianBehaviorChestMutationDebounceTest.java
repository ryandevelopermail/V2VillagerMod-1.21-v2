package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.entity.goal.LibrarianBellChestDistributionGoal;
import dev.sterner.guardvillagers.common.entity.goal.LibrarianCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.QuartermasterGoal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LibrarianBehaviorChestMutationDebounceTest {

    @AfterEach
    void clearStaticState() throws Exception {
        map("CRAFTING_GOALS").clear();
        map("DISTRIBUTION_GOALS").clear();
        map("QUARTERMASTER_GOALS").clear();
        map("LAST_IMMEDIATE_REQUEST_TICK").clear();
        map("INVENTORY_DIRTY_FLAGS").clear();
    }

    @Test
    void repeatedChestMutationsWithinDebounce_onlyTriggerOneImmediateRefresh() throws Exception {
        LibrarianBehavior behavior = new LibrarianBehavior();
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity villager = mock(VillagerEntity.class);
        LibrarianCraftingGoal craftingGoal = mock(LibrarianCraftingGoal.class);
        LibrarianBellChestDistributionGoal distributionGoal = mock(LibrarianBellChestDistributionGoal.class);
        QuartermasterGoal quartermasterGoal = mock(QuartermasterGoal.class);

        map("CRAFTING_GOALS").put(villager, craftingGoal);
        map("DISTRIBUTION_GOALS").put(villager, distributionGoal);
        map("QUARTERMASTER_GOALS").put(villager, quartermasterGoal);

        when(world.getTime()).thenReturn(100L, 105L, 110L, 140L);

        invokeScheduleImmediateRefresh(behavior, world, villager, false);
        invokeScheduleImmediateRefresh(behavior, world, villager, false);
        invokeScheduleImmediateRefresh(behavior, world, villager, false);
        invokeScheduleImmediateRefresh(behavior, world, villager, false);

        verify(craftingGoal, times(2)).requestImmediateCraft(world);
        verify(distributionGoal, times(2)).requestImmediateDistribution();
        verify(quartermasterGoal, times(2)).requestImmediatePrerequisiteRevalidation();
    }

    @Test
    void meaningfulStateChangeBypassesDebounce_once() throws Exception {
        LibrarianBehavior behavior = new LibrarianBehavior();
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity villager = mock(VillagerEntity.class);
        LibrarianCraftingGoal craftingGoal = mock(LibrarianCraftingGoal.class);
        LibrarianBellChestDistributionGoal distributionGoal = mock(LibrarianBellChestDistributionGoal.class);

        map("CRAFTING_GOALS").put(villager, craftingGoal);
        map("DISTRIBUTION_GOALS").put(villager, distributionGoal);

        when(world.getTime()).thenReturn(200L, 205L);

        invokeScheduleImmediateRefresh(behavior, world, villager, false);
        invokeScheduleImmediateRefresh(behavior, world, villager, true);

        verify(craftingGoal, times(2)).requestImmediateCraft(world);
        verify(distributionGoal, times(2)).requestImmediateDistribution();
    }

    private static void invokeScheduleImmediateRefresh(LibrarianBehavior behavior,
                                                       ServerWorld world,
                                                       VillagerEntity villager,
                                                       boolean bypassDebounce) throws Exception {
        Method method = LibrarianBehavior.class.getDeclaredMethod(
                "scheduleImmediateInventoryRefresh",
                ServerWorld.class,
                VillagerEntity.class,
                boolean.class
        );
        method.setAccessible(true);
        method.invoke(behavior, world, villager, bypassDebounce);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> map(String fieldName) throws Exception {
        Field field = LibrarianBehavior.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<Object, Object>) field.get(null);
    }
}
