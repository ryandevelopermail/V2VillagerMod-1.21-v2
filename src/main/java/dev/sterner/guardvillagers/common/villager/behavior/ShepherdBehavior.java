package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.goal.ShepherdCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.ShepherdSpecialGoal;
import dev.sterner.guardvillagers.common.entity.goal.ShepherdToLibrarianDistributionGoal;
import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehavior;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.WeakHashMap;

public class ShepherdBehavior implements VillagerProfessionBehavior {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShepherdBehavior.class);
    private static final long SPECIAL_GOAL_CHECK_DEBOUNCE_TICKS = 10L;
    private static final long SPECIAL_GOAL_CHECK_MAX_COALESCE_DELAY_TICKS = 40L;
    private static final int SPECIAL_GOAL_PRIORITY = 3;
    private static final int DISTRIBUTION_GOAL_PRIORITY = 4;
    private static final int CRAFTING_GOAL_PRIORITY = 5;
    private static final Map<VillagerEntity, ShepherdCraftingGoal> CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ShepherdSpecialGoal> SPECIAL_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ShepherdToLibrarianDistributionGoal> DISTRIBUTION_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ChestListener> CHEST_LISTENERS = new WeakHashMap<>();
    private static final Map<VillagerEntity, Long> LAST_SPECIAL_GOAL_CHECK_TICKS = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        double jobToChestDistance = calculateJobToChestDistance(jobPos, chestPos);
        double distanceTolerance = getShepherdChestDistanceTolerance();

        if (!villager.isAlive()) {
            LOGGER.info("Shepherd chest pairing skipped because villager {} is not alive", villager.getUuidAsString());
            logPairingFailureDiagnostics(villager, jobPos, chestPos, jobToChestDistance, distanceTolerance, "villager_dead");
            clearChestListener(villager);
            return;
        }

        if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.SHEPHERD, world.getBlockState(jobPos))) {
            LOGGER.info("Shepherd chest pairing skipped for villager {} because job block at {} is not a loom",
                    villager.getUuidAsString(), jobPos.toShortString());
            logPairingFailureDiagnostics(villager, jobPos, chestPos, jobToChestDistance, distanceTolerance, "wrong_job_block");
            clearChestListener(villager);
            return;
        }

        if (jobToChestDistance > distanceTolerance) {
            LOGGER.info("Shepherd chest pairing skipped for villager {} because distance {} exceeds tolerance {}",
                    villager.getUuidAsString(), formatDistance(jobToChestDistance), formatDistance(distanceTolerance));
            logPairingFailureDiagnostics(villager, jobPos, chestPos, jobToChestDistance, distanceTolerance, "distance_exceeded");
            clearChestListener(villager);
            return;
        }

        ShepherdSpecialGoal specialGoal = SPECIAL_GOALS.get(villager);
        if (specialGoal == null) {
            specialGoal = new ShepherdSpecialGoal(villager, jobPos, chestPos);
            SPECIAL_GOALS.put(villager, specialGoal);
            GoalSelector selector = villager.goalSelector;
            selector.add(SPECIAL_GOAL_PRIORITY, specialGoal);
        } else {
            specialGoal.setTargets(jobPos, chestPos);
        }
        specialGoal.requestImmediateCheck();
        LAST_SPECIAL_GOAL_CHECK_TICKS.put(villager, world.getTime());

        ShepherdToLibrarianDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
        if (distributionGoal == null) {
            distributionGoal = new ShepherdToLibrarianDistributionGoal(villager, jobPos, chestPos, null);
            DISTRIBUTION_GOALS.put(villager, distributionGoal);
            GoalSelector selector = villager.goalSelector;
            selector.add(DISTRIBUTION_GOAL_PRIORITY, distributionGoal);
        } else {
            distributionGoal.setTargets(jobPos, chestPos, distributionGoal.getCraftingTablePos());
        }
        distributionGoal.requestImmediateDistribution();

        ShepherdCraftingGoal craftingGoal = CRAFTING_GOALS.get(villager);
        if (craftingGoal != null) {
            craftingGoal.setTargets(jobPos, chestPos, craftingGoal.getCraftingTablePos());
        }
        updateChestListener(world, villager, chestPos);
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        ShepherdCraftingGoal goal = CRAFTING_GOALS.get(villager);
        if (goal == null) {
            goal = new ShepherdCraftingGoal(villager, jobPos, chestPos, craftingTablePos);
            CRAFTING_GOALS.put(villager, goal);
            GoalSelector selector = villager.goalSelector;
            selector.add(CRAFTING_GOAL_PRIORITY, goal);
        } else {
            goal.setTargets(jobPos, chestPos, craftingTablePos);
        }
        goal.requestImmediateCraft(world);

        ShepherdToLibrarianDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
        if (distributionGoal == null) {
            distributionGoal = new ShepherdToLibrarianDistributionGoal(villager, jobPos, chestPos, craftingTablePos);
            DISTRIBUTION_GOALS.put(villager, distributionGoal);
            villager.goalSelector.add(DISTRIBUTION_GOAL_PRIORITY, distributionGoal);
        } else {
            distributionGoal.setTargets(jobPos, chestPos, craftingTablePos);
        }
        distributionGoal.requestImmediateDistribution();
        updateChestListener(world, villager, chestPos);
    }

    private void updateChestListener(ServerWorld world, VillagerEntity villager, BlockPos chestPos) {
        ChestListener existing = CHEST_LISTENERS.get(villager);
        BlockPos normalizedChestPos = chestPos.toImmutable();
        if (existing != null && existing.world() == world && existing.chestPos().equals(normalizedChestPos)) {
            return;
        }
        clearChestListener(villager);

        ChestInventoryChangeDispatcher.Subscription subscription = ChestInventoryChangeDispatcher.register(world, normalizedChestPos,
                (serverWorld, changedPos) -> handleChestInventoryChanged(serverWorld, villager));
        CHEST_LISTENERS.put(villager, new ChestListener(world, normalizedChestPos, subscription));
    }

    private void handleChestInventoryChanged(ServerWorld serverWorld, VillagerEntity villager) {
        ShepherdSpecialGoal specialGoal = SPECIAL_GOALS.get(villager);
        if (specialGoal != null) {
            boolean chestChanged = specialGoal.onChestInventoryChanged(serverWorld);
            if (chestChanged) {
                long now = serverWorld.getTime();
                long lastWakeTick = LAST_SPECIAL_GOAL_CHECK_TICKS.getOrDefault(villager, Long.MIN_VALUE);
                if (now - lastWakeTick >= SPECIAL_GOAL_CHECK_DEBOUNCE_TICKS) {
                    specialGoal.requestImmediateCheck();
                    LAST_SPECIAL_GOAL_CHECK_TICKS.put(villager, now);
                } else {
                    specialGoal.requestCheckNoSoonerThan(lastWakeTick + SPECIAL_GOAL_CHECK_MAX_COALESCE_DELAY_TICKS);
                }
            }
        }
        ShepherdCraftingGoal craftingGoal = CRAFTING_GOALS.get(villager);
        if (craftingGoal != null) {
            craftingGoal.requestImmediateCraft(serverWorld);
        }
        ShepherdToLibrarianDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
        if (distributionGoal != null) {
            distributionGoal.requestImmediateDistribution();
        }
    }

    private void clearChestListener(VillagerEntity villager) {
        ChestListener existing = CHEST_LISTENERS.remove(villager);
        LAST_SPECIAL_GOAL_CHECK_TICKS.remove(villager);
        if (existing != null) {
            removeChestListener(existing);
        }
    }

    private void removeChestListener(ChestListener existing) {
        ChestInventoryChangeDispatcher.unregister(existing.subscription());
    }

    private void logPairingFailureDiagnostics(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos,
                                              double jobToChestDistance, double distanceTolerance, String reason) {
        LOGGER.info("Shepherd pairing diagnostics [reason={}] villager={} jobPos={} chestPos={} distance={} tolerance={}",
                reason,
                villager.getUuidAsString(),
                jobPos.toShortString(),
                chestPos.toShortString(),
                formatDistance(jobToChestDistance),
                formatDistance(distanceTolerance));
    }

    private double calculateJobToChestDistance(BlockPos jobPos, BlockPos chestPos) {
        return Math.sqrt(jobPos.getSquaredDistance(chestPos));
    }

    private double getShepherdChestDistanceTolerance() {
        return Math.max(3.0D, GuardVillagersConfig.shepherdChestPairingDistanceTolerance);
    }

    private String formatDistance(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private record ChestListener(ServerWorld world, BlockPos chestPos, ChestInventoryChangeDispatcher.Subscription subscription) {
    }
}
