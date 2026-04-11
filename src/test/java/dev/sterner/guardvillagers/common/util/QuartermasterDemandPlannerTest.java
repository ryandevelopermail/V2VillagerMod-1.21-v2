package dev.sterner.guardvillagers.common.util;

import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuartermasterDemandPlannerTest {

    @Test
    void plan_ordersQueueDeterministicallyUsingUrgencyThenTieBreak() {
        RecipeDemandIndex.RouteIndex routeIndex = RecipeDemandIndex.buildFixedRouteIndexForTests();
        BlockPos qmChest = new BlockPos(0, 64, 0);
        BlockPos firstFarmerChest = new BlockPos(6, 64, 0);
        BlockPos secondFarmerChest = new BlockPos(8, 64, 0);

        List<QuartermasterDemandPlanner.ChestSnapshot> snapshots = List.of(
                snapshot(VillagerProfession.LIBRARIAN, qmChest, Map.of(Items.OAK_PLANKS, 64, Items.STICK, 64)),
                snapshot(VillagerProfession.FARMER, firstFarmerChest, Map.of()),
                snapshot(VillagerProfession.FARMER, secondFarmerChest, Map.of())
        );

        List<QuartermasterDemandPlanner.QueueEntry> planned = QuartermasterDemandPlanner.plan(routeIndex, qmChest, snapshots, 16);

        assertEquals(4, planned.size());
        // Highest urgency entries are stick deficits; both are equal so tie-break key must be stable by chest position.
        assertEquals(Items.STICK, planned.get(0).requestedStack().getItem());
        assertEquals(firstFarmerChest, planned.get(0).recipientChestPos());
        assertEquals(Items.STICK, planned.get(1).requestedStack().getItem());
        assertEquals(secondFarmerChest, planned.get(1).recipientChestPos());

        assertEquals(Items.OAK_PLANKS, planned.get(2).requestedStack().getItem());
        assertEquals(firstFarmerChest, planned.get(2).recipientChestPos());
        assertEquals(Items.OAK_PLANKS, planned.get(3).requestedStack().getItem());
        assertEquals(secondFarmerChest, planned.get(3).recipientChestPos());
    }

    @Test
    void plan_prioritizesCartographerRecipeCompletionOverPartialSend() {
        QuartermasterDemandPlanner.resetContestedAllocatorsForTests();
        RecipeDemandIndex.RouteIndex routeIndex = RecipeDemandIndex.buildFixedRouteIndexForTests();
        BlockPos qmChest = new BlockPos(0, 64, 0);
        BlockPos cartographerChest = new BlockPos(5, 64, 0);
        BlockPos farmerChest = new BlockPos(9, 64, 0);

        List<QuartermasterDemandPlanner.ChestSnapshot> snapshots = List.of(
                snapshot(VillagerProfession.LIBRARIAN, qmChest, Map.of(
                        Items.PAPER, 16,
                        Items.COMPASS, 2,
                        Items.OAK_PLANKS, 64
                )),
                snapshot(VillagerProfession.CARTOGRAPHER, cartographerChest, Map.of(Items.PAPER, 0)),
                snapshot(VillagerProfession.FARMER, farmerChest, Map.of())
        );

        List<QuartermasterDemandPlanner.QueueEntry> planned = QuartermasterDemandPlanner.plan(routeIndex, qmChest, snapshots, 16);

        assertEquals(VillagerProfession.CARTOGRAPHER, planned.get(0).recipientProfession());
        assertEquals(Items.PAPER, planned.get(0).requestedStack().getItem());
        assertTrue(planned.get(0).completesCraftNow());
    }

    @Test
    void plan_rotatesDiamondAllocationAcrossSmithChain() {
        QuartermasterDemandPlanner.resetContestedAllocatorsForTests();
        RecipeDemandIndex.RouteIndex routeIndex = RecipeDemandIndex.buildFixedRouteIndexForTests();
        BlockPos qmChest = new BlockPos(0, 64, 0);
        BlockPos toolsmithChest = new BlockPos(4, 64, 0);
        BlockPos weaponsmithChest = new BlockPos(6, 64, 0);
        BlockPos armorerChest = new BlockPos(8, 64, 0);

        List<QuartermasterDemandPlanner.ChestSnapshot> firstPlanSnapshots = List.of(
                snapshot(VillagerProfession.LIBRARIAN, qmChest, Map.of(Items.DIAMOND, 1)),
                snapshot(VillagerProfession.TOOLSMITH, toolsmithChest, Map.of()),
                snapshot(VillagerProfession.WEAPONSMITH, weaponsmithChest, Map.of()),
                snapshot(VillagerProfession.ARMORER, armorerChest, Map.of())
        );
        List<QuartermasterDemandPlanner.ChestSnapshot> secondPlanSnapshots = List.of(
                snapshot(VillagerProfession.LIBRARIAN, qmChest, Map.of(Items.DIAMOND, 1)),
                snapshot(VillagerProfession.TOOLSMITH, toolsmithChest, Map.of()),
                snapshot(VillagerProfession.WEAPONSMITH, weaponsmithChest, Map.of()),
                snapshot(VillagerProfession.ARMORER, armorerChest, Map.of())
        );

        List<QuartermasterDemandPlanner.QueueEntry> firstPlan = QuartermasterDemandPlanner.plan(routeIndex, qmChest, firstPlanSnapshots, 1);
        List<QuartermasterDemandPlanner.QueueEntry> secondPlan = QuartermasterDemandPlanner.plan(routeIndex, qmChest, secondPlanSnapshots, 1);

        assertEquals(1, firstPlan.stream().filter(entry -> entry.requestedStack().isOf(Items.DIAMOND)).count());
        assertEquals(1, secondPlan.stream().filter(entry -> entry.requestedStack().isOf(Items.DIAMOND)).count());
        assertEquals(VillagerProfession.TOOLSMITH,
                firstPlan.stream().filter(entry -> entry.requestedStack().isOf(Items.DIAMOND)).findFirst().orElseThrow().recipientProfession());
        assertEquals(VillagerProfession.WEAPONSMITH,
                secondPlan.stream().filter(entry -> entry.requestedStack().isOf(Items.DIAMOND)).findFirst().orElseThrow().recipientProfession());
    }

    private static QuartermasterDemandPlanner.ChestSnapshot snapshot(VillagerProfession profession,
                                                                      BlockPos chest,
                                                                      Map<Item, Integer> counts) {
        return new QuartermasterDemandPlanner.ChestSnapshot(profession, chest, counts);
    }
}
