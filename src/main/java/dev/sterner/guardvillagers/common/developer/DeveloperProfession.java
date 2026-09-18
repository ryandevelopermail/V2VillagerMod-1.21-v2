package dev.sterner.guardvillagers.common.developer;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public enum DeveloperProfession {
    LUMBERJACK(0, "Lumberjack", null),
    FARMER(1, "Farmer", "minecraft:farmer"),
    FISHERMAN(2, "Fisherman", "minecraft:fisherman"),
    FLETCHER(3, "Fletcher", "minecraft:fletcher"),
    SHEPHERD(4, "Shepherd", "minecraft:shepherd"),
    LIBRARIAN(5, "Librarian", "minecraft:librarian"),
    CARTOGRAPHER(6, "Cartographer", "minecraft:cartographer"),
    CLERIC(7, "Cleric", "minecraft:cleric"),
    ARMORER(8, "Armorer", "minecraft:armorer"),
    WEAPONSMITH(9, "Weaponsmith", "minecraft:weaponsmith"),
    TOOLSMITH(10, "Toolsmith", "minecraft:toolsmith"),
    BUTCHER(11, "Butcher", "minecraft:butcher"),
    LEATHERWORKER(12, "Leatherworker", "minecraft:leatherworker"),
    MASON(13, "Mason", "minecraft:mason");

    private static final List<DeveloperProfession> V1_PROFESSIONS = Arrays.stream(values())
            .filter(DeveloperProfession::supportsVanillaV1)
            .toList();

    private final int networkId;
    private final String displayName;
    private final String vanillaProfessionId;

    DeveloperProfession(int networkId, String displayName, String vanillaProfessionId) {
        this.networkId = networkId;
        this.displayName = displayName;
        this.vanillaProfessionId = vanillaProfessionId;
    }

    public String displayName() {
        return displayName;
    }

    public int networkId() {
        return networkId;
    }

    public boolean supportsVanillaV1() {
        return vanillaProfessionId != null;
    }

    public Optional<String> vanillaProfessionId() {
        return Optional.ofNullable(vanillaProfessionId);
    }

    public static List<DeveloperProfession> v1Professions() {
        return V1_PROFESSIONS;
    }

    public DeveloperProfession next() {
        DeveloperProfession[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public static Optional<DeveloperProfession> fromNetworkId(int id) {
        return Arrays.stream(values()).filter(value -> value.networkId() == id).findFirst();
    }
}
