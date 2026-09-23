package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

import java.util.UUID;

/** Stable Farmer career metrics and the narrow confirmed-mutation write path. */
public final class FarmerWorkMetrics {
    private static final ProfessionalRoleId FARMER_ROLE =
            new ProfessionalRoleId(Identifier.of("minecraft", "farmer"));
    public static final ProfessionalMetricId CROPS_HARVESTED =
            new ProfessionalMetricId("farmer.crops_harvested");
    public static final ProfessionalMetricId CROPS_PLANTED =
            new ProfessionalMetricId("farmer.crops_planted");
    public static final ProfessionalMetricId GROUND_TILLED =
            new ProfessionalMetricId("farmer.ground_tilled");
    public static final ProfessionalMetricId MATERIALS_CRAFTED =
            new ProfessionalMetricId("farmer.materials_crafted");

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

    public static void recordMaterialsCrafted(ServerWorld world, UUID workerUuid, long outputCount) {
        if (outputCount <= 0L) {
            return;
        }
        ProfessionalWorkStatsState.get(world.getServer()).increment(
                workerUuid,
                FARMER_ROLE,
                MATERIALS_CRAFTED,
                outputCount);
    }

    static void recordMaterialsCrafted(
            ProfessionalWorkStatsState state,
            UUID workerUuid,
            long outputCount
    ) {
        recordMaterialsCrafted(state, workerUuid, outputCount, true);
    }

    static void recordMaterialsCrafted(
            ProfessionalWorkStatsState state,
            UUID workerUuid,
            long outputCount,
            boolean confirmedSuccess
    ) {
        if (!confirmedSuccess || outputCount <= 0L) {
            return;
        }
        state.increment(
                workerUuid,
                FARMER_ROLE,
                MATERIALS_CRAFTED,
                outputCount);
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
