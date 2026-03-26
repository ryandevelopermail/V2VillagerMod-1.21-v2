package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.entity.goal.QuartermasterGoal;
import dev.sterner.guardvillagers.common.util.VillageAnchorState;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.ChestType;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.MinecraftServer;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LibrarianBehaviorQuartermasterLifecycleTest {

    @AfterEach
    void clearStaticState() throws Exception {
        map("QUARTERMASTER_GOALS").clear();
        map("PAIRED_CHEST_POS").clear();
    }

    @Test
    void librarianPairedWithSingleChest_only_doesNotPromoteToQuartermaster() throws Exception {
        LibrarianBehavior behavior = new LibrarianBehavior();
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity villager = mockLibrarian();
        GoalSelector goalSelector = mock(GoalSelector.class);
        setGoalSelector(villager, goalSelector);

        BlockPos jobPos = new BlockPos(10, 64, 10);
        BlockPos chestPos = new BlockPos(11, 64, 10);

        when(world.getBlockState(jobPos)).thenReturn(Blocks.LECTERN.getDefaultState());
        when(world.getBlockState(chestPos)).thenReturn(chestState(ChestType.SINGLE, Direction.NORTH));

        invokeSyncQuartermasterState(behavior, world, villager, jobPos, chestPos, "single_chest_only");

        assertFalse(map("QUARTERMASTER_GOALS").containsKey(villager));
        verify(goalSelector, never()).add(eq(3), any(QuartermasterGoal.class));
    }

    @Test
    void librarianGainsSecondAdjacentChest_promotesToQuartermaster_then_demotesWhenRemoved() throws Exception {
        LibrarianBehavior behavior = new LibrarianBehavior();
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity villager = mockLibrarian();
        GoalSelector goalSelector = mock(GoalSelector.class);
        setGoalSelector(villager, goalSelector);
        MinecraftServer server = mock(MinecraftServer.class);
        VillageAnchorState anchorState = mock(VillageAnchorState.class);

        BlockPos jobPos = new BlockPos(20, 64, 20);
        BlockPos chestPos = new BlockPos(21, 64, 20);
        BlockPos secondPos = chestPos.east();

        when(world.getBlockState(jobPos)).thenReturn(Blocks.LECTERN.getDefaultState());
        when(world.getBlockState(chestPos)).thenReturn(chestState(ChestType.LEFT, Direction.NORTH));
        when(world.getBlockState(secondPos)).thenReturn(chestState(ChestType.RIGHT, Direction.NORTH));
        when(world.getServer()).thenReturn(server);

        invokeSyncQuartermasterState(behavior, world, villager, jobPos, chestPos, "second_chest_added");

        assertTrue(map("QUARTERMASTER_GOALS").containsKey(villager));
        map("PAIRED_CHEST_POS").put(villager, chestPos);
        verify(goalSelector).add(eq(3), any(QuartermasterGoal.class));

        when(world.getBlockState(secondPos)).thenReturn(Blocks.AIR.getDefaultState());
        try (MockedStatic<VillageAnchorState> anchorStateStatic = Mockito.mockStatic(VillageAnchorState.class)) {
            anchorStateStatic.when(() -> VillageAnchorState.get(server)).thenReturn(anchorState);

            invokeSyncQuartermasterState(behavior, world, villager, jobPos, chestPos, "second_chest_removed");
        }

        assertFalse(map("QUARTERMASTER_GOALS").containsKey(villager));
        verify(goalSelector).remove(any(QuartermasterGoal.class));
        verify(anchorState).unregister(world, chestPos);
    }

    private static VillagerEntity mockLibrarian() {
        VillagerEntity villager = mock(VillagerEntity.class);
        VillagerData villagerData = mock(VillagerData.class);
        when(villager.getVillagerData()).thenReturn(villagerData);
        when(villagerData.getProfession()).thenReturn(VillagerProfession.LIBRARIAN);
        when(villager.isAlive()).thenReturn(true);
        when(villager.getUuidAsString()).thenReturn("test-librarian");
        return villager;
    }

    private static BlockState chestState(ChestType type, Direction facing) {
        return Blocks.CHEST.getDefaultState()
                .with(net.minecraft.block.ChestBlock.CHEST_TYPE, type)
                .with(net.minecraft.block.ChestBlock.FACING, facing);
    }

    private static void invokeSyncQuartermasterState(LibrarianBehavior behavior,
                                                     ServerWorld world,
                                                     VillagerEntity villager,
                                                     BlockPos jobPos,
                                                     BlockPos chestPos,
                                                     String reason) throws Exception {
        Method method = LibrarianBehavior.class.getDeclaredMethod(
                "syncQuartermasterState",
                ServerWorld.class,
                VillagerEntity.class,
                BlockPos.class,
                BlockPos.class,
                String.class
        );
        method.setAccessible(true);
        method.invoke(behavior, world, villager, jobPos, chestPos, reason);
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
