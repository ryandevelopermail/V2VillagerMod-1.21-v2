package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbstractInventoryDistributionGoalItemConservationTest {

    private static final BlockPos JOB = new BlockPos(0, 64, 0);
    private static final BlockPos SOURCE = new BlockPos(1, 64, 0);
    private static final BlockPos NEW_JOB = new BlockPos(10, 64, 0);
    private static final BlockPos NEW_SOURCE = new BlockPos(11, 64, 0);

    @Test
    void interruptionAfterExtraction_recoversExactSourceCount() {
        Fixture fixture = fixture(11);

        fixture.goal.extractPending(4);
        assertCounts(fixture, 7, 0, 4);

        fixture.goal.stop();

        assertCounts(fixture, 11, 0, 0);
        assertEquals(11, total(fixture.source) + total(fixture.villagerInventory));
    }

    @Test
    void retargetAfterExtraction_recoversToOriginalSourceBeforeReplacement() {
        Fixture fixture = fixture(13);

        fixture.goal.extractPending(5);
        assertCounts(fixture, 8, 0, 5);

        fixture.goal.setTargets(NEW_JOB, NEW_SOURCE, null);

        assertCounts(fixture, 13, 0, 0);
        assertEquals(0, total(fixture.newSource));
        assertEquals(13, total(fixture.source) + total(fixture.newSource) + total(fixture.villagerInventory));
    }

    private static Fixture fixture(int sourceCount) {
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity villager = mock(VillagerEntity.class);
        SimpleInventory villagerInventory = new SimpleInventory(8);
        when(villager.getWorld()).thenReturn(world);
        when(villager.getInventory()).thenReturn(villagerInventory);
        when(villager.getNavigation()).thenReturn(mock(EntityNavigation.class));

        SimpleInventory source = new SimpleInventory(new ItemStack(Items.BREAD, sourceCount));
        SimpleInventory newSource = new SimpleInventory(1);
        TestDistributionGoal goal = new TestDistributionGoal(villager);
        goal.putInventory(SOURCE, source);
        goal.putInventory(NEW_SOURCE, newSource);
        return new Fixture(goal, source, newSource, villagerInventory);
    }

    private static void assertCounts(Fixture fixture, int source, int villager, int pending) {
        assertEquals(source, total(fixture.source), "source count");
        assertEquals(villager, total(fixture.villagerInventory), "villager inventory count");
        assertEquals(pending, fixture.goal.pendingCount(), "in-flight pending count");
    }

    private static int total(Inventory inventory) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            total += inventory.getStack(slot).getCount();
        }
        return total;
    }

    private record Fixture(TestDistributionGoal goal,
                           SimpleInventory source,
                           SimpleInventory newSource,
                           SimpleInventory villagerInventory) {
    }

    private static final class TestDistributionGoal extends AbstractInventoryDistributionGoal {
        private final Map<BlockPos, Inventory> inventories = new HashMap<>();

        private TestDistributionGoal(VillagerEntity villager) {
            super(villager, JOB, SOURCE, null);
        }

        void putInventory(BlockPos pos, Inventory inventory) {
            inventories.put(pos.toImmutable(), inventory);
        }

        void extractPending(int count) {
            Inventory source = inventories.get(chestPos);
            pendingItem = source.removeStack(0, count);
            source.markDirty();
            pendingTargetPos = chestPos.east();
        }

        int pendingCount() {
            return pendingItem.getCount();
        }

        @Override
        protected Optional<Inventory> getChestInventory(ServerWorld world) {
            return Optional.ofNullable(inventories.get(chestPos));
        }

        @Override
        protected boolean isDistributableItem(ItemStack stack) {
            return stack.isOf(Items.BREAD);
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
            return true;
        }
    }
}
