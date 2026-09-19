package dev.sterner.guardvillagers.common.developer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeveloperSetupRequestTest {
    @Test
    void acceptsValidLumberjackV2Request() {
        DeveloperSetupRequest request = request(
                DeveloperSetupType.V2_PROFESSION,
                List.of(selection(DeveloperProfession.LUMBERJACK, 1)),
                true, true, true, true,
                LumberjackInventoryPreset.GENERAL_MIXED_TEST, true, 4);

        assertTrue(request.validationError().isEmpty());
    }

    @Test
    void acceptsMixedV1BatchAndCountsEveryVillager() {
        DeveloperSetupRequest request = validV1(List.of(
                selection(DeveloperProfession.FARMER, 2),
                selection(DeveloperProfession.FLETCHER, 1),
                selection(DeveloperProfession.SHEPHERD, 3)));

        assertTrue(request.validationError().isEmpty());
        assertEquals(6, request.totalV1Villagers());
    }

    @Test
    void selectAllProducesOneValidEntryPerSupportedV1Profession() {
        List<DeveloperProfessionSelection> selections = DeveloperProfession.vanillaV1Professions().stream()
                .map(profession -> selection(profession, DeveloperSetupRequest.DEFAULT_PROFESSION_QUANTITY))
                .toList();
        DeveloperSetupRequest request = validV1(selections);

        assertEquals(13, selections.size());
        assertEquals(13, request.totalV1Villagers());
        assertTrue(request.validationError().isEmpty());
    }

    @Test
    void selectAllIncludesMoreVillagersProfessionsWhenAvailable() {
        List<DeveloperProfessionSelection> selections = DeveloperProfession.v1Professions(true).stream()
                .map(profession -> selection(profession, DeveloperSetupRequest.DEFAULT_PROFESSION_QUANTITY))
                .toList();
        DeveloperSetupRequest request = validV1(selections);

        assertEquals(21, selections.size());
        assertEquals(21, request.totalV1Villagers());
        assertTrue(request.validationError(true).isEmpty());
    }

    @Test
    void mixedVanillaAndMoreVillagersBatchRequiresCompatibilityMod() {
        DeveloperSetupRequest request = validV1(List.of(
                selection(DeveloperProfession.FARMER, 2),
                selection(DeveloperProfession.OCEANOGRAPHER, 1),
                selection(DeveloperProfession.WOODWORKER, 3)));

        assertEquals(6, request.totalV1Villagers());
        assertTrue(request.validationError(true).isEmpty());
        assertTrue(request.validationError(false).orElseThrow().contains("unsupported"));
    }

    @Test
    void rejectsEmptyV1Selection() {
        assertTrue(validV1(List.of()).validationError().orElseThrow().contains("Select at least one"));
    }

    @Test
    void rejectsOutOfRangeV1Quantity() {
        DeveloperSetupRequest zero = validV1(List.of(selection(DeveloperProfession.FARMER, 0)));
        DeveloperSetupRequest tooMany = validV1(List.of(selection(
                DeveloperProfession.FARMER,
                DeveloperSetupRequest.MAX_PROFESSION_QUANTITY + 1)));

        assertTrue(zero.validationError().orElseThrow().contains("quantity"));
        assertTrue(tooMany.validationError().orElseThrow().contains("quantity"));
    }

    @Test
    void rejectsDuplicateAndUnsupportedV1Entries() {
        DeveloperSetupRequest duplicate = validV1(List.of(
                selection(DeveloperProfession.FARMER, 1),
                selection(DeveloperProfession.FARMER, 2)));
        DeveloperSetupRequest lumberjack = validV1(List.of(selection(DeveloperProfession.LUMBERJACK, 1)));

        assertTrue(duplicate.validationError().orElseThrow().contains("only appear once"));
        assertTrue(lumberjack.validationError().orElseThrow().contains("unsupported"));
    }

    @Test
    void rejectsBatchAboveSafeTotalLimit() {
        DeveloperSetupRequest request = validV1(List.of(
                selection(DeveloperProfession.FARMER, 16),
                selection(DeveloperProfession.FLETCHER, 16),
                selection(DeveloperProfession.SHEPHERD, 16),
                selection(DeveloperProfession.ARMORER, 16),
                selection(DeveloperProfession.CLERIC, 1)));

        assertTrue(request.validationError().orElseThrow().contains("at most 64"));
    }

    @Test
    void rejectsV2WithoutPairedChest() {
        DeveloperSetupRequest request = request(
                DeveloperSetupType.V2_PROFESSION,
                List.of(selection(DeveloperProfession.LUMBERJACK, 1)),
                false, true, false, false,
                LumberjackInventoryPreset.EMPTY_NATURAL, false, 4);

        assertTrue(request.validationError().orElseThrow().contains("paired chest"));
    }

    @Test
    void rejectsPlainSetupThatForcesInfrastructure() {
        DeveloperSetupRequest request = request(
                DeveloperSetupType.PLAIN_VILLAGER,
                List.of(),
                false, true, false, false,
                LumberjackInventoryPreset.EMPTY_NATURAL, false, 4);

        assertTrue(request.validationError().orElseThrow().contains("Plain Villager"));
    }

    @Test
    void rejectsOutOfRangeTreeCount() {
        DeveloperSetupRequest request = request(
                DeveloperSetupType.V2_PROFESSION,
                List.of(selection(DeveloperProfession.LUMBERJACK, 1)),
                true, true, false, false,
                LumberjackInventoryPreset.EMPTY_NATURAL, true,
                DeveloperSetupRequest.MAX_TREE_COUNT + 1);

        assertTrue(request.validationError().orElseThrow().contains("Tree count"));
    }

    @Test
    void rejectsInventoryPresetWithoutV2Chest() {
        DeveloperSetupRequest request = request(
                DeveloperSetupType.V1_PROFESSION,
                List.of(selection(DeveloperProfession.FARMER, 1)),
                false, true, false, false,
                LumberjackInventoryPreset.SHEPHERD_SUPPLY_TEST, false,
                DeveloperSetupRequest.DEFAULT_TREE_COUNT);

        assertTrue(request.validationError().orElseThrow().contains("Lumberjack V2"));
    }

    private static DeveloperSetupRequest validV1(List<DeveloperProfessionSelection> selections) {
        return request(
                DeveloperSetupType.V1_PROFESSION,
                selections,
                false, true, false, false,
                LumberjackInventoryPreset.EMPTY_NATURAL, false,
                DeveloperSetupRequest.DEFAULT_TREE_COUNT);
    }

    private static DeveloperProfessionSelection selection(DeveloperProfession profession, int quantity) {
        return new DeveloperProfessionSelection(profession, quantity);
    }

    private static DeveloperSetupRequest request(
            DeveloperSetupType type,
            List<DeveloperProfessionSelection> selections,
            boolean chest,
            boolean jobBlock,
            boolean furnace,
            boolean supply,
            LumberjackInventoryPreset preset,
            boolean trees,
            int treeCount
    ) {
        return new DeveloperSetupRequest(
                type, selections, chest, jobBlock, furnace, supply, preset, trees, treeCount);
    }
}
