package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButcherWorkMetricsTest {
    private static final UUID FIRST = UUID.fromString("86000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("86000000-0000-0000-0000-000000000002");

    @Test
    void metricIdsAndRoleAreStable() {
        assertEquals("butcher.cooked_food_collected", ButcherWorkMetrics.COOKED_FOOD_COLLECTED.value());
        assertEquals("butcher.smokers_crafted", ButcherWorkMetrics.SMOKERS_CRAFTED.value());
        assertEquals("butcher.meals_delivered", ButcherWorkMetrics.MEALS_DELIVERED.value());
        assertEquals("butcher.leather_hide_delivered", ButcherWorkMetrics.LEATHER_HIDE_DELIVERED.value());
        assertEquals("minecraft:butcher", ButcherWorkMetrics.BUTCHER_ROLE.toString());
    }

    @Test
    void allMetricIdsSurviveNbtRoundTrip() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        ButcherWorkMetrics.record(state, FIRST, ButcherWorkMetrics.COOKED_FOOD_COLLECTED, 4, true);
        ButcherWorkMetrics.record(state, FIRST, ButcherWorkMetrics.SMOKERS_CRAFTED, 2, true);
        ButcherWorkMetrics.record(state, FIRST, ButcherWorkMetrics.MEALS_DELIVERED, 7, true);
        ButcherWorkMetrics.record(state, FIRST, ButcherWorkMetrics.LEATHER_HIDE_DELIVERED, 3, true);

        NbtCompound nbt = state.writeNbt(new NbtCompound(), null);
        ProfessionalWorkStatsState restored = ProfessionalWorkStatsState.fromNbt(nbt, null);
        assertEquals(4, restored.read(FIRST, ButcherWorkMetrics.BUTCHER_ROLE,
                ButcherWorkMetrics.COOKED_FOOD_COLLECTED));
        assertEquals(2, restored.read(FIRST, ButcherWorkMetrics.BUTCHER_ROLE,
                ButcherWorkMetrics.SMOKERS_CRAFTED));
        assertEquals(7, restored.read(FIRST, ButcherWorkMetrics.BUTCHER_ROLE,
                ButcherWorkMetrics.MEALS_DELIVERED));
        assertEquals(3, restored.read(FIRST, ButcherWorkMetrics.BUTCHER_ROLE,
                ButcherWorkMetrics.LEATHER_HIDE_DELIVERED));
    }

    @Test
    void zeroNegativeAndUnconfirmedAmountsAreIgnored() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        ButcherWorkMetrics.record(state, FIRST, ButcherWorkMetrics.MEALS_DELIVERED, 0, true);
        ButcherWorkMetrics.record(state, FIRST, ButcherWorkMetrics.MEALS_DELIVERED, -3, true);
        ButcherWorkMetrics.record(state, FIRST, ButcherWorkMetrics.MEALS_DELIVERED, 5, false);
        assertEquals(0, state.read(FIRST, ButcherWorkMetrics.BUTCHER_ROLE,
                ButcherWorkMetrics.MEALS_DELIVERED));
    }

    @Test
    void distinctAggregationSaturatesAtLongMaximum() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        ButcherWorkMetrics.record(state, FIRST, ButcherWorkMetrics.COOKED_FOOD_COLLECTED,
                Long.MAX_VALUE, true);
        ButcherWorkMetrics.record(state, SECOND, ButcherWorkMetrics.COOKED_FOOD_COLLECTED, 9, true);
        assertEquals(Long.MAX_VALUE, state.aggregate(
                List.of(FIRST, SECOND, FIRST),
                ButcherWorkMetrics.BUTCHER_ROLE,
                ButcherWorkMetrics.COOKED_FOOD_COLLECTED));
    }
}
