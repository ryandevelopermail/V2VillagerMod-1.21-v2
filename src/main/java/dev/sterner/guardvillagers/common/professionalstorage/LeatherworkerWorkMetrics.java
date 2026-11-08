package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

import java.util.UUID;

/** Stable Leatherworker career metrics and confirmed-success write paths. */
public final class LeatherworkerWorkMetrics {
    public static final ProfessionalMetricId LEATHER_GOODS_CRAFTED =
            new ProfessionalMetricId("leatherworker.leather_goods_crafted");
    public static final ProfessionalMetricId GOODS_DELIVERED =
            new ProfessionalMetricId("leatherworker.goods_delivered");
    public static final ProfessionalRoleId LEATHERWORKER_ROLE =
            new ProfessionalRoleId(Identifier.of("minecraft", "leatherworker"));

    private LeatherworkerWorkMetrics() {
    }

    public static void recordGoodsCrafted(ServerWorld world, UUID workerUuid, long outputCount) {
        record(world, workerUuid, LEATHER_GOODS_CRAFTED, outputCount);
    }

    public static void recordGoodsDelivered(ServerWorld world, UUID workerUuid, long deliveredCount) {
        record(world, workerUuid, GOODS_DELIVERED, deliveredCount);
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
                    LEATHERWORKER_ROLE,
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
            state.increment(workerUuid, LEATHERWORKER_ROLE, metric, amount);
        }
    }
}
