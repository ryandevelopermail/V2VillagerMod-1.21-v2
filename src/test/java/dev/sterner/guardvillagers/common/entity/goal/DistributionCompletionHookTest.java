package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalWorkStatsState;
import dev.sterner.guardvillagers.common.professionalstorage.ToolsmithWorkMetrics;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DistributionCompletionHookTest {
    private static final UUID WORKER = UUID.fromString("60000000-0000-0000-0000-000000000001");

    @Test
    void completedTransferRouteClassificationUsesOverflowPrecedence() {
        assertEquals(AbstractInventoryDistributionGoal.TransferRoute.DIRECT,
                AbstractInventoryDistributionGoal.classifyTransferRoute(false, false));
        assertEquals(AbstractInventoryDistributionGoal.TransferRoute.UNIVERSAL,
                AbstractInventoryDistributionGoal.classifyTransferRoute(false, true));
        assertEquals(AbstractInventoryDistributionGoal.TransferRoute.OVERFLOW,
                AbstractInventoryDistributionGoal.classifyTransferRoute(true, false));
        assertEquals(AbstractInventoryDistributionGoal.TransferRoute.OVERFLOW,
                AbstractInventoryDistributionGoal.classifyTransferRoute(true, true));
    }

    @Test
    void completeTransferInvokesHookExactlyOnce() {
        AtomicInteger hooks = new AtomicInteger();
        assertEquals(AbstractInventoryDistributionGoal.TransferAttemptOutcome.COMPLETE,
                AbstractInventoryDistributionGoal.attemptPendingTransfer(
                        true,
                        () -> true,
                        () -> "delivered",
                        ignored -> hooks.incrementAndGet()));
        assertEquals(1, hooks.get());
    }

    @Test
    void partialInsertionDoesNotInvokeHookOrSnapshot() {
        AtomicInteger hooks = new AtomicInteger();
        AtomicInteger snapshots = new AtomicInteger();
        assertEquals(AbstractInventoryDistributionGoal.TransferAttemptOutcome.INCOMPLETE,
                AbstractInventoryDistributionGoal.attemptPendingTransfer(
                        true,
                        () -> false,
                        () -> { snapshots.incrementAndGet(); return "not delivered"; },
                        value -> hooks.incrementAndGet()));
        assertEquals(0, hooks.get());
        assertEquals(0, snapshots.get());
    }

    @Test
    void targetInvalidationReturnsWithoutAttemptOrHook() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger hooks = new AtomicInteger();
        assertEquals(AbstractInventoryDistributionGoal.TransferAttemptOutcome.TARGET_INVALID,
                AbstractInventoryDistributionGoal.attemptPendingTransfer(
                        false,
                        () -> { attempts.incrementAndGet(); return true; },
                        () -> "not delivered",
                        value -> hooks.incrementAndGet()));
        assertEquals(0, attempts.get());
        assertEquals(0, hooks.get());
    }

    @Test
    void retryInvokesHookOnceWithOriginalFullTransferQuantity() {
        AtomicInteger hooks = new AtomicInteger();
        assertEquals(AbstractInventoryDistributionGoal.TransferAttemptOutcome.INCOMPLETE,
                AbstractInventoryDistributionGoal.attemptPendingTransfer(
                        true, () -> false, () -> 4, hooks::addAndGet));
        assertEquals(AbstractInventoryDistributionGoal.TransferAttemptOutcome.COMPLETE,
                AbstractInventoryDistributionGoal.attemptPendingTransfer(
                        true, () -> true, () -> 4, hooks::addAndGet));
        assertEquals(4, hooks.get());
    }

    @Test
    void successfulSupportedDeliveryRecordsDeliveredStackCount() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        assertEquals(AbstractInventoryDistributionGoal.TransferAttemptOutcome.COMPLETE,
                AbstractInventoryDistributionGoal.attemptPendingTransfer(
                        true,
                        () -> true,
                        () -> 3L,
                        count -> state.increment(
                                WORKER,
                                ToolsmithWorkMetrics.TOOLSMITH_ROLE,
                                ToolsmithWorkMetrics.TOOLS_DISTRIBUTED,
                                count)));
        assertEquals(3, state.read(
                WORKER,
                ToolsmithWorkMetrics.TOOLSMITH_ROLE,
                ToolsmithWorkMetrics.TOOLS_DISTRIBUTED));
    }

    @Test
    void unsupportedSelectionNeverEntersSuccessfulToolsmithCompletion() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        assertEquals(AbstractInventoryDistributionGoal.TransferAttemptOutcome.INCOMPLETE,
                AbstractInventoryDistributionGoal.attemptPendingTransfer(
                        true,
                        () -> false,
                        () -> 1L,
                        count -> state.increment(
                                WORKER,
                                ToolsmithWorkMetrics.TOOLSMITH_ROLE,
                                ToolsmithWorkMetrics.TOOLS_DISTRIBUTED,
                                count)));
        assertEquals(0, state.read(
                WORKER,
                ToolsmithWorkMetrics.TOOLSMITH_ROLE,
                ToolsmithWorkMetrics.TOOLS_DISTRIBUTED));
    }
}
