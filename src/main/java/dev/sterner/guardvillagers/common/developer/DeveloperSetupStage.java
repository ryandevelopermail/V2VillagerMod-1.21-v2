package dev.sterner.guardvillagers.common.developer;

public enum DeveloperSetupStage {
    PREPARE,
    WAIT_FOR_PROFESSION,
    PLACE_V2_BLOCKS,
    WAIT_FOR_PAIRING,
    GENERATE_TREES,
    COMPLETE,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETE || this == FAILED;
    }
}
