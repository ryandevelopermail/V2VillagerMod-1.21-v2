package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.DistributionRecipientHelper;
import dev.sterner.guardvillagers.common.util.ToolsmithDemandPlanner;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolsmithCraftingGoalFishingRodNeedTest {

    @Test
    void hasPracticalFishingRodNeed_returnsTrueWhenAggregateDeficitIsZeroButRecipientDeficitExists() {
        ToolsmithDemandPlanner.ToolDemand toolDemand = fishingRodDemand(1, 1, 0, true);

        boolean practicalNeed = ToolsmithCraftingGoal.hasPracticalFishingRodNeed(toolDemand, 0);

        assertTrue(practicalNeed);
    }

    @Test
    void hasPracticalFishingRodNeed_returnsFalseWhenRecipientDeficitsAreEmptyAtDeficitZero() {
        ToolsmithDemandPlanner.ToolDemand toolDemand = fishingRodDemand(1, 1, 0, false);

        boolean practicalNeed = ToolsmithCraftingGoal.hasPracticalFishingRodNeed(toolDemand, 0);

        assertFalse(practicalNeed);
    }

    @Test
    void hasPracticalFishingRodNeed_returnsFalseForNegativeDeficitEvenIfRecipientsNeedRods() {
        ToolsmithDemandPlanner.ToolDemand toolDemand = fishingRodDemand(2, 1, -1, true);

        boolean practicalNeed = ToolsmithCraftingGoal.hasPracticalFishingRodNeed(toolDemand, -1);

        assertFalse(practicalNeed);
    }

    private static ToolsmithDemandPlanner.ToolDemand fishingRodDemand(int sourceStock, int recipientCount, int demandDeficit, boolean includeRecipientDeficit) {
        List<ToolsmithDemandPlanner.RecipientDemand> rankedRecipients = includeRecipientDeficit
                ? List.of(new ToolsmithDemandPlanner.RecipientDemand(
                new DistributionRecipientHelper.RecipientRecord(null, BlockPos.ORIGIN, BlockPos.ORIGIN, 0.0D),
                0,
                1
        ))
                : List.of();
        return new ToolsmithDemandPlanner.ToolDemand(
                ToolsmithDemandPlanner.ToolType.FISHING_ROD,
                sourceStock,
                recipientCount,
                demandDeficit,
                rankedRecipients
        );
    }
}
