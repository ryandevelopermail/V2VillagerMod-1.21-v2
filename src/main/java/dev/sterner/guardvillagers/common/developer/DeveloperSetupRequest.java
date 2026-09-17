package dev.sterner.guardvillagers.common.developer;

import java.util.Optional;

public record DeveloperSetupRequest(
        DeveloperSetupType setupType,
        DeveloperProfession profession,
        boolean createPairedChest,
        boolean createCraftingTable,
        boolean createFurnaceSetup,
        boolean createPenSetup,
        LumberjackInventoryPreset inventoryPreset,
        boolean generateMatureTrees,
        int treeCount
) {
    public static final int DEFAULT_TREE_COUNT = 4;
    public static final int MAX_TREE_COUNT = 12;

    public Optional<String> validationError() {
        if (setupType == null) {
            return Optional.of("Unknown setup type.");
        }
        if (profession == null) {
            return Optional.of("Unknown profession.");
        }
        if (inventoryPreset == null) {
            return Optional.of("Unknown inventory preset.");
        }
        if (setupType == DeveloperSetupType.PLAIN_VILLAGER && (createPairedChest || createCraftingTable)) {
            return Optional.of("Plain Villager setup cannot force job or pairing blocks.");
        }
        if (setupType != DeveloperSetupType.PLAIN_VILLAGER && !createCraftingTable) {
            return Optional.of("Lumberjack V1/V2 setup requires a crafting table.");
        }
        if (setupType == DeveloperSetupType.V1_PROFESSION && createPairedChest) {
            return Optional.of("V1 setup cannot create a paired chest.");
        }
        if (setupType == DeveloperSetupType.V2_PROFESSION && !createPairedChest) {
            return Optional.of("Lumberjack V2 setup requires a paired chest.");
        }
        if (setupType != DeveloperSetupType.V2_PROFESSION
                && (createFurnaceSetup || createPenSetup || inventoryPreset != LumberjackInventoryPreset.EMPTY_NATURAL)) {
            return Optional.of("Lumberjack infrastructure and inventory presets require a V2 paired chest.");
        }
        if (generateMatureTrees && (treeCount < 1 || treeCount > MAX_TREE_COUNT)) {
            return Optional.of("Tree count must be between 1 and " + MAX_TREE_COUNT + ".");
        }
        return Optional.empty();
    }

    public boolean needsInfrastructure() {
        return createFurnaceSetup;
    }

    public boolean needsInventoryPopulation() {
        return createFurnaceSetup
                || createPenSetup
                || inventoryPreset != LumberjackInventoryPreset.EMPTY_NATURAL;
    }
}
