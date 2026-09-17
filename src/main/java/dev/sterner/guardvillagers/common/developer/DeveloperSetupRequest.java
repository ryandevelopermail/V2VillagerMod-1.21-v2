package dev.sterner.guardvillagers.common.developer;

import java.util.Optional;

public record DeveloperSetupRequest(
        DeveloperSetupType setupType,
        DeveloperProfession profession,
        boolean createPairedChest,
        boolean createCraftingTable,
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
        if (generateMatureTrees && (treeCount < 1 || treeCount > MAX_TREE_COUNT)) {
            return Optional.of("Tree count must be between 1 and " + MAX_TREE_COUNT + ".");
        }
        return Optional.empty();
    }
}
