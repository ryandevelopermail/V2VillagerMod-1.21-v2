package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.VillagePenRegistry;
import net.minecraft.block.Blocks;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShepherdSpecialGoalTaskSelectionTest {

    @Test
    void selectTaskTypeByAvailability_bannerPresentWithoutPlacementTargetAndGatherPen_prefersWheatGather() {
        ShepherdSpecialGoal.TaskType selectedTask = ShepherdSpecialGoal.selectTaskTypeByAvailability(
                true,
                false,
                false,
                true,
                true
        );

        assertEquals(ShepherdSpecialGoal.TaskType.WHEAT_GATHER, selectedTask);
    }

    @Test
    void selectTaskTypeByAvailability_equivalentAvailabilityStates_preservePriorityOrdering() {
        ShepherdSpecialGoal.TaskType noBannerState = ShepherdSpecialGoal.selectTaskTypeByAvailability(
                false,
                false,
                true,
                true,
                true
        );
        ShepherdSpecialGoal.TaskType bannerWithoutTargetState = ShepherdSpecialGoal.selectTaskTypeByAvailability(
                true,
                false,
                true,
                true,
                true
        );
        ShepherdSpecialGoal.TaskType equivalentNonBannerSelection = ShepherdSpecialGoal.selectNonBannerTaskType(
                true,
                true,
                true
        );
        assertEquals(ShepherdSpecialGoal.TaskType.SHEARS, noBannerState);
        assertEquals(noBannerState, bannerWithoutTargetState);
        assertEquals(noBannerState, equivalentNonBannerSelection);

        ShepherdSpecialGoal.TaskType bannerWithoutOtherSupply = ShepherdSpecialGoal.selectTaskTypeByAvailability(
                true,
                true,
                false,
                false,
                false
        );
        ShepherdSpecialGoal.TaskType bannerWithOtherSupply = ShepherdSpecialGoal.selectTaskTypeByAvailability(
                true,
                true,
                true,
                true,
                true
        );
        assertEquals(ShepherdSpecialGoal.TaskType.BANNER, bannerWithoutOtherSupply);
        assertEquals(bannerWithoutOtherSupply, bannerWithOtherSupply);
    }

    @Test
    void canStart_withoutBellPenCache_usesJobSiteFallbackGatherPenForWheatTask() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        MinecraftServer server = mock(MinecraftServer.class);
        VillagerEntity villager = mock(VillagerEntity.class);
        VillagePenRegistry registry = mock(VillagePenRegistry.class);
        BlockPos jobPos = new BlockPos(20, 64, 20);
        BlockPos chestPos = new BlockPos(4, 64, 4);
        BlockPos villagerPos = new BlockPos(8, 64, 8);

        when(villager.getWorld()).thenReturn(world);
        when(villager.getBlockPos()).thenReturn(villagerPos);
        when(villager.getUuidAsString()).thenReturn("test-shepherd");
        when(villager.getInventory()).thenReturn(new SimpleInventory(new ItemStack(Items.WHEAT)));
        when(villager.getMainHandStack()).thenReturn(ItemStack.EMPTY);
        when(villager.getOffHandStack()).thenReturn(ItemStack.EMPTY);

        when(world.getServer()).thenReturn(server);
        when(world.getTimeOfDay()).thenReturn(1000L);
        when(world.getTime()).thenReturn(1000L);
        when(world.isDay()).thenReturn(true);
        when(world.getBlockState(chestPos)).thenReturn(Blocks.AIR.getDefaultState());

        VillagePenRegistry.PenEntry jobSiteFallbackPen =
                new VillagePenRegistry.PenEntry(new BlockPos(10, 64, 9), new BlockPos(10, 64, 10), new BlockPos(10, 64, 10));
        when(registry.getNearestBellPensWithJobSiteFallback(world, villagerPos, 200))
                .thenReturn(List.of(jobSiteFallbackPen));
        when(registry.getNearestBellPens(any(), any(), anyInt())).thenReturn(List.of());

        try (MockedStatic<VillagePenRegistry> registryStatic = Mockito.mockStatic(VillagePenRegistry.class)) {
            registryStatic.when(() -> VillagePenRegistry.get(server)).thenReturn(registry);

            ShepherdSpecialGoal goal = new ShepherdSpecialGoal(villager, jobPos, chestPos);
            assertTrue(goal.canStart());
            assertEquals(ShepherdSpecialGoal.TaskType.WHEAT_GATHER, getTaskType(goal));

            Mockito.verify(registry).getNearestBellPensWithJobSiteFallback(world, villagerPos, 200);
        }
    }

    @Test
    void findNearestPenTarget_withFreshSpatialCache_reusesCachedTargetAndGateWithoutRescan() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity villager = mock(VillagerEntity.class);
        BlockPos jobPos = new BlockPos(20, 64, 20);
        BlockPos chestPos = new BlockPos(4, 64, 4);
        BlockPos cachedGate = new BlockPos(9, 64, 9);

        when(world.getTime()).thenReturn(1000L);
        when(villager.getWorld()).thenReturn(world);

        ShepherdSpecialGoal goal = new ShepherdSpecialGoal(villager, jobPos, chestPos);
        setField(goal, "nearestPenCacheTick", 980L);
        setField(goal, "cachedNearestPenTarget", cachedGate);
        setField(goal, "cachedNearestPenGatePos", cachedGate);
        setField(goal, "penGatePos", null);

        BlockPos result = invokeFindNearestPenTarget(goal, world);

        assertEquals(cachedGate, result);
        assertEquals(cachedGate, getField(goal, "penGatePos"));
        Mockito.verify(world, never()).getBottomY();
        Mockito.verify(world, never()).getTopY();
        Mockito.verify(world, never()).getBlockState(any(BlockPos.class));
    }

    @Test
    void invalidateSpatialSearchCache_clearsNearestPenCacheFieldsUsedByTaskPolling() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity villager = mock(VillagerEntity.class);
        BlockPos jobPos = new BlockPos(20, 64, 20);
        BlockPos chestPos = new BlockPos(4, 64, 4);
        BlockPos cachedGate = new BlockPos(9, 64, 9);

        when(villager.getWorld()).thenReturn(world);

        ShepherdSpecialGoal goal = new ShepherdSpecialGoal(villager, jobPos, chestPos);
        setField(goal, "nearestPenCacheTick", 980L);
        setField(goal, "cachedNearestPenTarget", cachedGate);
        setField(goal, "cachedNearestPenGatePos", cachedGate);

        invokeInvalidateSpatialSearchCache(goal);

        assertEquals(Long.MIN_VALUE, getField(goal, "nearestPenCacheTick"));
        assertNull(getField(goal, "cachedNearestPenTarget"));
        assertNull(getField(goal, "cachedNearestPenGatePos"));
    }

    private static ShepherdSpecialGoal.TaskType getTaskType(ShepherdSpecialGoal goal) throws Exception {
        Field taskTypeField = ShepherdSpecialGoal.class.getDeclaredField("taskType");
        taskTypeField.setAccessible(true);
        return (ShepherdSpecialGoal.TaskType) taskTypeField.get(goal);
    }

    private static BlockPos invokeFindNearestPenTarget(ShepherdSpecialGoal goal, ServerWorld world) throws Exception {
        Method method = ShepherdSpecialGoal.class.getDeclaredMethod("findNearestPenTarget", ServerWorld.class);
        method.setAccessible(true);
        return (BlockPos) method.invoke(goal, world);
    }

    private static void invokeInvalidateSpatialSearchCache(ShepherdSpecialGoal goal) throws Exception {
        Method method = ShepherdSpecialGoal.class.getDeclaredMethod("invalidateSpatialSearchCache");
        method.setAccessible(true);
        method.invoke(goal);
    }

    private static void setField(ShepherdSpecialGoal goal, String fieldName, Object value) throws Exception {
        Field field = ShepherdSpecialGoal.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(goal, value);
    }

    private static Object getField(ShepherdSpecialGoal goal, String fieldName) throws Exception {
        Field field = ShepherdSpecialGoal.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(goal);
    }
}
