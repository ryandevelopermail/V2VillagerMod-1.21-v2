package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import net.minecraft.block.BlockState;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.util.math.Vec3d;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LumberjackGuardCraftingGoalTest {

    @Test
    void craftBootstrapChestAndAttemptPlacementIfNeeded_noChestAndEnoughPlanks_craftsChestAndAttemptsPlacement() {
        AtomicInteger craftCalls = new AtomicInteger();
        AtomicInteger placeCalls = new AtomicInteger();

        boolean acted = LumberjackGuardCraftingGoal.craftBootstrapChestAndAttemptPlacementIfNeeded(
                true,
                () -> {
                    craftCalls.incrementAndGet();
                    return true;
                },
                () -> {
                    placeCalls.incrementAndGet();
                    return false;
                }
        );

        assertTrue(acted);
        assertEquals(1, craftCalls.get());
        assertEquals(1, placeCalls.get());
    }

    @Test
    void craftBootstrapChestAndAttemptPlacementIfNeeded_craftFails_doesNotAttemptPlacement() {
        AtomicBoolean placementAttempted = new AtomicBoolean(false);

        boolean acted = LumberjackGuardCraftingGoal.craftBootstrapChestAndAttemptPlacementIfNeeded(
                true,
                () -> false,
                () -> {
                    placementAttempted.set(true);
                    return true;
                }
        );

        assertFalse(acted);
        assertFalse(placementAttempted.get());
    }

    @Disabled("Requires full Minecraft bootstrap: ItemStack/SimpleInventory cannot initialize without MC registry")
    @Test
    void craftSingleUpgradeDemandOutputIfPossible_fenceDemandCraftsThreeFencesPerOperation() {
        List<ItemStack> buffer = new ArrayList<>();
        SimpleInventory chestInventory = new SimpleInventory(2);
        chestInventory.setStack(0, new ItemStack(Items.OAK_PLANKS, 4));
        chestInventory.setStack(1, new ItemStack(Items.STICK, 2));

        boolean crafted = LumberjackGuardCraftingGoal.craftSingleUpgradeDemandOutputIfPossible(
                buffer,
                chestInventory,
                LumberjackChestTriggerController.UpgradeDemand.v3Fence()
        );

        assertTrue(crafted);
        assertEquals(1, buffer.size());
        assertTrue(buffer.getFirst().isOf(Items.OAK_FENCE));
        assertEquals(3, buffer.getFirst().getCount());
    }

    @Disabled("Requires full Minecraft bootstrap: mock(LumberjackGuardEntity.class) fails without MC classloading")
    @Test
    void singleTreeBootstrap_enoughForChestOnly_alwaysCraftsAndPlacesChest() throws Exception {
        LumberjackGuardEntity guard = mock(LumberjackGuardEntity.class);
        ServerWorld world = bootstrapWorld();
        List<ItemStack> buffer = new ArrayList<>();
        buffer.add(new ItemStack(Items.OAK_LOG, 2)); // 8 planks total => chest only
        AtomicReference<BlockPos> pairedChestPos = new AtomicReference<>(null);

        when(guard.getGatheredStackBuffer()).thenReturn(buffer);
        when(guard.getMainHandStack()).thenReturn(new ItemStack(Items.WOODEN_AXE)); // Axe already available
        when(guard.getPairedCraftingTablePos()).thenReturn(new BlockPos(0, 64, 0));
        when(guard.getPairedChestPos()).thenAnswer(invocation -> pairedChestPos.get());
        doAnswer(invocation -> {
            pairedChestPos.set(invocation.getArgument(0));
            return null;
        }).when(guard).setPairedChestPos(any());

        LumberjackGuardCraftingGoal goal = new LumberjackGuardCraftingGoal(guard);
        invokePerformWoodConversion(goal, null);
        boolean meaningfulAction = invokeCraftPriorityOutputs(goal, world, null, true);

        assertTrue(meaningfulAction);
        assertTrue(pairedChestPos.get() != null, "Chest should be placed during bootstrap.");
    }

    @Disabled("Requires full Minecraft bootstrap: mock(LumberjackGuardEntity.class) fails without MC classloading")
    @Test
    void singleTreeBootstrap_enoughForChestAndAxe_completesBothInSameCycle() throws Exception {
        LumberjackGuardEntity guard = mock(LumberjackGuardEntity.class);
        ServerWorld world = bootstrapWorld();
        List<ItemStack> buffer = new ArrayList<>();
        buffer.add(new ItemStack(Items.OAK_LOG, 4)); // 16 planks => chest + axe + sticks
        AtomicReference<BlockPos> pairedChestPos = new AtomicReference<>(null);

        when(guard.getGatheredStackBuffer()).thenReturn(buffer);
        when(guard.getMainHandStack()).thenReturn(ItemStack.EMPTY);
        when(guard.getPairedCraftingTablePos()).thenReturn(new BlockPos(0, 64, 0));
        when(guard.getPairedChestPos()).thenAnswer(invocation -> pairedChestPos.get());
        doAnswer(invocation -> {
            pairedChestPos.set(invocation.getArgument(0));
            return null;
        }).when(guard).setPairedChestPos(any());

        LumberjackGuardCraftingGoal goal = new LumberjackGuardCraftingGoal(guard);
        invokePerformWoodConversion(goal, null);
        boolean meaningfulAction = invokeCraftPriorityOutputs(goal, world, null, true);

        assertTrue(meaningfulAction);
        assertTrue(pairedChestPos.get() != null, "Chest should be placed before axe equip.");
        InOrder orderedCalls = inOrder(guard);
        orderedCalls.verify(guard).setPairedChestPos(any());
        orderedCalls.verify(guard).equipStack(eq(EquipmentSlot.MAINHAND), any());
    }

    @Disabled("Requires full Minecraft bootstrap: mock(LumberjackGuardEntity.class) fails without MC classloading")
    @Test
    void canStartAtNight_unpairedWithBootstrapInputs_placesChestBeforeDepositing() {
        LumberjackGuardEntity guard = mock(LumberjackGuardEntity.class);
        ServerWorld world = bootstrapWorld();
        EntityNavigation navigation = mock(EntityNavigation.class);
        AtomicReference<LumberjackGuardEntity.WorkflowStage> workflowStage =
                new AtomicReference<>(LumberjackGuardEntity.WorkflowStage.CRAFTING);
        AtomicReference<BlockPos> pairedChestPos = new AtomicReference<>(null);
        List<ItemStack> buffer = new ArrayList<>();
        buffer.add(new ItemStack(Items.OAK_LOG, 2));

        when(guard.getWorld()).thenReturn(world);
        when(guard.isAlive()).thenReturn(true);
        when(guard.getWorkflowStage()).thenAnswer(invocation -> workflowStage.get());
        doAnswer(invocation -> {
            workflowStage.set(invocation.getArgument(0));
            return null;
        }).when(guard).setWorkflowStage(any());
        when(guard.getGatheredStackBuffer()).thenReturn(buffer);
        when(guard.getPairedChestPos()).thenAnswer(invocation -> pairedChestPos.get());
        doAnswer(invocation -> {
            pairedChestPos.set(invocation.getArgument(0));
            return null;
        }).when(guard).setPairedChestPos(any());
        when(guard.getPairedCraftingTablePos()).thenReturn(new BlockPos(0, 64, 0));
        when(guard.squaredDistanceTo(any(Vec3d.class))).thenReturn(0.0D);
        when(guard.getMainHandStack()).thenReturn(new ItemStack(Items.WOODEN_AXE));
        when(guard.getNavigation()).thenReturn(navigation);
        when(world.isDay()).thenReturn(false);
        when(world.getTime()).thenReturn(42000L);

        LumberjackGuardCraftingGoal goal = new LumberjackGuardCraftingGoal(guard);

        assertTrue(goal.canStart(), "Bootstrap crafting should be allowed at night when inputs exist.");
        verify(guard, never()).setWorkflowStage(LumberjackGuardEntity.WorkflowStage.DEPOSITING);

        goal.tick();

        assertTrue(pairedChestPos.get() != null, "Unpaired lumberjack should place a chest during bootstrap crafting.");
        assertEquals(LumberjackGuardEntity.WorkflowStage.DEPOSITING, workflowStage.get(), "Goal should complete and hand off to depositing after bootstrap attempt.");
    }

    private static boolean invokePerformWoodConversion(LumberjackGuardCraftingGoal goal, SimpleInventory chestInventory) throws Exception {
        Method method = LumberjackGuardCraftingGoal.class.getDeclaredMethod("performWoodConversion", net.minecraft.inventory.Inventory.class);
        method.setAccessible(true);
        return (boolean) method.invoke(goal, chestInventory);
    }

    private static boolean invokeCraftPriorityOutputs(LumberjackGuardCraftingGoal goal, ServerWorld world, SimpleInventory chestInventory, boolean demandEnabled) throws Exception {
        Method method = LumberjackGuardCraftingGoal.class.getDeclaredMethod("craftPriorityOutputs", ServerWorld.class, net.minecraft.inventory.Inventory.class, boolean.class);
        method.setAccessible(true);
        return (boolean) method.invoke(goal, world, chestInventory, demandEnabled);
    }

    private static ServerWorld bootstrapWorld() {
        ServerWorld world = mock(ServerWorld.class);
        BlockState permissiveState = mock(BlockState.class);
        when(permissiveState.isAir()).thenReturn(true);
        when(permissiveState.isSolidBlock(any(), any())).thenReturn(true);
        when(world.getBlockState(any())).thenReturn(permissiveState);
        when(world.setBlockState(any(), any())).thenReturn(true);
        return world;
    }
}
