package dev.sterner.guardvillagers.common.developer;

import java.util.Arrays;
import java.util.Optional;

public enum DeveloperProfession {
    LUMBERJACK("Lumberjack");

    private final String displayName;

    DeveloperProfession(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public int networkId() {
        return ordinal();
    }

    public DeveloperProfession next() {
        DeveloperProfession[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public static Optional<DeveloperProfession> fromNetworkId(int id) {
        return Arrays.stream(values()).filter(value -> value.networkId() == id).findFirst();
    }
}
