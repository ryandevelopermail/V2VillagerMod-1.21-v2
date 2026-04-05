package dev.sterner.guardvillagers.common.util;

import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private static QuartermasterDemandPlanner.ChestSnapshot snapshot(VillagerProfession profession,
                                                                      BlockPos chest,
                                                                      Map<Item, Integer> counts) {
        return new QuartermasterDemandPlanner.ChestSnapshot(profession, chest, counts);
    }
}
