package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

import java.util.UUID;

/** Stable Armorer career metrics recorded only at confirmed work boundaries. */
public final class ArmorerWorkMetrics {
    public static final ProfessionalMetricId ARMOR_CRAFTED =
            new ProfessionalMetricId("armorer.armor_crafted");
    public static final ProfessionalMetricId SMELTED_OUTPUT_COLLECTED =
            new ProfessionalMetricId("armorer.smelted_output_collected");
    public static final ProfessionalMetricId ARMOR_EQUIPPED =
            new ProfessionalMetricId("armorer.armor_equipped");
    public static final ProfessionalRoleId ARMORER_ROLE =
            new ProfessionalRoleId(Identifier.of("minecraft", "armorer"));

    private ArmorerWorkMetrics() {
    }

    public static void recordArmorCrafted(ServerWorld world, UUID workerUuid, long outputCount) {
        record(world, workerUuid, ARMOR_CRAFTED, outputCount);
    }

    public static void recordSmeltedOutputCollected(ServerWorld world, UUID workerUuid, long movedCount) {
        record(world, workerUuid, SMELTED_OUTPUT_COLLECTED, movedCount);
    }

    public static void recordArmorEquipped(ServerWorld world, UUID workerUuid) {
        record(world, workerUuid, ARMOR_EQUIPPED, 1L);
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
                    ARMORER_ROLE,
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
            state.increment(workerUuid, ARMORER_ROLE, metric, amount);
        }
    }
}
