package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.professionalstorage.LeatherworkerWorkMetrics;
import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalWorkStatsState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeatherworkerCompletionSeamTest {
    private static final UUID WORKER = UUID.fromString("79000000-0000-0000-0000-000000000001");
    private static final UUID CARTOGRAPHER = UUID.fromString("79000000-0000-0000-0000-000000000002");
    private static final UUID SECOND_CARTOGRAPHER = UUID.fromString("79000000-0000-0000-0000-000000000003");
    private static final UUID NON_V2 = UUID.fromString("79000000-0000-0000-0000-000000000004");

    @Test
    void rawCraftabilityUsesLeatherAndIngredientsWithoutAntiRepeatState() {
        assertTrue(LeatherworkerCraftingGoal.isCraftableLeatherRecipe(true, true, true));
        assertFalse(LeatherworkerCraftingGoal.isCraftableLeatherRecipe(false, true, true));
        assertFalse(LeatherworkerCraftingGoal.isCraftableLeatherRecipe(true, false, true));
        assertFalse(LeatherworkerCraftingGoal.isCraftableLeatherRecipe(true, true, false));
    }

    @Test
    void confirmedCraftMarksDirtyThenRecordsOutputAndMemory() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        List<String> order = new ArrayList<>();
        AtomicInteger memoryWrites = new AtomicInteger();
        assertTrue(LeatherworkerCraftingGoal.executeConfirmedCraft(
                () -> true,
                () -> true,
                () -> true,
                () -> true,
                () -> order.add("dirty"),
                () -> {
                    order.add("success");
                    state.increment(
                            WORKER,
                            LeatherworkerWorkMetrics.LEATHERWORKER_ROLE,
                            LeatherworkerWorkMetrics.LEATHER_GOODS_CRAFTED,
                            4);
                    memoryWrites.incrementAndGet();
                }));
        assertEquals(List.of("dirty", "success"), order);
        assertEquals(4, state.read(
                WORKER,
                LeatherworkerWorkMetrics.LEATHERWORKER_ROLE,
                LeatherworkerWorkMetrics.LEATHER_GOODS_CRAFTED));
        assertEquals(1, memoryWrites.get());
    }

    @Test
    void framePriorityCraftUsesSameConfirmedSuccessBoundary() {
        assertEquals(1, LeatherworkerCraftingGoal.selectRecipeIndexByFrameShape(
                List.of(false, true, false),
                true,
                bound -> 0));
        assertEquals(2, LeatherworkerCraftingGoal.selectRecipeIndexByFrameShape(
                List.of(false, true, false),
                false,
                bound -> 2));
    }

    @Test
    void failedCraftPhasesDoNotConsumeMarkDirtyRecordOrChangeMemory() {
        AtomicInteger consumes = new AtomicInteger();
        AtomicInteger dirty = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        assertFalse(LeatherworkerCraftingGoal.executeConfirmedCraft(
                () -> false, () -> true,
                () -> { consumes.incrementAndGet(); return true; },
                () -> true, dirty::incrementAndGet, completions::incrementAndGet));
        assertFalse(LeatherworkerCraftingGoal.executeConfirmedCraft(
                () -> true, () -> false,
                () -> { consumes.incrementAndGet(); return true; },
                () -> true, dirty::incrementAndGet, completions::incrementAndGet));
        assertFalse(LeatherworkerCraftingGoal.executeConfirmedCraft(
                () -> true, () -> true, () -> false,
                () -> true, dirty::incrementAndGet, completions::incrementAndGet));
        assertFalse(LeatherworkerCraftingGoal.executeConfirmedCraft(
                () -> true, () -> true, () -> true,
                () -> false, dirty::incrementAndGet, completions::incrementAndGet));
        assertEquals(0, consumes.get());
        assertEquals(0, dirty.get());
        assertEquals(0, completions.get());
    }

    @Test
    void frameDemandCountsUniqueV2DeficitsOnly() {
        assertEquals(3, LeatherworkerDistributionGoal.countUniqueFrameDemand(List.of(
                new LeatherworkerDistributionGoal.FrameDemandView(CARTOGRAPHER, true, 1),
                new LeatherworkerDistributionGoal.FrameDemandView(CARTOGRAPHER, true, 0),
                new LeatherworkerDistributionGoal.FrameDemandView(SECOND_CARTOGRAPHER, true, 4),
                new LeatherworkerDistributionGoal.FrameDemandView(NON_V2, false, 0)), 4));
    }

    @Test
    void supportedDistributionPredicateIncludesWhitelistAndLeatherArmorOnly() {
        assertTrue(LeatherworkerDistributionGoal.isSupportedDistributionShape(true, false));
        assertTrue(LeatherworkerDistributionGoal.isSupportedDistributionShape(false, true));
        assertFalse(LeatherworkerDistributionGoal.isSupportedDistributionShape(false, false));
    }

    @Test
    void deliveryMetricRequiresDirectSupportedValidRecipientAndStorage() {
        assertTrue(LeatherworkerDistributionGoal.isConfirmedDirectDelivery(
                AbstractInventoryDistributionGoal.TransferRoute.DIRECT, true, true, true));
        assertFalse(LeatherworkerDistributionGoal.isConfirmedDirectDelivery(
                AbstractInventoryDistributionGoal.TransferRoute.UNIVERSAL, true, true, true));
        assertFalse(LeatherworkerDistributionGoal.isConfirmedDirectDelivery(
                AbstractInventoryDistributionGoal.TransferRoute.OVERFLOW, true, true, true));
        assertFalse(LeatherworkerDistributionGoal.isConfirmedDirectDelivery(
                AbstractInventoryDistributionGoal.TransferRoute.DIRECT, false, true, true));
        assertFalse(LeatherworkerDistributionGoal.isConfirmedDirectDelivery(
                AbstractInventoryDistributionGoal.TransferRoute.DIRECT, true, false, true));
        assertFalse(LeatherworkerDistributionGoal.isConfirmedDirectDelivery(
                AbstractInventoryDistributionGoal.TransferRoute.DIRECT, true, true, false));
    }

    @Test
    void partialRetargetCanceledAndRetryCountOnlyOneFinalCompletedStack() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        AtomicInteger snapshots = new AtomicInteger();
        assertEquals(AbstractInventoryDistributionGoal.TransferAttemptOutcome.INCOMPLETE,
                AbstractInventoryDistributionGoal.attemptPendingTransfer(
                        true, () -> false,
                        () -> { snapshots.incrementAndGet(); return 3L; },
                        count -> state.increment(
                                WORKER,
                                LeatherworkerWorkMetrics.LEATHERWORKER_ROLE,
                                LeatherworkerWorkMetrics.GOODS_DELIVERED,
                                count)));
        assertEquals(AbstractInventoryDistributionGoal.TransferAttemptOutcome.TARGET_INVALID,
                AbstractInventoryDistributionGoal.attemptPendingTransfer(
                        false, () -> true,
                        () -> { snapshots.incrementAndGet(); return 3L; },
                        count -> state.increment(
                                WORKER,
                                LeatherworkerWorkMetrics.LEATHERWORKER_ROLE,
                                LeatherworkerWorkMetrics.GOODS_DELIVERED,
                                count)));
        assertFalse(AbstractInventoryDistributionGoal.notifyAfterComplete(
                false,
                () -> 3L,
                count -> state.increment(
                        WORKER,
                        LeatherworkerWorkMetrics.LEATHERWORKER_ROLE,
                        LeatherworkerWorkMetrics.GOODS_DELIVERED,
                        count)));
        assertEquals(AbstractInventoryDistributionGoal.TransferAttemptOutcome.COMPLETE,
                AbstractInventoryDistributionGoal.attemptPendingTransfer(
                        true, () -> true,
                        () -> { snapshots.incrementAndGet(); return 3L; },
                        count -> state.increment(
                                WORKER,
                                LeatherworkerWorkMetrics.LEATHERWORKER_ROLE,
                                LeatherworkerWorkMetrics.GOODS_DELIVERED,
                                count)));
        assertEquals(1, snapshots.get());
        assertEquals(3, state.read(
                WORKER,
                LeatherworkerWorkMetrics.LEATHERWORKER_ROLE,
                LeatherworkerWorkMetrics.GOODS_DELIVERED));
    }
}
