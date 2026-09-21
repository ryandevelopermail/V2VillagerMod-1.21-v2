package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.DistributionRecipientHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeatherworkerDistributionGoalRecipientResolutionTest {

    @Test
    void onlyActualLibrarianRecipeInputsRemainLibrarianBound() {
        assertTrue(LeatherworkerDistributionGoal.isLibrarianCraftingInput(new ItemStack(Items.LEATHER)));
        assertTrue(LeatherworkerDistributionGoal.isLibrarianCraftingInput(new ItemStack(Items.BOOK)));
        assertFalse(LeatherworkerDistributionGoal.isLibrarianCraftingInput(new ItemStack(Items.SADDLE)));
        assertFalse(LeatherworkerDistributionGoal.isLibrarianCraftingInput(new ItemStack(Items.ENCHANTED_BOOK)));
    }

    @Test
    void resolveItemFrameRecipients_selectsV2CartographerChestFirst() {
        DistributionRecipientHelper.RecipientRecord nonV2Cartographer =
                recipient(new BlockPos(0, 64, 0), new BlockPos(1, 64, 1));
        DistributionRecipientHelper.RecipientRecord v2Cartographer =
                recipient(new BlockPos(10, 64, 10), new BlockPos(11, 64, 11));
        DistributionRecipientHelper.RecipientRecord quartermaster =
                recipient(new BlockPos(20, 64, 20), new BlockPos(21, 64, 21));

        List<DistributionRecipientHelper.RecipientRecord> resolved = LeatherworkerDistributionGoal.resolveItemFrameRecipients(
                new ItemStack(Items.ITEM_FRAME),
                List.of(nonV2Cartographer, v2Cartographer),
                List.of(v2Cartographer),
                List.of(quartermaster),
                LoggerFactory.getLogger(LeatherworkerDistributionGoalRecipientResolutionTest.class),
                "test-leatherworker"
        );

        assertEquals(v2Cartographer.chestPos(), resolved.getFirst().chestPos());
    }

    @Test
    void resolveItemFrameRecipients_excludesNonV2Cartographers() {
        DistributionRecipientHelper.RecipientRecord nonV2Cartographer =
                recipient(new BlockPos(0, 64, 0), new BlockPos(1, 64, 1));
        DistributionRecipientHelper.RecipientRecord v2Cartographer =
                recipient(new BlockPos(10, 64, 10), new BlockPos(11, 64, 11));
        DistributionRecipientHelper.RecipientRecord quartermaster =
                recipient(new BlockPos(20, 64, 20), new BlockPos(21, 64, 21));

        List<DistributionRecipientHelper.RecipientRecord> resolved = LeatherworkerDistributionGoal.resolveItemFrameRecipients(
                new ItemStack(Items.ITEM_FRAME),
                List.of(nonV2Cartographer, v2Cartographer),
                List.of(v2Cartographer),
                List.of(quartermaster),
                LoggerFactory.getLogger(LeatherworkerDistributionGoalRecipientResolutionTest.class),
                "test-leatherworker"
        );

        assertEquals(List.of(v2Cartographer.chestPos(), quartermaster.chestPos()), resolved.stream().map(DistributionRecipientHelper.RecipientRecord::chestPos).toList());
    }

    @Test
    void resolveItemFrameRecipients_fallsBackToQuartermasterWhenNoEligibleV2CartographerExists() {
        DistributionRecipientHelper.RecipientRecord nonV2Cartographer =
                recipient(new BlockPos(0, 64, 0), new BlockPos(1, 64, 1));
        DistributionRecipientHelper.RecipientRecord quartermaster =
                recipient(new BlockPos(20, 64, 20), new BlockPos(21, 64, 21));

        List<DistributionRecipientHelper.RecipientRecord> resolved = LeatherworkerDistributionGoal.resolveItemFrameRecipients(
                new ItemStack(Items.ITEM_FRAME),
                List.of(nonV2Cartographer),
                List.of(),
                List.of(quartermaster),
                LoggerFactory.getLogger(LeatherworkerDistributionGoalRecipientResolutionTest.class),
                "test-leatherworker"
        );

        assertEquals(List.of(quartermaster.chestPos()), resolved.stream().map(DistributionRecipientHelper.RecipientRecord::chestPos).toList());
    }

    private static DistributionRecipientHelper.RecipientRecord recipient(BlockPos jobPos, BlockPos chestPos) {
        return new DistributionRecipientHelper.RecipientRecord(null, jobPos, chestPos, 0.0D);
    }
}
