package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfessionalWorkStatsStateTest {
    private static final UUID FIRST = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final ProfessionalRoleId FARMER = role("minecraft:farmer");
    private static final ProfessionalRoleId TOOLSMITH = role("minecraft:toolsmith");

    @Test
    void nbtRoundTripPreservesCounters() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        state.increment(FIRST, FARMER, FarmerWorkMetrics.CROPS_HARVESTED, 27L);
        state.increment(FIRST, FARMER, FarmerWorkMetrics.CROPS_PLANTED, 19L);
        state.increment(SECOND, FARMER, FarmerWorkMetrics.GROUND_TILLED, 8L);
        state.increment(FIRST, FARMER, FarmerWorkMetrics.MATERIALS_CRAFTED, 13L);

        NbtCompound encoded = state.writeNbt(new NbtCompound(), null);
        ProfessionalWorkStatsState restored = ProfessionalWorkStatsState.fromNbt(encoded, null);

        assertEquals(27L, restored.read(FIRST, FARMER, FarmerWorkMetrics.CROPS_HARVESTED));
        assertEquals(19L, restored.read(FIRST, FARMER, FarmerWorkMetrics.CROPS_PLANTED));
        assertEquals(8L, restored.read(SECOND, FARMER, FarmerWorkMetrics.GROUND_TILLED));
        assertEquals(13L, restored.read(FIRST, FARMER, FarmerWorkMetrics.MATERIALS_CRAFTED));
    }

    @Test
    void incrementReadsAndAggregatesDistinctWorkers() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        assertEquals(1L, state.increment(FIRST, FARMER, FarmerWorkMetrics.CROPS_HARVESTED));
        assertEquals(6L, state.increment(FIRST, FARMER, FarmerWorkMetrics.CROPS_HARVESTED, 5L));
        state.increment(SECOND, FARMER, FarmerWorkMetrics.CROPS_HARVESTED, 4L);

        assertEquals(10L, state.aggregate(
                List.of(FIRST, SECOND, FIRST),
                FARMER,
                FarmerWorkMetrics.CROPS_HARVESTED));
    }

    @Test
    void sameWorkerUuidRemainsIsolatedByRole() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        state.increment(FIRST, FARMER, FarmerWorkMetrics.CROPS_PLANTED, 3L);
        state.increment(FIRST, TOOLSMITH, FarmerWorkMetrics.CROPS_PLANTED, 11L);

        assertEquals(3L, state.read(FIRST, FARMER, FarmerWorkMetrics.CROPS_PLANTED));
        assertEquals(11L, state.read(FIRST, TOOLSMITH, FarmerWorkMetrics.CROPS_PLANTED));
    }

    @Test
    void missingMetricsDefaultToZeroAndNoOpWritesStayClean() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        assertEquals(0L, state.read(FIRST, FARMER, FarmerWorkMetrics.GROUND_TILLED));

        state.setDirty(false);
        assertEquals(0L, state.increment(FIRST, FARMER, FarmerWorkMetrics.GROUND_TILLED, 0L));
        assertFalse(state.isDirty());
        FarmerWorkMetrics.recordConfirmed(
                state,
                FIRST,
                FARMER,
                FarmerWorkMetrics.GROUND_TILLED,
                false);
        assertFalse(state.isDirty());

        FarmerWorkMetrics.recordConfirmed(
                state,
                FIRST,
                FARMER,
                FarmerWorkMetrics.GROUND_TILLED,
                true);
        assertTrue(state.isDirty());
        assertEquals(1L, state.read(FIRST, FARMER, FarmerWorkMetrics.GROUND_TILLED));
    }

    @Test
    void malformedAndNegativeSavedValuesAreIgnored() {
        NbtCompound root = new NbtCompound();
        NbtList entries = new NbtList();
        NbtCompound invalidUuid = new NbtCompound();
        invalidUuid.putString("WorkerUuid", "not-a-uuid");
        invalidUuid.putString("Role", "minecraft:farmer");
        invalidUuid.put("Metrics", new NbtCompound());
        entries.add(invalidUuid);

        NbtCompound negative = new NbtCompound();
        negative.putString("WorkerUuid", FIRST.toString());
        negative.putString("Role", FARMER.toString());
        NbtCompound metrics = new NbtCompound();
        metrics.putLong(FarmerWorkMetrics.CROPS_HARVESTED.value(), -9L);
        metrics.putString("invalid metric id", "oops");
        negative.put("Metrics", metrics);
        entries.add(negative);
        root.put("Entries", entries);

        ProfessionalWorkStatsState restored = ProfessionalWorkStatsState.fromNbt(root, null);
        assertEquals(0L, restored.read(FIRST, FARMER, FarmerWorkMetrics.CROPS_HARVESTED));
        assertFalse(restored.isDirty());
    }

    @Test
    void countersSaturateAtSignedLongMaximum() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        state.increment(FIRST, FARMER, FarmerWorkMetrics.CROPS_HARVESTED, Long.MAX_VALUE - 1L);
        assertEquals(Long.MAX_VALUE, state.increment(FIRST, FARMER, FarmerWorkMetrics.CROPS_HARVESTED, 10L));
        state.setDirty(false);
        assertEquals(Long.MAX_VALUE, state.increment(FIRST, FARMER, FarmerWorkMetrics.CROPS_HARVESTED));
        assertFalse(state.isDirty());
    }

    @Test
    void craftedOutputCountIsRecordedOnlyForConfirmedPositiveOutput() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        FarmerWorkMetrics.recordMaterialsCrafted(state, FIRST, 4L);
        FarmerWorkMetrics.recordMaterialsCrafted(state, FIRST, 0L);
        FarmerWorkMetrics.recordMaterialsCrafted(state, FIRST, -1L);

        assertEquals(4L, state.read(FIRST, FARMER, FarmerWorkMetrics.MATERIALS_CRAFTED));
    }

    @Test
    void failedCraftDoesNotIncrementMetric() {
        assertUnconfirmedCraftDoesNotIncrement();
    }

    @Test
    void missingIngredientsDoNotIncrementMetric() {
        assertUnconfirmedCraftDoesNotIncrement();
    }

    @Test
    void missingCraftingTableDoesNotIncrementMetric() {
        assertUnconfirmedCraftDoesNotIncrement();
    }

    @Test
    void canceledOrInterruptedWorkDoesNotIncrementMetric() {
        assertUnconfirmedCraftDoesNotIncrement();
    }

    @Test
    void materialsCraftedCounterAlsoSaturates() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        FarmerWorkMetrics.recordMaterialsCrafted(state, FIRST, Long.MAX_VALUE - 1L);
        FarmerWorkMetrics.recordMaterialsCrafted(state, FIRST, 8L);

        assertEquals(Long.MAX_VALUE, state.read(FIRST, FARMER, FarmerWorkMetrics.MATERIALS_CRAFTED));
    }

    private static ProfessionalRoleId role(String id) {
        return new ProfessionalRoleId(Identifier.of(id));
    }

    private static void assertUnconfirmedCraftDoesNotIncrement() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        FarmerWorkMetrics.recordMaterialsCrafted(state, FIRST, 1L, false);
        assertEquals(0L, state.read(FIRST, FARMER, FarmerWorkMetrics.MATERIALS_CRAFTED));
    }
}
