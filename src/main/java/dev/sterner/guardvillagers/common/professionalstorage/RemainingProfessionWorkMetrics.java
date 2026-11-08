package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.UUID;

/** Career counters for the remaining storage profiles, updated at completed work boundaries. */
public final class RemainingProfessionWorkMetrics {
    public static final ProfessionalRoleId SHEPHERD = role("shepherd");
    public static final ProfessionalRoleId LIBRARIAN = role("librarian");
    public static final ProfessionalRoleId MASON = role("mason");
    public static final ProfessionalMetricId SHEEP_SHEARED = id("shepherd.sheep_sheared");
    public static final ProfessionalMetricId SHEPHERD_CRAFTED = id("shepherd.items_crafted");
    public static final ProfessionalMetricId BEDS_PLACED = id("shepherd.beds_placed");
    public static final ProfessionalMetricId SHEPHERD_DELIVERED = id("shepherd.items_delivered");
    public static final ProfessionalMetricId LIBRARIAN_CRAFTED = id("librarian.items_crafted");
    public static final ProfessionalMetricId LIBRARIAN_DELIVERED = id("librarian.items_delivered");
    public static final ProfessionalMetricId MASON_TOOLS = id("mason.tools_crafted");
    public static final ProfessionalMetricId MASON_CUT = id("mason.stonecut_output");
    public static final ProfessionalMetricId MASON_DELIVERED = id("mason.items_delivered");
    public static final ProfessionalMetricId MASON_MINED = id("mason.blocks_mined");
    public static final ProfessionalMetricId MASON_WALLS = id("mason.wall_blocks_placed");
    public static final ProfessionalMetricId LUMBERJACK_LOGS = id("lumberjack.logs_harvested");
    public static final ProfessionalMetricId BUTCHER_GUARD_HUNTED = id("butcher_guard.animals_hunted");
    public static final ProfessionalMetricId BUTCHER_GUARD_LOOT = id("butcher_guard.loot_stored");
    public static final ProfessionalMetricId LUMBERJACK_STORED = id("lumberjack.items_stored");
    public static final ProfessionalMetricId LUMBERJACK_DELIVERED = id("lumberjack.items_delivered");
    public static final ProfessionalMetricId QUARTERMASTER_DELIVERED = id("quartermaster.items_delivered");

    private RemainingProfessionWorkMetrics() { }

    private static ProfessionalRoleId role(String path) {
        return new ProfessionalRoleId(Identifier.of("minecraft", path));
    }

    private static ProfessionalMetricId id(String value) {
        return new ProfessionalMetricId(value);
    }

    public static void record(ServerWorld world, UUID worker, ProfessionalRoleId role,
                              ProfessionalMetricId metric, long amount) {
        if (amount > 0 && world.getServer() != null) {
            ProfessionalWorkStatsState.get(world.getServer()).increment(worker, role, metric, amount);
        }
    }

    /** Retain the native worker's applicable career totals when conversion succeeds. */
    public static void transferMason(ServerWorld world, UUID villager, UUID guard) {
        ProfessionalWorkStatsState.get(world.getServer()).transferMetrics(villager, MASON, guard,
                ProfessionalRoleId.MASON_GUARD, List.of(MASON_CUT, MASON_DELIVERED, MASON_TOOLS));
    }

    public static void transferButcher(ServerWorld world, UUID villager, UUID guard) {
        ProfessionalWorkStatsState.get(world.getServer()).transferMetrics(villager, ButcherWorkMetrics.BUTCHER_ROLE,
                guard, ProfessionalRoleId.BUTCHER_GUARD, List.of(ButcherWorkMetrics.COOKED_FOOD_COLLECTED,
                        ButcherWorkMetrics.SMOKERS_CRAFTED, ButcherWorkMetrics.MEALS_DELIVERED,
                        ButcherWorkMetrics.LEATHER_HIDE_DELIVERED));
    }
}
