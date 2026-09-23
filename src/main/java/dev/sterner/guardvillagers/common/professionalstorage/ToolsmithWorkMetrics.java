package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

import java.util.UUID;

/** Stable Toolsmith career metrics and confirmed-success write paths. */
public final class ToolsmithWorkMetrics {
    public static final ProfessionalMetricId TOOLS_CRAFTED =
            new ProfessionalMetricId("toolsmith.tools_crafted");
    public static final ProfessionalMetricId TOOLS_DISTRIBUTED =
            new ProfessionalMetricId("toolsmith.tools_distributed");
    public static final ProfessionalMetricId SMITHING_JOBS_COMPLETED =
            new ProfessionalMetricId("toolsmith.smithing_jobs_completed");

    public static final ProfessionalRoleId TOOLSMITH_ROLE =
            new ProfessionalRoleId(Identifier.of("minecraft", "toolsmith"));

    private ToolsmithWorkMetrics() {
    }

    public static void recordToolsCrafted(ServerWorld world, UUID workerUuid, long outputCount) {
        record(world, workerUuid, TOOLS_CRAFTED, outputCount);
    }

    public static void recordSmithingJobCompleted(ServerWorld world, UUID workerUuid) {
        record(world, workerUuid, SMITHING_JOBS_COMPLETED, 1L);
    }

    public static void recordToolsDistributed(ServerWorld world, UUID workerUuid, long deliveredCount) {
        record(world, workerUuid, TOOLS_DISTRIBUTED, deliveredCount);
    }

    private static void record(
            ServerWorld world,
            UUID workerUuid,
            ProfessionalMetricId metric,
            long amount
    ) {
        if (amount <= 0L) {
            return;
        }
        ProfessionalWorkStatsState.get(world.getServer()).increment(
                workerUuid,
                TOOLSMITH_ROLE,
                metric,
                amount);
    }

    static void record(
            ProfessionalWorkStatsState state,
            UUID workerUuid,
            ProfessionalMetricId metric,
            long amount,
            boolean confirmedSuccess
    ) {
        if (confirmedSuccess && amount > 0L) {
            state.increment(workerUuid, TOOLSMITH_ROLE, metric, amount);
        }
    }
}
