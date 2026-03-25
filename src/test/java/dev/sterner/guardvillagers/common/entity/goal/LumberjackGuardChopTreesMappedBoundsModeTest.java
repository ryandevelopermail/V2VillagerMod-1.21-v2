package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.VillageMappedBoundsState;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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


    @Test
    void mappedBoundsMode_remainsEnabledUntilLastValidBoundIsRemovedAcrossScanCycles() {
        VillageMappedBoundsState.MappedBounds a = new VillageMappedBoundsState.MappedBounds(-64, 64, -64, 64);
        VillageMappedBoundsState.MappedBounds b = new VillageMappedBoundsState.MappedBounds(256, 384, 256, 384);

        assertTrue(LumberjackGuardChopTreesGoal.isMappedModeEnabled(List.of(a, b, a)));
        assertTrue(LumberjackGuardChopTreesGoal.isMappedModeEnabled(List.of(b)));
        assertFalse(LumberjackGuardChopTreesGoal.isMappedModeEnabled(List.of()));
    }

    @Test
    void mergeOverlappingMappedBounds_combinesOverlapsIntoMinimalScanRegion() {
        List<VillageMappedBoundsState.MappedBounds> merged = LumberjackGuardChopTreesGoal.mergeOverlappingMappedBounds(List.of(
                new VillageMappedBoundsState.MappedBounds(-64, 64, -64, 64),
                new VillageMappedBoundsState.MappedBounds(60, 180, -64, 64),
                new VillageMappedBoundsState.MappedBounds(300, 360, 300, 360)
        ));

        assertEquals(2, merged.size());
        assertTrue(merged.stream().anyMatch(bounds -> bounds.contains(new BlockPos(140, 64, 0))));
    }
}
