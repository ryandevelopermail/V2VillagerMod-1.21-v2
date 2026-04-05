package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuartermasterGoalBootstrapTest {

    private int originalScanRange = GuardVillagersConfig.quartermasterScanRange;

    @AfterEach
    void tearDown() {
        GuardVillagersConfig.quartermasterScanRange = originalScanRange;
    }

    @Test
    @SuppressWarnings("unchecked")
    void bootstrapDiscovery_selectsOnlyUnpairedVillageChests() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        when(world.getRegistryKey()).thenReturn(World.OVERWORLD);
        when(world.getEntitiesByClass(any(), any(), any())).thenReturn(List.of());

        BlockPos qmJob = new BlockPos(0, 64, 0);
        BlockPos qmChest = new BlockPos(1, 64, 0);
        VillagerEntity quartermaster = villagerWithJobSite(world, UUID.randomUUID(), VillagerProfession.LIBRARIAN, qmJob, qmChest);
        QuartermasterGoal goal = new QuartermasterGoal(quartermaster, qmJob, qmChest);

        BlockPos unpairedChest = new BlockPos(4, 64, 0);
        BlockPos pairedChest = new BlockPos(6, 64, 0);
        BlockPos wildernessChest = new BlockPos(8, 64, 0);
        BlockPos workerJob = new BlockPos(6, 64, 1);

        JobBlockPairingHelper.clearWorldCaches(world);
        VillagerEntity worker = villagerWithJobSite(world, UUID.randomUUID(), VillagerProfession.FARMER, workerJob, pairedChest);
        JobBlockPairingHelper.cacheVillagerChestPairing(world, worker, workerJob, pairedChest);

        GuardVillagersConfig.quartermasterScanRange = 12;
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(qmChest, chestState(ChestType.SINGLE));
        states.put(unpairedChest, chestState(ChestType.SINGLE));
        states.put(pairedChest, chestState(ChestType.SINGLE));
        states.put(wildernessChest, chestState(ChestType.SINGLE));
        // POI cluster for village chest candidates.
        states.put(unpairedChest.north(), Blocks.BELL.getDefaultState());
        states.put(unpairedChest.south(), Blocks.WHITE_BED.getDefaultState());
        states.put(pairedChest.north(), Blocks.BELL.getDefaultState());
        states.put(pairedChest.south(), Blocks.WHITE_BED.getDefaultState());
        // wildernessChest intentionally has no nearby POI cluster
        when(world.getBlockState(any(BlockPos.class))).thenAnswer(invocation -> states.getOrDefault(invocation.getArgument(0), Blocks.AIR.getDefaultState()));

        Method discover = QuartermasterGoal.class.getDeclaredMethod("discoverBootstrapSourceChests", ServerWorld.class, BlockPos.class);
        discover.setAccessible(true);
        List<BlockPos> discovered = (List<BlockPos>) discover.invoke(goal, world, qmChest);

        assertTrue(discovered.contains(unpairedChest));
        assertFalse(discovered.contains(pairedChest));
        assertFalse(discovered.contains(wildernessChest));
        JobBlockPairingHelper.clearWorldCaches(world);
    }

    @Test
    @SuppressWarnings("unchecked")
    void bootstrapDiscovery_handlesDoubleChestsSafely() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        when(world.getRegistryKey()).thenReturn(World.OVERWORLD);
        when(world.getEntitiesByClass(any(), any(), any())).thenReturn(List.of());

        BlockPos qmJob = new BlockPos(0, 64, 0);
        BlockPos qmChest = new BlockPos(1, 64, 0);
        BlockPos qmChestOtherHalf = qmChest.east();
        VillagerEntity quartermaster = villagerWithJobSite(world, UUID.randomUUID(), VillagerProfession.LIBRARIAN, qmJob, qmChest);
        QuartermasterGoal goal = new QuartermasterGoal(quartermaster, qmJob, qmChest);

        BlockPos sourceLeft = new BlockPos(4, 64, 0);
        BlockPos sourceRight = sourceLeft.east();
        GuardVillagersConfig.quartermasterScanRange = 12;

        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(qmChest, chestState(ChestType.LEFT));
        states.put(qmChestOtherHalf, chestState(ChestType.RIGHT));
        states.put(sourceLeft, chestState(ChestType.LEFT));
        states.put(sourceRight, chestState(ChestType.RIGHT));
        states.put(sourceLeft.north(), Blocks.BELL.getDefaultState());
        states.put(sourceLeft.south(), Blocks.WHITE_BED.getDefaultState());
        when(world.getBlockState(any(BlockPos.class))).thenAnswer(invocation -> states.getOrDefault(invocation.getArgument(0), Blocks.AIR.getDefaultState()));

        Method discover = QuartermasterGoal.class.getDeclaredMethod("discoverBootstrapSourceChests", ServerWorld.class, BlockPos.class);
        discover.setAccessible(true);
        List<BlockPos> discovered = (List<BlockPos>) discover.invoke(goal, world, qmChest);

        assertEquals(1, discovered.size());
        assertTrue(discovered.contains(sourceLeft) || discovered.contains(sourceRight));
        assertFalse(discovered.contains(qmChest));
        assertFalse(discovered.contains(qmChestOtherHalf));
    }

    @Test
    void bootstrapDiscovery_runsOnlyOncePerPromotion() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        when(world.getRegistryKey()).thenReturn(World.OVERWORLD);
        when(world.getEntitiesByClass(any(), any(), any())).thenReturn(List.of());

        BlockPos qmJob = new BlockPos(0, 64, 0);
        BlockPos qmChest = new BlockPos(1, 64, 0);
        UUID qmUuid = UUID.randomUUID();
        VillagerEntity quartermaster = villagerWithJobSite(world, qmUuid, VillagerProfession.LIBRARIAN, qmJob, qmChest);
        QuartermasterGoal goal = new QuartermasterGoal(quartermaster, qmJob, qmChest);
        GuardVillagersConfig.quartermasterScanRange = 10;

        when(world.getBlockState(any(BlockPos.class))).thenReturn(Blocks.AIR.getDefaultState());

        Method tryPlanTransfer = QuartermasterGoal.class.getDeclaredMethod("tryPlanTransfer", ServerWorld.class);
        tryPlanTransfer.setAccessible(true);

        boolean first = (boolean) tryPlanTransfer.invoke(goal, world);
        boolean second = (boolean) tryPlanTransfer.invoke(goal, world);

        assertFalse(first);
        assertFalse(second);
        assertEquals(1, QuartermasterGoal.getBootstrapDiscoveryRunsForTest(goal));
        QuartermasterGoal.clearBootstrapState(world, qmUuid);
    }

    @SuppressWarnings("unchecked")
    private static VillagerEntity villagerWithJobSite(ServerWorld world,
                                                      UUID uuid,
                                                      VillagerProfession profession,
                                                      BlockPos jobSite,
                                                      BlockPos chestPos) {
        VillagerEntity villager = mock(VillagerEntity.class);
        VillagerData data = mock(VillagerData.class);
        Brain<VillagerEntity> brain = (Brain<VillagerEntity>) mock(Brain.class);
        when(villager.isAlive()).thenReturn(true);
        when(villager.isRemoved()).thenReturn(false);
        when(villager.getUuid()).thenReturn(uuid);
        when(villager.getVillagerData()).thenReturn(data);
        when(villager.getBrain()).thenReturn(brain);
        when(data.getProfession()).thenReturn(profession);
        when(brain.getOptionalMemory(MemoryModuleType.JOB_SITE)).thenReturn(Optional.of(GlobalPos.create(world.getRegistryKey(), jobSite)));
        when(world.getEntity(uuid)).thenReturn(villager);
        when(villager.squaredDistanceTo(anyDouble(), anyDouble(), anyDouble())).thenReturn(0.0D);
        return villager;
    }

    private static BlockState chestState(ChestType type) {
        return Blocks.CHEST.getDefaultState()
                .with(ChestBlock.CHEST_TYPE, type)
                .with(ChestBlock.FACING, net.minecraft.util.math.Direction.NORTH);
    }
}
