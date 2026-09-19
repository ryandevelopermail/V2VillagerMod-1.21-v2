package dev.sterner.guardvillagers.common.developer;

import net.fabricmc.loader.api.FabricLoader;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public enum DeveloperProfession {
    LUMBERJACK(0, "Lumberjack", null, null, false),
    FARMER(1, "Farmer", "minecraft:farmer", "minecraft:composter", false),
    FISHERMAN(2, "Fisherman", "minecraft:fisherman", "minecraft:barrel", false),
    FLETCHER(3, "Fletcher", "minecraft:fletcher", "minecraft:fletching_table", false),
    SHEPHERD(4, "Shepherd", "minecraft:shepherd", "minecraft:loom", false),
    LIBRARIAN(5, "Librarian", "minecraft:librarian", "minecraft:lectern", false),
    CARTOGRAPHER(6, "Cartographer", "minecraft:cartographer", "minecraft:cartography_table", false),
    CLERIC(7, "Cleric", "minecraft:cleric", "minecraft:brewing_stand", false),
    ARMORER(8, "Armorer", "minecraft:armorer", "minecraft:blast_furnace", false),
    WEAPONSMITH(9, "Weaponsmith", "minecraft:weaponsmith", "minecraft:grindstone", false),
    TOOLSMITH(10, "Toolsmith", "minecraft:toolsmith", "minecraft:smithing_table", false),
    BUTCHER(11, "Butcher", "minecraft:butcher", "minecraft:smoker", false),
    LEATHERWORKER(12, "Leatherworker", "minecraft:leatherworker", "minecraft:cauldron", false),
    MASON(13, "Mason", "minecraft:mason", "minecraft:stonecutter", false),
    OCEANOGRAPHER(14, "Oceanographer", "morevillagers:oceanographer", "morevillagers:oceanography_table", true),
    NETHERIAN(15, "Netherian", "morevillagers:netherian", "morevillagers:decayed_workbench", true),
    WOODWORKER(16, "Forester / Woodworker", "morevillagers:woodworker", "morevillagers:woodworking_table", true),
    ENDERIAN(17, "Enderian", "morevillagers:enderian", "morevillagers:purpur_altar", true),
    ENGINEER(18, "Engineer", "morevillagers:engineer", "morevillagers:blueprint_table", true),
    FLORIST(19, "Florist", "morevillagers:florist", "morevillagers:gardening_table", true),
    HUNTER(20, "Hunter", "morevillagers:hunter", "morevillagers:hunting_post", true),
    MINER(21, "Miner", "morevillagers:miner", "morevillagers:mining_bench", true);

    public static final String MORE_VILLAGERS_MOD_ID = "morevillagers";

    private static final List<DeveloperProfession> VANILLA_V1_PROFESSIONS = Arrays.stream(values())
            .filter(DeveloperProfession::supportsVanillaV1)
            .toList();
    private static final List<DeveloperProfession> MORE_VILLAGERS_V1_PROFESSIONS = Arrays.stream(values())
            .filter(DeveloperProfession::requiresMoreVillagers)
            .toList();
    private static final List<DeveloperProfession> ALL_V1_PROFESSIONS = Arrays.stream(values())
            .filter(DeveloperProfession::supportsV1)
            .toList();

    private final int networkId;
    private final String displayName;
    private final String professionId;
    private final String jobBlockId;
    private final boolean requiresMoreVillagers;

    DeveloperProfession(
            int networkId,
            String displayName,
            String professionId,
            String jobBlockId,
            boolean requiresMoreVillagers
    ) {
        this.networkId = networkId;
        this.displayName = displayName;
        this.professionId = professionId;
        this.jobBlockId = jobBlockId;
        this.requiresMoreVillagers = requiresMoreVillagers;
    }

    public String displayName() {
        return displayName;
    }

    public int networkId() {
        return networkId;
    }

    public boolean supportsV1() {
        return professionId != null && jobBlockId != null;
    }

    public boolean supportsVanillaV1() {
        return supportsV1() && !requiresMoreVillagers;
    }

    public boolean requiresMoreVillagers() {
        return requiresMoreVillagers;
    }

    public Optional<String> professionId() {
        return Optional.ofNullable(professionId);
    }

    public Optional<String> vanillaProfessionId() {
        return supportsVanillaV1() ? professionId() : Optional.empty();
    }

    public Optional<String> jobBlockId() {
        return Optional.ofNullable(jobBlockId);
    }

    public static List<DeveloperProfession> v1Professions() {
        return v1Professions(FabricLoader.getInstance().isModLoaded(MORE_VILLAGERS_MOD_ID));
    }

    static List<DeveloperProfession> v1Professions(boolean moreVillagersLoaded) {
        return moreVillagersLoaded ? ALL_V1_PROFESSIONS : VANILLA_V1_PROFESSIONS;
    }

    static List<DeveloperProfession> vanillaV1Professions() {
        return VANILLA_V1_PROFESSIONS;
    }

    static List<DeveloperProfession> moreVillagersV1Professions() {
        return MORE_VILLAGERS_V1_PROFESSIONS;
    }

    public DeveloperProfession next() {
        DeveloperProfession[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public static Optional<DeveloperProfession> fromNetworkId(int id) {
        return Arrays.stream(values()).filter(value -> value.networkId() == id).findFirst();
    }
}
