package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MasonMiningJobBlockExclusionTest {
    private static final BlockPos JOB_BLOCK = new BlockPos(0, 64, 0);

    @Test
    void originWithinFourBlocksOfVanillaJobBlockIsRejected() {
        assertTrue(MasonMiningStairGoal.isKnownVillagerJobBlockId("minecraft:stonecutter"));
        assertTrue(MasonMiningStairGoal.isKnownVillagerJobBlockId("minecraft:crafting_table"));
        assertTrue(MasonMiningStairGoal.isExcavationBlockedByJobBlock(
                new BlockPos(4, 64, 0),
                JOB_BLOCK,
                "minecraft:stonecutter"));
    }

    @Test
    void fiveBlockBoundaryIsProtectedInclusively() {
        assertTrue(MasonMiningStairGoal.isExcavationBlockedByJobBlock(
                new BlockPos(MasonMiningStairGoal.JOB_BLOCK_EXCLUSION_RADIUS, 64, 0),
                JOB_BLOCK,
                "minecraft:lectern"));
    }

    @Test
    void originOutsideExclusionZoneIsAllowed() {
        assertFalse(MasonMiningStairGoal.isExcavationBlockedByJobBlock(
                new BlockPos(MasonMiningStairGoal.JOB_BLOCK_EXCLUSION_RADIUS + 1, 64, 0),
                JOB_BLOCK,
                "minecraft:composter"));
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

        assertTrue(ids.stream().allMatch(MasonMiningStairGoal::isKnownVillagerJobBlockId));
        assertTrue(MasonMiningStairGoal.isExcavationBlockedByJobBlock(
                new BlockPos(0, 64, 4),
                JOB_BLOCK,
                "morevillagers:mining_bench"));
    }

    @Test
    void ordinaryUnrelatedBlocksDoNotCreateAnExclusionZone() {
        assertFalse(MasonMiningStairGoal.isKnownVillagerJobBlockId("minecraft:stone"));
        assertFalse(MasonMiningStairGoal.isKnownVillagerJobBlockId("minecraft:dirt"));
        assertFalse(MasonMiningStairGoal.isExcavationBlockedByJobBlock(
                new BlockPos(1, 64, 0),
                JOB_BLOCK,
                "minecraft:stone"));
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
