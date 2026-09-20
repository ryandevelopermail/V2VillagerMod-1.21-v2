package dev.sterner.guardvillagers.common.villager.behavior;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FarmerBehaviorPairingRefreshTest {
    @Test
    void periodicSamePairRefreshDoesNotOverwriteChestMutationDebounceTimestamp() {
        Map<String, Long> wakeTimestamps = new HashMap<>();
        String villager = "farmer";

        FarmerBehavior.updateHarvestWakeTimestampForPairing(
                wakeTimestamps, villager, 100L, true);
        FarmerBehavior.updateHarvestWakeTimestampForPairing(
                wakeTimestamps, villager, 1200L, false);

        assertEquals(100L, wakeTimestamps.get(villager));
    }
}
