package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuartermasterGoalDailyReclaimTest {

    @Test
    void dailyReclaim_runsOnMinecraftDayCadenceSeparateFromReactiveChecks() {
        ServerWorld world = mock(ServerWorld.class);
        when(world.getRegistryKey()).thenReturn(World.OVERWORLD);
        when(world.getTime()).thenReturn(24_000L, 24_001L, 48_000L);
        JobBlockPairingHelper.clearWorldCaches(world);

        BlockPos qmJob = new BlockPos(0, 64, 0);
        BlockPos qmChest = new BlockPos(1, 64, 0);
        VillagerEntity quartermaster = villagerWithJobSite(world, UUID.randomUUID(), VillagerProfession.LIBRARIAN, qmJob, qmChest);

        BlockPos masonJob = new BlockPos(6, 64, 6);
        BlockPos masonChest = new BlockPos(7, 64, 6);
        VillagerEntity mason = villagerWithJobSite(world, UUID.randomUUID(), VillagerProfession.MASON, masonJob, masonChest);
        JobBlockPairingHelper.cacheVillagerChestPairing(world, mason, masonJob, masonChest);

        SimpleInventory masonInventory = new SimpleInventory(3);
        masonInventory.setStack(0, new ItemStack(Items.COBBLESTONE_STAIRS, 20));

        TestQuartermasterGoal goal = new TestQuartermasterGoal(quartermaster, qmJob, qmChest);
        goal.setInventory(masonChest, masonInventory);
        goal.setInventory(qmChest, new SimpleInventory(3));

        assertTrue(goal.tryPlanDailyReclaimTransferIfDue(world));
        QuartermasterGoal.PlannedTransfer firstPlan = QuartermasterGoal.getPlannedTransferForTest(goal);
        assertEquals(masonChest, firstPlan.sourcePos());
        assertEquals(qmChest, firstPlan.destPos());
        assertEquals(16, firstPlan.transferStack().getCount());

        assertFalse(goal.tryPlanDailyReclaimTransferIfDue(world));
        assertTrue(goal.tryPlanDailyReclaimTransferIfDue(world));

        JobBlockPairingHelper.clearWorldCaches(world);
    }

    @Test
    void masonPolicy_reclaimsCompletedOutputsWhileKeepingMiningBufferAndCobbleReserve() {
        ServerWorld world = mock(ServerWorld.class);
        when(world.getRegistryKey()).thenReturn(World.OVERWORLD);
        when(world.getTime()).thenReturn(24_000L);
        JobBlockPairingHelper.clearWorldCaches(world);

        BlockPos qmJob = new BlockPos(0, 64, 0);
        BlockPos qmChest = new BlockPos(1, 64, 0);
        VillagerEntity quartermaster = villagerWithJobSite(world, UUID.randomUUID(), VillagerProfession.LIBRARIAN, qmJob, qmChest);

        BlockPos masonJob = new BlockPos(6, 64, 6);
        BlockPos masonChest = new BlockPos(7, 64, 6);
        VillagerEntity mason = villagerWithJobSite(world, UUID.randomUUID(), VillagerProfession.MASON, masonJob, masonChest);
        JobBlockPairingHelper.cacheVillagerChestPairing(world, mason, masonJob, masonChest);

        SimpleInventory masonInventory = new SimpleInventory(6);
        masonInventory.setStack(0, new ItemStack(Items.COBBLESTONE, 8));
        masonInventory.setStack(1, new ItemStack(Items.DIRT, 8));
        masonInventory.setStack(2, new ItemStack(Items.COBBLESTONE_SLAB, 12));

        TestQuartermasterGoal goal = new TestQuartermasterGoal(quartermaster, qmJob, qmChest);
        goal.setInventory(masonChest, masonInventory);
        goal.setInventory(qmChest, new SimpleInventory(3));

        assertTrue(goal.tryPlanDailyReclaimTransferIfDue(world));
        QuartermasterGoal.PlannedTransfer plan = QuartermasterGoal.getPlannedTransferForTest(goal);
        assertEquals(masonChest, plan.sourcePos());
        assertEquals(qmChest, plan.destPos());
        assertEquals(Items.COBBLESTONE_SLAB, plan.transferStack().getItem());
        assertEquals(12, plan.transferStack().getCount());

        JobBlockPairingHelper.clearWorldCaches(world);
    }

    @Test
    void dailyReclaim_noopsWhenProfessionChestMissingOrInvalid() {
        ServerWorld world = mock(ServerWorld.class);
        when(world.getRegistryKey()).thenReturn(World.OVERWORLD);
        when(world.getTime()).thenReturn(24_000L);
        JobBlockPairingHelper.clearWorldCaches(world);

        BlockPos qmJob = new BlockPos(0, 64, 0);
        BlockPos qmChest = new BlockPos(1, 64, 0);
        VillagerEntity quartermaster = villagerWithJobSite(world, UUID.randomUUID(), VillagerProfession.LIBRARIAN, qmJob, qmChest);

        BlockPos farmerJob = new BlockPos(6, 64, 6);
        BlockPos farmerChest = new BlockPos(7, 64, 6);
        VillagerEntity farmer = villagerWithJobSite(world, UUID.randomUUID(), VillagerProfession.FARMER, farmerJob, farmerChest);
        JobBlockPairingHelper.cacheVillagerChestPairing(world, farmer, farmerJob, farmerChest);

        TestQuartermasterGoal goal = new TestQuartermasterGoal(quartermaster, qmJob, qmChest);
        goal.setInventory(qmChest, new SimpleInventory(3));

        assertFalse(goal.tryPlanDailyReclaimTransferIfDue(world));

        JobBlockPairingHelper.clearWorldCaches(world);
    }

    @Test
    void dailyReclaim_skipsLumberjackChestBecauseDrainSchedulerOwnsThatFlow() {
        ServerWorld world = mock(ServerWorld.class);
        when(world.getRegistryKey()).thenReturn(World.OVERWORLD);
        when(world.getTime()).thenReturn(24_000L);
        JobBlockPairingHelper.clearWorldCaches(world);

        BlockPos qmJob = new BlockPos(0, 64, 0);
        BlockPos qmChest = new BlockPos(1, 64, 0);
        VillagerEntity quartermaster = villagerWithJobSite(world, UUID.randomUUID(), VillagerProfession.LIBRARIAN, qmJob, qmChest);

        LumberjackGuardEntity lumberjack = mock(LumberjackGuardEntity.class);
        BlockPos lumberjackChestPos = new BlockPos(8, 64, 8);
        when(lumberjack.isAlive()).thenReturn(true);
        when(lumberjack.getPairedChestPos()).thenReturn(lumberjackChestPos);
        when(lumberjack.getPairedFurnaceModifierPos()).thenReturn(null);

        SimpleInventory lumberjackChest = new SimpleInventory(8);
        lumberjackChest.setStack(0, new ItemStack(Items.OAK_LOG, 80));
        lumberjackChest.setStack(1, new ItemStack(Items.OAK_PLANKS, 40));
        lumberjackChest.setStack(2, new ItemStack(Items.STICK, 32));

        TestQuartermasterGoal goal = new TestQuartermasterGoal(quartermaster, qmJob, qmChest);
        goal.setInventory(qmChest, new SimpleInventory(5));
        goal.setInventory(lumberjackChestPos, lumberjackChest);
        goal.addNearbyLumberjack(lumberjack);
        goal.setDemand(lumberjack, LumberjackChestTriggerController.UpgradeDemand.v3FenceGate());

        assertFalse(goal.tryPlanDailyReclaimTransferIfDue(world));

        JobBlockPairingHelper.clearWorldCaches(world);
    }

    @Test
    void dailyReclaim_skipsLumberjackFurnaceBecauseDrainSchedulerOwnsThatFlow() {
        ServerWorld world = mock(ServerWorld.class);
        when(world.getRegistryKey()).thenReturn(World.OVERWORLD);
        when(world.getTime()).thenReturn(24_000L);
        JobBlockPairingHelper.clearWorldCaches(world);

        BlockPos qmJob = new BlockPos(0, 64, 0);
        BlockPos qmChest = new BlockPos(1, 64, 0);
        VillagerEntity quartermaster = villagerWithJobSite(world, UUID.randomUUID(), VillagerProfession.LIBRARIAN, qmJob, qmChest);

        LumberjackGuardEntity lumberjack = mock(LumberjackGuardEntity.class);
        BlockPos furnacePos = new BlockPos(9, 64, 9);
        when(lumberjack.isAlive()).thenReturn(true);
        when(lumberjack.getPairedChestPos()).thenReturn(null);
        when(lumberjack.getPairedFurnaceModifierPos()).thenReturn(furnacePos);

        SimpleInventory furnaceInventory = new SimpleInventory(3);
        furnaceInventory.setStack(1, new ItemStack(Items.OAK_LOG, 20));
        furnaceInventory.setStack(2, new ItemStack(Items.CHARCOAL, 28));

        TestQuartermasterGoal goal = new TestQuartermasterGoal(quartermaster, qmJob, qmChest);
        goal.setInventory(qmChest, new SimpleInventory(5));
        goal.setInventory(furnacePos, furnaceInventory);
        goal.addNearbyLumberjack(lumberjack);
        goal.setDemand(lumberjack, null);

        assertFalse(goal.tryPlanDailyReclaimTransferIfDue(world));

        JobBlockPairingHelper.clearWorldCaches(world);
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
        BlockState chestState = mock(BlockState.class);

        when(villager.isAlive()).thenReturn(true);
        when(villager.isRemoved()).thenReturn(false);
        when(villager.getUuid()).thenReturn(uuid);
        when(villager.getVillagerData()).thenReturn(data);
        when(villager.getBrain()).thenReturn(brain);
        when(data.getProfession()).thenReturn(profession);
        when(brain.getOptionalMemory(MemoryModuleType.JOB_SITE)).thenReturn(Optional.of(GlobalPos.create(world.getRegistryKey(), jobSite)));

        when(world.getEntity(uuid)).thenReturn(villager);
        when(world.getBlockState(chestPos)).thenReturn(chestState);
        when(chestState.getBlock()).thenReturn(Blocks.BARREL);

        return villager;
    }

    private static final class TestQuartermasterGoal extends QuartermasterGoal {
        private final Map<BlockPos, Inventory> inventories = new HashMap<>();
        private final List<LumberjackGuardEntity> nearbyLumberjacks = new ArrayList<>();
        private final Map<LumberjackGuardEntity, LumberjackChestTriggerController.UpgradeDemand> demandByLumberjack = new HashMap<>();

        private TestQuartermasterGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
            super(villager, jobPos, chestPos);
        }

        void setInventory(BlockPos pos, Inventory inventory) {
            inventories.put(pos, inventory);
        }

        void addNearbyLumberjack(LumberjackGuardEntity lumberjack) {
            nearbyLumberjacks.add(lumberjack);
        }

        void setDemand(LumberjackGuardEntity lumberjack, LumberjackChestTriggerController.UpgradeDemand demand) {
            demandByLumberjack.put(lumberjack, demand);
        }

        @Override
        protected Optional<Inventory> getInventory(ServerWorld world, BlockPos pos) {
            return Optional.ofNullable(inventories.get(pos));
        }

        @Override
        protected List<LumberjackGuardEntity> getNearbyLumberjacks(ServerWorld world) {
            return nearbyLumberjacks;
        }

        @Override
        protected LumberjackChestTriggerController.UpgradeDemand resolveLumberjackUpgradeDemand(ServerWorld world, LumberjackGuardEntity lumberjack) {
            return demandByLumberjack.get(lumberjack);
        }
    }
}
