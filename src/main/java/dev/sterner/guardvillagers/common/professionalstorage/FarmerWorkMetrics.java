package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.server.world.ServerWorld;

import java.util.UUID;

/** Stable Farmer career metrics and the narrow confirmed-mutation write path. */
public final class FarmerWorkMetrics {
    public static final ProfessionalMetricId CROPS_HARVESTED =
            new ProfessionalMetricId("farmer.crops_harvested");
    public static final ProfessionalMetricId CROPS_PLANTED =
            new ProfessionalMetricId("farmer.crops_planted");
    public static final ProfessionalMetricId GROUND_TILLED =
            new ProfessionalMetricId("farmer.ground_tilled");

    private FarmerWorkMetrics() {
    }

    public static void recordConfirmed(
            ServerWorld world,
            UUID workerUuid,
            ProfessionalRoleId role,
            ProfessionalMetricId metric,
            boolean confirmed
    ) {
        if (confirmed) {
            ProfessionalWorkStatsState.get(world.getServer()).increment(workerUuid, role, metric);
        }
    }

    static void recordConfirmed(
            ProfessionalWorkStatsState state,
            UUID workerUuid,
            ProfessionalRoleId role,
            ProfessionalMetricId metric,
            boolean confirmed
    ) {
        if (confirmed) {
            state.increment(workerUuid, role, metric);
        }
    }
}
