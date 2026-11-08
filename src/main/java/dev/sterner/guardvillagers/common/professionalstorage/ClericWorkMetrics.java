package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

import java.util.UUID;

/** Stable native-Cleric career metrics recorded only at confirmed completion boundaries. */
public final class ClericWorkMetrics {
    public static final ProfessionalMetricId POTIONS_COMPLETED =
            new ProfessionalMetricId("cleric.potions_completed");
    public static final ProfessionalMetricId BREWING_STANDS_CRAFTED =
            new ProfessionalMetricId("cleric.brewing_stands_crafted");
    public static final ProfessionalMetricId HEALING_POTIONS_THROWN =
            new ProfessionalMetricId("cleric.healing_potions_thrown");
    public static final ProfessionalMetricId POTIONS_DELIVERED =
            new ProfessionalMetricId("cleric.potions_delivered");
    public static final ProfessionalRoleId CLERIC_ROLE =
            new ProfessionalRoleId(Identifier.of("minecraft", "cleric"));

    private ClericWorkMetrics() {
    }

    public static void recordPotionsCompleted(ServerWorld world, UUID workerUuid, long amount) {
        record(world, workerUuid, POTIONS_COMPLETED, amount);
    }

    public static void recordBrewingStandsCrafted(ServerWorld world, UUID workerUuid, long amount) {
        record(world, workerUuid, BREWING_STANDS_CRAFTED, amount);
    }

    public static void recordHealingPotionsThrown(ServerWorld world, UUID workerUuid, long amount) {
        record(world, workerUuid, HEALING_POTIONS_THROWN, amount);
    }

    public static void recordPotionsDelivered(ServerWorld world, UUID workerUuid, long amount) {
        record(world, workerUuid, POTIONS_DELIVERED, amount);
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
                    CLERIC_ROLE,
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
            state.increment(workerUuid, CLERIC_ROLE, metric, amount);
        }
    }
}
