package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.MasonGuardEntity;
import dev.sterner.guardvillagers.common.entity.goal.MasonCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.MasonTableCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.MasonToLibrarianDistributionGoal;
import dev.sterner.guardvillagers.common.entity.goal.MasonCraftingGoal.CraftingCheckTrigger;
import dev.sterner.guardvillagers.common.util.BellChestMappingState;
import dev.sterner.guardvillagers.common.util.ConvertedWorkerJobSiteReservationManager;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.util.PairedStorageHelper;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import dev.sterner.guardvillagers.common.villager.GuardConversionHelper;
import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehavior;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import dev.sterner.guardvillagers.common.villager.VillagerConversionCandidateIndex;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.PickaxeItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

public class MasonBehavior implements VillagerProfessionBehavior {
    private static final Logger LOGGER = LoggerFactory.getLogger(MasonBehavior.class);
    private static final int CRAFTING_GOAL_PRIORITY = 4;
    private static final int DISTRIBUTION_GOAL_PRIORITY = 5;
    private static final int TABLE_CRAFTING_GOAL_PRIORITY = 6;
    private static final Map<VillagerEntity, MasonCraftingGoal> CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, MasonToLibrarianDistributionGoal> DISTRIBUTION_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, MasonTableCraftingGoal> TABLE_CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ChestRegistration> CHEST_REGISTRATIONS = new WeakHashMap<>();
    private static final Map<BlockPos, Set<VillagerEntity>> CHEST_WATCHERS_BY_POS = new HashMap<>();
    private static final Map<VillagerEntity, Long> NEXT_CONVERSION_SCAN_TICK = new WeakHashMap<>();
    private static final long CHEST_SCAN_COOLDOWN_TICKS = 10L;

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!villager.isAlive()) {
            clearChestListener(villager);
            return;
        }

        if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.MASON, world.getBlockState(jobPos))) {
            clearChestListener(villager);
            return;
        }

        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
            clearChestListener(villager);
            return;
        }

        LOGGER.info("Mason {} paired chest at {} for job site {}",
                villager.getUuidAsString(),
                chestPos.toShortString(),
                jobPos.toShortString());

        MasonCraftingGoal craftingGoal = CRAFTING_GOALS.get(villager);
        if (craftingGoal == null) {
            craftingGoal = new MasonCraftingGoal(villager, jobPos, chestPos);
            CRAFTING_GOALS.put(villager, craftingGoal);
            GoalSelector selector = villager.goalSelector;
            selector.add(CRAFTING_GOAL_PRIORITY, craftingGoal);
        } else {
            craftingGoal.setTargets(jobPos, chestPos);
        }
        craftingGoal.requestImmediateCraft(world, CraftingCheckTrigger.CHEST_PAIRED);

        MasonToLibrarianDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
        if (distributionGoal == null) {
            distributionGoal = new MasonToLibrarianDistributionGoal(villager, jobPos, chestPos, null);
            DISTRIBUTION_GOALS.put(villager, distributionGoal);
            GoalSelector selector = villager.goalSelector;
            selector.add(DISTRIBUTION_GOAL_PRIORITY, distributionGoal);
        } else {
            distributionGoal.setTargets(jobPos, chestPos, distributionGoal.getCraftingTablePos());
        }
        distributionGoal.requestImmediateDistribution();

        updateChestListener(world, villager, chestPos);
        tryConvertWithMiningTool(world, villager, jobPos, chestPos, "chest paired workflow");
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        if (!villager.isAlive()) {
            clearChestListener(villager);
            return;
        }

        if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.MASON, world.getBlockState(jobPos))) {
            clearChestListener(villager);
            return;
        }

        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
            clearChestListener(villager);
            return;
        }

        MasonTableCraftingGoal goal = TABLE_CRAFTING_GOALS.get(villager);
        if (goal == null) {
            goal = new MasonTableCraftingGoal(villager, jobPos, chestPos, craftingTablePos);
            TABLE_CRAFTING_GOALS.put(villager, goal);
            villager.goalSelector.add(TABLE_CRAFTING_GOAL_PRIORITY, goal);
        } else {
            goal.setTargets(jobPos, chestPos, craftingTablePos);
        }

        goal.requestImmediateCraft(world);

        MasonToLibrarianDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
        if (distributionGoal == null) {
            distributionGoal = new MasonToLibrarianDistributionGoal(villager, jobPos, chestPos, craftingTablePos);
            DISTRIBUTION_GOALS.put(villager, distributionGoal);
            villager.goalSelector.add(DISTRIBUTION_GOAL_PRIORITY, distributionGoal);
        } else {
            distributionGoal.setTargets(jobPos, chestPos, craftingTablePos);
        }
        distributionGoal.requestImmediateDistribution();

        updateChestListener(world, villager, chestPos);
    }

    public static void tryConvertMasonsWithMiningTool(ServerWorld world) {
        for (VillagerEntity villager : VillagerConversionCandidateIndex.pollCandidates(world, VillagerProfession.MASON)) {
            Optional<BlockPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE).map(net.minecraft.util.math.GlobalPos::pos);
            if (jobSite.isEmpty()) {
                continue;
            }

            BlockPos jobPos = jobSite.get();
            if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.MASON, world.getBlockState(jobPos))) {
                continue;
            }

            Optional<BlockPos> chestPos = JobBlockPairingHelper.findNearbyChest(world, jobPos);
            if (chestPos.isEmpty() || !jobPos.isWithinDistance(chestPos.get(), 3.0D)) {
                continue;
            }

            tryConvertWithMiningTool(world, villager, jobPos, chestPos.get(), "candidate scan");
        }
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

    public static void onChestInventoryMutated(ServerWorld world, BlockPos chestPos) {
        Set<VillagerEntity> villagers = CHEST_WATCHERS_BY_POS.get(chestPos);
        if (villagers == null || villagers.isEmpty()) {
            return;
        }

        Set<VillagerEntity> snapshot = Set.copyOf(villagers);
        for (VillagerEntity villager : snapshot) {
            if (!villager.isAlive() || villager.getWorld() != world) {
                continue;
            }

            MasonCraftingGoal goal = CRAFTING_GOALS.get(villager);
            if (goal != null) {
                goal.requestImmediateCraft(world, CraftingCheckTrigger.CHEST_CONTENT_CHANGED);
            }

            MasonToLibrarianDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
            if (distributionGoal != null) {
                distributionGoal.requestImmediateDistribution();
            }

            VillagerConversionCandidateIndex.markCandidate(world, villager);
            if (!isConversionScanOnCooldown(world, villager)) {
                Optional<BlockPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE)
                        .map(net.minecraft.util.math.GlobalPos::pos);
                if (jobSite.isPresent()) {
                    BlockPos jobPos = jobSite.get();
                    Optional<StorageSlotReference> triggerSlot = findMiningToolTriggerSlot(world, jobPos, chestPos);
                    if (triggerSlot.isPresent()) {
                        LOGGER.info("Mason {} conversion trigger found at {} slot {} during chest listener scan",
                                villager.getUuidAsString(),
                                triggerSlot.get().sourceDescription(),
                                triggerSlot.get().slot());
                        ProfessionDefinitions.runConversionHooks(world);
                    } else {
                        LOGGER.info("Mason {} conversion trigger missing during chest listener scan around chest {}",
                                villager.getUuidAsString(),
                                chestPos.toShortString());
                    }
                }
            }
        }
    }

    private static boolean isConversionScanOnCooldown(ServerWorld world, VillagerEntity villager) {
        long now = world.getTime();
        long nextAllowedTick = NEXT_CONVERSION_SCAN_TICK.getOrDefault(villager, 0L);
        if (now < nextAllowedTick) {
            return true;
        }
        NEXT_CONVERSION_SCAN_TICK.put(villager, now + CHEST_SCAN_COOLDOWN_TICKS);
        return false;
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

    private static Set<BlockPos> getObservedChestPositions(ServerWorld world, BlockPos chestPos) {
        return PairedStorageHelper.getNormalizedStoragePositions(world, chestPos);
    }

    private record ChestRegistration(VillagerEntity villager, Set<BlockPos> observedChestPositions) {
        private ChestRegistration(VillagerEntity villager, Set<BlockPos> observedChestPositions) {
            this.villager = villager;
            this.observedChestPositions = Set.copyOf(observedChestPositions);
        }
    }

    private static void tryConvertWithMiningTool(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, String source) {
        Optional<MiningToolTrigger> trigger = checkMiningToolConversionTrigger(world, villager, jobPos, chestPos, source);
        if (trigger.isEmpty()) {
            return;
        }

        executeMiningToolConversion(world, villager, jobPos, chestPos, trigger.get(), source);
    }

    private static Optional<MiningToolTrigger> checkMiningToolConversionTrigger(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, String source) {
        if (!villager.isAlive()) {
            LOGGER.info("Mason {} conversion rejected from {}: villager is not alive", villager.getUuidAsString(), source);
            return Optional.empty();
        }

        if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.MASON, world.getBlockState(jobPos))) {
            LOGGER.info("Mason {} conversion rejected from {}: job block at {} is no longer mason-compatible",
                    villager.getUuidAsString(), source, jobPos.toShortString());
            return Optional.empty();
        }

        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
            LOGGER.info("Mason {} conversion rejected from {}: chest {} is too far from job site {}",
                    villager.getUuidAsString(), source, chestPos.toShortString(), jobPos.toShortString());
            return Optional.empty();
        }

        Optional<StorageSlotReference> triggerSlot = findMiningToolTriggerSlot(world, jobPos, chestPos);
        if (triggerSlot.isEmpty()) {
            LOGGER.info("Mason {} conversion rejected from {}: no mining-tool trigger in paired storage",
                    villager.getUuidAsString(),
                    source);
            return Optional.empty();
        }

        Optional<MiningToolTrigger> trigger = takeTriggerFromStorage(world, jobPos, chestPos);
        if (trigger.isPresent()) {
            LOGGER.info("Mason {} conversion accepted from {} with trigger {} slot {}",
                    villager.getUuidAsString(),
                    source,
                    trigger.get().sourceDescription(),
                    trigger.get().slot());
            return trigger;
        }

        LOGGER.info("Mason {} conversion rejected from {}: trigger disappeared before conversion execution",
                villager.getUuidAsString(),
                source);
        return Optional.empty();
    }

    private static void executeMiningToolConversion(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, MiningToolTrigger trigger, String source) {
        MasonGuardEntity guard = GuardVillagers.MASON_GUARD_VILLAGER.create(world);
        if (guard == null) {
            LOGGER.warn("Mason {} conversion rejected from {}: unable to create Mason Guard entity", villager.getUuidAsString(), source);
            return;
        }

        GuardConversionHelper.initializeConvertedGuard(world, villager, guard, jobPos);
        GuardConversionHelper.applyStandardEquipmentDropChances(guard);
        guard.equipStack(EquipmentSlot.MAINHAND, trigger.tool().copy());
        guard.setExpectedMiningTool(trigger.tool());
        guard.setPairedChestPos(chestPos);
        guard.setPairedJobPos(jobPos);

        // Cluster 4: assign home bell so the wall builder goal knows which village this mason belongs to
        BlockPos primaryBell = BellChestMappingState.get(world.getServer())
                .registerBellAndGetPrimary(world, jobPos, VillageGuardStandManager.BELL_EFFECT_RANGE);
        guard.setWallBuilderHomeBell(GlobalPos.create(world.getRegistryKey(), primaryBell));

        ConvertedWorkerJobSiteReservationManager.reserve(world, jobPos, guard.getUuid(), VillagerProfession.MASON, "mason conversion");

        world.spawnEntityAndPassengers(guard);
        VillageGuardStandManager.handleGuardSpawn(world, guard, villager);

        LOGGER.info("Mason converted into guard using tool {} from {} slot {} ({}) ({})",
                trigger.tool().getItem(),
                trigger.sourceDescription(),
                trigger.slot(),
                source,
                GuardConversionHelper.buildConversionMetadata(villager, guard, jobPos, chestPos, source));

        GuardConversionHelper.cleanupVillagerAfterConversion(villager);
    }

    private static Optional<StorageSlotReference> findMiningToolTriggerSlot(ServerWorld world, BlockPos jobPos, BlockPos chestPos) {
        return findTriggerInStorage(world, jobPos, chestPos);
    }

    private static Optional<MiningToolTrigger> takeTriggerFromStorage(ServerWorld world, BlockPos jobPos, BlockPos chestPos) {
        Optional<StorageSlotReference> reference = findTriggerInStorage(world, jobPos, chestPos);
        if (reference.isEmpty()) {
            return Optional.empty();
        }
        return extractTriggerFromSlot(reference.get());
    }

    private static Optional<StorageSlotReference> findTriggerInStorage(ServerWorld world, BlockPos jobPos, BlockPos chestPos) {
        Optional<StorageSlotReference> fromChest = findMiningToolSlot(world.getBlockEntity(chestPos), "paired chest " + chestPos.toShortString());
        if (fromChest.isPresent()) {
            return fromChest;
        }

        Set<BlockPos> pairedChestPositions = getObservedChestPositions(world, chestPos);
        for (BlockPos pairedChestPos : pairedChestPositions) {
            if (pairedChestPos.equals(chestPos)) {
                continue;
            }
            Optional<StorageSlotReference> pairedTrigger = findMiningToolSlot(world.getBlockEntity(pairedChestPos), "paired chest " + pairedChestPos.toShortString());
            if (pairedTrigger.isPresent()) {
                return pairedTrigger;
            }
        }

        LOGGER.debug("No mining tool trigger found in paired chest storage for mason job site {} and chest {}",
                jobPos.toShortString(),
                chestPos.toShortString());
        return Optional.empty();
    }

    private static Optional<StorageSlotReference> findMiningToolSlot(BlockEntity blockEntity, String sourceDescription) {
        if (!(blockEntity instanceof Inventory inventory)) {
            return Optional.empty();
        }

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof PickaxeItem) {
                return Optional.of(new StorageSlotReference(inventory, sourceDescription, slot));
            }
        }

        return Optional.empty();
    }

    private static Optional<MiningToolTrigger> extractTriggerFromSlot(StorageSlotReference reference) {
        ItemStack stack = reference.inventory().getStack(reference.slot());
        if (stack.isEmpty() || !(stack.getItem() instanceof PickaxeItem)) {
            return Optional.empty();
        }

        ItemStack extracted = stack.split(1);
        reference.inventory().markDirty();
        LOGGER.info("Mason conversion trigger extracted {} from {} slot {}",
                extracted.getItem(),
                reference.sourceDescription(),
                reference.slot());
        return Optional.of(new MiningToolTrigger(extracted, reference.sourceDescription(), reference.slot()));
    }

    private record StorageSlotReference(Inventory inventory, String sourceDescription, int slot) {
    }

    private record MiningToolTrigger(ItemStack tool, String sourceDescription, int slot) {
    }
}
