package dev.sterner.guardvillagers.common.developer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DeveloperSetupRequestTest {
    @Test
    void acceptsValidLumberjackV2Request() {
        DeveloperSetupRequest request = new DeveloperSetupRequest(
                DeveloperSetupType.V2_PROFESSION,
                DeveloperProfession.LUMBERJACK,
                true,
                true,
                true,
                4
        );

        assertTrue(request.validationError().isEmpty());
    }

    @Test
    void rejectsV2WithoutPairedChest() {
        DeveloperSetupRequest request = new DeveloperSetupRequest(
                DeveloperSetupType.V2_PROFESSION,
                DeveloperProfession.LUMBERJACK,
                false,
                true,
                false,
                4
        );

        assertTrue(request.validationError().orElseThrow().contains("paired chest"));
    }

    @Test
    void rejectsPlainSetupThatForcesInfrastructure() {
        DeveloperSetupRequest request = new DeveloperSetupRequest(
                DeveloperSetupType.PLAIN_VILLAGER,
                DeveloperProfession.LUMBERJACK,
                false,
                true,
                false,
                4
        );

        assertTrue(request.validationError().orElseThrow().contains("Plain Villager"));
    }

    @Test
    void rejectsOutOfRangeTreeCount() {
        DeveloperSetupRequest request = new DeveloperSetupRequest(
                DeveloperSetupType.V1_PROFESSION,
                DeveloperProfession.LUMBERJACK,
                false,
                true,
                true,
                DeveloperSetupRequest.MAX_TREE_COUNT + 1
        );

        assertTrue(request.validationError().orElseThrow().contains("Tree count"));
    }
}
