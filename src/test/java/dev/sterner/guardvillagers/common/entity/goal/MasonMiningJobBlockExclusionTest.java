package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.compat.morevillagers.MoreVillagersBehaviorBridge;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MasonMiningJobBlockExclusionTest {
    private static final BlockPos JOB_BLOCK = new BlockPos(0, 64, 0);

    @Test
    void originWithinFourBlocksOfVanillaJobBlockIsRejected() {
        assertTrue(MasonMiningStairGoal.isExcavationBlockedByJobBlock(
                new BlockPos(4, 64, 0),
                JOB_BLOCK,
                true));
    }

    @Test
    void fiveBlockBoundaryIsProtectedInclusively() {
        assertTrue(MasonMiningStairGoal.isExcavationBlockedByJobBlock(
                new BlockPos(MasonMiningStairGoal.JOB_BLOCK_EXCLUSION_RADIUS, 64, 0),
                JOB_BLOCK,
                true));
    }

    @Test
    void originOutsideExclusionZoneIsAllowed() {
        assertFalse(MasonMiningStairGoal.isExcavationBlockedByJobBlock(
                new BlockPos(MasonMiningStairGoal.JOB_BLOCK_EXCLUSION_RADIUS + 1, 64, 0),
                JOB_BLOCK,
                true));
    }

    @Test
    void cubeCornerOutsideFiveBlockSphereIsAllowed() {
        assertFalse(MasonMiningStairGoal.isExcavationBlockedByJobBlock(
                new BlockPos(5, 69, 5),
                JOB_BLOCK,
                true));
    }

    @Test
    void supportedMoreVillagersJobBlocksAreProtected() {
        List<String> ids = List.of(
                "morevillagers:oceanography_table",
                "morevillagers:decayed_workbench",
                "morevillagers:woodworking_table",
                "morevillagers:purpur_altar",
                "morevillagers:blueprint_table",
                "morevillagers:gardening_table",
                "morevillagers:hunting_post",
                "morevillagers:mining_bench");

        assertEquals(Set.copyOf(ids), MoreVillagersBehaviorBridge.supportedJobBlockIds());
        assertTrue(MasonMiningStairGoal.isExcavationBlockedByJobBlock(
                new BlockPos(0, 64, 4),
                JOB_BLOCK,
                MoreVillagersBehaviorBridge.supportedJobBlockIds().contains(
                        "morevillagers:mining_bench")));
    }

    @Test
    void ordinaryUnrelatedBlocksDoNotCreateAnExclusionZone() {
        assertFalse(MasonMiningStairGoal.isExcavationBlockedByJobBlock(
                new BlockPos(1, 64, 0),
                JOB_BLOCK,
                false));
    }

    @Test
    void replanSkipsProtectedOriginAndSelectsNextValidCandidate() {
        BlockPos protectedCandidate = new BlockPos(4, 64, 0);
        BlockPos validCandidate = new BlockPos(6, 64, 0);

        BlockPos selected = MasonMiningStairGoal.selectFirstAllowedMiningOrigin(
                List.of(protectedCandidate, validCandidate),
                candidate -> !MasonMiningStairGoal.isWithinJobBlockExclusion(candidate, JOB_BLOCK),
                2);

        assertEquals(validCandidate, selected);
    }
}
