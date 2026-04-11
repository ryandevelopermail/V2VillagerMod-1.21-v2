package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.util.math.BlockPos;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LumberjackGuardChopTreesGoalTest {

    @Test
    void collectConnectedLogsWithinTreeBounds_rootRemovedButAdjacentTrunkRemains_usesFallbackAndFindsLogs() {
        BlockPos root = new BlockPos(0, 64, 0);
        Set<BlockPos> survivingLogs = new HashSet<>(Set.of(
                root.up(),
                root.up(2),
                root.up(3)
        ));

        LumberjackGuardChopTreesGoal.ConnectedLogScanResult result = LumberjackGuardChopTreesGoal.collectConnectedLogsWithinTreeBounds(
                root,
                survivingLogs::contains,
                this::adjacent
        );

        assertTrue(result.usedFallbackSeed());
        assertTrue(result.logs().size() > 0);
        assertFalse(result.logs().contains(root));
    }

    @Test
    void resolveReplacementRoot_rootRemovedButTrunkRemains_returnsConnectedFallbackLog() {
        BlockPos root = new BlockPos(0, 64, 0);
        Set<BlockPos> logs = Set.of(root.up(), root.up(2));

        BlockPos replacement = LumberjackGuardChopTreesGoal.resolveReplacementRoot(
                root,
                logs::contains,
                Function.identity(),
                candidate -> false,
                this::adjacent
        );

        assertEquals(root.up(), replacement);
    }

    @Test
    void resolveReplacementRoot_prefersStrictRootCandidateWhenAvailable() {
        BlockPos root = new BlockPos(0, 64, 0);
        BlockPos strictRootCandidate = root.add(1, 0, 0);
        Set<BlockPos> logs = Set.of(strictRootCandidate, root.up());

        BlockPos replacement = LumberjackGuardChopTreesGoal.resolveReplacementRoot(
                root,
                logs::contains,
                Function.identity(),
                strictRootCandidate::equals,
                this::adjacent
        );

        assertEquals(strictRootCandidate, replacement);
    }

    @Test
    void resolveReplacementRoot_doesNotHopAcrossTreeBeyondCrownRadius() {
        BlockPos root = new BlockPos(0, 64, 0);
        BlockPos farOtherTree = root.add(5, 0, 0);

        BlockPos replacement = LumberjackGuardChopTreesGoal.resolveReplacementRoot(
                root,
                farOtherTree::equals,
                Function.identity(),
                candidate -> false,
                this::adjacent
        );

        assertTrue(replacement == null);
    }


    @Test
    void runMidpointUpgradeNeedPass_oneNeedPerPass_craftsAtMostOneDemandBeforeImmediatePass() {
        AtomicInteger craftCalls = new AtomicInteger();
        AtomicInteger immediatePassCalls = new AtomicInteger();

        boolean acted = LumberjackGuardChopTreesGoal.runMidpointUpgradeNeedPass(
                LumberjackChestTriggerController.UpgradeDemand::v2CraftingTable,
                demand -> 0,
                demand -> {
                    craftCalls.incrementAndGet();
                    return true;
                },
                () -> {
                    immediatePassCalls.incrementAndGet();
                    return true;
                }
        );

        assertTrue(acted);
        assertEquals(1, craftCalls.get());
        assertEquals(1, immediatePassCalls.get());
    }

    @Test
    void runMidpointUpgradeNeedPass_prefersResolvedChestDemandOverTableDemand() {
        AtomicReference<LumberjackChestTriggerController.UpgradeDemand> craftedDemand = new AtomicReference<>();

        boolean acted = LumberjackGuardChopTreesGoal.runMidpointUpgradeNeedPass(
                LumberjackChestTriggerController.UpgradeDemand::v1Chest,
                demand -> 0,
                demand -> {
                    craftedDemand.set(demand);
                    return true;
                },
                () -> true
        );

        assertTrue(acted);
        assertEquals(LumberjackChestTriggerController.UpgradeDemand.v1Chest(), craftedDemand.get());
    }

    @Test
    void isGatherableTreeDrop_acceptsSaplingsAndApplesInAdditionToWoodDrops() {
        assertTrue(LumberjackGuardChopTreesGoal.isGatherableTreeDrop(new ItemStack(Items.OAK_LOG)));
        assertTrue(LumberjackGuardChopTreesGoal.isGatherableTreeDrop(new ItemStack(Items.STICK)));
        assertTrue(LumberjackGuardChopTreesGoal.isGatherableTreeDrop(new ItemStack(Items.OAK_SAPLING)));
        assertTrue(LumberjackGuardChopTreesGoal.isGatherableTreeDrop(new ItemStack(Items.APPLE)));
        assertFalse(LumberjackGuardChopTreesGoal.isGatherableTreeDrop(new ItemStack(Items.DIRT)));
    }

    @Test
    void breakObstacleAndBufferDrops_success_buffersLogDropsImmediately() {
        List<ItemStack> bufferedDrops = new ArrayList<>();

        boolean broken = LumberjackGuardChopTreesGoal.breakObstacleAndBufferDrops(
                () -> List.of(new ItemStack(Items.OAK_LOG), ItemStack.EMPTY),
                () -> true,
                bufferedDrops::add
        );

        assertTrue(broken);
        assertEquals(1, bufferedDrops.size());
        assertTrue(bufferedDrops.get(0).isOf(Items.OAK_LOG));
    }

    @Test
    void getEffectiveTreeSearchRadiusForAttempts_expandsAfterRepeatedNoTreeSessions() {
        assertEquals(20, LumberjackGuardChopTreesGoal.getEffectiveTreeSearchRadiusForAttempts(0));
        assertEquals(20, LumberjackGuardChopTreesGoal.getEffectiveTreeSearchRadiusForAttempts(1));
        assertEquals(32, LumberjackGuardChopTreesGoal.getEffectiveTreeSearchRadiusForAttempts(2));
        assertEquals(32, LumberjackGuardChopTreesGoal.getEffectiveTreeSearchRadiusForAttempts(3));
        assertEquals(40, LumberjackGuardChopTreesGoal.getEffectiveTreeSearchRadiusForAttempts(4));
    }

    @Test
    void isCandidateInScanMode_localModeUsesProvidedDynamicRadius() {
        BlockPos center = new BlockPos(0, 64, 0);
        BlockPos withinExpandedOnly = new BlockPos(0, 64, 25);

        assertFalse(LumberjackGuardChopTreesGoal.isCandidateInScanMode(center, withinExpandedOnly, null, 20));
        assertTrue(LumberjackGuardChopTreesGoal.isCandidateInScanMode(center, withinExpandedOnly, null, 32));
    }

    @Test
    void shouldRunNoTreeEscalation_onlyAtHighWaterAndBoundedRepeats() {
        assertFalse(LumberjackGuardChopTreesGoal.shouldRunNoTreeEscalation(4));
        assertTrue(LumberjackGuardChopTreesGoal.shouldRunNoTreeEscalation(5));
        assertFalse(LumberjackGuardChopTreesGoal.shouldRunNoTreeEscalation(6));
        assertFalse(LumberjackGuardChopTreesGoal.shouldRunNoTreeEscalation(7));
        assertTrue(LumberjackGuardChopTreesGoal.shouldRunNoTreeEscalation(8));
    }

    @Test
    void midpointMissingChestFallback_refreshesPairingsAndTransitionsToRecipientsChosen() {
        AtomicInteger distributionCalls = new AtomicInteger();
        Supplier<List<String>> distributionAfterRefresh = () -> {
            distributionCalls.incrementAndGet();
            return List.of("stick->TOOLSMITH@abc");
        };

        LumberjackGuardChopTreesGoal.MidpointUpgradeRecoveryResult result =
                LumberjackGuardChopTreesGoal.recoverMidpointRecipientsWhenMissingChestDemandHigh(
                        4,
                        List.of(),
                        3,
                        () -> new LumberjackChestTriggerController.MidpointPairingRefreshResult(true, 4, 4, true),
                        () -> new LumberjackChestTriggerController.MidpointChestPlacementResult(false, 0, 0, 0, 0),
                        distributionAfterRefresh,
                        () -> new LumberjackChestTriggerController.MidpointRetryPlan(false, 0, 0L, 0L, false),
                        () -> {
                        },
                        "guard-1"
                );

        assertEquals(List.of("stick->TOOLSMITH@abc"), result.recipientsChosen());
        assertTrue(result.pairingRefreshTriggered());
        assertTrue(result.pairedAfterRefresh());
        assertFalse(result.upgradeRetryScheduled());
        assertEquals(1, distributionCalls.get());
    }

    @Test
    void midpointMissingChestFallback_schedulesRetryWhenRefreshDoesNotRecoverPairing() {
        AtomicInteger retryCalls = new AtomicInteger();

        LumberjackGuardChopTreesGoal.MidpointUpgradeRecoveryResult result =
                LumberjackGuardChopTreesGoal.recoverMidpointRecipientsWhenMissingChestDemandHigh(
                        3,
                        List.of(),
                        3,
                        () -> new LumberjackChestTriggerController.MidpointPairingRefreshResult(true, 2, 2, false),
                        () -> new LumberjackChestTriggerController.MidpointChestPlacementResult(false, 2, 0, 2, 3),
                        List::of,
                        () -> {
                            retryCalls.incrementAndGet();
                            return new LumberjackChestTriggerController.MidpointRetryPlan(true, 1, 600L, 1_600L, false);
                        },
                        () -> {
                        },
                        "guard-2"
                );

        assertTrue(result.recipientsChosen().isEmpty());
        assertTrue(result.pairingRefreshTriggered());
        assertFalse(result.pairedAfterRefresh());
        assertTrue(result.upgradeRetryScheduled());
        assertEquals(1, result.retryAttempt());
        assertEquals(600L, result.retryDelayTicks());
        assertFalse(result.retryThrottled());
        assertEquals(1, retryCalls.get());
    }

    @Test
    void midpointMissingChestFallback_placesChestThenTransitionsToRecipientsChosen() {
        AtomicInteger distributionCalls = new AtomicInteger();
        LumberjackGuardChopTreesGoal.MidpointUpgradeRecoveryResult result =
                LumberjackGuardChopTreesGoal.recoverMidpointRecipientsWhenMissingChestDemandHigh(
                        5,
                        List.of(),
                        3,
                        () -> new LumberjackChestTriggerController.MidpointPairingRefreshResult(true, 5, 5, false),
                        () -> new LumberjackChestTriggerController.MidpointChestPlacementResult(true, 2, 1, 2, 1),
                        () -> {
                            distributionCalls.incrementAndGet();
                            return List.of("plank->FARMER@xyz");
                        },
                        () -> {
                            throw new AssertionError("Retry should not be scheduled after successful placement recovery");
                        },
                        () -> {
                        },
                        "guard-3"
                );

        assertEquals(List.of("plank->FARMER@xyz"), result.recipientsChosen());
        assertTrue(result.pairingRefreshTriggered());
        assertTrue(result.pairedAfterRefresh());
        assertFalse(result.upgradeRetryScheduled());
        assertEquals(1, distributionCalls.get());
    }

    @Test
    void shouldSkipBackpressureDeferRestart_activeDeferWithSubstantialRemaining_skipsRestart() {
        long requestedDeferTicks = 240L;
        long substantialRemaining = LumberjackGuardChopTreesGoal.getSubstantialBackpressureRemainingTicks(requestedDeferTicks);

        boolean shouldSkip = LumberjackGuardChopTreesGoal.shouldSkipBackpressureDeferRestart(
                true,
                substantialRemaining,
                requestedDeferTicks,
                1_000L,
                -1L,
                200L
        );

        assertTrue(shouldSkip);
    }

    @Test
    void shouldSkipBackpressureDeferRestart_retriggerCooldownBlocksImmediateRescheduleAfterExpiry() {
        long requestedDeferTicks = 240L;
        long now = 1_000L;
        long cooldown = 200L;

        boolean shouldSkipDuringCooldown = LumberjackGuardChopTreesGoal.shouldSkipBackpressureDeferRestart(
                false,
                0L,
                requestedDeferTicks,
                now,
                now - 50L,
                cooldown
        );
        boolean shouldAllowAfterCooldown = LumberjackGuardChopTreesGoal.shouldSkipBackpressureDeferRestart(
                false,
                0L,
                requestedDeferTicks,
                now + cooldown,
                now - 50L,
                cooldown
        );

        assertTrue(shouldSkipDuringCooldown);
        assertFalse(shouldAllowAfterCooldown);
    }

    @Test
    void shouldLogDeferredMidpointAudit_rateLimitsDeferOnlyMidpointAuditLogs() {
        long minInterval = 1_200L;

        assertTrue(LumberjackGuardChopTreesGoal.shouldLogDeferredMidpointAudit(5_000L, -1L, minInterval));
        assertFalse(LumberjackGuardChopTreesGoal.shouldLogDeferredMidpointAudit(5_500L, 5_000L, minInterval));
        assertTrue(LumberjackGuardChopTreesGoal.shouldLogDeferredMidpointAudit(6_200L, 5_000L, minInterval));
    }

    @Test
    void backpressureDefer_oneEventCreatesOneConfiguredCountdownAndNoRepeatedRestartWhileActive() {
        long requestedDeferTicks = 300L;
        long now = 10_000L;

        boolean shouldStartFirstDefer = !LumberjackGuardChopTreesGoal.shouldSkipBackpressureDeferRestart(
                false,
                0L,
                requestedDeferTicks,
                now,
                -1L,
                200L
        );
        boolean shouldKeepExisting = LumberjackGuardChopTreesGoal.shouldKeepExistingBackpressureCountdown(
                true,
                requestedDeferTicks - 1L,
                "governor backpressure"
        );

        assertTrue(shouldStartFirstDefer);
        assertEquals(300L, requestedDeferTicks);
        assertTrue(shouldKeepExisting);
    }

    @Test
    void backpressureDefer_scanResumesAfterCountdownExpiryWhenCooldownElapsed() {
        long requestedDeferTicks = 300L;
        long cooldown = 200L;
        long deferStartedAt = 2_000L;
        long afterExpiryAndCooldown = deferStartedAt + requestedDeferTicks + cooldown + 1L;

        boolean shouldKeepExisting = LumberjackGuardChopTreesGoal.shouldKeepExistingBackpressureCountdown(
                true,
                0L,
                "governor backpressure"
        );
        boolean shouldSkipRestart = LumberjackGuardChopTreesGoal.shouldSkipBackpressureDeferRestart(
                false,
                0L,
                requestedDeferTicks,
                afterExpiryAndCooldown,
                deferStartedAt,
                cooldown
        );

        assertFalse(shouldKeepExisting);
        assertFalse(shouldSkipRestart);
    }

    @Test
    void shouldRunMidpointAuditForCountdown_onlyOncePerCountdownStartTick() {
        long countdownStartTick = 4_000L;

        assertTrue(LumberjackGuardChopTreesGoal.shouldRunMidpointAuditForCountdown(countdownStartTick, -1L));
        assertFalse(LumberjackGuardChopTreesGoal.shouldRunMidpointAuditForCountdown(countdownStartTick, countdownStartTick));
        assertTrue(LumberjackGuardChopTreesGoal.shouldRunMidpointAuditForCountdown(countdownStartTick + 300L, countdownStartTick));
    }

    private List<BlockPos> adjacent(BlockPos pos) {
        return List.of(
                pos.up(),
                pos.down(),
                pos.north(),
                pos.south(),
                pos.east(),
                pos.west()
        );
    }
}
