package dev.sterner.guardvillagers.common.developer;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private static final Map<String, String> MORE_VILLAGERS_JOB_SITES = Map.ofEntries(
            Map.entry("morevillagers:oceanographer", "morevillagers:oceanography_table"),
            Map.entry("morevillagers:netherian", "morevillagers:decayed_workbench"),
            Map.entry("morevillagers:woodworker", "morevillagers:woodworking_table"),
            Map.entry("morevillagers:enderian", "morevillagers:purpur_altar"),
            Map.entry("morevillagers:engineer", "morevillagers:blueprint_table"),
            Map.entry("morevillagers:florist", "morevillagers:gardening_table"),
            Map.entry("morevillagers:hunter", "morevillagers:hunting_post"),
            Map.entry("morevillagers:miner", "morevillagers:mining_bench"));

    @Test
    void everySelectableVanillaProfessionKeepsItsExpectedJobSiteMapping() {
        assertEquals(VANILLA_JOB_SITES, mappings(DeveloperProfession.vanillaV1Professions()));
        assertEquals(13, DeveloperProfession.vanillaV1Professions().size());
        assertFalse(DeveloperProfession.LUMBERJACK.supportsV1());
    }

    @Test
    void allEightMoreVillagersProfessionsAppearOnlyWhenModIsAvailable() {
        assertEquals(Set.copyOf(DeveloperProfession.vanillaV1Professions()),
                Set.copyOf(DeveloperProfession.v1Professions(false)));
        assertTrue(DeveloperProfession.v1Professions(false).stream()
                .noneMatch(DeveloperProfession::requiresMoreVillagers));

        assertEquals(8, DeveloperProfession.moreVillagersV1Professions().size());
        assertTrue(DeveloperProfession.v1Professions(true)
                .containsAll(DeveloperProfession.moreVillagersV1Professions()));
        assertEquals(21, DeveloperProfession.v1Professions(true).size());
    }

    @Test
    void moreVillagersMappingsMatchTheExistingCompatibilityBridge() {
        assertEquals(MORE_VILLAGERS_JOB_SITES,
                mappings(DeveloperProfession.moreVillagersV1Professions()));
    }

    @Test
    void foresterUsesWoodworkerInternalIds() {
        assertTrue(DeveloperProfession.WOODWORKER.displayName().startsWith("Forester / Woodworker"));
        assertEquals("morevillagers:woodworker",
                DeveloperProfession.WOODWORKER.professionId().orElseThrow());
        assertEquals("morevillagers:woodworking_table",
                DeveloperProfession.WOODWORKER.jobBlockId().orElseThrow());
    }

    private static Map<String, String> mappings(java.util.List<DeveloperProfession> professions) {
        return professions.stream().collect(Collectors.toUnmodifiableMap(
                profession -> profession.professionId().orElseThrow(),
                profession -> profession.jobBlockId().orElseThrow()));
    }
}
