package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalWorkStatsState;
import dev.sterner.guardvillagers.common.professionalstorage.ToolsmithWorkMetrics;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistributionCompletionHookTest {
    private static final UUID WORKER = UUID.fromString("60000000-0000-0000-0000-000000000001");

    @Test
    void completeTransferInvokesHookExactlyOnce() {
        AtomicInteger hooks = new AtomicInteger();
        assertTrue(AbstractInventoryDistributionGoal.notifyAfterComplete(
                true, () -> "delivered", ignored -> hooks.incrementAndGet()));
        assertEquals(1, hooks.get());
    }

    @Test
    void partialFailureInvalidationReturnAndCancellationDoNotInvokeHook() {
        AtomicInteger hooks = new AtomicInteger();
        for (int ignored = 0; ignored < 5; ignored++) {
            assertFalse(AbstractInventoryDistributionGoal.notifyAfterComplete(
                    false, () -> "not delivered", value -> hooks.incrementAndGet()));
        }
        assertEquals(0, hooks.get());
    }

    @Test
    void retryInvokesHookOnceWithOriginalFullTransferQuantity() {
        AtomicInteger hooks = new AtomicInteger();
        assertFalse(AbstractInventoryDistributionGoal.notifyAfterComplete(
                false, () -> 4, value -> hooks.addAndGet(value)));
        assertTrue(AbstractInventoryDistributionGoal.notifyAfterComplete(
                true, () -> 4, value -> hooks.addAndGet(value)));
        assertEquals(4, hooks.get());
    }

    @Test
    void successfulSupportedDeliveryRecordsDeliveredStackCount() {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        assertTrue(AbstractInventoryDistributionGoal.notifyAfterComplete(
                true,
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
        assertFalse(AbstractInventoryDistributionGoal.notifyAfterComplete(
                false,
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
