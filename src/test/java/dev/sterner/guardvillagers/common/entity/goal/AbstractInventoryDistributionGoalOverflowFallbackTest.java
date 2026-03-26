package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.DistributionRecipientHelper;
import dev.sterner.guardvillagers.common.util.VillageAnchorState;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbstractInventoryDistributionGoalOverflowFallbackTest {

    @Test
    void overflowCandidateWithoutDirectRecipient_routesToNearestQmChest() {
        ServerWorld world = mock(ServerWorld.class);
        MinecraftServer server = mock(MinecraftServer.class);
        VillageAnchorState anchorState = mock(VillageAnchorState.class);
        VillagerEntity villager = mock(VillagerEntity.class);
        BlockPos sourceChest = new BlockPos(5, 64, 5);
        BlockPos qmChest = new BlockPos(12, 64, 12);
        SimpleInventory sourceInventory = new SimpleInventory(new ItemStack(Items.WHEAT, 64));

        when(world.getServer()).thenReturn(server);
        when(anchorState.getNearestQmChest(world, sourceChest, 300)).thenReturn(Optional.of(qmChest));

        OverflowFallbackTestGoal goal = new OverflowFallbackTestGoal(villager, sourceChest);
        goal.registerChest(qmChest, new SimpleInventory(27));

        try (MockedStatic<VillageAnchorState> anchorStateStatic = Mockito.mockStatic(VillageAnchorState.class)) {
            anchorStateStatic.when(() -> VillageAnchorState.get(server)).thenReturn(anchorState);

            boolean selected = goal.trySelectOverflow(world, sourceInventory, stack -> !stack.isEmpty());

            assertTrue(selected);
            assertTrue(goal.isPendingOverflowTransfer());
            assertEquals(qmChest, goal.getPendingTargetPos());
            assertNull(goal.getPendingTargetId());
            assertEquals(63, sourceInventory.getStack(0).getCount());
        }
    }

    @Test
    void overflowCandidateWithoutQmAvailable_noOpWithoutItemLoss() {
        ServerWorld world = mock(ServerWorld.class);
        MinecraftServer server = mock(MinecraftServer.class);
        VillageAnchorState anchorState = mock(VillageAnchorState.class);
        VillagerEntity villager = mock(VillagerEntity.class);
        BlockPos sourceChest = new BlockPos(5, 64, 5);
        SimpleInventory sourceInventory = new SimpleInventory(new ItemStack(Items.WHEAT, 64));

        when(world.getServer()).thenReturn(server);
        when(anchorState.getNearestQmChest(world, sourceChest, 300)).thenReturn(Optional.empty());

        OverflowFallbackTestGoal goal = new OverflowFallbackTestGoal(villager, sourceChest);

        try (MockedStatic<VillageAnchorState> anchorStateStatic = Mockito.mockStatic(VillageAnchorState.class)) {
            anchorStateStatic.when(() -> VillageAnchorState.get(server)).thenReturn(anchorState);

            boolean selected = goal.trySelectOverflow(world, sourceInventory, stack -> !stack.isEmpty());

            assertFalse(selected);
            assertTrue(goal.getPendingItem().isEmpty());
            assertEquals(64, sourceInventory.getStack(0).getCount());
        }
    }

    @Test
    void overflowCandidateWithSelfTargetQmChest_skipsSelfLoopInsertion() {
        ServerWorld world = mock(ServerWorld.class);
        MinecraftServer server = mock(MinecraftServer.class);
        VillageAnchorState anchorState = mock(VillageAnchorState.class);
        VillagerEntity villager = mock(VillagerEntity.class);
        BlockPos sourceChest = new BlockPos(5, 64, 5);
        SimpleInventory sourceInventory = new SimpleInventory(new ItemStack(Items.WHEAT, 64));

        when(world.getServer()).thenReturn(server);
        when(anchorState.getNearestQmChest(world, sourceChest, 300)).thenReturn(Optional.of(sourceChest));

        OverflowFallbackTestGoal goal = new OverflowFallbackTestGoal(villager, sourceChest);
        goal.registerChest(sourceChest, new SimpleInventory(27));

        try (MockedStatic<VillageAnchorState> anchorStateStatic = Mockito.mockStatic(VillageAnchorState.class)) {
            anchorStateStatic.when(() -> VillageAnchorState.get(server)).thenReturn(anchorState);

            boolean selected = goal.trySelectOverflow(world, sourceInventory, stack -> !stack.isEmpty());

            assertFalse(selected);
            assertTrue(goal.getPendingItem().isEmpty());
            assertEquals(64, sourceInventory.getStack(0).getCount());
        }
    }

    private static final class OverflowFallbackTestGoal extends AbstractInventoryDistributionGoal {
        private final java.util.Map<BlockPos, Inventory> chestInventories = new java.util.HashMap<>();

        private OverflowFallbackTestGoal(VillagerEntity villager, BlockPos sourceChest) {
            super(villager, sourceChest, sourceChest, null);
        }

        void registerChest(BlockPos pos, Inventory inventory) {
            chestInventories.put(pos.toImmutable(), inventory);
        }

        boolean trySelectOverflow(ServerWorld world, Inventory sourceInventory, Predicate<ItemStack> selector) {
            return trySelectOverflowTransfer(world, sourceInventory, selector);
        }

        boolean isPendingOverflowTransfer() {
            return pendingOverflowTransfer;
        }

        BlockPos getPendingTargetPos() {
            return pendingTargetPos;
        }

        UUID getPendingTargetId() {
            return pendingTargetId;
        }

        ItemStack getPendingItem() {
            return pendingItem;
        }

        @Override
        protected Optional<Inventory> getChestInventoryAt(ServerWorld world, BlockPos position) {
            return Optional.ofNullable(chestInventories.get(position));
        }

        @Override
        protected boolean isDistributableItem(ItemStack stack) {
            return !stack.isEmpty();
        }

        @Override
        protected Optional<OverflowRecipientType> getOverflowRecipientType() {
            return Optional.of(OverflowRecipientType.LIBRARIAN);
        }

        @Override
        protected List<DistributionRecipientHelper.RecipientRecord> getOverflowRecipients(ServerWorld world) {
            return List.of();
        }

        @Override
        protected Optional<ArmorStandEntity> findPlacementStand(ServerWorld world, ItemStack stack) {
            return Optional.empty();
        }

        @Override
        protected boolean isStandAvailableForPendingItem(ServerWorld world, ArmorStandEntity stand) {
            return false;
        }

        @Override
        protected boolean placePendingItemOnStand(ServerWorld world, ArmorStandEntity stand) {
            return false;
        }

        @Override
        protected void clearPendingTargetState() {
        }

        @Override
        protected boolean matchesProfession(VillagerEntity villager) {
            return villager.getVillagerData().getProfession() == VillagerProfession.NONE;
        }
    }
}
