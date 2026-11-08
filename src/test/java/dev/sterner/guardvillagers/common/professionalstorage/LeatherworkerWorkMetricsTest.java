package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeatherworkerWorkMetricsTest {
    private static final UUID FIRST = UUID.fromString("78000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("78000000-0000-0000-0000-000000000002");

    @Test
    void metricIdsAndRoleAreStable() {
        assertEquals("leatherworker.leather_goods_crafted", LeatherworkerWorkMetrics.LEATHER_GOODS_CRAFTED.value());
        assertEquals("leatherworker.goods_delivered", LeatherworkerWorkMetrics.GOODS_DELIVERED.value());
        assertEquals("minecraft:leatherworker", LeatherworkerWorkMetrics.LEATHERWORKER_ROLE.toString());
    }

    @Test
    void bothMetricIdsSurviveNbtRoundTrip() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        LeatherworkerWorkMetrics.record(
                state, FIRST, LeatherworkerWorkMetrics.LEATHER_GOODS_CRAFTED, 4, true);
        LeatherworkerWorkMetrics.record(
                state, FIRST, LeatherworkerWorkMetrics.GOODS_DELIVERED, 7, true);

        ProfessionalWorkStatsState restored = ProfessionalWorkStatsState.fromNbt(
                state.writeNbt(new NbtCompound(), null), null);
        assertEquals(4, restored.read(
                FIRST,
                LeatherworkerWorkMetrics.LEATHERWORKER_ROLE,
                LeatherworkerWorkMetrics.LEATHER_GOODS_CRAFTED));
        assertEquals(7, restored.read(
                FIRST,
                LeatherworkerWorkMetrics.LEATHERWORKER_ROLE,
                LeatherworkerWorkMetrics.GOODS_DELIVERED));
    }

    @Test
    void unconfirmedWritesAreIgnoredAndDistinctAggregationSaturates() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        LeatherworkerWorkMetrics.record(
                state, FIRST, LeatherworkerWorkMetrics.GOODS_DELIVERED, 9, false);
        LeatherworkerWorkMetrics.record(
                state, FIRST, LeatherworkerWorkMetrics.GOODS_DELIVERED, Long.MAX_VALUE - 1, true);
        LeatherworkerWorkMetrics.record(
                state, SECOND, LeatherworkerWorkMetrics.GOODS_DELIVERED, 10, true);
        assertEquals(Long.MAX_VALUE, state.aggregate(
                List.of(FIRST, SECOND, FIRST),
                LeatherworkerWorkMetrics.LEATHERWORKER_ROLE,
                LeatherworkerWorkMetrics.GOODS_DELIVERED));
    }
}
