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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

    private static ShepherdSpecialGoal.TaskType getTaskType(ShepherdSpecialGoal goal) throws Exception {
        Field taskTypeField = ShepherdSpecialGoal.class.getDeclaredField("taskType");
        taskTypeField.setAccessible(true);
        return (ShepherdSpecialGoal.TaskType) taskTypeField.get(goal);
    }
}
