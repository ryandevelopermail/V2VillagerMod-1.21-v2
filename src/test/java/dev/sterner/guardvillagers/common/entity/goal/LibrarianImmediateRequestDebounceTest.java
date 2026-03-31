package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LibrarianImmediateRequestDebounceTest {

    @Test
    void craftingGoalRepeatedImmediateRequestsWithinDebounce_doNotResetNextCheckTime() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity villager = mock(VillagerEntity.class);
        LibrarianCraftingGoal goal = new LibrarianCraftingGoal(villager, BlockPos.ORIGIN, BlockPos.ORIGIN, BlockPos.ORIGIN);

        when(world.getTime()).thenReturn(100L, 110L);

        goal.requestImmediateCraft(world);
        setField(goal, "nextCheckTime", 240L);
        goal.requestImmediateCraft(world);

        assertTrue((boolean) getField(goal, "immediateCheckPending"));
        assertEquals(240L, getField(goal, "nextCheckTime"));
    }

    @Test
    void distributionGoalRepeatedImmediateRequestsWithinDebounce_doNotResetNextCheckTime() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity villager = mock(VillagerEntity.class);
        when(villager.getWorld()).thenReturn(world);

        LibrarianBellChestDistributionGoal goal = new LibrarianBellChestDistributionGoal(villager, BlockPos.ORIGIN, BlockPos.ORIGIN, BlockPos.ORIGIN);
        when(world.getTime()).thenReturn(300L, 310L);

        goal.requestImmediateDistribution();
        setField(AbstractInventoryDistributionGoal.class, goal, "nextCheckTime", 450L);
        goal.requestImmediateDistribution();

        assertTrue((boolean) getField(AbstractInventoryDistributionGoal.class, goal, "immediateCheckPending"));
        assertEquals(450L, getField(AbstractInventoryDistributionGoal.class, goal, "nextCheckTime"));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        setField(target.getClass(), target, name, value);
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        return getField(target.getClass(), target, name);
    }

    private static Object getField(Class<?> owner, Object target, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
