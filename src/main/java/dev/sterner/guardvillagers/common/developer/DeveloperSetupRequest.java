package dev.sterner.guardvillagers.common.developer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public record DeveloperSetupRequest(
        DeveloperSetupType setupType,
        List<DeveloperProfessionSelection> professionSelections,
        boolean createPairedChest,
        boolean createCraftingTable,
        boolean createFurnaceSetup,
        boolean createShepherdSupply,
        LumberjackInventoryPreset inventoryPreset,
        boolean generateMatureTrees,
        int treeCount
) {
    public static final int DEFAULT_TREE_COUNT = 4;
    public static final int MAX_TREE_COUNT = 12;
    public static final int DEFAULT_PROFESSION_QUANTITY = 1;
    public static final int MAX_PROFESSION_QUANTITY = 16;
    public static final int MAX_TOTAL_V1_VILLAGERS = 64;

    public DeveloperSetupRequest {
        if (professionSelections != null) {
            professionSelections = Collections.unmodifiableList(new ArrayList<>(professionSelections));
        }
    }

    public Optional<String> validationError() {
        if (setupType == null) {
            return Optional.of("Unknown setup type.");
        }
        if (professionSelections == null) {
            return Optional.of("Malformed profession selection.");
        }
        if (inventoryPreset == null) {
            return Optional.of("Unknown inventory preset.");
        }
        Optional<String> modeError = switch (setupType) {
            case PLAIN_VILLAGER -> validatePlainMode();
            case V1_PROFESSION -> validateV1Mode();
            case V2_PROFESSION -> validateV2Mode();
        };
        if (modeError.isPresent()) {
            return modeError;
        }
        if (generateMatureTrees && (treeCount < 1 || treeCount > MAX_TREE_COUNT)) {
            return Optional.of("Tree count must be between 1 and " + MAX_TREE_COUNT + ".");
        }
        return Optional.empty();
    }

    private Optional<String> validatePlainMode() {
        if (!professionSelections.isEmpty() || createPairedChest || createCraftingTable) {
            return Optional.of("Plain Villager setup cannot force professions, job blocks, or pairing blocks.");
        }
        if (hasLumberjackOptions()) {
            return Optional.of("Lumberjack options require a Lumberjack V2 setup.");
        }
        return Optional.empty();
    }

    private Optional<String> validateV1Mode() {
        if (professionSelections.isEmpty()) {
            return Optional.of("Select at least one V1 profession.");
        }
        if (professionSelections.size() > DeveloperProfession.v1Professions().size()) {
            return Optional.of("Too many V1 profession entries.");
        }
        if (createPairedChest) {
            return Optional.of("V1 setup cannot create a paired chest.");
        }
        if (!createCraftingTable) {
            return Optional.of("V1 setup requires a job site for every villager.");
        }
        if (hasLumberjackOptions()) {
            return Optional.of("Lumberjack options require a Lumberjack V2 setup.");
        }

        Set<DeveloperProfession> seen = EnumSet.noneOf(DeveloperProfession.class);
        int total = 0;
        for (DeveloperProfessionSelection selection : professionSelections) {
            if (selection == null || selection.profession() == null || !selection.profession().supportsVanillaV1()) {
                return Optional.of("Unknown or unsupported V1 profession.");
            }
            if (!seen.add(selection.profession())) {
                return Optional.of("Each V1 profession may only appear once.");
            }
            if (selection.quantity() < 1 || selection.quantity() > MAX_PROFESSION_QUANTITY) {
                return Optional.of("Profession quantity must be between 1 and " + MAX_PROFESSION_QUANTITY + ".");
            }
            total += selection.quantity();
            if (total > MAX_TOTAL_V1_VILLAGERS) {
                return Optional.of("A V1 batch may contain at most " + MAX_TOTAL_V1_VILLAGERS + " villagers.");
            }
        }
        return Optional.empty();
    }

    private Optional<String> validateV2Mode() {
        if (professionSelections.size() != 1
                || professionSelections.getFirst() == null
                || professionSelections.getFirst().profession() != DeveloperProfession.LUMBERJACK
                || professionSelections.getFirst().quantity() != 1) {
            return Optional.of("V2 setup currently supports exactly one Lumberjack.");
        }
        if (!createCraftingTable) {
            return Optional.of("Lumberjack V2 setup requires a crafting table.");
        }
        if (!createPairedChest) {
            return Optional.of("Lumberjack V2 setup requires a paired chest.");
        }
        return Optional.empty();
    }

    private boolean hasLumberjackOptions() {
        return createFurnaceSetup
                || createShepherdSupply
                || inventoryPreset != LumberjackInventoryPreset.EMPTY_NATURAL
                || generateMatureTrees;
    }

    public int totalV1Villagers() {
        if (professionSelections == null) {
            return 0;
        }
        return professionSelections.stream()
                .filter(selection -> selection != null && selection.profession() != null && selection.profession().supportsVanillaV1())
                .mapToInt(DeveloperProfessionSelection::quantity)
                .sum();
    }

    public boolean needsInfrastructure() {
        return createFurnaceSetup;
    }

    public boolean needsInventoryPopulation() {
        return createFurnaceSetup
                || createShepherdSupply
                || inventoryPreset != LumberjackInventoryPreset.EMPTY_NATURAL;
    }
}
