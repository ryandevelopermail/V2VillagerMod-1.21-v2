package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.VillageMappedBoundsState;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LumberjackGuardChopTreesMappedBoundsModeTest {

    @Test
    void mappedBoundsMode_acceptsCandidateBeyondLocalTreeRadius() {
        BlockPos center = new BlockPos(0, 64, 0);
        BlockPos farCandidate = new BlockPos(42, 64, 0);
        VillageMappedBoundsState.MappedBounds mappedBounds =
                new VillageMappedBoundsState.MappedBounds(-64, 64, -64, 64);

        assertTrue(LumberjackGuardChopTreesGoal.isCandidateInScanMode(center, farCandidate, mappedBounds));
    }

    @Test
    void fallbackMode_withoutMappedBounds_remainsConstrainedToLocalRadius() {
        BlockPos center = new BlockPos(0, 64, 0);
        BlockPos nearCandidate = new BlockPos(18, 64, 0);
        BlockPos farCandidate = new BlockPos(42, 64, 0);

        assertTrue(LumberjackGuardChopTreesGoal.isCandidateInScanMode(center, nearCandidate, null));
        assertFalse(LumberjackGuardChopTreesGoal.isCandidateInScanMode(center, farCandidate, null));
    }
}
