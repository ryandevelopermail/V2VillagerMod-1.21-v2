package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.entity.goal.LibrarianBellChestDistributionGoal;
import dev.sterner.guardvillagers.common.entity.goal.LibrarianCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.QuartermasterGoal;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.DoubleInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerProfession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

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
        map("DISTRIBUTION_GOALS").clear();
        map("QUARTERMASTER_GOALS").clear();
        map("PAIRED_CHEST_POS").clear();
        map("CHEST_LISTENERS").clear();
        map("LAST_IMMEDIATE_REQUEST_TICK").clear();
        map("INVENTORY_DIRTY_FLAGS").clear();
        map("LAST_QUARTERMASTER_PAIR").clear();
    }

    @Test
    void singleChestPairing_keepsListenerAndImmediateRefresh() throws Exception {
        LibrarianBehavior behavior = new LibrarianBehavior();
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity villager = mockLibrarian(world);
        SimpleInventory simpleInventory = new SimpleInventory(9);
        LibrarianCraftingGoal craftingGoal = mock(LibrarianCraftingGoal.class);
        LibrarianBellChestDistributionGoal distributionGoal = mock(LibrarianBellChestDistributionGoal.class);
        BlockPos chestPos = new BlockPos(8, 64, 8);

        map("CRAFTING_GOALS").put(villager, craftingGoal);
        map("DISTRIBUTION_GOALS").put(villager, distributionGoal);
        when(world.getBlockState(chestPos)).thenReturn(chestState(ChestType.SINGLE, Direction.NORTH));
        when(world.getTime()).thenReturn(200L);

        try (MockedStatic<ChestBlock> chestBlockStatic = Mockito.mockStatic(ChestBlock.class)) {
            chestBlockStatic.when(() -> ChestBlock.getInventory(any(ChestBlock.class), any(BlockState.class), eq(world), eq(chestPos), eq(false)))
                    .thenReturn(simpleInventory);

            invokeUpdateChestListener(behavior, world, villager, chestPos);
        }

        simpleInventory.markDirty();

        verify(craftingGoal, times(1)).requestImmediateCraft(world);
        verify(distributionGoal, times(1)).requestImmediateDistribution();
        assertTrue(map("CHEST_LISTENERS").containsKey(villager));
    }

    @Test
    void doubleChestPairing_skipsListenerAttachmentWithoutDemotionLoop() throws Exception {
        LibrarianBehavior behavior = new LibrarianBehavior();
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity villager = mockLibrarian(world);
        GoalSelector goalSelector = mock(GoalSelector.class);
        setGoalSelector(villager, goalSelector);
        DoubleInventory doubleInventory = new DoubleInventory(new SimpleInventory(9), new SimpleInventory(9));

        BlockPos jobPos = new BlockPos(20, 64, 20);
        BlockPos chestPos = new BlockPos(21, 64, 20);
        BlockPos secondPos = chestPos.east();

        when(world.getBlockState(jobPos)).thenReturn(Blocks.LECTERN.getDefaultState());
        when(world.getBlockState(chestPos)).thenReturn(chestState(ChestType.LEFT, Direction.NORTH));
        when(world.getBlockState(secondPos)).thenReturn(chestState(ChestType.RIGHT, Direction.NORTH));
        when(world.getTime()).thenReturn(300L, 300L, 300L, 300L, 300L, 300L);

        try (MockedStatic<ChestBlock> chestBlockStatic = Mockito.mockStatic(ChestBlock.class)) {
            chestBlockStatic.when(() -> ChestBlock.getInventory(any(ChestBlock.class), any(BlockState.class), eq(world), eq(chestPos), eq(false)))
                    .thenReturn(doubleInventory);

            behavior.onChestPaired(world, villager, jobPos, chestPos);
            behavior.onChestPaired(world, villager, jobPos, chestPos);
        }

        assertTrue(map("QUARTERMASTER_GOALS").containsKey(villager));
        verify(goalSelector, times(1)).add(eq(3), any(QuartermasterGoal.class));
        verify(goalSelector, never()).remove(any(QuartermasterGoal.class));
    }

    private static void invokeUpdateChestListener(LibrarianBehavior behavior,
                                                  ServerWorld world,
                                                  VillagerEntity villager,
                                                  BlockPos chestPos) throws Exception {
        Method method = LibrarianBehavior.class.getDeclaredMethod(
                "updateChestListener",
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

    private static void setGoalSelector(VillagerEntity villager, GoalSelector selector) throws Exception {
        Field field = net.minecraft.entity.mob.MobEntity.class.getDeclaredField("goalSelector");
        field.setAccessible(true);
        field.set(villager, selector);
    }
}
