package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.ToolsmithDemandPlanner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ToolsmithCraftingGoal#hasPracticalFishingRodNeed}.
 * FishermanGuardEntity is not a VillagerEntity, so the fishing rod demand path uses a
 * fishermanEntryCount (not RecipientDemand/RecipientRecord) to avoid Minecraft bootstrap in tests.
 */
class ToolsmithCraftingGoalFishingRodNeedTest {

    @Test
    void hasPracticalFishingRodNeed_returnsTrueWhenAggregateDeficitIsZeroButRecipientDeficitExists() {
        ToolsmithDemandPlanner.ToolDemand toolDemand = fishingRodDemand(1, 1, 0);

        boolean practicalNeed = ToolsmithCraftingGoal.hasPracticalFishingRodNeed(toolDemand, 0, 1);

        assertTrue(practicalNeed);
    }

    @Test
    void hasPracticalFishingRodNeed_returnsFalseWhenRecipientDeficitsAreEmptyAtDeficitZero() {
        ToolsmithDemandPlanner.ToolDemand toolDemand = fishingRodDemand(1, 1, 0);

        boolean practicalNeed = ToolsmithCraftingGoal.hasPracticalFishingRodNeed(toolDemand, 0, 0);

        assertFalse(practicalNeed);
    }

    @Test
    void hasPracticalFishingRodNeed_returnsFalseForNegativeDeficitEvenIfRecipientsNeedRods() {
        ToolsmithDemandPlanner.ToolDemand toolDemand = fishingRodDemand(2, 1, -1);

        boolean practicalNeed = ToolsmithCraftingGoal.hasPracticalFishingRodNeed(toolDemand, -1, 1);

        assertFalse(practicalNeed);
    }

    private static ToolsmithDemandPlanner.ToolDemand fishingRodDemand(int sourceStock, int recipientCount, int demandDeficit) {
        return new ToolsmithDemandPlanner.ToolDemand(
                ToolsmithDemandPlanner.ToolType.FISHING_ROD,
                sourceStock,
                recipientCount,
                demandDeficit,
                List.of() // rankedRecipients always empty for FISHING_ROD; fishermanEntryCount is passed separately
        );
    }
}
