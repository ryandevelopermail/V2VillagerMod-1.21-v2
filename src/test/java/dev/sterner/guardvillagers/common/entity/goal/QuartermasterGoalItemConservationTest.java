package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.DoubleInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuartermasterGoalItemConservationTest {

    private static final BlockPos JOB = new BlockPos(0, 64, 0);
    private static final BlockPos SOURCE = new BlockPos(1, 64, 0);
    private static final BlockPos DESTINATION = new BlockPos(2, 64, 0);

    @Test
    void stopImmediatelyAfterPickup_restoresExactSourceCount() throws Exception {
        Fixture fixture = fixture(new SimpleInventory(new ItemStack(Items.BREAD, 32)), new SimpleInventory(1));
        planSingle(fixture.goal, 32);

        assertTrue(take(fixture));
        assertCounts(fixture, 0, 0, 32);

        fixture.goal.stop();

        assertCounts(fixture, 32, 0, 0);
        assertEquals(32, total(fixture.source) + total(fixture.destination) + total(fixture.villagerInventory));
    }

    @Test
    void missingDestinationAfterPickup_restoresPayloadToSource() throws Exception {
        Fixture fixture = fixture(new SimpleInventory(new ItemStack(Items.WHEAT, 24)), new SimpleInventory(1));
        planSingle(fixture.goal, Items.WHEAT, 24);
        assertTrue(take(fixture));
        fixture.goal.removeInventory(DESTINATION);

        insert(fixture);

        assertCounts(fixture, 24, 0, 0);
    }

    @Test
    void destinationBecomesFullAfterPickup_recoversWithoutDuplication() throws Exception {
        Fixture fixture = fixture(
                new SimpleInventory(new ItemStack(Items.BREAD, 16)),
                new SimpleInventory(new ItemStack(Items.BREAD, 63)));
        planSingle(fixture.goal, 16);
        assertTrue(take(fixture));
        assertCounts(fixture, 15, 63, 1);

        fixture.destination.getStack(0).increment(1); // An external actor consumes the reserved capacity.
        insert(fixture);

        assertCounts(fixture, 16, 64, 0);
        assertEquals(80, total(fixture.source) + total(fixture.destination));
    }

    @Test
    void partialCapacity_extractsOnlyWhatCanFit() throws Exception {
        Fixture fixture = fixture(
                new SimpleInventory(new ItemStack(Items.BREAD, 20)),
                new SimpleInventory(new ItemStack(Items.BREAD, 59)));
        planSingle(fixture.goal, 20);

        assertTrue(take(fixture));
        assertCounts(fixture, 15, 59, 5);
        insert(fixture);

        assertCounts(fixture, 15, 64, 0);
        assertEquals(79, total(fixture.source) + total(fixture.destination));
    }

    @Test
    void bootstrapDestinationFills_sourceRemainsForLaterTrips() throws Exception {
        SimpleInventory source = new SimpleInventory(2);
        source.setStack(0, new ItemStack(Items.BREAD, 64));
        source.setStack(1, new ItemStack(Items.BREAD, 36));
        Fixture fixture = fixture(source, new SimpleInventory(new ItemStack(Items.BREAD, 32)));
        planFull(fixture.goal);

        assertTrue(take(fixture));
        assertCounts(fixture, 68, 32, 32);
        insert(fixture);
        assertCounts(fixture, 68, 64, 0);

        planFull(fixture.goal);
        assertFalse(take(fixture));
        assertCounts(fixture, 68, 64, 0);
        assertEquals(132, total(fixture.source) + total(fixture.destination));
    }

    @Test
    void successfulTransfer_movesRequestedQuantityExactlyOnce() throws Exception {
        Fixture fixture = fixture(
                new SimpleInventory(new ItemStack(Items.BREAD, 12)),
                new SimpleInventory(new ItemStack(Items.BREAD, 3)));
        planSingle(fixture.goal, 12);

        assertTrue(take(fixture));
        insert(fixture);
        insert(fixture); // Re-entry must be a no-op once ownership was discharged.

        assertCounts(fixture, 0, 15, 0);
    }

    @Test
    void destinationAcceptsPartAfterPickup_onlyRemainderIsRecovered() throws Exception {
        Fixture fixture = fixture(
                new SimpleInventory(new ItemStack(Items.BREAD, 10)),
                new SimpleInventory(new ItemStack(Items.BREAD, 54)));
        planSingle(fixture.goal, 10);
        assertTrue(take(fixture));
        fixture.destination.getStack(0).increment(6);

        insert(fixture);

        assertCounts(fixture, 6, 64, 0);
        assertEquals(70, total(fixture.source) + total(fixture.destination));
    }

    @Test
    void doubleChestCapacity_usesAggregateInventoryAndConservesCounts() throws Exception {
        SimpleInventory sourceLeft = new SimpleInventory(new ItemStack(Items.BREAD, 64));
        SimpleInventory sourceRight = new SimpleInventory(new ItemStack(Items.BREAD, 6));
        DoubleInventory source = new DoubleInventory(sourceLeft, sourceRight);
        SimpleInventory destinationLeft = new SimpleInventory(new ItemStack(Items.BREAD, 24));
        SimpleInventory destinationRight = new SimpleInventory(new ItemStack(Items.CARROT, 64));
        DoubleInventory destination = new DoubleInventory(destinationLeft, destinationRight);
        Fixture fixture = fixture(source, destination);
        planFull(fixture.goal);

        assertTrue(take(fixture));
        assertCounts(fixture, 30, 88, 40);
        insert(fixture);

        assertCounts(fixture, 30, 128, 0);
        assertEquals(94, count(source, Items.BREAD) + count(destination, Items.BREAD));
    }

    @Test
    void failedSourceRestoration_usesVillagerThenIntentionalDrop() throws Exception {
        SimpleInventory villagerInventory = new SimpleInventory(new ItemStack(Items.BREAD, 61));
        Fixture fixture = fixture(
                new SimpleInventory(new ItemStack(Items.BREAD, 8)),
                new SimpleInventory(1),
                villagerInventory);
        planSingle(fixture.goal, 8);
        assertTrue(take(fixture));

        fixture.goal.putInventory(SOURCE, new SimpleInventory(new ItemStack(Items.CARROT, 64)));
        fixture.goal.removeInventory(DESTINATION);
        insert(fixture);

        assertEquals(64, count(villagerInventory, Items.BREAD));
        org.mockito.ArgumentCaptor<ItemStack> dropped = org.mockito.ArgumentCaptor.forClass(ItemStack.class);
        verify(fixture.villager).dropStack(dropped.capture());
        assertEquals(5, dropped.getValue().getCount());
        assertEquals(0, payloadCount(fixture.goal));
    }

    private static Fixture fixture(Inventory source, Inventory destination) {
        return fixture(source, destination, new SimpleInventory(8));
    }

    private static Fixture fixture(Inventory source, Inventory destination, SimpleInventory villagerInventory) {
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity villager = mock(VillagerEntity.class);
        EntityNavigation navigation = mock(EntityNavigation.class);
        when(villager.getWorld()).thenReturn(world);
        when(villager.getInventory()).thenReturn(villagerInventory);
        when(villager.getNavigation()).thenReturn(navigation);
        when(villager.getUuidAsString()).thenReturn("quartermaster-test");

        TestQuartermasterGoal goal = new TestQuartermasterGoal(villager);
        goal.putInventory(SOURCE, source);
        goal.putInventory(DESTINATION, destination);
        return new Fixture(world, villager, goal, source, destination, villagerInventory);
    }

    private static void planSingle(QuartermasterGoal goal, int count) throws Exception {
        planSingle(goal, Items.BREAD, count);
    }

    private static void planSingle(QuartermasterGoal goal, Item item, int count) throws Exception {
        Method method = QuartermasterGoal.class.getDeclaredMethod(
                "planSingleStackHaul", BlockPos.class, BlockPos.class, ItemStack.class);
        method.setAccessible(true);
        method.invoke(goal, SOURCE, DESTINATION, new ItemStack(item, count));
    }

    private static void planFull(QuartermasterGoal goal) throws Exception {
        Method method = QuartermasterGoal.class.getDeclaredMethod("planFullChestHaul", BlockPos.class, BlockPos.class);
        method.setAccessible(true);
        method.invoke(goal, SOURCE, DESTINATION);
    }

    private static boolean take(Fixture fixture) throws Exception {
        Method method = QuartermasterGoal.class.getDeclaredMethod(
                "takePayloadFromInventory", ServerWorld.class, BlockPos.class);
        method.setAccessible(true);
        return (boolean) method.invoke(fixture.goal, fixture.world, SOURCE);
    }

    private static void insert(Fixture fixture) throws Exception {
        Method method = QuartermasterGoal.class.getDeclaredMethod(
                "insertPayloadToInventory", ServerWorld.class, BlockPos.class);
        method.setAccessible(true);
        method.invoke(fixture.goal, fixture.world, DESTINATION);
    }

    private static void assertCounts(Fixture fixture, int source, int destination, int payload) throws Exception {
        assertEquals(source, total(fixture.source), "source count");
        assertEquals(destination, total(fixture.destination), "destination count");
        assertEquals(payload, payloadCount(fixture.goal), "in-transit payload count");
    }

    private static int payloadCount(QuartermasterGoal goal) throws Exception {
        Field field = QuartermasterGoal.class.getDeclaredField("transferPayload");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") List<ItemStack> payload = (List<ItemStack>) field.get(goal);
        return payload.stream().mapToInt(ItemStack::getCount).sum();
    }

    private static int total(Inventory inventory) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            total += inventory.getStack(slot).getCount();
        }
        return total;
    }

    private static int count(Inventory inventory, Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (inventory.getStack(slot).isOf(item)) {
                total += inventory.getStack(slot).getCount();
            }
        }
        return total;
    }

    private record Fixture(ServerWorld world,
                           VillagerEntity villager,
                           TestQuartermasterGoal goal,
                           Inventory source,
                           Inventory destination,
                           SimpleInventory villagerInventory) {
    }

    private static final class TestQuartermasterGoal extends QuartermasterGoal {
        private final Map<BlockPos, Inventory> inventories = new HashMap<>();

        private TestQuartermasterGoal(VillagerEntity villager) {
            super(villager, JOB, DESTINATION);
        }

        void putInventory(BlockPos pos, Inventory inventory) {
            inventories.put(pos.toImmutable(), inventory);
        }

        void removeInventory(BlockPos pos) {
            inventories.remove(pos);
        }

        @Override
        protected Optional<Inventory> getInventory(ServerWorld world, BlockPos pos) {
            return Optional.ofNullable(inventories.get(pos));
        }
    }
}
