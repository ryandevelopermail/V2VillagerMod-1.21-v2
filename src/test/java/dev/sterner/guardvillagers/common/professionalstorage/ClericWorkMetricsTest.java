package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClericWorkMetricsTest {
    private static final UUID FIRST = UUID.fromString("88000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("88000000-0000-0000-0000-000000000002");

    @Test
    void metricIdsAndRoleAreStable() {
        assertEquals("cleric.potions_completed", ClericWorkMetrics.POTIONS_COMPLETED.value());
        assertEquals("cleric.brewing_stands_crafted", ClericWorkMetrics.BREWING_STANDS_CRAFTED.value());
        assertEquals("cleric.healing_potions_thrown", ClericWorkMetrics.HEALING_POTIONS_THROWN.value());
        assertEquals("cleric.potions_delivered", ClericWorkMetrics.POTIONS_DELIVERED.value());
        assertEquals("minecraft:cleric", ClericWorkMetrics.CLERIC_ROLE.toString());
    }

    @Test
    void allMetricIdsSurviveNbtRoundTrip() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        ClericWorkMetrics.record(state, FIRST, ClericWorkMetrics.POTIONS_COMPLETED, 4, true);
        ClericWorkMetrics.record(state, FIRST, ClericWorkMetrics.BREWING_STANDS_CRAFTED, 2, true);
        ClericWorkMetrics.record(state, FIRST, ClericWorkMetrics.HEALING_POTIONS_THROWN, 7, true);
        ClericWorkMetrics.record(state, FIRST, ClericWorkMetrics.POTIONS_DELIVERED, 3, true);

        NbtCompound nbt = state.writeNbt(new NbtCompound(), null);
        ProfessionalWorkStatsState restored = ProfessionalWorkStatsState.fromNbt(nbt, null);
        assertEquals(4, restored.read(FIRST, ClericWorkMetrics.CLERIC_ROLE,
                ClericWorkMetrics.POTIONS_COMPLETED));
        assertEquals(2, restored.read(FIRST, ClericWorkMetrics.CLERIC_ROLE,
                ClericWorkMetrics.BREWING_STANDS_CRAFTED));
        assertEquals(7, restored.read(FIRST, ClericWorkMetrics.CLERIC_ROLE,
                ClericWorkMetrics.HEALING_POTIONS_THROWN));
        assertEquals(3, restored.read(FIRST, ClericWorkMetrics.CLERIC_ROLE,
                ClericWorkMetrics.POTIONS_DELIVERED));
    }

    @Test
    void zeroNegativeAndUnconfirmedAmountsAreIgnored() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        ClericWorkMetrics.record(state, FIRST, ClericWorkMetrics.POTIONS_DELIVERED, 0, true);
        ClericWorkMetrics.record(state, FIRST, ClericWorkMetrics.POTIONS_DELIVERED, -3, true);
        ClericWorkMetrics.record(state, FIRST, ClericWorkMetrics.POTIONS_DELIVERED, 5, false);
        assertEquals(0, state.read(FIRST, ClericWorkMetrics.CLERIC_ROLE,
                ClericWorkMetrics.POTIONS_DELIVERED));
    }

    @Test
    void distinctAggregationSaturatesAtLongMaximum() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        ClericWorkMetrics.record(state, FIRST, ClericWorkMetrics.POTIONS_COMPLETED,
                Long.MAX_VALUE, true);
        ClericWorkMetrics.record(state, SECOND, ClericWorkMetrics.POTIONS_COMPLETED, 9, true);
        assertEquals(Long.MAX_VALUE, state.aggregate(
                List.of(FIRST, SECOND, FIRST),
                ClericWorkMetrics.CLERIC_ROLE,
                ClericWorkMetrics.POTIONS_COMPLETED));
    }
}
