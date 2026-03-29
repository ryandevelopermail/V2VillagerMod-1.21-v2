package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LumberjackGuardCraftingGoalTest {

    @Test
    void craftBootstrapChestAndAttemptPlacementIfNeeded_noChestAndEnoughPlanks_craftsChestAndAttemptsPlacement() {
        AtomicInteger craftCalls = new AtomicInteger();
        AtomicInteger placeCalls = new AtomicInteger();

        boolean acted = LumberjackGuardCraftingGoal.craftBootstrapChestAndAttemptPlacementIfNeeded(
                true,
                () -> {
                    craftCalls.incrementAndGet();
                    return true;
                },
                () -> {
                    placeCalls.incrementAndGet();
                    return false;
                }
        );

        assertTrue(acted);
        assertEquals(1, craftCalls.get());
        assertEquals(1, placeCalls.get());
    }

    @Test
    void craftBootstrapChestAndAttemptPlacementIfNeeded_craftFails_doesNotAttemptPlacement() {
        AtomicBoolean placementAttempted = new AtomicBoolean(false);

        boolean acted = LumberjackGuardCraftingGoal.craftBootstrapChestAndAttemptPlacementIfNeeded(
                true,
                () -> false,
                () -> {
                    placementAttempted.set(true);
                    return true;
                }
        );

        assertFalse(acted);
        assertFalse(placementAttempted.get());
    }

    @Test
    void craftSingleUpgradeDemandOutputIfPossible_fenceDemandCraftsThreeFencesPerOperation() {
        List<ItemStack> buffer = new ArrayList<>();
        SimpleInventory chestInventory = new SimpleInventory(2);
        chestInventory.setStack(0, new ItemStack(Items.OAK_PLANKS, 4));
        chestInventory.setStack(1, new ItemStack(Items.STICK, 2));

        boolean crafted = LumberjackGuardCraftingGoal.craftSingleUpgradeDemandOutputIfPossible(
                buffer,
                chestInventory,
                LumberjackChestTriggerController.UpgradeDemand.v3Fence()
        );

        assertTrue(crafted);
        assertEquals(1, buffer.size());
        assertTrue(buffer.getFirst().isOf(Items.OAK_FENCE));
        assertEquals(3, buffer.getFirst().getCount());
    }
}
