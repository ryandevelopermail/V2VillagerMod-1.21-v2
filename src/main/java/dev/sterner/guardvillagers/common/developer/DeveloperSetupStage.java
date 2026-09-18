package dev.sterner.guardvillagers.common.developer;

public enum DeveloperSetupStage {
    PREPARE,
    WAIT_FOR_PROFESSION,
    PLACE_V2_BLOCKS,
    WAIT_FOR_PAIRING,
    PLACE_LUMBERJACK_INFRASTRUCTURE,
    POPULATE_LUMBERJACK_INVENTORY,
    GENERATE_TREES,
    COMPLETE,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETE || this == FAILED;
    }
}
