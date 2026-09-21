package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.entity.goal.LibrarianCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.QuartermasterGoal;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerProfession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LibrarianBehaviorChestListenerAndDoubleInventoryRegressionTest {

    @AfterEach
    void clearStaticState() throws Exception {
        map("CRAFTING_GOALS").clear();
        map("QUARTERMASTER_GOALS").clear();
        map("PAIRED_CHEST_POS").clear();
        map("CHEST_REGISTRATIONS").clear();
        map("CHEST_WATCHERS_BY_POS").clear();
        map("LAST_IMMEDIATE_REQUEST_TICK").clear();
        map("INVENTORY_DIRTY_FLAGS").clear();
        map("LAST_QUARTERMASTER_PAIR").clear();
        quartermasterMap("ACTIVE_QM_BY_WORLD_ANCHOR").clear();
    }

    @Test
    void singleChestMutation_wakesLibrarianCrafting() throws Exception {
        LibrarianBehavior behavior = new LibrarianBehavior();
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity villager = mockLibrarian(world);
        LibrarianCraftingGoal craftingGoal = mock(LibrarianCraftingGoal.class);
        BlockPos chestPos = new BlockPos(8, 64, 8);

        map("CRAFTING_GOALS").put(villager, craftingGoal);
        when(world.getBlockState(chestPos)).thenReturn(chestState(ChestType.SINGLE, Direction.NORTH));
        when(world.getTime()).thenReturn(200L);

        invokeUpdateChestWatcher(behavior, world, villager, chestPos);
        LibrarianBehavior.onChestInventoryMutated(world, chestPos);

        verify(craftingGoal).requestImmediateCraft(world);
        assertTrue(map("CHEST_REGISTRATIONS").containsKey(villager));
        assertEquals(Set.of(villager), watcherMap().get(chestPos));
    }

    @Test
    void eitherDoubleChestHalf_wakesAllQuartermasterAndLibrarianPaths() throws Exception {
        LibrarianBehavior behavior = new LibrarianBehavior();
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity villager = mockLibrarian(world);
        LibrarianCraftingGoal craftingGoal = mock(LibrarianCraftingGoal.class);
        QuartermasterGoal quartermasterGoal = mock(QuartermasterGoal.class);
        BlockPos chestPos = new BlockPos(21, 64, 20);
        BlockPos secondPos = chestPos.east();

        map("CRAFTING_GOALS").put(villager, craftingGoal);
        map("QUARTERMASTER_GOALS").put(villager, quartermasterGoal);
        when(world.getBlockState(chestPos)).thenReturn(chestState(ChestType.LEFT, Direction.NORTH));
        when(world.getBlockState(secondPos)).thenReturn(chestState(ChestType.RIGHT, Direction.NORTH));
        when(world.getTime()).thenReturn(300L, 331L);

        invokeUpdateChestWatcher(behavior, world, villager, chestPos);
        LibrarianBehavior.onChestInventoryMutated(world, chestPos);
        LibrarianBehavior.onChestInventoryMutated(world, secondPos);

        verify(craftingGoal, times(2)).requestImmediateCraft(world);
        verify(quartermasterGoal, times(2)).requestImmediatePrerequisiteRevalidation();
        verify(quartermasterGoal, times(2)).requestImmediateDemandReplan();
        assertEquals(Set.of(villager), watcherMap().get(chestPos));
        assertEquals(Set.of(villager), watcherMap().get(secondPos));
    }

    @Test
    void repeatedDoubleChestPairing_keepsSingleGoalAndWatcherRegistration() throws Exception {
        LibrarianBehavior behavior = new LibrarianBehavior();
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity villager = mockLibrarian(world);
        GoalSelector goalSelector = mock(GoalSelector.class);
        setGoalSelector(villager, goalSelector);

        BlockPos jobPos = new BlockPos(20, 64, 20);
        BlockPos chestPos = new BlockPos(21, 64, 20);
        BlockPos secondPos = chestPos.east();

        when(world.getBlockState(jobPos)).thenReturn(Blocks.LECTERN.getDefaultState());
        when(world.getBlockState(chestPos)).thenReturn(chestState(ChestType.LEFT, Direction.NORTH));
        when(world.getBlockState(secondPos)).thenReturn(chestState(ChestType.RIGHT, Direction.NORTH));
        when(world.getTime()).thenReturn(400L);

        behavior.onChestPaired(world, villager, jobPos, chestPos);
        behavior.onChestPaired(world, villager, jobPos, chestPos);
        LibrarianBehavior.onChestInventoryMutated(world, chestPos);
        LibrarianBehavior.onChestInventoryMutated(world, secondPos);

        assertTrue(map("QUARTERMASTER_GOALS").containsKey(villager));
        assertEquals(1, map("CHEST_REGISTRATIONS").size());
        assertEquals(Set.of(villager), watcherMap().get(chestPos));
        assertEquals(Set.of(villager), watcherMap().get(secondPos));
        Map<?, ?> activeByAnchor = (Map<?, ?>) quartermasterMap("ACTIVE_QM_BY_WORLD_ANCHOR")
                .values().iterator().next();
        assertEquals(Set.of(villager.getUuid()), activeByAnchor.get(chestPos));
        verify(goalSelector, times(1)).add(eq(3), any(QuartermasterGoal.class));
        verify(goalSelector, times(1)).add(eq(4), any(LibrarianCraftingGoal.class));
        verify(goalSelector, never()).add(eq(5), any());
        verify(goalSelector, never()).remove(any(QuartermasterGoal.class));
    }

    @Test
    void doubleChestBecomesSingle_removesStaleHalfWatcher() throws Exception {
        LibrarianBehavior behavior = new LibrarianBehavior();
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity villager = mockLibrarian(world);
        BlockPos chestPos = new BlockPos(31, 64, 30);
        BlockPos removedHalfPos = chestPos.east();

        when(world.getBlockState(chestPos)).thenReturn(
                chestState(ChestType.LEFT, Direction.NORTH),
                chestState(ChestType.SINGLE, Direction.NORTH));
        when(world.getBlockState(removedHalfPos)).thenReturn(chestState(ChestType.RIGHT, Direction.NORTH));

        invokeUpdateChestWatcher(behavior, world, villager, chestPos);
        invokeUpdateChestWatcher(behavior, world, villager, chestPos);

        assertEquals(Set.of(villager), watcherMap().get(chestPos));
        assertFalse(watcherMap().containsKey(removedHalfPos));
        LibrarianBehavior.onChestInventoryMutated(world, removedHalfPos);
        assertFalse(map("LAST_IMMEDIATE_REQUEST_TICK").containsKey(villager));
    }

    private static void invokeUpdateChestWatcher(LibrarianBehavior behavior,
                                                 ServerWorld world,
                                                 VillagerEntity villager,
                                                 BlockPos chestPos) throws Exception {
        Method method = LibrarianBehavior.class.getDeclaredMethod(
                "updateChestWatcher",
                ServerWorld.class,
                VillagerEntity.class,
                BlockPos.class
        );
        method.setAccessible(true);
        method.invoke(behavior, world, villager, chestPos);
    }

    private static VillagerEntity mockLibrarian(ServerWorld world) {
        VillagerEntity villager = mock(VillagerEntity.class);
        VillagerData villagerData = mock(VillagerData.class);
        when(villager.getVillagerData()).thenReturn(villagerData);
        when(villagerData.getProfession()).thenReturn(VillagerProfession.LIBRARIAN);
        when(villager.isAlive()).thenReturn(true);
        when(villager.getUuid()).thenReturn(UUID.randomUUID());
        when(villager.getUuidAsString()).thenReturn("test-librarian");
        when(villager.getWorld()).thenReturn(world);
        return villager;
    }

    private static BlockState chestState(ChestType type, Direction facing) {
        return Blocks.CHEST.getDefaultState()
                .with(ChestBlock.CHEST_TYPE, type)
                .with(ChestBlock.FACING, facing);
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

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> quartermasterMap(String fieldName) throws Exception {
        Field field = QuartermasterGoal.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<Object, Object>) field.get(null);
    }

    private static void setGoalSelector(VillagerEntity villager, GoalSelector selector) throws Exception {
        Field field = net.minecraft.entity.mob.MobEntity.class.getDeclaredField("goalSelector");
        field.setAccessible(true);
        field.set(villager, selector);
    }
}
