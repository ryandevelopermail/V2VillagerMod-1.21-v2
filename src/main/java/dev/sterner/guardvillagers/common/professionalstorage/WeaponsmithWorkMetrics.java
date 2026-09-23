package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

import java.util.UUID;

/** Stable Weaponsmith career metrics and confirmed-success write paths. */
public final class WeaponsmithWorkMetrics {
    public static final ProfessionalMetricId WEAPONS_CRAFTED =
            new ProfessionalMetricId("weaponsmith.weapons_crafted");
    public static final ProfessionalMetricId WEAPONS_REPAIRED =
            new ProfessionalMetricId("weaponsmith.weapons_repaired");
    public static final ProfessionalMetricId WEAPONS_EQUIPPED =
            new ProfessionalMetricId("weaponsmith.weapons_equipped");
    public static final ProfessionalRoleId WEAPONSMITH_ROLE =
            new ProfessionalRoleId(Identifier.of("minecraft", "weaponsmith"));

    private WeaponsmithWorkMetrics() {
    }

    public static void recordWeaponsCrafted(ServerWorld world, UUID workerUuid, long outputCount) {
        record(world, workerUuid, WEAPONS_CRAFTED, outputCount);
    }

    public static void recordWeaponRepaired(ServerWorld world, UUID workerUuid) {
        record(world, workerUuid, WEAPONS_REPAIRED, 1L);
    }

    public static void recordWeaponsEquipped(ServerWorld world, UUID workerUuid, long equippedCount) {
        record(world, workerUuid, WEAPONS_EQUIPPED, equippedCount);
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
                    WEAPONSMITH_ROLE,
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
            state.increment(workerUuid, WEAPONSMITH_ROLE, metric, amount);
        }
    }
}
