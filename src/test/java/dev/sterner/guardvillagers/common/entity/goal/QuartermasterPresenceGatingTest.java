package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.ai.goal.PrioritizedGoal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerProfession;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuartermasterPresenceGatingTest {

    @Test
    void plainLibrarianNearby_doesNotSuppressFallbackGoals() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        BlockPos anchor = new BlockPos(0, 64, 0);
        VillagerEntity plainLibrarian = librarianWithGoals(Set.of());

        mockLibrarianQuery(world, plainLibrarian);

        boolean anyActive = QuartermasterGoal.isAnyActive(world, anchor, 300.0D);

        assertFalse(anyActive);
    }

    @Test
    void librarianWithQuartermasterGoal_doesSuppressFallbackGoals() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        BlockPos anchor = new BlockPos(0, 64, 0);

        VillagerEntity qmVillager = mock(VillagerEntity.class);
        when(qmVillager.getUuidAsString()).thenReturn("qm-librarian");
        GoalSelector qmSelector = mock(GoalSelector.class);
        setGoalSelector(qmVillager, qmSelector);

        QuartermasterGoal qmGoal = new QuartermasterGoal(qmVillager, anchor, anchor.north());
        PrioritizedGoal prioritizedGoal = new PrioritizedGoal(3, qmGoal);

        VillagerEntity librarian = librarianWithGoals(Set.of(prioritizedGoal));
        mockLibrarianQuery(world, librarian);

        boolean anyActive = QuartermasterGoal.isAnyActive(world, anchor, 300.0D);

        assertTrue(anyActive);
    }

    @SuppressWarnings("unchecked")
    private static void mockLibrarianQuery(ServerWorld world, VillagerEntity librarian) {
        when(world.getEntitiesByClass(
                eq(VillagerEntity.class),
                any(Box.class),
                any(Predicate.class)
        )).thenReturn((List) List.of(librarian));
    }

    private static VillagerEntity librarianWithGoals(Set<PrioritizedGoal> goals) throws Exception {
        VillagerEntity villager = mock(VillagerEntity.class);
        VillagerData villagerData = mock(VillagerData.class);
        when(villager.isAlive()).thenReturn(true);
        when(villager.getVillagerData()).thenReturn(villagerData);
        when(villagerData.getProfession()).thenReturn(VillagerProfession.LIBRARIAN);

        GoalSelector selector = mock(GoalSelector.class);
        when(selector.getGoals()).thenReturn(goals);
        setGoalSelector(villager, selector);
        return villager;
    }

    private static void setGoalSelector(VillagerEntity villager, GoalSelector selector) throws Exception {
        Field field = net.minecraft.entity.mob.MobEntity.class.getDeclaredField("goalSelector");
        field.setAccessible(true);
        field.set(villager, selector);
    }
}
