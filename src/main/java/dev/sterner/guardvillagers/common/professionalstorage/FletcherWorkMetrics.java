package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

import java.util.UUID;

/** Stable Fletcher career metrics and confirmed-success write paths. */
public final class FletcherWorkMetrics {
    public static final ProfessionalMetricId FLETCHING_GOODS_CRAFTED =
            new ProfessionalMetricId("fletcher.fletching_goods_crafted");
    public static final ProfessionalMetricId RANGED_WEAPONS_EQUIPPED =
            new ProfessionalMetricId("fletcher.ranged_weapons_equipped");
    public static final ProfessionalMetricId ARROWS_DELIVERED =
            new ProfessionalMetricId("fletcher.arrows_delivered");
    public static final ProfessionalMetricId STICKS_DELIVERED =
            new ProfessionalMetricId("fletcher.sticks_delivered");
    public static final ProfessionalRoleId FLETCHER_ROLE =
            new ProfessionalRoleId(Identifier.of("minecraft", "fletcher"));

    private FletcherWorkMetrics() {
    }

    public static void recordGoodsCrafted(ServerWorld world, UUID workerUuid, long outputCount) {
        record(world, workerUuid, FLETCHING_GOODS_CRAFTED, outputCount);
    }

    public static void recordRangedWeaponEquipped(ServerWorld world, UUID workerUuid) {
        record(world, workerUuid, RANGED_WEAPONS_EQUIPPED, 1L);
    }

    public static void recordArrowsDelivered(ServerWorld world, UUID workerUuid, long deliveredCount) {
        record(world, workerUuid, ARROWS_DELIVERED, deliveredCount);
    }

    public static void recordSticksDelivered(ServerWorld world, UUID workerUuid, long deliveredCount) {
        record(world, workerUuid, STICKS_DELIVERED, deliveredCount);
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
                    FLETCHER_ROLE,
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
            state.increment(workerUuid, FLETCHER_ROLE, metric, amount);
        }
    }
}
