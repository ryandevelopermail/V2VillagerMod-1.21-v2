package dev.sterner.guardvillagers.common.developer;

import java.util.Arrays;
import java.util.Optional;

public enum DeveloperSetupType {
    PLAIN_VILLAGER("Plain Villager"),
    V1_PROFESSION("V1 Profession"),
    V2_PROFESSION("V2 Profession");

    private final String displayName;

    DeveloperSetupType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public int networkId() {
        return ordinal();
    }

    public DeveloperSetupType next() {
        DeveloperSetupType[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public static Optional<DeveloperSetupType> fromNetworkId(int id) {
        return Arrays.stream(values()).filter(value -> value.networkId() == id).findFirst();
    }
}
