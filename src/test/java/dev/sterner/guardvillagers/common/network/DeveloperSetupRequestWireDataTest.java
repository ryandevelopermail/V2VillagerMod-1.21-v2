package dev.sterner.guardvillagers.common.network;

import dev.sterner.guardvillagers.common.developer.DeveloperProfession;
import dev.sterner.guardvillagers.common.developer.DeveloperProfessionSelection;
import dev.sterner.guardvillagers.common.developer.DeveloperSetupRequest;
import dev.sterner.guardvillagers.common.developer.DeveloperSetupType;
import dev.sterner.guardvillagers.common.developer.LumberjackInventoryPreset;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeveloperSetupRequestWireDataTest {
    @Test
    void multiProfessionRequestRoundTripsThroughWireData() {
        DeveloperSetupRequest request = new DeveloperSetupRequest(
                DeveloperSetupType.V1_PROFESSION,
                List.of(
                        new DeveloperProfessionSelection(DeveloperProfession.FARMER, 2),
                        new DeveloperProfessionSelection(DeveloperProfession.FLETCHER, 1),
                        new DeveloperProfessionSelection(DeveloperProfession.SHEPHERD, 3)),
                false, true, false, false,
                LumberjackInventoryPreset.EMPTY_NATURAL,
                false,
                DeveloperSetupRequest.DEFAULT_TREE_COUNT);

        DeveloperSetupRequest decoded = DeveloperSetupRequestWireData.fromRequest(request)
                .decodeRequest()
                .orElseThrow();

        assertEquals(request, decoded);
        assertEquals(6, decoded.totalV1Villagers());
    }

    @Test
    void unknownProfessionIdIsRejectedDuringDecode() {
        DeveloperSetupRequestWireData data = new DeveloperSetupRequestWireData(
                DeveloperSetupType.V1_PROFESSION.networkId(),
                List.of(new DeveloperSetupRequestWireData.ProfessionEntry(999, 1)),
                false, true, false, false,
                LumberjackInventoryPreset.EMPTY_NATURAL.networkId(),
                false,
                DeveloperSetupRequest.DEFAULT_TREE_COUNT);

        assertTrue(data.decodeRequest().isEmpty());
    }
}
