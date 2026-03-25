package dev.sterner.guardvillagers.common.villager.behavior;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShepherdBehaviorFenceOwnershipTest {

    @Test
    void chestPairedOnly_assignsNoFenceCraftingOwner() {
        ShepherdBehavior.FenceCraftingOwner owner = ShepherdBehavior.resolveFenceCraftingOwner(false);

        assertEquals(ShepherdBehavior.FenceCraftingOwner.NONE, owner);
    }

    @Test
    void chestAndTablePaired_assignsDedicatedFenceCraftingGoalOwner() {
        ShepherdBehavior.FenceCraftingOwner owner = ShepherdBehavior.resolveFenceCraftingOwner(true);

        assertEquals(ShepherdBehavior.FenceCraftingOwner.DEDICATED_FENCE_GOAL, owner);
    }
}
