package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.ButcherGuardEntity;
import dev.sterner.guardvillagers.common.entity.goal.ButcherCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.ButcherMeatDistributionGoal;
import dev.sterner.guardvillagers.common.entity.goal.ButcherSmokerGoal;
import dev.sterner.guardvillagers.common.entity.goal.ButcherToLeatherworkerDistributionGoal;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehavior;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import dev.sterner.guardvillagers.common.villager.VillagerConversionCandidateIndex;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.InventoryChangedListener;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

public class ButcherBehavior implements VillagerProfessionBehavior {
    private static final Logger LOGGER = LoggerFactory.getLogger(ButcherBehavior.class);
    private static final int SMOKER_GOAL_PRIORITY = 3;
    private static final int CRAFTING_GOAL_PRIORITY = 4;
    private static final int MEAT_DISTRIBUTION_GOAL_PRIORITY = 6;
    private static final int LEATHER_DISTRIBUTION_GOAL_PRIORITY = 7;
    private static final Map<VillagerEntity, ButcherSmokerGoal> GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ButcherCraftingGoal> CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ButcherMeatDistributionGoal> MEAT_DISTRIBUTION_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ButcherToLeatherworkerDistributionGoal> LEATHER_DISTRIBUTION_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ChestListener> CHEST_LISTENERS = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!villager.isAlive()) {
            clearChestListener(villager);
            return;
        }

        if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.BUTCHER, world.getBlockState(jobPos))) {
            clearChestListener(villager);
            return;
        }

        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
            clearChestListener(villager);
            return;
        }

        LOGGER.info("Butcher {} paired chest at {} for job site {}",
                villager.getUuidAsString(),
                chestPos.toShortString(),
                jobPos.toShortString());

        ButcherMeatDistributionGoal meatDistributionGoal = MEAT_DISTRIBUTION_GOALS.get(villager);
        if (meatDistributionGoal == null) {
            meatDistributionGoal = new ButcherMeatDistributionGoal(villager, jobPos, chestPos);
            MEAT_DISTRIBUTION_GOALS.put(villager, meatDistributionGoal);
            villager.goalSelector.add(MEAT_DISTRIBUTION_GOAL_PRIORITY, meatDistributionGoal);
        } else {
            meatDistributionGoal.setTargets(jobPos, chestPos);
        }
        meatDistributionGoal.requestImmediateDistribution();

        ButcherToLeatherworkerDistributionGoal leatherDistributionGoal = LEATHER_DISTRIBUTION_GOALS.get(villager);
        if (leatherDistributionGoal == null) {
            leatherDistributionGoal = new ButcherToLeatherworkerDistributionGoal(villager, jobPos, chestPos, null);
            LEATHER_DISTRIBUTION_GOALS.put(villager, leatherDistributionGoal);
            villager.goalSelector.add(LEATHER_DISTRIBUTION_GOAL_PRIORITY, leatherDistributionGoal);
        } else {
            leatherDistributionGoal.setTargets(jobPos, chestPos, leatherDistributionGoal.getCraftingTablePos());
        }
        leatherDistributionGoal.requestImmediateDistribution();

        ButcherSmokerGoal goal = GOALS.get(villager);
        if (goal == null) {
            goal = new ButcherSmokerGoal(villager, jobPos, chestPos);
            GOALS.put(villager, goal);
            GoalSelector selector = villager.goalSelector;
            selector.add(SMOKER_GOAL_PRIORITY, goal);
        } else {
            goal.setTargets(jobPos, chestPos);
        }

        updateChestListener(world, villager, chestPos);

        tryConvertWithAxe(world, villager, jobPos, chestPos);
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        if (!villager.isAlive()) {
            clearChestListener(villager);
            return;
        }

        if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.BUTCHER, world.getBlockState(jobPos))) {
            clearChestListener(villager);
            return;
        }

        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
            clearChestListener(villager);
            return;
        }

        ButcherCraftingGoal goal = CRAFTING_GOALS.get(villager);
        if (goal == null) {
            goal = new ButcherCraftingGoal(villager, jobPos, chestPos, craftingTablePos);
            CRAFTING_GOALS.put(villager, goal);
            villager.goalSelector.add(CRAFTING_GOAL_PRIORITY, goal);
        } else {
            goal.setTargets(jobPos, chestPos, craftingTablePos);
        }
        goal.requestImmediateCraft(world);

        ButcherMeatDistributionGoal meatDistributionGoal = MEAT_DISTRIBUTION_GOALS.get(villager);
        if (meatDistributionGoal == null) {
            meatDistributionGoal = new ButcherMeatDistributionGoal(villager, jobPos, chestPos);
            MEAT_DISTRIBUTION_GOALS.put(villager, meatDistributionGoal);
            villager.goalSelector.add(MEAT_DISTRIBUTION_GOAL_PRIORITY, meatDistributionGoal);
        } else {
            meatDistributionGoal.setTargets(jobPos, chestPos);
        }
        meatDistributionGoal.requestImmediateDistribution();

        ButcherToLeatherworkerDistributionGoal leatherDistributionGoal = LEATHER_DISTRIBUTION_GOALS.get(villager);
        if (leatherDistributionGoal == null) {
            leatherDistributionGoal = new ButcherToLeatherworkerDistributionGoal(villager, jobPos, chestPos, craftingTablePos);
            LEATHER_DISTRIBUTION_GOALS.put(villager, leatherDistributionGoal);
            villager.goalSelector.add(LEATHER_DISTRIBUTION_GOAL_PRIORITY, leatherDistributionGoal);
        } else {
            leatherDistributionGoal.setTargets(jobPos, chestPos, craftingTablePos);
        }
        leatherDistributionGoal.requestImmediateDistribution();

        updateChestListener(world, villager, chestPos);
    }

    public static void tryConvertButchersWithAxe(ServerWorld world) {
        for (VillagerEntity villager : VillagerConversionCandidateIndex.pollCandidates(world, VillagerProfession.BUTCHER)) {
            Optional<BlockPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE).map(net.minecraft.util.math.GlobalPos::pos);
            if (jobSite.isEmpty()) {
                continue;
            }

            BlockPos jobPos = jobSite.get();
            if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.BUTCHER, world.getBlockState(jobPos))) {
                continue;
            }

            Optional<BlockPos> chestPos = JobBlockPairingHelper.findNearbyChest(world, jobPos);
            if (chestPos.isEmpty() || !jobPos.isWithinDistance(chestPos.get(), 3.0D)) {
                continue;
            }

            tryConvertWithAxe(world, villager, jobPos, chestPos.get());
        }
    }

    private static void tryConvertWithAxe(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        ButcherGuardEntity guard = GuardVillagers.BUTCHER_GUARD_VILLAGER.create(world);
        if (guard == null) {
            return;
        }

        ItemStack axeStack = takeAxeFromChest(world, chestPos);
        if (axeStack.isEmpty()) {
            return;
        }

        guard.spawnWithArmor = true;
        guard.initialize(world, world.getLocalDifficulty(jobPos), SpawnReason.CONVERSION, null);
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
        guard.equipStack(EquipmentSlot.MAINHAND, axeStack);
        guard.setHuntOnSpawn();
        guard.setPairedChestPos(chestPos);
        guard.setPairedSmokerPos(jobPos);

        world.spawnEntityAndPassengers(guard);
        VillageGuardStandManager.handleGuardSpawn(world, guard, villager);

        LOGGER.info("Butcher {} converted into Butcher Guard {} using axe from chest {}",
                villager.getUuidAsString(),
                guard.getUuidAsString(),
                chestPos.toShortString());

        villager.releaseTicketFor(MemoryModuleType.HOME);
        villager.releaseTicketFor(MemoryModuleType.JOB_SITE);
        villager.releaseTicketFor(MemoryModuleType.MEETING_POINT);
        villager.discard();
    }

    private static ItemStack takeAxeFromChest(ServerWorld world, BlockPos chestPos) {
        BlockEntity blockEntity = world.getBlockEntity(chestPos);
        if (!(blockEntity instanceof Inventory inventory)) {
            return ItemStack.EMPTY;
        }

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof AxeItem) {
                ItemStack extracted = stack.split(1);
                inventory.markDirty();
                return extracted;
            }
        }

        return ItemStack.EMPTY;
    }

    private void updateChestListener(ServerWorld world, VillagerEntity villager, BlockPos chestPos) {
        Inventory inventory = getChestInventory(world, chestPos);
        ChestListener existing = CHEST_LISTENERS.get(villager);
        if (existing != null && existing.inventory() == inventory) {
            return;
        }
        if (existing != null) {
            removeChestListener(existing);
            CHEST_LISTENERS.remove(villager);
        }
        if (!(inventory instanceof SimpleInventory simpleInventory)) {
            return;
        }
        InventoryChangedListener listener = sender -> {
            ButcherCraftingGoal craftingGoal = CRAFTING_GOALS.get(villager);
            if (craftingGoal != null && villager.getWorld() instanceof ServerWorld serverWorld) {
                craftingGoal.requestImmediateCraft(serverWorld);
            }

            ButcherMeatDistributionGoal meatDistributionGoal = MEAT_DISTRIBUTION_GOALS.get(villager);
            if (meatDistributionGoal != null) {
                meatDistributionGoal.requestImmediateDistribution();
            }

            ButcherToLeatherworkerDistributionGoal leatherDistributionGoal = LEATHER_DISTRIBUTION_GOALS.get(villager);
            if (leatherDistributionGoal != null) {
                leatherDistributionGoal.requestImmediateDistribution();
            }
            if (villager.getWorld() instanceof ServerWorld serverWorld) {
                VillagerConversionCandidateIndex.markCandidate(serverWorld, villager);
                ProfessionDefinitions.runConversionHooks(serverWorld);
            }
        };
        simpleInventory.addListener(listener);
        CHEST_LISTENERS.put(villager, new ChestListener(simpleInventory, listener));
    }

    private void clearChestListener(VillagerEntity villager) {
        ChestListener existing = CHEST_LISTENERS.remove(villager);
        if (existing != null) {
            removeChestListener(existing);
        }
    }

    private void removeChestListener(ChestListener existing) {
        existing.inventory().removeListener(existing.listener());
    }

    private Inventory getChestInventory(ServerWorld world, BlockPos chestPos) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return null;
        }
        return ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
    }

    private record ChestListener(SimpleInventory inventory, InventoryChangedListener listener) {
    }
}
