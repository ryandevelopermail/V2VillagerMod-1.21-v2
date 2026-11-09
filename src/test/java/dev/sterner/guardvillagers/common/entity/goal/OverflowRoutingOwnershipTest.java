package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.DistributionRecipientHelper;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OverflowRoutingOwnershipTest {

    @Test
    void quartermasterCentralOverflowPolicy_collectsBulkButProtectsSpecialistGoods() {
        assertTrue(QuartermasterOverflowPolicy.canCollect(new ItemStack(Items.WHEAT)));
        assertTrue(QuartermasterOverflowPolicy.canCollect(new ItemStack(Items.COBBLESTONE)));
        assertTrue(QuartermasterOverflowPolicy.canCollect(new ItemStack(Items.STICK)));

        assertFalse(QuartermasterOverflowPolicy.canCollect(new ItemStack(Items.POTION)));
        assertFalse(QuartermasterOverflowPolicy.canCollect(new ItemStack(Items.ARROW)));
        assertFalse(QuartermasterOverflowPolicy.canCollect(new ItemStack(Items.IRON_CHESTPLATE)));
        assertFalse(QuartermasterOverflowPolicy.canCollect(new ItemStack(Items.COD)));
        assertFalse(QuartermasterOverflowPolicy.canCollect(new ItemStack(Items.FILLED_MAP)));
    }

    @Test
    void fullFarmerChest_preservesExplicitShepherdDependency() {
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity farmer = mock(VillagerEntity.class);
        VillagerEntity shepherd = mock(VillagerEntity.class);
        UUID shepherdId = UUID.randomUUID();
        BlockPos shepherdChest = new BlockPos(8, 64, 8);
        Inventory source = fullInventory(new ItemStack(Items.WHEAT, 64));
        DistributionRecipientHelper.RecipientRecord recipient = new DistributionRecipientHelper.RecipientRecord(
                shepherd,
                new BlockPos(8, 64, 9),
                shepherdChest,
                4.0D
        );
        when(shepherd.getUuid()).thenReturn(shepherdId);

        TestFarmerDistributionGoal goal = new TestFarmerDistributionGoal(farmer);
        try (MockedStatic<DistributionRecipientHelper> recipients = Mockito.mockStatic(DistributionRecipientHelper.class)) {
            recipients.when(() -> DistributionRecipientHelper.findEligibleShepherdRecipients(world, farmer, 24.0D))
                    .thenReturn(List.of(recipient));

            assertTrue(goal.select(world, source));
            assertEquals(shepherdId, goal.pendingRecipient());
            assertEquals(shepherdChest, goal.pendingChest());
            recipients.verify(() -> DistributionRecipientHelper.findEligibleShepherdRecipients(world, farmer, 24.0D));
            recipients.verifyNoMoreInteractions();
        }
    }

    @Test
    void fullMasonChest_routesExplicitCentralStorageDependencyToQuartermaster() {
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity mason = mock(VillagerEntity.class);
        VillagerEntity librarian = mock(VillagerEntity.class);
        DistributionRecipientHelper.RecipientRecord recipient = new DistributionRecipientHelper.RecipientRecord(
                librarian,
                new BlockPos(12, 64, 13),
                new BlockPos(12, 64, 12),
                9.0D
        );
        Inventory source = fullInventory(new ItemStack(Items.STONE, 64));
        TestMasonDistributionGoal goal = new TestMasonDistributionGoal(mason);

        try (MockedStatic<DistributionRecipientHelper> recipients = Mockito.mockStatic(DistributionRecipientHelper.class)) {
            recipients.when(() -> DistributionRecipientHelper.findEligibleQuartermasterRecipients(world, mason, 24.0D))
                    .thenReturn(List.of(recipient));

            assertTrue(goal.canStartForTest(world, source));
            recipients.verify(() -> DistributionRecipientHelper.findEligibleQuartermasterRecipients(world, mason, 24.0D));
            recipients.verifyNoMoreInteractions();
        }
    }

    @Test
    void clericAndShepherdCentralStorageRoutes_targetQuartermasterResolver() {
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity cleric = mock(VillagerEntity.class);
        VillagerEntity shepherd = mock(VillagerEntity.class);
        VillagerEntity quartermaster = mock(VillagerEntity.class);
        UUID quartermasterId = UUID.randomUUID();
        BlockPos centralStorage = new BlockPos(20, 64, 20);
        DistributionRecipientHelper.RecipientRecord recipient = new DistributionRecipientHelper.RecipientRecord(
                quartermaster, new BlockPos(20, 64, 21), centralStorage, 16.0D);
        when(quartermaster.getUuid()).thenReturn(quartermasterId);

        TestClericDistributionGoal clericGoal = new TestClericDistributionGoal(cleric);
        TestShepherdDistributionGoal shepherdGoal = new TestShepherdDistributionGoal(shepherd);
        SimpleInventory clericInventory = new SimpleInventory(new ItemStack(Items.POTION));
        SimpleInventory shepherdInventory = new SimpleInventory(new ItemStack(Items.WHITE_WOOL));

        try (MockedStatic<DistributionRecipientHelper> recipients = Mockito.mockStatic(DistributionRecipientHelper.class)) {
            recipients.when(() -> DistributionRecipientHelper.findEligibleQuartermasterRecipients(world, cleric, 24.0D))
                    .thenReturn(List.of(recipient));
            recipients.when(() -> DistributionRecipientHelper.findEligibleQuartermasterRecipients(world, shepherd, 24.0D))
                    .thenReturn(List.of(recipient));

            assertTrue(clericGoal.select(world, clericInventory));
            assertEquals(quartermasterId, clericGoal.pendingRecipient());
            assertEquals(centralStorage, clericGoal.pendingChest());
            assertTrue(shepherdGoal.select(world, shepherdInventory));
            assertEquals(quartermasterId, shepherdGoal.pendingRecipient());
            assertEquals(centralStorage, shepherdGoal.pendingChest());

            recipients.verify(() -> DistributionRecipientHelper.findEligibleQuartermasterRecipients(world, cleric, 24.0D));
            recipients.verify(() -> DistributionRecipientHelper.findEligibleQuartermasterRecipients(world, shepherd, 24.0D));
            recipients.verifyNoMoreInteractions();
        }
    }

    @Test
    void distributionGoals_exposeNoGenericCentralOverflowFallback() {
        assertFalse(Arrays.stream(AbstractInventoryDistributionGoal.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().contains("Overflow")));
        assertFalse(Arrays.stream(AbstractInventoryDistributionGoal.class.getDeclaredFields())
                .anyMatch(field -> field.getName().contains("Overflow")));
    }

    private static Inventory fullInventory(ItemStack stack) {
        SimpleInventory inventory = new SimpleInventory(27);
        for (int slot = 0; slot < inventory.size(); slot++) {
            inventory.setStack(slot, stack.copy());
        }
        return inventory;
    }

    private static final class TestFarmerDistributionGoal extends FarmerDistributionGoal {
        private TestFarmerDistributionGoal(VillagerEntity farmer) {
            super(farmer, BlockPos.ORIGIN, BlockPos.ORIGIN, null);
        }

        boolean select(ServerWorld world, Inventory source) {
            return selectPendingTransfer(world, source);
        }

        UUID pendingRecipient() {
            return pendingTargetId;
        }

        BlockPos pendingChest() {
            return pendingTargetPos;
        }
    }

    private static final class TestMasonDistributionGoal extends MasonToLibrarianDistributionGoal {
        private TestMasonDistributionGoal(VillagerEntity mason) {
            super(mason, BlockPos.ORIGIN, BlockPos.ORIGIN, null);
        }

        boolean canStartForTest(ServerWorld world, Inventory source) {
            return canStartWithInventory(world, source);
        }
    }

    private static final class TestClericDistributionGoal extends ClericDistributionGoal {
        private TestClericDistributionGoal(VillagerEntity cleric) {
            super(cleric, BlockPos.ORIGIN, BlockPos.ORIGIN, null);
        }

        boolean select(ServerWorld world, Inventory source) {
            return selectPendingTransfer(world, source);
        }

        UUID pendingRecipient() {
            return pendingTargetId;
        }

        BlockPos pendingChest() {
            return pendingTargetPos;
        }
    }

    private static final class TestShepherdDistributionGoal extends ShepherdToLibrarianDistributionGoal {
        private TestShepherdDistributionGoal(VillagerEntity shepherd) {
            super(shepherd, BlockPos.ORIGIN, BlockPos.ORIGIN, null);
        }

        boolean select(ServerWorld world, Inventory source) {
            return selectPendingTransfer(world, source);
        }

        UUID pendingRecipient() {
            return pendingTargetId;
        }

        BlockPos pendingChest() {
            return pendingTargetPos;
        }
    }
}
