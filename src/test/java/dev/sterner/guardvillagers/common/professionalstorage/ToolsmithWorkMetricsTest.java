package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolsmithWorkMetricsTest {
    private static final UUID WORKER = UUID.fromString("40000000-0000-0000-0000-000000000001");

    @Test
    void metricIdsAreStable() {
        assertEquals("toolsmith.tools_crafted", ToolsmithWorkMetrics.TOOLS_CRAFTED.value());
        assertEquals("toolsmith.tools_distributed", ToolsmithWorkMetrics.TOOLS_DISTRIBUTED.value());
        assertEquals("toolsmith.smithing_jobs_completed", ToolsmithWorkMetrics.SMITHING_JOBS_COMPLETED.value());
    }

    @Test
    void allMetricIdsSurviveNbtRoundTrip() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        ToolsmithWorkMetrics.record(state, WORKER, ToolsmithWorkMetrics.TOOLS_CRAFTED, 4, true);
        ToolsmithWorkMetrics.record(state, WORKER, ToolsmithWorkMetrics.SMITHING_JOBS_COMPLETED, 2, true);
        ToolsmithWorkMetrics.record(state, WORKER, ToolsmithWorkMetrics.TOOLS_DISTRIBUTED, 3, true);

        ProfessionalWorkStatsState restored = ProfessionalWorkStatsState.fromNbt(
                state.writeNbt(new NbtCompound(), null), null);
        assertEquals(4, restored.read(WORKER, ToolsmithWorkMetrics.TOOLSMITH_ROLE, ToolsmithWorkMetrics.TOOLS_CRAFTED));
        assertEquals(2, restored.read(WORKER, ToolsmithWorkMetrics.TOOLSMITH_ROLE, ToolsmithWorkMetrics.SMITHING_JOBS_COMPLETED));
        assertEquals(3, restored.read(WORKER, ToolsmithWorkMetrics.TOOLSMITH_ROLE, ToolsmithWorkMetrics.TOOLS_DISTRIBUTED));
    }

    @Test
    void confirmedAmountsRecordAndUnconfirmedOrUnsupportedAmountsDoNot() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        ToolsmithWorkMetrics.record(state, WORKER, ToolsmithWorkMetrics.TOOLS_CRAFTED, 5, true);
        ToolsmithWorkMetrics.record(state, WORKER, ToolsmithWorkMetrics.TOOLS_CRAFTED, 9, false);
        ToolsmithWorkMetrics.record(state, WORKER, ToolsmithWorkMetrics.TOOLS_DISTRIBUTED, 0, true);
        assertEquals(5, state.read(
                WORKER, ToolsmithWorkMetrics.TOOLSMITH_ROLE, ToolsmithWorkMetrics.TOOLS_CRAFTED));
        assertEquals(0, state.read(
                WORKER, ToolsmithWorkMetrics.TOOLSMITH_ROLE, ToolsmithWorkMetrics.TOOLS_DISTRIBUTED));
    }

    @Test
    void countersStillSaturate() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        ToolsmithWorkMetrics.record(
                state, WORKER, ToolsmithWorkMetrics.TOOLS_DISTRIBUTED, Long.MAX_VALUE - 1, true);
        ToolsmithWorkMetrics.record(state, WORKER, ToolsmithWorkMetrics.TOOLS_DISTRIBUTED, 10, true);
        assertEquals(Long.MAX_VALUE, state.read(
                WORKER, ToolsmithWorkMetrics.TOOLSMITH_ROLE, ToolsmithWorkMetrics.TOOLS_DISTRIBUTED));
    }
}
