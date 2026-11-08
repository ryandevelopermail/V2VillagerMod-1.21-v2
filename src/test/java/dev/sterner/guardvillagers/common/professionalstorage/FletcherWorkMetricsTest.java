package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FletcherWorkMetricsTest {
    private static final UUID FIRST = UUID.fromString("76000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("76000000-0000-0000-0000-000000000002");

    @Test
    void metricIdsAndRoleAreStable() {
        assertEquals("fletcher.fletching_goods_crafted", FletcherWorkMetrics.FLETCHING_GOODS_CRAFTED.value());
        assertEquals("fletcher.ranged_weapons_equipped", FletcherWorkMetrics.RANGED_WEAPONS_EQUIPPED.value());
        assertEquals("fletcher.arrows_delivered", FletcherWorkMetrics.ARROWS_DELIVERED.value());
        assertEquals("fletcher.sticks_delivered", FletcherWorkMetrics.STICKS_DELIVERED.value());
        assertEquals("minecraft:fletcher", FletcherWorkMetrics.FLETCHER_ROLE.toString());
    }

    @Test
    void allMetricIdsSurviveNbtRoundTrip() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        FletcherWorkMetrics.record(state, FIRST, FletcherWorkMetrics.FLETCHING_GOODS_CRAFTED, 4, true);
        FletcherWorkMetrics.record(state, FIRST, FletcherWorkMetrics.RANGED_WEAPONS_EQUIPPED, 2, true);
        FletcherWorkMetrics.record(state, FIRST, FletcherWorkMetrics.ARROWS_DELIVERED, 8, true);
        FletcherWorkMetrics.record(state, FIRST, FletcherWorkMetrics.STICKS_DELIVERED, 3, true);

        ProfessionalWorkStatsState restored = ProfessionalWorkStatsState.fromNbt(
                state.writeNbt(new NbtCompound(), null), null);
        assertEquals(4, restored.read(FIRST, FletcherWorkMetrics.FLETCHER_ROLE,
                FletcherWorkMetrics.FLETCHING_GOODS_CRAFTED));
        assertEquals(2, restored.read(FIRST, FletcherWorkMetrics.FLETCHER_ROLE,
                FletcherWorkMetrics.RANGED_WEAPONS_EQUIPPED));
        assertEquals(8, restored.read(FIRST, FletcherWorkMetrics.FLETCHER_ROLE,
                FletcherWorkMetrics.ARROWS_DELIVERED));
        assertEquals(3, restored.read(FIRST, FletcherWorkMetrics.FLETCHER_ROLE,
                FletcherWorkMetrics.STICKS_DELIVERED));
    }

    @Test
    void unconfirmedWritesAreIgnoredAndDistinctAggregationSaturates() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        FletcherWorkMetrics.record(state, FIRST, FletcherWorkMetrics.ARROWS_DELIVERED, 9, false);
        FletcherWorkMetrics.record(state, FIRST, FletcherWorkMetrics.ARROWS_DELIVERED, Long.MAX_VALUE - 1, true);
        FletcherWorkMetrics.record(state, SECOND, FletcherWorkMetrics.ARROWS_DELIVERED, 10, true);
        assertEquals(Long.MAX_VALUE, state.aggregate(
                List.of(FIRST, SECOND, FIRST),
                FletcherWorkMetrics.FLETCHER_ROLE,
                FletcherWorkMetrics.ARROWS_DELIVERED));
    }
}
