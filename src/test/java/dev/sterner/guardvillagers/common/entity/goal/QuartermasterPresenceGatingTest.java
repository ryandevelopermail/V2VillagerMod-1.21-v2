package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.VillageAnchorState;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.ai.goal.PrioritizedGoal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerProfession;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.function.Predicate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuartermasterPresenceGatingTest {

    @Test
    void plainLibrarianNearby_doesNotSuppressFallbackGoals() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        BlockPos anchor = new BlockPos(0, 64, 0);
        VillageAnchorState anchorState = mock(VillageAnchorState.class);
        when(world.getServer()).thenReturn(mock(net.minecraft.server.MinecraftServer.class));
        when(anchorState.getAllQmChests(world)).thenReturn(Set.of(anchor));

        boolean anyActive;
        try (MockedStatic<VillageAnchorState> anchorStateStatic = Mockito.mockStatic(VillageAnchorState.class)) {
            anchorStateStatic.when(() -> VillageAnchorState.get(world.getServer())).thenReturn(anchorState);
            anyActive = QuartermasterGoal.isAnyActive(world, anchor, 300.0D);
        }

        assertFalse(anyActive);
        verify(world, never()).getEntitiesByClass(eq(VillagerEntity.class), any(Box.class), any(Predicate.class));
    }

    @Test
    void librarianWithQuartermasterGoal_doesSuppressFallbackGoals() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        BlockPos anchor = new BlockPos(0, 64, 0);
        net.minecraft.server.MinecraftServer server = mock(net.minecraft.server.MinecraftServer.class);
        when(world.getServer()).thenReturn(server);
        when(world.getRegistryKey()).thenReturn(net.minecraft.world.World.OVERWORLD);

        VillagerEntity qmVillager = mock(VillagerEntity.class);
        UUID villagerId = UUID.randomUUID();
        when(qmVillager.getUuid()).thenReturn(villagerId);
        when(qmVillager.getUuidAsString()).thenReturn("qm-librarian");
        when(qmVillager.isAlive()).thenReturn(true);
        when(qmVillager.isRemoved()).thenReturn(false);
        VillagerData villagerData = mock(VillagerData.class);
        when(qmVillager.getVillagerData()).thenReturn(villagerData);
        when(villagerData.getProfession()).thenReturn(VillagerProfession.LIBRARIAN);
        GoalSelector qmSelector = mock(GoalSelector.class);
        setGoalSelector(qmVillager, qmSelector);

        QuartermasterGoal qmGoal = new QuartermasterGoal(qmVillager, anchor, anchor.north());
        PrioritizedGoal prioritizedGoal = new PrioritizedGoal(3, qmGoal);
        when(qmSelector.getGoals()).thenReturn(Set.of(prioritizedGoal));
        when(world.getEntity(villagerId)).thenReturn(qmVillager);

        VillageAnchorState anchorState = mock(VillageAnchorState.class);
        when(anchorState.getAllQmChests(world)).thenReturn(Set.of(anchor.north()));

        boolean anyActive;
        try (MockedStatic<VillageAnchorState> anchorStateStatic = Mockito.mockStatic(VillageAnchorState.class)) {
            anchorStateStatic.when(() -> VillageAnchorState.get(server)).thenReturn(anchorState);
            QuartermasterGoal.registerActiveQuartermaster(world, anchor.north(), villagerId);
            anyActive = QuartermasterGoal.isAnyActive(world, anchor, 300.0D);
            QuartermasterGoal.unregisterActiveQuartermaster(world, anchor.north(), villagerId);
        }

        assertTrue(anyActive);
        verify(world, never()).getEntitiesByClass(eq(VillagerEntity.class), any(Box.class), any(Predicate.class));
    }

    @Test
    void staleQuartermasterEntry_isPrunedLazily() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        BlockPos anchor = new BlockPos(0, 64, 0);
        net.minecraft.server.MinecraftServer server = mock(net.minecraft.server.MinecraftServer.class);
        when(world.getServer()).thenReturn(server);
        when(world.getRegistryKey()).thenReturn(net.minecraft.world.World.OVERWORLD);

        UUID staleId = UUID.randomUUID();
        when(world.getEntity(staleId)).thenReturn(null);

        VillageAnchorState anchorState = mock(VillageAnchorState.class);
        when(anchorState.getAllQmChests(world)).thenReturn(Set.of(anchor));

        boolean firstCheck;
        boolean secondCheck;
        try (MockedStatic<VillageAnchorState> anchorStateStatic = Mockito.mockStatic(VillageAnchorState.class)) {
            anchorStateStatic.when(() -> VillageAnchorState.get(server)).thenReturn(anchorState);
            QuartermasterGoal.registerActiveQuartermaster(world, anchor, staleId);
            firstCheck = QuartermasterGoal.isAnyActive(world, anchor, 300.0D);
            secondCheck = QuartermasterGoal.isAnyActive(world, anchor, 300.0D);
        } finally {
            QuartermasterGoal.unregisterActiveQuartermaster(world, anchor, staleId);
        }

        assertFalse(firstCheck);
        assertFalse(secondCheck);
        verify(world, never()).getEntitiesByClass(eq(VillagerEntity.class), any(Box.class), any(Predicate.class));
    }

    private static void setGoalSelector(VillagerEntity villager, GoalSelector selector) throws Exception {
        Field field = net.minecraft.entity.mob.MobEntity.class.getDeclaredField("goalSelector");
        field.setAccessible(true);
        field.set(villager, selector);
    }
}
