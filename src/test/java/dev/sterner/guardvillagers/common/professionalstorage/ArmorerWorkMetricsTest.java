package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArmorerWorkMetricsTest {
    private static final UUID FIRST = UUID.fromString("82000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("82000000-0000-0000-0000-000000000002");

    @Test
    void metricIdsAndRoleAreStable() {
        assertEquals("armorer.armor_crafted", ArmorerWorkMetrics.ARMOR_CRAFTED.value());
        assertEquals("armorer.smelted_output_collected", ArmorerWorkMetrics.SMELTED_OUTPUT_COLLECTED.value());
        assertEquals("armorer.armor_equipped", ArmorerWorkMetrics.ARMOR_EQUIPPED.value());
        assertEquals("minecraft:armorer", ArmorerWorkMetrics.ARMORER_ROLE.toString());
    }

    @Test
    void allMetricIdsSurviveNbtRoundTrip() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        ArmorerWorkMetrics.record(state, FIRST, ArmorerWorkMetrics.ARMOR_CRAFTED, 4, true);
        ArmorerWorkMetrics.record(state, FIRST, ArmorerWorkMetrics.SMELTED_OUTPUT_COLLECTED, 7, true);
        ArmorerWorkMetrics.record(state, FIRST, ArmorerWorkMetrics.ARMOR_EQUIPPED, 2, true);

        NbtCompound nbt = state.writeNbt(new NbtCompound(), null);
        ProfessionalWorkStatsState restored = ProfessionalWorkStatsState.fromNbt(nbt, null);
        assertEquals(4, restored.read(FIRST, ArmorerWorkMetrics.ARMORER_ROLE, ArmorerWorkMetrics.ARMOR_CRAFTED));
        assertEquals(7, restored.read(
                FIRST,
                ArmorerWorkMetrics.ARMORER_ROLE,
                ArmorerWorkMetrics.SMELTED_OUTPUT_COLLECTED));
        assertEquals(2, restored.read(FIRST, ArmorerWorkMetrics.ARMORER_ROLE, ArmorerWorkMetrics.ARMOR_EQUIPPED));
    }

    @Test
    void zeroNegativeAndUnconfirmedAmountsAreIgnored() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        ArmorerWorkMetrics.record(state, FIRST, ArmorerWorkMetrics.ARMOR_CRAFTED, 0, true);
        ArmorerWorkMetrics.record(state, FIRST, ArmorerWorkMetrics.ARMOR_CRAFTED, -3, true);
        ArmorerWorkMetrics.record(state, FIRST, ArmorerWorkMetrics.ARMOR_CRAFTED, 5, false);
        assertEquals(0, state.read(FIRST, ArmorerWorkMetrics.ARMORER_ROLE, ArmorerWorkMetrics.ARMOR_CRAFTED));
    }

    @Test
    void distinctAggregationSaturatesAtLongMaximum() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        ArmorerWorkMetrics.record(
                state,
                FIRST,
                ArmorerWorkMetrics.SMELTED_OUTPUT_COLLECTED,
                Long.MAX_VALUE,
                true);
        ArmorerWorkMetrics.record(
                state,
                SECOND,
                ArmorerWorkMetrics.SMELTED_OUTPUT_COLLECTED,
                9,
                true);
        assertEquals(Long.MAX_VALUE, state.aggregate(
                List.of(FIRST, SECOND, FIRST),
                ArmorerWorkMetrics.ARMORER_ROLE,
                ArmorerWorkMetrics.SMELTED_OUTPUT_COLLECTED));
    }
}
