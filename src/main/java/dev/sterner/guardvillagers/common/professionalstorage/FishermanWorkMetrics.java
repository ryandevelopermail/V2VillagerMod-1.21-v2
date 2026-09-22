package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

import java.util.UUID;

/** Stable native-Fisherman career metrics recorded only after confirmed completion. */
public final class FishermanWorkMetrics {
    public static final ProfessionalMetricId FISHING_RODS_CRAFTED =
            new ProfessionalMetricId("fisherman.fishing_rods_crafted");
    public static final ProfessionalMetricId BUCKETS_CRAFTED =
            new ProfessionalMetricId("fisherman.buckets_crafted");
    public static final ProfessionalMetricId BOATS_CRAFTED =
            new ProfessionalMetricId("fisherman.boats_crafted");
    public static final ProfessionalMetricId FISH_DELIVERED =
            new ProfessionalMetricId("fisherman.fish_delivered");
    public static final ProfessionalRoleId FISHERMAN_ROLE =
            new ProfessionalRoleId(Identifier.of("minecraft", "fisherman"));

    private FishermanWorkMetrics() {
    }

    public static void recordFishingRodsCrafted(ServerWorld world, UUID workerUuid, long amount) {
        record(world, workerUuid, FISHING_RODS_CRAFTED, amount);
    }

    public static void recordBucketsCrafted(ServerWorld world, UUID workerUuid, long amount) {
        record(world, workerUuid, BUCKETS_CRAFTED, amount);
    }

    public static void recordBoatsCrafted(ServerWorld world, UUID workerUuid, long amount) {
        record(world, workerUuid, BOATS_CRAFTED, amount);
    }

    public static void recordFishDelivered(ServerWorld world, UUID workerUuid, long amount) {
        record(world, workerUuid, FISH_DELIVERED, amount);
    }

    private static void record(
            ServerWorld world,
            UUID workerUuid,
            ProfessionalMetricId metric,
            long amount
    ) {
        if (amount > 0L) {
            ProfessionalWorkStatsState.get(world.getServer()).increment(workerUuid, FISHERMAN_ROLE, metric, amount);
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
            state.increment(workerUuid, FISHERMAN_ROLE, metric, amount);
        }
    }
}
