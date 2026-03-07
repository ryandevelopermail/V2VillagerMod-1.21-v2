package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.AxeGuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.goal.AxeGuardWorkflowRegistry;
import dev.sterner.guardvillagers.common.entity.goal.LumberjackBootstrapGoal;
import dev.sterner.guardvillagers.common.villager.LumberjackLifecyclePhase;
import dev.sterner.guardvillagers.common.villager.LumberjackProfession;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import dev.sterner.guardvillagers.common.villager.SpecialModifier;
import dev.sterner.guardvillagers.common.villager.VillagerConversionCandidateIndex;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

public class LumberjackBehavior extends AbstractPairedProfessionBehavior {
    private static final Logger LOGGER = LoggerFactory.getLogger(LumberjackBehavior.class);
    private static final int BOOTSTRAP_GOAL_PRIORITY = 2;
    private static final long BOOTSTRAP_STALL_TIMEOUT_TICKS = 300L;
    private static final int BOOTSTRAP_STALL_MAX_RETRIES = 3;
    private static final long FALLBACK_STARTUP_COUNTDOWN_TICKS = 20L * 60L;
    private static final long POST_BOOTSTRAP_CALLBACK_WARN_TICKS = 100L;

    private static final Map<VillagerEntity, LumberjackBootstrapGoal> BOOTSTRAP_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, Long> PENDING_INITIAL_COUNTDOWNS = new WeakHashMap<>();
    private static final Map<VillagerEntity, LumberjackLifecyclePhase> LIFECYCLE_PHASES = new WeakHashMap<>();
    private static final Map<VillagerEntity, Long> BOOTSTRAP_STARTED_AT = new WeakHashMap<>();
    private static final Map<VillagerEntity, Integer> BOOTSTRAP_RECOVERY_ATTEMPTS = new WeakHashMap<>();

    private static final Map<VillagerEntity, ChestRegistration> CHEST_REGISTRATIONS = new WeakHashMap<>();
    private static final Map<BlockPos, Set<VillagerEntity>> CHEST_WATCHERS_BY_POS = new HashMap<>();
    private static final Map<VillagerEntity, PendingPostBootstrapCallback> PENDING_POST_BOOTSTRAP_CALLBACKS = new WeakHashMap<>();

    public static void queueInitialCountdown(VillagerEntity villager, long ticks) {
        PENDING_INITIAL_COUNTDOWNS.put(villager, Math.max(20L, ticks));
        transitionPhase(villager, LumberjackLifecyclePhase.BOOTSTRAP_COMPLETE,
                "BOOTSTRAP_COUNTDOWN_QUEUED",
                "bootstrap countdown queued");
    }

    public static void runPostBootstrapHandoff(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        runPostBootstrapHandoff(world, villager, jobPos, chestPos, true, "POST_BOOTSTRAP_HANDOFF");
    }

    private static void runPostBootstrapHandoff(ServerWorld world,
                                                 VillagerEntity villager,
                                                 BlockPos jobPos,
                                                 BlockPos chestPos,
                                                 boolean awaitCallbackPath,
                                                 String source) {
        updateChestListener(world, villager, chestPos);
        resolveCraftingTablePos(world, jobPos, chestPos);
        resolveNearbyFurnacePos(world, chestPos);
        reconcileAndMaybeConvert(world, villager, jobPos, chestPos, null, source);

        if (awaitCallbackPath) {
            PENDING_POST_BOOTSTRAP_CALLBACKS.put(villager,
                    new PendingPostBootstrapCallback(jobPos.toImmutable(), chestPos.toImmutable(), world.getTime()));
        }
    }

    @Override
    public void onJobBlockPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos) {
        if (!villager.isAlive()) {
            return;
        }
        if (!ProfessionDefinitions.isExpectedJobBlock(LumberjackProfession.LUMBERJACK, world.getBlockState(jobPos))) {
            return;
        }

        if (getPhase(villager).ordinal() < LumberjackLifecyclePhase.BOOTSTRAP_COMPLETE.ordinal()) {
            transitionPhase(villager, LumberjackLifecyclePhase.BOOTSTRAP_PENDING,
                    "JOB_BLOCK_PAIRED",
                    "job block paired");
        } else {
            logLifecycleBlocked(villager,
                    LumberjackLifecyclePhase.BOOTSTRAP_PENDING,
                    "BLOCKED_BACKWARD_TRANSITION",
                    "jobPos=" + jobPos.toShortString() + ",currentPhase=" + getPhase(villager));
        }

        boolean reusedGoal = BOOTSTRAP_GOALS.containsKey(villager);
        LumberjackBootstrapGoal bootstrapGoal = getOrCreateBootstrapGoal(villager, jobPos);
        BOOTSTRAP_STARTED_AT.put(villager, world.getTime());
        BOOTSTRAP_RECOVERY_ATTEMPTS.remove(villager);
        bootstrapGoal.setJobPos(jobPos);
        bootstrapGoal.requestImmediateStart();

        LOGGER.info("Lumberjack {} started startup workflow at {} (goal={})",
                villager.getUuidAsString(),
                jobPos.toShortString(),
                reusedGoal ? "reused" : "new");
    }

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!checkPairingPreconditions(world, villager, jobPos, chestPos,
                (serverWorld, pos) -> ProfessionDefinitions.isExpectedJobBlock(LumberjackProfession.LUMBERJACK, serverWorld.getBlockState(pos)),
                () -> {
                    clearChestListener(villager);
                    PENDING_INITIAL_COUNTDOWNS.remove(villager);
                    LIFECYCLE_PHASES.remove(villager);
                    BOOTSTRAP_STARTED_AT.remove(villager);
                    BOOTSTRAP_RECOVERY_ATTEMPTS.remove(villager);
                    PENDING_POST_BOOTSTRAP_CALLBACKS.remove(villager);
                })) {
            return;
        }

        BOOTSTRAP_STARTED_AT.remove(villager);
        BOOTSTRAP_RECOVERY_ATTEMPTS.remove(villager);
        PENDING_POST_BOOTSTRAP_CALLBACKS.remove(villager);

        LOGGER.info("Lumberjack {} paired chest at {} for job site {}",
                villager.getUuidAsString(),
                chestPos.toShortString(),
                jobPos.toShortString());

        runPostBootstrapHandoff(world, villager, jobPos, chestPos, false, "CHEST_PAIRED_WORKFLOW");
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        transitionPhase(villager, LumberjackLifecyclePhase.BOOTSTRAP_PENDING,
                "CRAFTING_TABLE_JOB_PAIRED",
                "crafting-table/job pairing");

        LumberjackBootstrapGoal bootstrapGoal = getOrCreateBootstrapGoal(villager, jobPos);
        bootstrapGoal.setJobPos(jobPos);
        bootstrapGoal.requestImmediateStart();

        updateChestListener(world, villager, chestPos);
    }


    public static boolean isBootstrapGoalActiveFor(VillagerEntity villager, BlockPos jobPos) {
        LumberjackBootstrapGoal bootstrapGoal = BOOTSTRAP_GOALS.get(villager);
        if (bootstrapGoal == null) {
            return false;
        }

        Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
        return jobSite.map(GlobalPos::pos).map(jobPos::equals).orElse(false);
    }

    @Override
    public void onSpecialModifierPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, SpecialModifier modifier, BlockPos modifierPos) {
        if (!modifier.block().equals(Blocks.FURNACE)) {
            return;
        }

        BlockPos furnacePos = modifierPos.toImmutable();

        AxeGuardWorkflowRegistry.onChestInventoryMutated(world, chestPos);

        LOGGER.info("Lumberjack {} paired furnace modifier at {}", villager.getUuidAsString(), furnacePos.toShortString());
    }

    public static void onChestInventoryMutated(ServerWorld world, BlockPos chestPos) {
        AxeGuardWorkflowRegistry.onChestInventoryMutated(world, chestPos);

        Set<VillagerEntity> villagers = CHEST_WATCHERS_BY_POS.get(chestPos);
        if (villagers == null || villagers.isEmpty()) {
            return;
        }

        for (VillagerEntity villager : Set.copyOf(villagers)) {
            if (!villager.isAlive() || villager.getWorld() != world) {
                continue;
            }

            if (villager.getVillagerData().getProfession() == LumberjackProfession.LUMBERJACK) {
                VillagerConversionCandidateIndex.markCandidate(world, villager);
                reconcileAndMaybeConvert(world, villager, null, chestPos, chestPos, "CHEST_MUTATION");
            }
        }
    }

    public static void tryConvertLumberjacksWithAxe(ServerWorld world) {
        warnIfPostBootstrapCallbackMissing(world);

        Set<VillagerEntity> candidates = new LinkedHashSet<>(VillagerConversionCandidateIndex.pollCandidates(world, LumberjackProfession.LUMBERJACK));
        Box worldBounds = new Box(world.getWorldBorder().getBoundWest(), world.getBottomY(), world.getWorldBorder().getBoundNorth(),
                world.getWorldBorder().getBoundEast(), world.getTopY(), world.getWorldBorder().getBoundSouth());
        candidates.addAll(world.getEntitiesByClass(
                VillagerEntity.class,
                worldBounds,
                villager -> villager.isAlive() && villager.getVillagerData().getProfession() == LumberjackProfession.LUMBERJACK
        ));

        for (VillagerEntity villager : candidates) {
            if (!villager.isAlive() || villager.isRemoved() || villager.getWorld() != world) {
                continue;
            }

            Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
            BlockPos jobPos = jobSite
                    .filter(globalPos -> globalPos.dimension().equals(world.getRegistryKey()))
                    .map(GlobalPos::pos)
                    .orElse(null);
            if (jobPos == null) {
                continue;
            }
            if (!ProfessionDefinitions.isExpectedJobBlock(LumberjackProfession.LUMBERJACK, world.getBlockState(jobPos))) {
                continue;
            }

            reconcileAndMaybeConvert(world, villager, jobPos, null, null, "CANDIDATE_SCAN");
        }
    }

    private static void warnIfPostBootstrapCallbackMissing(ServerWorld world) {
        for (Map.Entry<VillagerEntity, PendingPostBootstrapCallback> entry : Set.copyOf(PENDING_POST_BOOTSTRAP_CALLBACKS.entrySet())) {
            VillagerEntity villager = entry.getKey();
            PendingPostBootstrapCallback callback = entry.getValue();
            if (villager == null || callback == null || !villager.isAlive() || villager.getWorld() != world) {
                PENDING_POST_BOOTSTRAP_CALLBACKS.remove(villager);
                continue;
            }

            if (world.getTime() - callback.bootstrapCompletedAt() <= POST_BOOTSTRAP_CALLBACK_WARN_TICKS) {
                continue;
            }

            LOGGER.warn("event=lumberjack_post_bootstrap_callback_missing villager_uuid={} job_pos={} chest_pos={} elapsed_ticks={} threshold_ticks={}",
                    villager.getUuidAsString(),
                    callback.jobPos().toShortString(),
                    callback.chestPos().toShortString(),
                    world.getTime() - callback.bootstrapCompletedAt(),
                    POST_BOOTSTRAP_CALLBACK_WARN_TICKS);
            PENDING_POST_BOOTSTRAP_CALLBACKS.remove(villager);
        }
    }

    private static void reconcileAndMaybeConvert(ServerWorld world,
                                                 VillagerEntity villager,
                                                 @Nullable BlockPos jobPos,
                                                 @Nullable BlockPos chestPos,
                                                 @Nullable BlockPos changedChestPos,
                                                 String source) {
        Optional<ResolvedLumberjackContext> resolvedContext = resolveLumberjackContext(world, villager, jobPos, chestPos, source);
        if (resolvedContext.isEmpty()) {
            return;
        }

        ResolvedLumberjackContext context = resolvedContext.orElseThrow(() -> new IllegalStateException(
                "Resolved context unexpectedly absent for villager " + villager.getUuidAsString() + " source=" + source));
        handleBootstrapStallRecovery(world, villager, context.jobPos());

        Optional<LifecycleReconcileContext> lifecycle = advanceLifecycleForConversion(villager, context.jobPos(), context.chestPos(), source);
        if (lifecycle.isEmpty()) {
            return;
        }

        LifecycleReconcileContext lifecycleContext = lifecycle.orElseThrow(() -> new IllegalStateException(
                "Lifecycle context unexpectedly absent for villager " + villager.getUuidAsString() + " source=" + source));

        if (changedChestPos != null) {
            BlockPos pairedChestPos = lifecycleContext.chestPos();
            if (pairedChestPos == null) {
                logLifecycleBlocked(villager,
                        LumberjackLifecyclePhase.CONVERSION_PENDING,
                        "BLOCKED_CHEST_REQUIRED_FOR_EVENT_FILTER",
                        "source=" + source);
                return;
            }

            if (!changedChestPos.equals(pairedChestPos)
                    && !getObservedChestPositions(world, pairedChestPos).contains(changedChestPos.toImmutable())) {
                logLifecycleBlocked(villager,
                        LumberjackLifecyclePhase.CONVERSION_PENDING,
                        "BLOCKED_CHEST_EVENT_NOT_TRACKED",
                        "source=" + source + ",changed=" + changedChestPos.toShortString() + ",tracked=" + pairedChestPos.toShortString());
                return;
            }
        }

        tryConvertWithAxe(world, villager, lifecycleContext, source);
    }

    private static void handleBootstrapStallRecovery(ServerWorld world, VillagerEntity villager, BlockPos jobPos) {
        if (getPhase(villager) != LumberjackLifecyclePhase.BOOTSTRAP_PENDING) {
            return;
        }

        if (JobBlockPairingHelper.findNearbyChest(world, jobPos).isPresent()) {
            BOOTSTRAP_STARTED_AT.remove(villager);
            BOOTSTRAP_RECOVERY_ATTEMPTS.remove(villager);
            return;
        }

        long startedAt = BOOTSTRAP_STARTED_AT.computeIfAbsent(villager, ignored -> world.getTime());
        long elapsed = world.getTime() - startedAt;
        if (elapsed <= BOOTSTRAP_STALL_TIMEOUT_TICKS) {
            return;
        }

        int attempt = BOOTSTRAP_RECOVERY_ATTEMPTS.getOrDefault(villager, 0) + 1;
        BOOTSTRAP_RECOVERY_ATTEMPTS.put(villager, attempt);
        BOOTSTRAP_STARTED_AT.put(villager, world.getTime());

        LumberjackBootstrapGoal bootstrapGoal = getOrCreateBootstrapGoal(villager, jobPos);
        bootstrapGoal.setJobPos(jobPos);
        bootstrapGoal.requestImmediateStart();
        JobBlockPairingHelper.refreshVillagerPairings(world, villager);

        LOGGER.warn("LUMBERJACK_BOOTSTRAP_WARNING villager={} code=BOOTSTRAP_STALLED jobPos={} elapsedTicks={} attempt={} action=RETRY_BOOTSTRAP",
                villager.getUuidAsString(),
                jobPos.toShortString(),
                elapsed,
                attempt);

        if (attempt >= BOOTSTRAP_STALL_MAX_RETRIES) {
            forceDeterministicBootstrapFallback(world, villager, jobPos, attempt, elapsed);
        }
    }

    private static void forceDeterministicBootstrapFallback(ServerWorld world, VillagerEntity villager, BlockPos jobPos, int attempt, long elapsed) {
        if (JobBlockPairingHelper.findNearbyChest(world, jobPos).isPresent()) {
            BOOTSTRAP_STARTED_AT.remove(villager);
            BOOTSTRAP_RECOVERY_ATTEMPTS.remove(villager);
            return;
        }

        Optional<BlockPos> placement = findChestPlacementAdjacentToJob(world, jobPos);
        if (placement.isEmpty()) {
            LOGGER.warn("LUMBERJACK_BOOTSTRAP_WARNING villager={} code=BOOTSTRAP_STALLED jobPos={} elapsedTicks={} attempt={} action=FALLBACK_PLACE_FAILED",
                    villager.getUuidAsString(),
                    jobPos.toShortString(),
                    elapsed,
                    attempt);
            return;
        }

        BlockPos chestPos = placement.orElseThrow(() -> new IllegalStateException(
                "Chest placement unexpectedly absent for villager " + villager.getUuidAsString()));
        world.setBlockState(chestPos, Blocks.CHEST.getDefaultState());
        getChestInventoryOptional(world, chestPos).ifPresent(chest -> {
            chest.setStack(0, new ItemStack(Items.OAK_PLANKS, 11));
            chest.setStack(1, new ItemStack(Items.STICK, 2));
            chest.setStack(2, new ItemStack(Items.WOODEN_AXE, 1));
            chest.markDirty();
        });

        queueInitialCountdown(villager, FALLBACK_STARTUP_COUNTDOWN_TICKS);
        JobBlockPairingHelper.handlePairingBlockPlacement(world, chestPos, world.getBlockState(chestPos));
        runPostBootstrapHandoff(world, villager, jobPos, chestPos, false, "FALLBACK_CHEST_PLACED");
        JobBlockPairingHelper.refreshVillagerPairings(world, villager);
        BOOTSTRAP_STARTED_AT.remove(villager);
        BOOTSTRAP_RECOVERY_ATTEMPTS.remove(villager);

        LOGGER.warn("LUMBERJACK_BOOTSTRAP_WARNING villager={} code=BOOTSTRAP_STALLED jobPos={} elapsedTicks={} attempt={} action=FALLBACK_PLACE_CHEST chestPos={}",
                villager.getUuidAsString(),
                jobPos.toShortString(),
                elapsed,
                attempt,
                chestPos.toShortString());
    }

    private static LumberjackBootstrapGoal getOrCreateBootstrapGoal(VillagerEntity villager, BlockPos jobPos) {
        LumberjackBootstrapGoal goal = BOOTSTRAP_GOALS.get(villager);
        if (goal != null) {
            return goal;
        }

        goal = new LumberjackBootstrapGoal(villager, jobPos);
        BOOTSTRAP_GOALS.put(villager, goal);
        villager.goalSelector.add(BOOTSTRAP_GOAL_PRIORITY, goal);
        return goal;
    }

    private static Optional<BlockPos> findChestPlacementAdjacentToJob(ServerWorld world, BlockPos jobPos) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos candidate = jobPos.offset(direction);
            if (!world.getBlockState(candidate).isAir()) {
                continue;
            }
            if (!world.getBlockState(candidate.down()).isSolidBlock(world, candidate.down())) {
                continue;
            }
            return Optional.of(candidate.toImmutable());
        }
        return Optional.empty();
    }

    private static void tryConvertWithAxe(ServerWorld world, VillagerEntity villager, LifecycleReconcileContext context, String source) {
        if (!villager.isAlive() || villager.getVillagerData().getProfession() != LumberjackProfession.LUMBERJACK) {
            return;
        }
        BlockPos jobPos = context.jobPos();
        @Nullable BlockPos chestPos = context.chestPos();
        if (getPhase(villager) != LumberjackLifecyclePhase.CONVERSION_PENDING) {
            logLifecycleBlocked(villager,
                    LumberjackLifecyclePhase.CONVERSION_PENDING,
                    "BLOCKED_PHASE_NOT_PENDING",
                    "source=" + source + ",phase=" + getPhase(villager));
            return;
        }

        AxeGuardEntity guard = GuardVillagers.AXE_GUARD_VILLAGER.create(world);
        if (guard == null) {
            return;
        }

        transitionPhase(villager, LumberjackLifecyclePhase.CONVERTED_ACTIVE,
                "CONVERSION_STARTED",
                "source=" + source);

        ResolvedConversionAxe resolvedAxe = resolveConversionAxe(world, villager, chestPos);
        ItemStack axeStack = resolvedAxe.axeStack();

        guard.initialize(world, world.getLocalDifficulty(jobPos), SpawnReason.CONVERSION, null);
        guard.spawnWithArmor = false;
        guard.copyPositionAndRotation(villager);
        guard.headYaw = villager.headYaw;
        guard.refreshPositionAndAngles(villager.getX(), villager.getY(), villager.getZ(), villager.getYaw(), villager.getPitch());
        guard.setGuardVariant(GuardEntity.getRandomTypeForBiome(world, guard.getBlockPos()));
        guard.setPersistent();
        guard.setCustomName(villager.getCustomName());
        guard.setCustomNameVisible(villager.isCustomNameVisible());
        guard.setEquipmentDropChance(EquipmentSlot.HEAD, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.CHEST, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.LEGS, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.FEET, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.MAINHAND, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.OFFHAND, 100.0F);

        guard.equipStack(EquipmentSlot.HEAD, ItemStack.EMPTY);
        guard.equipStack(EquipmentSlot.CHEST, ItemStack.EMPTY);
        guard.equipStack(EquipmentSlot.LEGS, ItemStack.EMPTY);
        guard.equipStack(EquipmentSlot.FEET, ItemStack.EMPTY);
        guard.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        guard.equipStack(EquipmentSlot.OFFHAND, ItemStack.EMPTY);

        guard.equipStack(EquipmentSlot.MAINHAND, axeStack);
        guard.setJobPos(jobPos);
        if (chestPos != null) {
            guard.setChestPos(chestPos);
            resolveCraftingTablePos(world, jobPos, chestPos).ifPresent(guard::setCraftingTablePos);
            for (BlockPos checkPos : BlockPos.iterate(chestPos.add(-3, -2, -3), chestPos.add(3, 2, 3))) {
                if (world.getBlockState(checkPos).isOf(Blocks.FURNACE) && guard.getPairedFurnacePos() == null) {
                    guard.setPairedFurnacePos(checkPos.toImmutable());
                }
            }
        }

        Long pendingCountdown = PENDING_INITIAL_COUNTDOWNS.remove(villager);
        long transferredCountdown = pendingCountdown != null ? pendingCountdown : 20L * 20L;
        guard.initializeConvertedWorkflow(world, transferredCountdown);

        world.spawnEntityAndPassengers(guard);
        VillageGuardStandManager.handleGuardSpawn(world, guard, villager);

        LOGGER.info("Lumberjack {} converted into Axe Guard {} using axe from {} ({})",
                villager.getUuidAsString(),
                guard.getUuidAsString(),
                resolvedAxe.source(),
                source);

        villager.releaseTicketFor(MemoryModuleType.HOME);
        villager.releaseTicketFor(MemoryModuleType.JOB_SITE);
        villager.releaseTicketFor(MemoryModuleType.MEETING_POINT);
        BOOTSTRAP_STARTED_AT.remove(villager);
        BOOTSTRAP_RECOVERY_ATTEMPTS.remove(villager);
        villager.discard();

    }

    private static Optional<ResolvedLumberjackContext> resolveLumberjackContext(ServerWorld world,
                                                                                 VillagerEntity villager,
                                                                                 @Nullable BlockPos jobPos,
                                                                                 @Nullable BlockPos chestPos,
                                                                                 String source) {
        LumberjackLifecyclePhase currentPhase = getPhase(villager);
        if (currentPhase == LumberjackLifecyclePhase.CONVERTED_ACTIVE) {
            logLifecycleBlocked(villager,
                    LumberjackLifecyclePhase.CONVERSION_PENDING,
                    "BLOCKED_ALREADY_CONVERTED",
                    "source=" + source);
            return Optional.empty();
        }

        BlockPos candidateJobPos = jobPos != null
                ? jobPos
                : villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE)
                .filter(globalPos -> globalPos.dimension().equals(world.getRegistryKey()))
                .map(GlobalPos::pos)
                .orElse(null);

        final BlockPos resolvedJobPos = candidateJobPos;

        if (resolvedJobPos == null || !ProfessionDefinitions.isExpectedJobBlock(LumberjackProfession.LUMBERJACK, world.getBlockState(resolvedJobPos))) {
            logLifecycleBlocked(villager,
                    LumberjackLifecyclePhase.CONVERSION_PENDING,
                    "BLOCKED_INVALID_JOB_BLOCK",
                    "source=" + source + ",jobPos=" + (resolvedJobPos == null ? "<none>" : resolvedJobPos.toShortString()));
            return Optional.empty();
        }

        BlockPos resolvedChestPos = chestPos != null
                ? chestPos
                : JobBlockPairingHelper.findNearbyChest(world, resolvedJobPos)
                .filter(pos -> resolvedJobPos.isWithinDistance(pos, 3.0D))
                .orElse(null);

        return Optional.of(new ResolvedLumberjackContext(resolvedJobPos, resolvedChestPos));
    }

    private static Optional<LifecycleReconcileContext> advanceLifecycleForConversion(VillagerEntity villager,
                                                                                      BlockPos resolvedJobPos,
                                                                                      @Nullable BlockPos resolvedChestPos,
                                                                                      String source) {
        LumberjackLifecyclePhase currentPhase = getPhase(villager);

        boolean bootstrapComplete = currentPhase.ordinal() >= LumberjackLifecyclePhase.BOOTSTRAP_COMPLETE.ordinal();
        if (!bootstrapComplete && PENDING_INITIAL_COUNTDOWNS.containsKey(villager)) {
            bootstrapComplete = true;
            transitionPhase(villager,
                    LumberjackLifecyclePhase.BOOTSTRAP_COMPLETE,
                    "BOOTSTRAP_COMPLETE_COUNTDOWN_FALLBACK",
                    "source=" + source);
            currentPhase = getPhase(villager);
        }

        if (!bootstrapComplete && !isBootstrapGoalActiveFor(villager, resolvedJobPos)) {
            bootstrapComplete = true;
            transitionPhase(villager,
                    LumberjackLifecyclePhase.BOOTSTRAP_COMPLETE,
                    "BOOTSTRAP_COMPLETE_GOAL_FALLBACK",
                    "source=" + source + ",jobPos=" + resolvedJobPos.toShortString());
            currentPhase = getPhase(villager);
        }

        if (!bootstrapComplete) {
            logLifecycleBlocked(villager,
                    LumberjackLifecyclePhase.CONVERSION_PENDING,
                    "BLOCKED_BOOTSTRAP_INCOMPLETE",
                    "source=" + source + ",phase=" + currentPhase);
            return Optional.empty();
        }

        if (currentPhase == LumberjackLifecyclePhase.BOOTSTRAP_COMPLETE) {
            transitionPhase(villager,
                    LumberjackLifecyclePhase.CONVERSION_PENDING,
                    "ADVANCED_TO_CONVERSION_PENDING",
                    "source=" + source + ",chestPresent=" + (resolvedChestPos != null));
            currentPhase = getPhase(villager);
        }

        if (currentPhase != LumberjackLifecyclePhase.CONVERSION_PENDING) {
            logLifecycleBlocked(villager,
                    LumberjackLifecyclePhase.CONVERSION_PENDING,
                    "BLOCKED_PHASE_NOT_PENDING",
                    "source=" + source + ",phase=" + currentPhase);
            return Optional.empty();
        }

        return Optional.of(new LifecycleReconcileContext(resolvedJobPos, resolvedChestPos));
    }

    private static LumberjackLifecyclePhase getPhase(VillagerEntity villager) {
        return LIFECYCLE_PHASES.getOrDefault(villager, LumberjackLifecyclePhase.BOOTSTRAP_PENDING);
    }

    private static void transitionPhase(VillagerEntity villager, LumberjackLifecyclePhase nextPhase, String reasonCode, String reasonDetail) {
        LumberjackLifecyclePhase previousPhase = LIFECYCLE_PHASES.put(villager, nextPhase);
        if (previousPhase != nextPhase) {
            LOGGER.info("LUMBERJACK_LIFECYCLE transition villager={} from={} to={} code={} detail={}",
                    villager.getUuidAsString(),
                    previousPhase == null ? "<unset>" : previousPhase,
                    nextPhase,
                    reasonCode,
                    reasonDetail);
        }
    }

    private static void logLifecycleBlocked(VillagerEntity villager, LumberjackLifecyclePhase targetPhase, String reasonCode, String reasonDetail) {
        LOGGER.info("LUMBERJACK_LIFECYCLE blocked villager={} current={} target={} code={} detail={}",
                villager.getUuidAsString(),
                getPhase(villager),
                targetPhase,
                reasonCode,
                reasonDetail);
    }

    private static ResolvedConversionAxe resolveConversionAxe(ServerWorld world, VillagerEntity villager, @Nullable BlockPos chestPos) {
        ItemStack mainHand = villager.getMainHandStack();
        if (!mainHand.isEmpty() && mainHand.getItem() instanceof AxeItem) {
            return new ResolvedConversionAxe(mainHand.split(1), "villager main hand");
        }

        ItemStack offHand = villager.getOffHandStack();
        if (!offHand.isEmpty() && offHand.getItem() instanceof AxeItem) {
            return new ResolvedConversionAxe(offHand.split(1), "villager off hand");
        }

        Inventory villagerInventory = villager.getInventory();
        for (int slot = 0; slot < villagerInventory.size(); slot++) {
            ItemStack stack = villagerInventory.getStack(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof AxeItem) {
                ItemStack extracted = stack.split(1);
                villagerInventory.markDirty();
                return new ResolvedConversionAxe(extracted, "villager inventory slot " + slot);
            }
        }

        if (chestPos != null) {
            Inventory chestInventory = getChestInventoryOptional(world, chestPos).orElse(null);
            if (chestInventory != null) {
                for (int slot = 0; slot < chestInventory.size(); slot++) {
                    ItemStack stack = chestInventory.getStack(slot);
                    if (!stack.isEmpty() && stack.getItem() instanceof AxeItem) {
                        ItemStack extracted = stack.split(1);
                        chestInventory.markDirty();
                        return new ResolvedConversionAxe(extracted,
                                "paired chest " + chestPos.toShortString() + " slot " + slot);
                    }
                }
            }
        }

        return new ResolvedConversionAxe(new ItemStack(Items.WOODEN_AXE), "default fallback wooden axe");
    }


    private record ResolvedConversionAxe(ItemStack axeStack, String source) {
    }

    private record LifecycleReconcileContext(BlockPos jobPos, @Nullable BlockPos chestPos) {
    }

    private record ResolvedLumberjackContext(BlockPos jobPos, @Nullable BlockPos chestPos) {
    }

    private static Optional<Inventory> getChestInventoryOptional(ServerWorld world, BlockPos chestPos) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }

        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, chestPos, true));
    }

    private static void updateChestListener(ServerWorld world, VillagerEntity villager, BlockPos chestPos) {
        Set<BlockPos> observedChestPositions = getObservedChestPositions(world, chestPos);
        if (observedChestPositions.isEmpty()) {
            clearChestListener(villager);
            return;
        }

        ChestRegistration existing = CHEST_REGISTRATIONS.get(villager);
        if (existing != null && existing.observedChestPositions().equals(observedChestPositions)) {
            return;
        }

        if (existing != null) {
            removeChestListener(existing);
            CHEST_REGISTRATIONS.remove(villager);
        }

        for (BlockPos observedPos : observedChestPositions) {
            CHEST_WATCHERS_BY_POS.computeIfAbsent(observedPos, ignored -> new HashSet<>()).add(villager);
        }

        CHEST_REGISTRATIONS.put(villager, new ChestRegistration(villager, observedChestPositions));
    }

    private static void clearChestListener(VillagerEntity villager) {
        ChestRegistration existing = CHEST_REGISTRATIONS.remove(villager);
        if (existing != null) {
            removeChestListener(existing);
        }
    }

    private static void removeChestListener(ChestRegistration existing) {
        for (BlockPos observedPos : existing.observedChestPositions()) {
            Set<VillagerEntity> watchers = CHEST_WATCHERS_BY_POS.get(observedPos);
            if (watchers == null) {
                continue;
            }
            watchers.remove(existing.villager());
            if (watchers.isEmpty()) {
                CHEST_WATCHERS_BY_POS.remove(observedPos);
            }
        }
    }


    private static Optional<BlockPos> resolveNearbyFurnacePos(ServerWorld world, BlockPos chestPos) {
        for (BlockPos checkPos : BlockPos.iterate(chestPos.add(-3, -2, -3), chestPos.add(3, 2, 3))) {
            if (world.getBlockState(checkPos).isOf(Blocks.FURNACE)) {
                return Optional.of(checkPos.toImmutable());
            }
        }

        return Optional.empty();
    }
    private static Optional<BlockPos> resolveCraftingTablePos(ServerWorld world, BlockPos jobPos, BlockPos chestPos) {
        if (world.getBlockState(jobPos).isOf(Blocks.CRAFTING_TABLE)) {
            return Optional.of(jobPos.toImmutable());
        }

        int range = (int) Math.ceil(CHEST_PAIR_RANGE);
        for (BlockPos checkPos : BlockPos.iterate(jobPos.add(-range, -range, -range), jobPos.add(range, range, range))) {
            if (!jobPos.isWithinDistance(checkPos, CHEST_PAIR_RANGE)) {
                continue;
            }
            if (!chestPos.isWithinDistance(checkPos, CHEST_PAIR_RANGE)) {
                continue;
            }
            if (world.getBlockState(checkPos).isOf(Blocks.CRAFTING_TABLE)) {
                return Optional.of(checkPos.toImmutable());
            }
        }

        return Optional.empty();
    }

    private static Set<BlockPos> getObservedChestPositions(ServerWorld world, BlockPos chestPos) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock)) {
            return Set.of();
        }

        Set<BlockPos> positions = new HashSet<>();
        positions.add(chestPos.toImmutable());

        ChestType chestType = state.get(ChestBlock.CHEST_TYPE);
        if (chestType != ChestType.SINGLE) {
            Direction facing = state.get(ChestBlock.FACING);
            Direction offsetDirection = chestType == ChestType.LEFT
                    ? facing.rotateYClockwise()
                    : facing.rotateYCounterclockwise();
            BlockPos otherHalfPos = chestPos.offset(offsetDirection);
            BlockState otherState = world.getBlockState(otherHalfPos);
            if (otherState.getBlock() instanceof ChestBlock && otherState.get(ChestBlock.FACING) == facing) {
                positions.add(otherHalfPos.toImmutable());
            }
        }

        return positions;
    }

    private record ChestRegistration(VillagerEntity villager, Set<BlockPos> observedChestPositions) {
        private ChestRegistration(VillagerEntity villager, Set<BlockPos> observedChestPositions) {
            this.villager = villager;
            this.observedChestPositions = Set.copyOf(observedChestPositions);
        }
    }

    private record PendingPostBootstrapCallback(BlockPos jobPos, BlockPos chestPos, long bootstrapCompletedAt) {
    }
}
