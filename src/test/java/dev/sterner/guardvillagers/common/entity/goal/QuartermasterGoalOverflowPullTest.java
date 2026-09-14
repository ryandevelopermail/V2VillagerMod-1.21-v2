package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import net.minecraft.block.Blocks;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import net.minecraft.village.VillagerData;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuartermasterGoalOverflowPullTest {

    @Test
    void highFullnessProfessionChest_isPulledEvenWhenCentralStorageIsNotLow() {
        Fixture fixture = new Fixture();
        try {
            SimpleInventory farmerChest = filledInventory(27, new ItemStack(Items.WHEAT, 64));
            SimpleInventory stockedCentralStorage = new SimpleInventory(4);
            stockedCentralStorage.setStack(0, new ItemStack(Items.COBBLESTONE, 64));
            stockedCentralStorage.setStack(1, new ItemStack(Items.COBBLESTONE, 64));
            stockedCentralStorage.setStack(2, new ItemStack(Items.COBBLESTONE, 64));
            fixture.addWorker(VillagerProfession.FARMER, fixture.workerChest, farmerChest);
            fixture.goal.setInventory(fixture.qmChest, stockedCentralStorage);

            assertTrue(fixture.goal.tryPlanOverflowTransfer(fixture.world, fixture.qmChest));
            QuartermasterGoal.PlannedTransfer transfer = QuartermasterGoal.getPlannedTransferForTest(fixture.goal);
            assertEquals(fixture.workerChest, transfer.sourcePos());
            assertEquals(fixture.qmChest, transfer.destPos());
            assertEquals(Items.WHEAT, transfer.transferStack().getItem());
            assertEquals(16, transfer.transferStack().getCount());
        } finally {
            fixture.close();
        }
    }

    @Test
    void highFullnessPull_preservesProfessionReserveAndSpecialistGoods() {
        Fixture fixture = new Fixture();
        try {
            SimpleInventory fletcherChest = filledInventory(27, new ItemStack(Items.ARROW, 64));
            fletcherChest.setStack(0, new ItemStack(Items.STICK, 32));
            fixture.addWorker(VillagerProfession.FLETCHER, fixture.workerChest, fletcherChest);
            fixture.goal.setInventory(fixture.qmChest, new SimpleInventory(4));

            assertFalse(fixture.goal.tryPlanOverflowTransfer(fixture.world, fixture.qmChest));
        } finally {
            fixture.close();
        }
    }

    @Test
    void highFullnessPull_rechecksDirectDependencyReserveAtPickup() throws Exception {
        Fixture fixture = new Fixture();
        try {
            SimpleInventory farmerChest = filledInventory(27, new ItemStack(Items.ARROW, 64));
            farmerChest.setStack(0, new ItemStack(Items.WHEAT, 40));
            fixture.addWorker(VillagerProfession.FARMER, fixture.workerChest, farmerChest);
            fixture.goal.setInventory(fixture.qmChest, new SimpleInventory(4));

            assertTrue(fixture.goal.tryPlanOverflowTransfer(fixture.world, fixture.qmChest));
            QuartermasterGoal.PlannedTransfer transfer = QuartermasterGoal.getPlannedTransferForTest(fixture.goal);
            assertEquals(Items.WHEAT, transfer.transferStack().getItem());
            assertEquals(8, transfer.transferStack().getCount());

            farmerChest.setStack(0, new ItemStack(Items.WHEAT, 33));
            Method takePayload = QuartermasterGoal.class.getDeclaredMethod(
                    "takePayloadFromInventory", ServerWorld.class, BlockPos.class);
            takePayload.setAccessible(true);
            assertTrue((boolean) takePayload.invoke(fixture.goal, fixture.world, fixture.workerChest));
            assertEquals(32, farmerChest.getStack(0).getCount());
            assertEquals(1, QuartermasterGoal.getPlannedTransferForTest(fixture.goal).transferStack().getCount());
        } finally {
            fixture.close();
        }
    }

    @Test
    void ordinaryLibrarianCraftingMaterials_areNotTreatedAsCentralOverflow() {
        Fixture fixture = new Fixture();
        try {
            fixture.addWorker(
                    VillagerProfession.LIBRARIAN,
                    fixture.workerChest,
                    filledInventory(27, new ItemStack(Items.OAK_PLANKS, 64)));
            fixture.goal.setInventory(fixture.qmChest, new SimpleInventory(4));

            assertFalse(fixture.goal.tryPlanOverflowTransfer(fixture.world, fixture.qmChest));
        } finally {
            fixture.close();
        }
    }

    private static SimpleInventory filledInventory(int size, ItemStack stack) {
        SimpleInventory inventory = new SimpleInventory(size);
        for (int slot = 0; slot < size; slot++) {
            inventory.setStack(slot, stack.copy());
        }
        return inventory;
    }

    private static final class Fixture {
        private final ServerWorld world = mock(ServerWorld.class);
        private final BlockPos qmJob = new BlockPos(0, 64, 0);
        private final BlockPos qmChest = new BlockPos(1, 64, 0);
        private final BlockPos workerJob = new BlockPos(8, 64, 8);
        private final BlockPos workerChest = new BlockPos(9, 64, 8);
        private final TestQuartermasterGoal goal;

        private Fixture() {
            when(world.getRegistryKey()).thenReturn(World.OVERWORLD);
            when(world.getTime()).thenReturn(1_000L);
            when(world.getBlockState(any(BlockPos.class))).thenReturn(Blocks.BARREL.getDefaultState());
            JobBlockPairingHelper.clearWorldCaches(world);

            VillagerEntity quartermaster = mock(VillagerEntity.class);
            when(quartermaster.getUuid()).thenReturn(UUID.randomUUID());
            goal = new TestQuartermasterGoal(quartermaster, qmJob, qmChest);
        }

        private void addWorker(VillagerProfession profession, BlockPos chestPos, Inventory inventory) {
            VillagerEntity worker = mock(VillagerEntity.class);
            VillagerData villagerData = mock(VillagerData.class);
            when(worker.getUuid()).thenReturn(UUID.randomUUID());
            when(worker.getVillagerData()).thenReturn(villagerData);
            when(villagerData.getProfession()).thenReturn(profession);
            JobBlockPairingHelper.cacheVillagerChestPairing(world, worker, workerJob, chestPos);
            goal.setInventory(chestPos, inventory);
        }

        private void close() {
            JobBlockPairingHelper.clearWorldCaches(world);
        }
    }

    private static final class TestQuartermasterGoal extends QuartermasterGoal {
        private final Map<BlockPos, Inventory> inventories = new HashMap<>();

        private TestQuartermasterGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
            super(villager, jobPos, chestPos);
        }

        private void setInventory(BlockPos pos, Inventory inventory) {
            inventories.put(pos, inventory);
        }

        @Override
        protected Optional<Inventory> getInventory(ServerWorld world, BlockPos pos) {
            return Optional.ofNullable(inventories.get(pos));
        }
    }
}
