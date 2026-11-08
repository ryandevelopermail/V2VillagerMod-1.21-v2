package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.entity.goal.ClericBrewingGoal;
import dev.sterner.guardvillagers.common.entity.goal.ClericCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.ClericDistributionGoal;
import dev.sterner.guardvillagers.common.entity.goal.HealGuardAndPlayerGoal;
import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehavior;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import net.minecraft.block.entity.BrewingStandBlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.InventoryChangedListener;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.Optional;

public class ClericBehavior implements VillagerProfessionBehavior {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClericBehavior.class);
    private static final int BREWING_GOAL_PRIORITY = 3;
    private static final int CRAFTING_GOAL_PRIORITY = 4;
    private static final int DISTRIBUTION_GOAL_PRIORITY = 5;
    private static final Map<VillagerEntity, BlockPos> PAIRED_CHESTS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ClericBrewingGoal> GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ChestListener> CHEST_LISTENERS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ClericCraftingGoal> CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ClericDistributionGoal> DISTRIBUTION_GOALS = new WeakHashMap<>();

    public static Optional<ClericLiveSnapshot> getLiveStorageSnapshot(
            ServerWorld world,
            VillagerEntity villager,
            Inventory storageInventory,
            long storedHealingReserve
    ) {
        if (!villager.isAlive()
                || villager.getWorld() != world
                || villager.getVillagerData().getProfession() != VillagerProfession.CLERIC) {
            return Optional.empty();
        }
        ClericBrewingGoal brewingGoal = GOALS.get(villager);
        ClericCraftingGoal craftingGoal = CRAFTING_GOALS.get(villager);
        BlockPos standPos = brewingGoal == null ? null : brewingGoal.getBrewingStandPos();
        BlockPos tablePos = craftingGoal == null ? null : craftingGoal.getCraftingTablePos();
        boolean tableReady = tablePos != null && world.getBlockState(tablePos).isOf(Blocks.CRAFTING_TABLE);
        BrewingStandBlockEntity stand = standPos != null
                && world.getBlockState(standPos).isOf(Blocks.BREWING_STAND)
                && world.getBlockEntity(standPos) instanceof BrewingStandBlockEntity found
                ? found
                : null;
        boolean standReady = stand != null;
        Set<ClericBrewingGoal.PotionTarget> reachable = standReady
                ? ClericBrewingGoal.getReachableRecipes(
                        storageInventory,
                        stand,
                        world.getBrewingRecipeRegistry())
                : Set.of();
        ClericBrewingGoal.PotionTarget target = ClericBrewingGoal.selectTargetPotion(reachable);
        int craftable = tableReady && craftingGoal != null
                ? craftingGoal.countCraftableBrewingStandRecipesReadOnly(storageInventory)
                : 0;
        long personalReserve = HealGuardAndPlayerGoal.countHealingPotionsReadOnly(villager.getInventory());
        long healingReserve = combineHealingReserve(personalReserve, storedHealingReserve);
        return Optional.of(new ClericLiveSnapshot(
                tableReady,
                standReady,
                ClericBrewingGoal.inspectBottleStageReadOnly(stand),
                ClericBrewingGoal.describePotionTarget(target),
                reachable.size(),
                craftable,
                healingReserve,
                HealGuardAndPlayerGoal.countEligibleInjuredGuardsReadOnly(villager)));
    }

    public static long combineHealingReserve(long personalInventoryCount, long storageCount) {
        long current = Math.max(0L, personalInventoryCount);
        long amount = Math.max(0L, storageCount);
        return current >= Long.MAX_VALUE - amount ? Long.MAX_VALUE : current + amount;
    }

    public record ClericLiveSnapshot(
            boolean craftingTableReady,
            boolean brewingStandReady,
            ClericBrewingGoal.BottleStage bottleStage,
            String brewTarget,
            int reachablePotions,
            int craftableBrewingStands,
            long healingReserve,
            int injuredGuards
    ) {
        public ClericLiveSnapshot {
            reachablePotions = Math.max(0, reachablePotions);
            craftableBrewingStands = Math.max(0, craftableBrewingStands);
            healingReserve = Math.max(0L, healingReserve);
            injuredGuards = Math.max(0, injuredGuards);
        }
    }

    public static Set<ClericBrewingGoal.PotionTarget> getReachableRecipes(VillagerEntity villager,
                                                                           Inventory chestInventory,
                                                                           BrewingStandBlockEntity stand) {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return Set.of();
        }
        return ClericBrewingGoal.getReachableRecipes(chestInventory, stand, world.getBrewingRecipeRegistry());
    }

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!villager.isAlive()) {
            clearPairedChest(villager);
            return;
        }

        if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.CLERIC, world.getBlockState(jobPos))) {
            clearPairedChest(villager);
            return;
        }

        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
            clearPairedChest(villager);
            return;
        }

        LOGGER.info("Cleric {} paired chest at {} for job site {}",
                villager.getUuidAsString(),
                chestPos.toShortString(),
                jobPos.toShortString());
        PAIRED_CHESTS.put(villager, chestPos.toImmutable());

        ClericBrewingGoal goal = GOALS.get(villager);
        if (goal == null) {
            goal = new ClericBrewingGoal(villager, jobPos, chestPos);
            GOALS.put(villager, goal);
            GoalSelector selector = villager.goalSelector;
            selector.add(BREWING_GOAL_PRIORITY, goal);
        } else {
            goal.setTargets(jobPos, chestPos);
        }
        goal.requestImmediateBrew();

        ClericDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
        if (distributionGoal == null) {
            distributionGoal = new ClericDistributionGoal(villager, jobPos, chestPos, null);
            DISTRIBUTION_GOALS.put(villager, distributionGoal);
            villager.goalSelector.add(DISTRIBUTION_GOAL_PRIORITY, distributionGoal);
        } else {
            distributionGoal.setTargets(jobPos, chestPos, distributionGoal.getCraftingTablePos());
        }
        distributionGoal.requestImmediateDistribution();

        updateChestListener(world, villager, chestPos);
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        if (!villager.isAlive()) {
            return;
        }

        if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.CLERIC, world.getBlockState(jobPos))) {
            return;
        }

        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
            return;
        }

        ClericCraftingGoal goal = CRAFTING_GOALS.get(villager);
        if (goal == null) {
            goal = new ClericCraftingGoal(villager, jobPos, chestPos, craftingTablePos);
            CRAFTING_GOALS.put(villager, goal);
            villager.goalSelector.add(CRAFTING_GOAL_PRIORITY, goal);
        } else {
            goal.setTargets(jobPos, chestPos, craftingTablePos);
        }

        goal.requestImmediateCraft(world);

        ClericDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
        if (distributionGoal == null) {
            distributionGoal = new ClericDistributionGoal(villager, jobPos, chestPos, craftingTablePos);
            DISTRIBUTION_GOALS.put(villager, distributionGoal);
            villager.goalSelector.add(DISTRIBUTION_GOAL_PRIORITY, distributionGoal);
        } else {
            distributionGoal.setTargets(jobPos, chestPos, craftingTablePos);
        }
        distributionGoal.requestImmediateDistribution();
    }

    public static BlockPos getPairedChestPos(VillagerEntity villager) {
        BlockPos pos = PAIRED_CHESTS.get(villager);
        return pos == null ? null : pos.toImmutable();
    }

    public static void clearPairedChest(VillagerEntity villager) {
        PAIRED_CHESTS.remove(villager);
        ChestListener existing = CHEST_LISTENERS.remove(villager);
        if (existing != null) {
            existing.inventory().removeListener(existing.listener());
        }
    }

    private void updateChestListener(ServerWorld world, VillagerEntity villager, BlockPos chestPos) {
        Inventory inventory = getChestInventory(world, chestPos);
        ChestListener existing = CHEST_LISTENERS.get(villager);
        if (existing != null && existing.inventory() == inventory) {
            return;
        }
        if (existing != null) {
            existing.inventory().removeListener(existing.listener());
            CHEST_LISTENERS.remove(villager);
        }
        if (!(inventory instanceof SimpleInventory simpleInventory)) {
            return;
        }
        InventoryChangedListener listener = sender -> {
            ClericBrewingGoal goal = GOALS.get(villager);
            if (goal != null) {
                goal.requestImmediateBrew();
            }
            ClericCraftingGoal craftingGoal = CRAFTING_GOALS.get(villager);
            if (craftingGoal != null && villager.getWorld() instanceof ServerWorld serverWorld) {
                craftingGoal.requestImmediateCraft(serverWorld);
            }
            ClericDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
            if (distributionGoal != null) {
                distributionGoal.requestImmediateDistribution();
            }
        };
        simpleInventory.addListener(listener);
        CHEST_LISTENERS.put(villager, new ChestListener(simpleInventory, listener));
    }

    private Inventory getChestInventory(ServerWorld world, BlockPos chestPos) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return null;
        }
        return ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
    }

    private record ChestListener(SimpleInventory inventory, InventoryChangedListener listener) {
    }
}
