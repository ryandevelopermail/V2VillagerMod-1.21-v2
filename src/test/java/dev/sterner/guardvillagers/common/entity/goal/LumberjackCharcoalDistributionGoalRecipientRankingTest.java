package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LumberjackCharcoalDistributionGoalRecipientRankingTest {

    @Test
    void storageShortfallRanksAheadOfNonDemandRecipientEvenWithoutProcessableInput() {
        LumberjackCharcoalDistributionGoal.RecipientEntry butcherStorageTopUp = new LumberjackCharcoalDistributionGoal.RecipientEntry(
                null,
                new BlockPos(10, 64, 10),
                false,
                false,
                true,
                12
        );
        LumberjackCharcoalDistributionGoal.RecipientEntry noDemandRecipient = new LumberjackCharcoalDistributionGoal.RecipientEntry(
                null,
                new BlockPos(2, 64, 2),
                false,
                false,
                false,
                0
        );

        List<LumberjackCharcoalDistributionGoal.RecipientEntry> recipients = new ArrayList<>(List.of(noDemandRecipient, butcherStorageTopUp));
        LumberjackCharcoalDistributionGoal.sortRecipientsForSelection(recipients);

        assertEquals(butcherStorageTopUp.chestPos(), recipients.getFirst().chestPos());
    }

    @Test
    void urgentFuelNeedOutranksStorageTopUp() {
        LumberjackCharcoalDistributionGoal.RecipientEntry urgentFurnaceRefill = new LumberjackCharcoalDistributionGoal.RecipientEntry(
                null,
                new BlockPos(3, 64, 3),
                true,
                true,
                false,
                0
        );
        LumberjackCharcoalDistributionGoal.RecipientEntry storageTopUp = new LumberjackCharcoalDistributionGoal.RecipientEntry(
                null,
                new BlockPos(1, 64, 1),
                false,
                false,
                true,
                16
        );

        List<LumberjackCharcoalDistributionGoal.RecipientEntry> recipients = new ArrayList<>(List.of(storageTopUp, urgentFurnaceRefill));
        LumberjackCharcoalDistributionGoal.sortRecipientsForSelection(recipients);

        assertEquals(urgentFurnaceRefill.chestPos(), recipients.getFirst().chestPos());
    }

    @Test
    void storageShortfallTargetsOnlyFuelConsumers() {
        assertTrue(LumberjackCharcoalDistributionGoal.computeStorageShortfall(VillagerProfession.BUTCHER, 0) > 0);
        assertTrue(LumberjackCharcoalDistributionGoal.computeStorageShortfall(VillagerProfession.ARMORER, 0) > 0);
        assertEquals(0, LumberjackCharcoalDistributionGoal.computeStorageShortfall(VillagerProfession.TOOLSMITH, 0));
        assertEquals(0, LumberjackCharcoalDistributionGoal.computeStorageShortfall(VillagerProfession.WEAPONSMITH, 0));
    }
}
