package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.DistributionRecipientHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeatherworkerDistributionGoalRecipientResolutionTest {

    @Test
    void resolveItemFrameRecipients_selectsV2CartographerChestFirst() {
        DistributionRecipientHelper.RecipientRecord nonV2Cartographer =
                recipient(new BlockPos(0, 64, 0), new BlockPos(1, 64, 1));
        DistributionRecipientHelper.RecipientRecord v2Cartographer =
                recipient(new BlockPos(10, 64, 10), new BlockPos(11, 64, 11));
        DistributionRecipientHelper.RecipientRecord librarian =
                recipient(new BlockPos(20, 64, 20), new BlockPos(21, 64, 21));

        List<DistributionRecipientHelper.RecipientRecord> resolved = LeatherworkerDistributionGoal.resolveItemFrameRecipients(
                new ItemStack(Items.ITEM_FRAME),
                List.of(nonV2Cartographer, v2Cartographer),
                List.of(v2Cartographer),
                List.of(librarian),
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
        DistributionRecipientHelper.RecipientRecord librarian =
                recipient(new BlockPos(20, 64, 20), new BlockPos(21, 64, 21));

        List<DistributionRecipientHelper.RecipientRecord> resolved = LeatherworkerDistributionGoal.resolveItemFrameRecipients(
                new ItemStack(Items.ITEM_FRAME),
                List.of(nonV2Cartographer, v2Cartographer),
                List.of(v2Cartographer),
                List.of(librarian),
                LoggerFactory.getLogger(LeatherworkerDistributionGoalRecipientResolutionTest.class),
                "test-leatherworker"
        );

        assertEquals(List.of(v2Cartographer.chestPos(), librarian.chestPos()), resolved.stream().map(DistributionRecipientHelper.RecipientRecord::chestPos).toList());
    }

    @Test
    void resolveItemFrameRecipients_fallsBackToLibrariansWhenNoEligibleV2CartographerExists() {
        DistributionRecipientHelper.RecipientRecord nonV2Cartographer =
                recipient(new BlockPos(0, 64, 0), new BlockPos(1, 64, 1));
        DistributionRecipientHelper.RecipientRecord librarian =
                recipient(new BlockPos(20, 64, 20), new BlockPos(21, 64, 21));

        List<DistributionRecipientHelper.RecipientRecord> resolved = LeatherworkerDistributionGoal.resolveItemFrameRecipients(
                new ItemStack(Items.ITEM_FRAME),
                List.of(nonV2Cartographer),
                List.of(),
                List.of(librarian),
                LoggerFactory.getLogger(LeatherworkerDistributionGoalRecipientResolutionTest.class),
                "test-leatherworker"
        );

        assertEquals(List.of(librarian.chestPos()), resolved.stream().map(DistributionRecipientHelper.RecipientRecord::chestPos).toList());
    }

    private static DistributionRecipientHelper.RecipientRecord recipient(BlockPos jobPos, BlockPos chestPos) {
        return new DistributionRecipientHelper.RecipientRecord(null, jobPos, chestPos, 0.0D);
    }
}
