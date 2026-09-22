package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

import java.util.UUID;

/** Stable native-Cartographer career metrics recorded only at confirmed completion boundaries. */
public final class CartographerWorkMetrics {
    public static final ProfessionalMetricId MAPS_COMPLETED =
            new ProfessionalMetricId("cartographer.maps_completed");
    public static final ProfessionalMetricId MAPS_COPIED =
            new ProfessionalMetricId("cartographer.maps_copied");
    public static final ProfessionalMetricId MATERIALS_CRAFTED =
            new ProfessionalMetricId("cartographer.materials_crafted");
    public static final ProfessionalMetricId MAPS_DISPLAYED =
            new ProfessionalMetricId("cartographer.maps_displayed");
    public static final ProfessionalRoleId CARTOGRAPHER_ROLE =
            new ProfessionalRoleId(Identifier.of("minecraft", "cartographer"));

    private CartographerWorkMetrics() {
    }

    public static void recordMapsCompleted(ServerWorld world, UUID workerUuid, long amount) {
        record(world, workerUuid, MAPS_COMPLETED, amount);
    }

    public static void recordMapsCopied(ServerWorld world, UUID workerUuid, long amount) {
        record(world, workerUuid, MAPS_COPIED, amount);
    }

    public static void recordMaterialsCrafted(ServerWorld world, UUID workerUuid, long amount) {
        record(world, workerUuid, MATERIALS_CRAFTED, amount);
    }

    public static void recordMapsDisplayed(ServerWorld world, UUID workerUuid, long amount) {
        record(world, workerUuid, MAPS_DISPLAYED, amount);
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
                    CARTOGRAPHER_ROLE,
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
            state.increment(workerUuid, CARTOGRAPHER_ROLE, metric, amount);
        }
    }
}
