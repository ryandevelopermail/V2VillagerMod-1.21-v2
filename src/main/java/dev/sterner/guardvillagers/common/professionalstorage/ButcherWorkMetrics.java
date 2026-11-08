package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

import java.util.UUID;

/** Stable native-Butcher career metrics recorded at confirmed completion boundaries. */
public final class ButcherWorkMetrics {
    public static final ProfessionalMetricId COOKED_FOOD_COLLECTED =
            new ProfessionalMetricId("butcher.cooked_food_collected");
    public static final ProfessionalMetricId SMOKERS_CRAFTED =
            new ProfessionalMetricId("butcher.smokers_crafted");
    public static final ProfessionalMetricId MEALS_DELIVERED =
            new ProfessionalMetricId("butcher.meals_delivered");
    public static final ProfessionalMetricId LEATHER_HIDE_DELIVERED =
            new ProfessionalMetricId("butcher.leather_hide_delivered");
    public static final ProfessionalRoleId BUTCHER_ROLE =
            new ProfessionalRoleId(Identifier.of("minecraft", "butcher"));

    private ButcherWorkMetrics() {
    }

    public static void recordCookedFoodCollected(ServerWorld world, UUID workerUuid, long amount) {
        record(world, workerUuid, COOKED_FOOD_COLLECTED, amount);
    }

    public static void recordSmokersCrafted(ServerWorld world, UUID workerUuid, long amount) {
        record(world, workerUuid, SMOKERS_CRAFTED, amount);
    }

    public static void recordMealsDelivered(ServerWorld world, UUID workerUuid, long amount) {
        record(world, workerUuid, MEALS_DELIVERED, amount);
    }

    public static void recordLeatherHideDelivered(ServerWorld world, UUID workerUuid, long amount) {
        record(world, workerUuid, LEATHER_HIDE_DELIVERED, amount);
    }

    private static void record(
            ServerWorld world,
            UUID workerUuid,
            ProfessionalMetricId metric,
            long amount
    ) {
        if (amount > 0L) {
            ProfessionalWorkStatsState.get(world.getServer()).increment(
                    workerUuid,
                    BUTCHER_ROLE,
                    metric,
                    amount);
        }
    }

    static void record(
            ProfessionalWorkStatsState state,
            UUID workerUuid,
            ProfessionalMetricId metric,
            long amount,
            boolean confirmedSuccess
    ) {
        if (confirmedSuccess && amount > 0L) {
            state.increment(workerUuid, BUTCHER_ROLE, metric, amount);
        }
    }
}
