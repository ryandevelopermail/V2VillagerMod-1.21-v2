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

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
