package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeaponsmithWorkMetricsTest {
    private static final UUID WORKER = UUID.fromString("71000000-0000-0000-0000-000000000001");

    @Test
    void metricIdsAndRoleAreStable() {
        assertEquals("weaponsmith.weapons_crafted", WeaponsmithWorkMetrics.WEAPONS_CRAFTED.value());
        assertEquals("weaponsmith.weapons_repaired", WeaponsmithWorkMetrics.WEAPONS_REPAIRED.value());
        assertEquals("weaponsmith.weapons_equipped", WeaponsmithWorkMetrics.WEAPONS_EQUIPPED.value());
        assertEquals("minecraft:weaponsmith", WeaponsmithWorkMetrics.WEAPONSMITH_ROLE.toString());
    }

    @Test
    void allMetricIdsSurviveNbtRoundTrip() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        WeaponsmithWorkMetrics.record(state, WORKER, WeaponsmithWorkMetrics.WEAPONS_CRAFTED, 4, true);
        WeaponsmithWorkMetrics.record(state, WORKER, WeaponsmithWorkMetrics.WEAPONS_REPAIRED, 2, true);
        WeaponsmithWorkMetrics.record(state, WORKER, WeaponsmithWorkMetrics.WEAPONS_EQUIPPED, 3, true);

        ProfessionalWorkStatsState restored = ProfessionalWorkStatsState.fromNbt(
                state.writeNbt(new NbtCompound(), null), null);
        assertEquals(4, restored.read(WORKER, WeaponsmithWorkMetrics.WEAPONSMITH_ROLE, WeaponsmithWorkMetrics.WEAPONS_CRAFTED));
        assertEquals(2, restored.read(WORKER, WeaponsmithWorkMetrics.WEAPONSMITH_ROLE, WeaponsmithWorkMetrics.WEAPONS_REPAIRED));
        assertEquals(3, restored.read(WORKER, WeaponsmithWorkMetrics.WEAPONSMITH_ROLE, WeaponsmithWorkMetrics.WEAPONS_EQUIPPED));
    }

    @Test
    void unconfirmedWritesAreIgnoredAndCountersSaturate() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        WeaponsmithWorkMetrics.record(state, WORKER, WeaponsmithWorkMetrics.WEAPONS_EQUIPPED, 9, false);
        WeaponsmithWorkMetrics.record(state, WORKER, WeaponsmithWorkMetrics.WEAPONS_EQUIPPED, Long.MAX_VALUE - 1, true);
        WeaponsmithWorkMetrics.record(state, WORKER, WeaponsmithWorkMetrics.WEAPONS_EQUIPPED, 10, true);
        assertEquals(Long.MAX_VALUE, state.read(
                WORKER, WeaponsmithWorkMetrics.WEAPONSMITH_ROLE, WeaponsmithWorkMetrics.WEAPONS_EQUIPPED));
    }
}
