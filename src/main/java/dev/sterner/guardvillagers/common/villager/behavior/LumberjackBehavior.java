package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.AxeGuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.goal.LumberjackBootstrapGoal;
import dev.sterner.guardvillagers.common.entity.goal.LumberjackCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.LumberjackDistributionGoal;
import dev.sterner.guardvillagers.common.entity.goal.LumberjackFurnaceGoal;
import dev.sterner.guardvillagers.common.entity.goal.LumberjackGatheringGoal;
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
    private static final int GATHERING_GOAL_PRIORITY = 3;
    private static final int FURNACE_GOAL_PRIORITY = 4;
    private static final int CRAFTING_GOAL_PRIORITY = 5;
    private static final int DISTRIBUTION_GOAL_PRIORITY = 6;

    private static final Map<VillagerEntity, LumberjackBootstrapGoal> BOOTSTRAP_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, LumberjackGatheringGoal> GATHERING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, LumberjackCraftingGoal> CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, LumberjackFurnaceGoal> FURNACE_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, LumberjackDistributionGoal> DISTRIBUTION_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, BlockPos> PAIRED_FURNACES = new WeakHashMap<>();
    private static final Map<VillagerEntity, Long> PENDING_INITIAL_COUNTDOWNS = new WeakHashMap<>();
    private static final Map<VillagerEntity, LumberjackLifecyclePhase> LIFECYCLE_PHASES = new WeakHashMap<>();

    private static final Map<VillagerEntity, ChestRegistration> CHEST_REGISTRATIONS = new WeakHashMap<>();
    private static final Map<BlockPos, Set<VillagerEntity>> CHEST_WATCHERS_BY_POS = new HashMap<>();

    public static void queueInitialCountdown(VillagerEntity villager, long ticks) {
        PENDING_INITIAL_COUNTDOWNS.put(villager, Math.max(20L, ticks));
        transitionPhase(villager, LumberjackLifecyclePhase.BOOTSTRAP_COMPLETE,
                "BOOTSTRAP_COUNTDOWN_QUEUED",
                "bootstrap countdown queued");
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

        LumberjackBootstrapGoal bootstrapGoal = upsertGoal(BOOTSTRAP_GOALS, villager, BOOTSTRAP_GOAL_PRIORITY,
                () -> new LumberjackBootstrapGoal(villager, jobPos));
        bootstrapGoal.setJobPos(jobPos);
        bootstrapGoal.requestImmediateStart();

        LOGGER.info("Lumberjack {} started startup workflow at {}",
                villager.getUuidAsString(),
                jobPos.toShortString());
    }

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!checkPairingPreconditions(world, villager, jobPos, chestPos,
                (serverWorld, pos) -> ProfessionDefinitions.isExpectedJobBlock(LumberjackProfession.LUMBERJACK, serverWorld.getBlockState(pos)),
                () -> {
                    clearChestListener(villager);
                    PAIRED_FURNACES.remove(villager);
                    PENDING_INITIAL_COUNTDOWNS.remove(villager);
                    LIFECYCLE_PHASES.remove(villager);
                })) {
            return;
        }

        LOGGER.info("Lumberjack {} paired chest at {} for job site {}",
                villager.getUuidAsString(),
                chestPos.toShortString(),
                jobPos.toShortString());

        Optional<BlockPos> resolvedFurnace = resolvePairedFurnace(world, jobPos, chestPos);
        resolvedFurnace.ifPresentOrElse(
                pos -> PAIRED_FURNACES.put(villager, pos),
                () -> PAIRED_FURNACES.remove(villager)
        );

        updateChestListener(world, villager, chestPos);

        Optional<LifecycleReconcileContext> context = reconcileLifecycle(world, villager, jobPos, Optional.of(chestPos), "CHEST_PAIRED_WORKFLOW");
        if (context.isEmpty()) {
            return;
        }

        tryConvertWithAxe(world, villager, context.get(), "CHEST_PAIRED_WORKFLOW");
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        transitionPhase(villager, LumberjackLifecyclePhase.BOOTSTRAP_PENDING,
                "CRAFTING_TABLE_JOB_PAIRED",
                "crafting-table/job pairing");

        LumberjackBootstrapGoal bootstrapGoal = upsertGoal(BOOTSTRAP_GOALS, villager, BOOTSTRAP_GOAL_PRIORITY,
                () -> new LumberjackBootstrapGoal(villager, jobPos));
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
        if (jobSite.isEmpty()) {
            return false;
        }

        GlobalPos globalPos = jobSite.get();
        return globalPos.pos().equals(jobPos);
    }

    @Override
    public void onSpecialModifierPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, SpecialModifier modifier, BlockPos modifierPos) {
        if (!modifier.block().equals(Blocks.FURNACE)) {
            return;
        }

        BlockPos furnacePos = modifierPos.toImmutable();
        PAIRED_FURNACES.put(villager, furnacePos);

        LumberjackFurnaceGoal furnaceGoal = upsertGoal(FURNACE_GOALS, villager, FURNACE_GOAL_PRIORITY,
                () -> new LumberjackFurnaceGoal(villager, jobPos, chestPos, furnacePos));
        furnaceGoal.setTargets(jobPos, chestPos, furnacePos);
        furnaceGoal.requestImmediateCheck();

        LumberjackCraftingGoal craftingGoal = CRAFTING_GOALS.get(villager);
        if (craftingGoal != null) {
            craftingGoal.setPairedFurnacePos(furnacePos);
            craftingGoal.requestImmediateCheck(world);
        }

        LumberjackDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
        if (distributionGoal != null) {
            distributionGoal.setPairedFurnacePos(furnacePos);
            distributionGoal.requestImmediateDistribution();
        }

        LOGGER.info("Lumberjack {} paired furnace modifier at {}", villager.getUuidAsString(), furnacePos.toShortString());
    }

    public static void onChestInventoryMutated(ServerWorld world, BlockPos chestPos) {
        Set<VillagerEntity> villagers = CHEST_WATCHERS_BY_POS.get(chestPos);
        if (villagers == null || villagers.isEmpty()) {
            return;
        }

        for (VillagerEntity villager : Set.copyOf(villagers)) {
            if (!villager.isAlive() || villager.getWorld() != world) {
                continue;
            }

            LumberjackGatheringGoal gatheringGoal = GATHERING_GOALS.get(villager);
            if (gatheringGoal != null) {
                gatheringGoal.requestImmediateCheck();
            }

            LumberjackCraftingGoal craftingGoal = CRAFTING_GOALS.get(villager);
            if (craftingGoal != null) {
                craftingGoal.onChestInventoryChanged(world);
                craftingGoal.requestImmediateCheck(world);
            }

            LumberjackFurnaceGoal furnaceGoal = FURNACE_GOALS.get(villager);
            if (furnaceGoal != null) {
                furnaceGoal.requestImmediateCheck();
            }

            LumberjackDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
            if (distributionGoal != null) {
                distributionGoal.requestImmediateDistribution();
            }

            if (villager.getVillagerData().getProfession() == LumberjackProfession.LUMBERJACK) {
                VillagerConversionCandidateIndex.markCandidate(world, villager);
                Optional<LifecycleReconcileContext> context = reconcileLifecycle(world, villager, null, Optional.of(chestPos), "CHEST_MUTATION");
                if (context.isPresent()) {
                    tryConvertOnChestMutation(world, villager, chestPos, context.get());
                }
            }
        }
    }

    private static void tryConvertOnChestMutation(ServerWorld world, VillagerEntity villager, BlockPos changedChestPos, LifecycleReconcileContext context) {
        BlockPos pairedChestPos = context.chestPos().orElse(null);
        if (pairedChestPos == null) {
            logLifecycleBlocked(villager,
                    LumberjackLifecyclePhase.CONVERSION_PENDING,
                    "BLOCKED_CHEST_REQUIRED_FOR_EVENT_FILTER",
                    "source=CHEST_MUTATION");
            return;
        }

        if (!changedChestPos.equals(pairedChestPos)
                && !getObservedChestPositions(world, pairedChestPos).contains(changedChestPos.toImmutable())) {
            logLifecycleBlocked(villager,
                    LumberjackLifecyclePhase.CONVERSION_PENDING,
                    "BLOCKED_CHEST_EVENT_NOT_TRACKED",
                    "source=CHEST_MUTATION,changed=" + changedChestPos.toShortString() + ",tracked=" + pairedChestPos.toShortString());
            return;
        }

        tryConvertWithAxe(world, villager, context, "CHEST_MUTATION");
    }

    public static void tryConvertLumberjacksWithAxe(ServerWorld world) {
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
            if (jobSite.isEmpty() || !jobSite.get().dimension().equals(world.getRegistryKey())) {
                continue;
            }

            BlockPos jobPos = jobSite.get().pos();
            if (!ProfessionDefinitions.isExpectedJobBlock(LumberjackProfession.LUMBERJACK, world.getBlockState(jobPos))) {
                continue;
            }

            Optional<LifecycleReconcileContext> context = reconcileLifecycle(world, villager, jobPos, Optional.empty(), "CANDIDATE_SCAN");
            if (context.isEmpty()) {
                continue;
            }

            tryConvertWithAxe(world, villager, context.get(), "CANDIDATE_SCAN");
        }
    }

    private static void tryConvertWithAxe(ServerWorld world, VillagerEntity villager, LifecycleReconcileContext context, String source) {
        if (!villager.isAlive() || villager.getVillagerData().getProfession() != LumberjackProfession.LUMBERJACK) {
            return;
        }
        BlockPos jobPos = context.jobPos();
        Optional<BlockPos> chestPos = context.chestPos();
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
        chestPos.ifPresent(guard::setChestPos);
        chestPos.ifPresent(pos -> {
            if (world.getBlockState(jobPos).isOf(Blocks.CRAFTING_TABLE)) {
                guard.setCraftingTablePos(jobPos);
            }
            for (BlockPos checkPos : BlockPos.iterate(pos.add(-3, -2, -3), pos.add(3, 2, 3))) {
                if (world.getBlockState(checkPos).isOf(Blocks.CRAFTING_TABLE) && guard.getCraftingTablePos() == null) {
                    guard.setCraftingTablePos(checkPos.toImmutable());
                }
                if (world.getBlockState(checkPos).isOf(Blocks.FURNACE) && guard.getPairedFurnacePos() == null) {
                    guard.setPairedFurnacePos(checkPos.toImmutable());
                }
            }
        });

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
        villager.discard();

    }

    private static Optional<LifecycleReconcileContext> reconcileLifecycle(ServerWorld world, VillagerEntity villager, BlockPos jobPos, Optional<BlockPos> chestPos, String source) {
        LumberjackLifecyclePhase currentPhase = getPhase(villager);
        if (currentPhase == LumberjackLifecyclePhase.CONVERTED_ACTIVE) {
            logLifecycleBlocked(villager,
                    LumberjackLifecyclePhase.CONVERSION_PENDING,
                    "BLOCKED_ALREADY_CONVERTED",
                    "source=" + source);
            return Optional.empty();
        }

        BlockPos candidateJobPos = jobPos;
        if (candidateJobPos == null) {
            Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
            if (jobSite.isPresent() && jobSite.get().dimension().equals(world.getRegistryKey())) {
                candidateJobPos = jobSite.get().pos();
            }
        }

        final BlockPos resolvedJobPos = candidateJobPos;

        if (resolvedJobPos == null || !ProfessionDefinitions.isExpectedJobBlock(LumberjackProfession.LUMBERJACK, world.getBlockState(resolvedJobPos))) {
            logLifecycleBlocked(villager,
                    LumberjackLifecyclePhase.CONVERSION_PENDING,
                    "BLOCKED_INVALID_JOB_BLOCK",
                    "source=" + source + ",jobPos=" + (resolvedJobPos == null ? "<none>" : resolvedJobPos.toShortString()));
            return Optional.empty();
        }

        Optional<BlockPos> resolvedChestPos = chestPos.isPresent()
                ? chestPos
                : JobBlockPairingHelper.findNearbyChest(world, resolvedJobPos).filter(pos -> resolvedJobPos.isWithinDistance(pos, 3.0D));

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
                    "source=" + source + ",chestPresent=" + resolvedChestPos.isPresent());
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

    private static ResolvedConversionAxe resolveConversionAxe(ServerWorld world, VillagerEntity villager, Optional<BlockPos> chestPos) {
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

        if (chestPos.isPresent()) {
            Inventory chestInventory = getChestInventoryOptional(world, chestPos.get()).orElse(null);
            if (chestInventory != null) {
                for (int slot = 0; slot < chestInventory.size(); slot++) {
                    ItemStack stack = chestInventory.getStack(slot);
                    if (!stack.isEmpty() && stack.getItem() instanceof AxeItem) {
                        ItemStack extracted = stack.split(1);
                        chestInventory.markDirty();
                        return new ResolvedConversionAxe(extracted,
                                "paired chest " + chestPos.get().toShortString() + " slot " + slot);
                    }
                }
            }
        }

        return new ResolvedConversionAxe(new ItemStack(Items.WOODEN_AXE), "default fallback wooden axe");
    }


    private record ResolvedConversionAxe(ItemStack axeStack, String source) {
    }

    private record LifecycleReconcileContext(BlockPos jobPos, Optional<BlockPos> chestPos) {
    }

    private static Optional<Inventory> getChestInventoryOptional(ServerWorld world, BlockPos chestPos) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }

        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, chestPos, true));
    }

    private void updateChestListener(ServerWorld world, VillagerEntity villager, BlockPos chestPos) {
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

    private void clearChestListener(VillagerEntity villager) {
        ChestRegistration existing = CHEST_REGISTRATIONS.remove(villager);
        if (existing != null) {
            removeChestListener(existing);
        }
    }

    private void removeChestListener(ChestRegistration existing) {
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

    private Optional<BlockPos> resolveCraftingTablePos(ServerWorld world, BlockPos jobPos, BlockPos chestPos) {
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

    private Optional<BlockPos> resolvePairedFurnace(ServerWorld world, BlockPos jobPos, BlockPos chestPos) {
        int range = (int) Math.ceil(CHEST_PAIR_RANGE);
        for (BlockPos checkPos : BlockPos.iterate(jobPos.add(-range, -range, -range), jobPos.add(range, range, range))) {
            if (!jobPos.isWithinDistance(checkPos, CHEST_PAIR_RANGE)) {
                continue;
            }
            if (!chestPos.isWithinDistance(checkPos, CHEST_PAIR_RANGE)) {
                continue;
            }
            if (world.getBlockState(checkPos).isOf(Blocks.FURNACE)) {
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
}
