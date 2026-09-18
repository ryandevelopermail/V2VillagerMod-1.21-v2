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
                true,
                LumberjackInventoryPreset.GENERAL_MIXED_TEST,
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
                false,
                LumberjackInventoryPreset.EMPTY_NATURAL,
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
                false,
                LumberjackInventoryPreset.EMPTY_NATURAL,
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
                false,
                false,
                LumberjackInventoryPreset.EMPTY_NATURAL,
                true,
                DeveloperSetupRequest.MAX_TREE_COUNT + 1
        );

        assertTrue(request.validationError().orElseThrow().contains("Tree count"));
    }

    @Test
    void rejectsInventoryPresetWithoutV2Chest() {
        DeveloperSetupRequest request = new DeveloperSetupRequest(
                DeveloperSetupType.V1_PROFESSION,
                DeveloperProfession.LUMBERJACK,
                false,
                true,
                false,
                false,
                LumberjackInventoryPreset.SHEPHERD_SUPPLY_TEST,
                false,
                DeveloperSetupRequest.DEFAULT_TREE_COUNT
        );

        assertTrue(request.validationError().orElseThrow().contains("V2 paired chest"));
    }
}
