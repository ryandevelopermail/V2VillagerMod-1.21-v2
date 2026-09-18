package dev.sterner.guardvillagers.common.developer;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DeveloperProfessionCatalogTest {
    private static final Map<String, String> VANILLA_JOB_SITES = Map.ofEntries(
            Map.entry("minecraft:farmer", "minecraft:composter"),
            Map.entry("minecraft:fisherman", "minecraft:barrel"),
            Map.entry("minecraft:fletcher", "minecraft:fletching_table"),
            Map.entry("minecraft:shepherd", "minecraft:loom"),
            Map.entry("minecraft:librarian", "minecraft:lectern"),
            Map.entry("minecraft:cartographer", "minecraft:cartography_table"),
            Map.entry("minecraft:cleric", "minecraft:brewing_stand"),
            Map.entry("minecraft:armorer", "minecraft:blast_furnace"),
            Map.entry("minecraft:weaponsmith", "minecraft:grindstone"),
            Map.entry("minecraft:toolsmith", "minecraft:smithing_table"),
            Map.entry("minecraft:butcher", "minecraft:smoker"),
            Map.entry("minecraft:leatherworker", "minecraft:cauldron"),
            Map.entry("minecraft:mason", "minecraft:stonecutter"));

    @Test
    void everySelectableV1ProfessionHasTheExpectedVanillaJobSiteMapping() {
        Set<String> selectableIds = DeveloperProfession.v1Professions().stream()
                .map(profession -> profession.vanillaProfessionId().orElseThrow())
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(VANILLA_JOB_SITES.keySet(), selectableIds);
        assertEquals(13, VANILLA_JOB_SITES.values().stream().distinct().count());
        assertFalse(DeveloperProfession.LUMBERJACK.supportsVanillaV1());
    }
}
