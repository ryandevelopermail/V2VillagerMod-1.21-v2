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
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        TestQuartermasterGoal goal = new TestQuartermasterGoal(quartermaster, qmJob, qmChest);

        BlockPos unpairedChest = new BlockPos(10, 64, 0);
        BlockPos pairedChest = new BlockPos(6, 64, 0);
        BlockPos wildernessChest = new BlockPos(30, 64, 0);
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
        // Village anchor area POI cluster near the quartermaster chest.
        states.put(qmChest.north(), Blocks.BELL.getDefaultState());
        states.put(qmChest.south(), Blocks.WHITE_BED.getDefaultState());
        states.put(unpairedChest.south(), Blocks.CARTOGRAPHY_TABLE.getDefaultState());
        states.put(pairedChest.south(), Blocks.SMOKER.getDefaultState());
        // wildernessChest intentionally has no nearby POI cluster
        when(world.getBlockState(any(BlockPos.class))).thenAnswer(invocation -> states.getOrDefault(invocation.getArgument(0), Blocks.AIR.getDefaultState()));
        goal.putInventory(unpairedChest, new SimpleInventory(new ItemStack(Items.BREAD, 8)));
        goal.putInventory(pairedChest, new SimpleInventory(new ItemStack(Items.WHEAT, 8)));

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
    void bootstrapDiscovery_acceptsVillageChestBeyondEightBlocksFromBell() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        when(world.getRegistryKey()).thenReturn(World.OVERWORLD);
        when(world.getEntitiesByClass(any(), any(), any())).thenReturn(List.of());

        BlockPos qmJob = new BlockPos(0, 64, 0);
        BlockPos qmChest = new BlockPos(1, 64, 0);
        VillagerEntity quartermaster = villagerWithJobSite(world, UUID.randomUUID(), VillagerProfession.LIBRARIAN, qmJob, qmChest);
        TestQuartermasterGoal goal = new TestQuartermasterGoal(quartermaster, qmJob, qmChest);
        GuardVillagersConfig.quartermasterScanRange = 24;

        BlockPos distantVillageChest = new BlockPos(12, 64, 0); // 11 blocks from bell.
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(qmChest, chestState(ChestType.SINGLE));
        states.put(distantVillageChest, chestState(ChestType.SINGLE));
        states.put(qmChest.north(), Blocks.BELL.getDefaultState());
        states.put(qmChest.south(), Blocks.WHITE_BED.getDefaultState());
        states.put(distantVillageChest.south(), Blocks.CARTOGRAPHY_TABLE.getDefaultState());
        when(world.getBlockState(any(BlockPos.class))).thenAnswer(invocation ->
                states.getOrDefault(invocation.getArgument(0), Blocks.AIR.getDefaultState()));
        goal.putInventory(distantVillageChest, new SimpleInventory(new ItemStack(Items.BREAD, 8)));

        Method discover = QuartermasterGoal.class.getDeclaredMethod("discoverBootstrapSourceChests", ServerWorld.class, BlockPos.class);
        discover.setAccessible(true);
        List<BlockPos> discovered = (List<BlockPos>) discover.invoke(goal, world, qmChest);

        assertTrue(discovered.contains(distantVillageChest));
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
        TestQuartermasterGoal goal = new TestQuartermasterGoal(quartermaster, qmJob, qmChest);

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
        goal.putInventory(sourceLeft, new SimpleInventory(new ItemStack(Items.BREAD, 4)));

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
        TestQuartermasterGoal goal = new TestQuartermasterGoal(quartermaster, qmJob, qmChest);
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

    @Test
    void bootstrapConsolidation_collectsFromAllEligibleNaturalChests() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        when(world.getRegistryKey()).thenReturn(World.OVERWORLD);
        when(world.getEntitiesByClass(any(), any(), any())).thenReturn(List.of());
        when(world.getTime()).thenReturn(0L);

        BlockPos qmJob = new BlockPos(0, 64, 0);
        BlockPos qmChest = new BlockPos(1, 64, 0);
        UUID qmUuid = UUID.randomUUID();
        VillagerEntity quartermaster = villagerWithJobSite(world, qmUuid, VillagerProfession.LIBRARIAN, qmJob, qmChest);
        TestQuartermasterGoal goal = new TestQuartermasterGoal(quartermaster, qmJob, qmChest);
        GuardVillagersConfig.quartermasterScanRange = 16;

        BlockPos first = new BlockPos(3, 64, 0);
        BlockPos second = new BlockPos(8, 64, 0);
        when(world.getBlockState(any(BlockPos.class))).thenAnswer(invocation -> {
            BlockPos pos = invocation.getArgument(0);
            if (pos.equals(qmChest) || pos.equals(first) || pos.equals(second)) {
                return chestState(ChestType.SINGLE);
            }
            if (pos.equals(first.north()) || pos.equals(second.north())) return Blocks.BELL.getDefaultState();
            if (pos.equals(first.south()) || pos.equals(second.south())) return Blocks.WHITE_BED.getDefaultState();
            return Blocks.AIR.getDefaultState();
        });

        SimpleInventory qmInventory = new SimpleInventory(9);
        SimpleInventory firstInv = new SimpleInventory(new ItemStack(Items.BREAD, 20));
        SimpleInventory secondInv = new SimpleInventory(new ItemStack(Items.APPLE, 10));
        goal.putInventory(qmChest, qmInventory);
        goal.putInventory(first, firstInv);
        goal.putInventory(second, secondInv);

        Method tryPlanTransfer = QuartermasterGoal.class.getDeclaredMethod("tryPlanTransfer", ServerWorld.class);
        tryPlanTransfer.setAccessible(true);

        Set<BlockPos> visitedSources = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            assertTrue((boolean) tryPlanTransfer.invoke(goal, world));
            QuartermasterGoal.PlannedTransfer plan = QuartermasterGoal.getPlannedTransferForTest(goal);
            assertNotNull(plan);
            visitedSources.add(plan.sourcePos());
            invokeTakeFromInventory(goal, world, plan.sourcePos());
            invokeInsertToInventory(goal, world, plan.destPos());
        }

        assertTrue(firstInv.getStack(0).isEmpty());
        assertTrue(secondInv.getStack(0).isEmpty());
        assertEquals(30, totalItems(qmInventory));
        assertTrue(visitedSources.contains(first));
        assertTrue(visitedSources.contains(second));
        QuartermasterGoal.clearBootstrapState(world, qmUuid);
    }

    @Test
    void bootstrapConsolidation_completionFlagPreventsRerunSpam() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        when(world.getRegistryKey()).thenReturn(World.OVERWORLD);
        when(world.getEntitiesByClass(any(), any(), any())).thenReturn(List.of());
        when(world.getTime()).thenReturn(0L);

        BlockPos qmJob = new BlockPos(0, 64, 0);
        BlockPos qmChest = new BlockPos(1, 64, 0);
        UUID qmUuid = UUID.randomUUID();
        VillagerEntity quartermaster = villagerWithJobSite(world, qmUuid, VillagerProfession.LIBRARIAN, qmJob, qmChest);
        TestQuartermasterGoal goal = new TestQuartermasterGoal(quartermaster, qmJob, qmChest);
        GuardVillagersConfig.quartermasterScanRange = 16;

        BlockPos source = new BlockPos(4, 64, 0);
        when(world.getBlockState(any(BlockPos.class))).thenAnswer(invocation -> {
            BlockPos pos = invocation.getArgument(0);
            if (pos.equals(qmChest) || pos.equals(source)) return chestState(ChestType.SINGLE);
            if (pos.equals(source.north())) return Blocks.BELL.getDefaultState();
            if (pos.equals(source.south())) return Blocks.WHITE_BED.getDefaultState();
            return Blocks.AIR.getDefaultState();
        });
        goal.putInventory(qmChest, new SimpleInventory(9));
        SimpleInventory sourceInv = new SimpleInventory(new ItemStack(Items.BREAD, 1));
        goal.putInventory(source, sourceInv);

        Method tryPlanTransfer = QuartermasterGoal.class.getDeclaredMethod("tryPlanTransfer", ServerWorld.class);
        tryPlanTransfer.setAccessible(true);
        assertTrue((boolean) tryPlanTransfer.invoke(goal, world));
        QuartermasterGoal.PlannedTransfer plan = QuartermasterGoal.getPlannedTransferForTest(goal);
        invokeTakeFromInventory(goal, world, plan.sourcePos());
        invokeInsertToInventory(goal, world, plan.destPos());

        assertTrue(sourceInv.getStack(0).isEmpty());
        assertFalse((boolean) tryPlanTransfer.invoke(goal, world));
        assertFalse((boolean) tryPlanTransfer.invoke(goal, world));
        assertEquals(1, QuartermasterGoal.getBootstrapDiscoveryRunsForTest(goal));
        QuartermasterGoal.clearBootstrapState(world, qmUuid);
    }

    @Test
    void bootstrapConsolidation_retriesAfterEmptyDiscoveryAndCollectsWhenChestsAppearLater() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        when(world.getRegistryKey()).thenReturn(World.OVERWORLD);
        when(world.getEntitiesByClass(any(), any(), any())).thenReturn(List.of());
        AtomicLong worldTime = new AtomicLong(0L);
        when(world.getTime()).thenAnswer(invocation -> worldTime.get());

        BlockPos qmJob = new BlockPos(0, 64, 0);
        BlockPos qmChest = new BlockPos(1, 64, 0);
        UUID qmUuid = UUID.randomUUID();
        VillagerEntity quartermaster = villagerWithJobSite(world, qmUuid, VillagerProfession.LIBRARIAN, qmJob, qmChest);
        TestQuartermasterGoal goal = new TestQuartermasterGoal(quartermaster, qmJob, qmChest);
        GuardVillagersConfig.quartermasterScanRange = 16;

        BlockPos delayedSource = new BlockPos(6, 64, 0);
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(qmChest, chestState(ChestType.SINGLE));
        when(world.getBlockState(any(BlockPos.class))).thenAnswer(invocation ->
                states.getOrDefault(invocation.getArgument(0), Blocks.AIR.getDefaultState()));

        SimpleInventory qmInventory = new SimpleInventory(9);
        goal.putInventory(qmChest, qmInventory);

        Method tryPlanTransfer = QuartermasterGoal.class.getDeclaredMethod("tryPlanTransfer", ServerWorld.class);
        tryPlanTransfer.setAccessible(true);

        // Promotion tick with no eligible source chests discovered.
        assertFalse((boolean) tryPlanTransfer.invoke(goal, world));
        assertEquals(1, QuartermasterGoal.getBootstrapDiscoveryRunsForTest(goal));

        // Before retry window, still no re-discovery.
        worldTime.set(1199L);
        assertFalse((boolean) tryPlanTransfer.invoke(goal, world));
        assertEquals(1, QuartermasterGoal.getBootstrapDiscoveryRunsForTest(goal));

        // Chest appears later and should be picked up on next retry window.
        states.put(delayedSource, chestState(ChestType.SINGLE));
        states.put(delayedSource.north(), Blocks.BELL.getDefaultState());
        states.put(delayedSource.south(), Blocks.WHITE_BED.getDefaultState());
        SimpleInventory delayedSourceInv = new SimpleInventory(new ItemStack(Items.BREAD, 7));
        goal.putInventory(delayedSource, delayedSourceInv);

        worldTime.set(1200L);
        assertTrue((boolean) tryPlanTransfer.invoke(goal, world));
        QuartermasterGoal.PlannedTransfer plan = QuartermasterGoal.getPlannedTransferForTest(goal);
        assertEquals(delayedSource, plan.sourcePos());
        invokeTakeFromInventory(goal, world, plan.sourcePos());
        invokeInsertToInventory(goal, world, plan.destPos());

        // Follow-up planning marks bootstrap complete after discovered sources are drained.
        assertFalse((boolean) tryPlanTransfer.invoke(goal, world));
        assertFalse((boolean) tryPlanTransfer.invoke(goal, world));
        assertEquals(0, totalItems(delayedSourceInv));
        assertEquals(7, totalItems(qmInventory));
        assertEquals(2, QuartermasterGoal.getBootstrapDiscoveryRunsForTest(goal));
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

    private static void invokeTakeFromInventory(QuartermasterGoal goal, ServerWorld world, BlockPos sourcePos) throws Exception {
        Method takeFromInventory = QuartermasterGoal.class.getDeclaredMethod("takeFromInventory", ServerWorld.class, BlockPos.class);
        takeFromInventory.setAccessible(true);
        assertTrue((boolean) takeFromInventory.invoke(goal, world, sourcePos));
    }

    private static void invokeInsertToInventory(QuartermasterGoal goal, ServerWorld world, BlockPos destPos) throws Exception {
        Method insertToInventory = QuartermasterGoal.class.getDeclaredMethod("insertToInventory", ServerWorld.class, BlockPos.class);
        insertToInventory.setAccessible(true);
        insertToInventory.invoke(goal, world, destPos);
    }

    private static final class TestQuartermasterGoal extends QuartermasterGoal {
        private final Map<BlockPos, Inventory> inventories = new HashMap<>();

        private TestQuartermasterGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
            super(villager, jobPos, chestPos);
        }

        void putInventory(BlockPos pos, Inventory inventory) {
            inventories.put(pos.toImmutable(), inventory);
        }

        @Override
        protected Optional<Inventory> getInventory(ServerWorld world, BlockPos pos) {
            return Optional.ofNullable(inventories.get(pos));
        }
    }

    private static int totalItems(Inventory inventory) {
        int total = 0;
        for (int i = 0; i < inventory.size(); i++) {
            total += inventory.getStack(i).getCount();
        }
        return total;
    }
}
