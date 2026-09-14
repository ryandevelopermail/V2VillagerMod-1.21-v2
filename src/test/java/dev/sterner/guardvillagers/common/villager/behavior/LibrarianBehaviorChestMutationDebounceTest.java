package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.entity.goal.LibrarianCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.QuartermasterGoal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LibrarianBehaviorChestMutationDebounceTest {

    @AfterEach
    void clearStaticState() throws Exception {
        map("CRAFTING_GOALS").clear();
        map("QUARTERMASTER_GOALS").clear();
        map("CHEST_WATCHERS_BY_POS").clear();
        map("LAST_IMMEDIATE_REQUEST_TICK").clear();
        map("INVENTORY_DIRTY_FLAGS").clear();
    }

    @Test
    void repeatedChestMutationsWithinDebounce_onlyTriggerOneImmediateRefresh() throws Exception {
        LibrarianBehavior behavior = new LibrarianBehavior();
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity villager = mock(VillagerEntity.class);
        LibrarianCraftingGoal craftingGoal = mock(LibrarianCraftingGoal.class);
        QuartermasterGoal quartermasterGoal = mock(QuartermasterGoal.class);
        BlockPos firstHalf = new BlockPos(10, 64, 10);
        BlockPos secondHalf = firstHalf.east();

        map("CRAFTING_GOALS").put(villager, craftingGoal);
        map("QUARTERMASTER_GOALS").put(villager, quartermasterGoal);
        watcherMap().put(firstHalf, new HashSet<>(Set.of(villager)));
        watcherMap().put(secondHalf, new HashSet<>(Set.of(villager)));
        when(villager.isAlive()).thenReturn(true);
        when(villager.getWorld()).thenReturn(world);

        when(world.getTime()).thenReturn(100L, 105L, 110L, 140L);

        LibrarianBehavior.onChestInventoryMutated(world, firstHalf);
        LibrarianBehavior.onChestInventoryMutated(world, secondHalf);
        LibrarianBehavior.onChestInventoryMutated(world, firstHalf);
        LibrarianBehavior.onChestInventoryMutated(world, secondHalf);

        verify(craftingGoal, times(2)).requestImmediateCraft(world);
        verify(quartermasterGoal, times(2)).requestImmediatePrerequisiteRevalidation();
        verify(quartermasterGoal, times(2)).requestImmediateDemandReplan();
    }

    @Test
    void meaningfulStateChangeBypassesDebounce_once() throws Exception {
        LibrarianBehavior behavior = new LibrarianBehavior();
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity villager = mock(VillagerEntity.class);
        LibrarianCraftingGoal craftingGoal = mock(LibrarianCraftingGoal.class);

        map("CRAFTING_GOALS").put(villager, craftingGoal);

        when(world.getTime()).thenReturn(200L, 205L);

        invokeScheduleImmediateRefresh(behavior, world, villager, false);
        invokeScheduleImmediateRefresh(behavior, world, villager, true);

        verify(craftingGoal, times(2)).requestImmediateCraft(world);
    }

    @Test
    void sameTickBypassRequests_coalesceToOneWakeup() throws Exception {
        LibrarianBehavior behavior = new LibrarianBehavior();
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity villager = mock(VillagerEntity.class);
        QuartermasterGoal quartermasterGoal = mock(QuartermasterGoal.class);

        map("QUARTERMASTER_GOALS").put(villager, quartermasterGoal);
        when(world.getTime()).thenReturn(250L, 250L);

        invokeScheduleImmediateRefresh(behavior, world, villager, true);
        invokeScheduleImmediateRefresh(behavior, world, villager, true);

        verify(quartermasterGoal).requestImmediatePrerequisiteRevalidation();
        verify(quartermasterGoal).requestImmediateDemandReplan();
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

    @SuppressWarnings("unchecked")
    private static Map<BlockPos, Set<VillagerEntity>> watcherMap() throws Exception {
        return (Map<BlockPos, Set<VillagerEntity>>) (Map<?, ?>) map("CHEST_WATCHERS_BY_POS");
    }
}
