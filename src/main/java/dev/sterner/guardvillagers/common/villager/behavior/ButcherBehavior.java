package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.ButcherGuardEntity;
import dev.sterner.guardvillagers.common.entity.goal.ButcherCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.ButcherMeatDistributionGoal;
import dev.sterner.guardvillagers.common.entity.goal.ButcherSmokerGoal;
import dev.sterner.guardvillagers.common.entity.goal.ButcherToLeatherworkerDistributionGoal;
import dev.sterner.guardvillagers.common.util.ConvertedWorkerJobSiteReservationManager;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import dev.sterner.guardvillagers.common.villager.GuardConversionHelper;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import dev.sterner.guardvillagers.common.villager.VillagerConversionCandidateIndex;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

public class ButcherBehavior extends AbstractPairedProfessionBehavior {
    private static final Logger LOGGER = LoggerFactory.getLogger(ButcherBehavior.class);
    private static final int SMOKER_GOAL_PRIORITY = 3;
    private static final int CRAFTING_GOAL_PRIORITY = 4;
    private static final int MEAT_DISTRIBUTION_GOAL_PRIORITY = 6;
    private static final int LEATHER_DISTRIBUTION_GOAL_PRIORITY = 7;
    private static final Map<VillagerEntity, ButcherSmokerGoal> GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ButcherCraftingGoal> CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ButcherMeatDistributionGoal> MEAT_DISTRIBUTION_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ButcherToLeatherworkerDistributionGoal> LEATHER_DISTRIBUTION_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ChestRegistration> CHEST_REGISTRATIONS = new WeakHashMap<>();
    private static final Map<BlockPos, Set<VillagerEntity>> CHEST_WATCHERS_BY_POS = new HashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!checkPairingPreconditions(world, villager, jobPos, chestPos,
                (serverWorld, pos) -> ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.BUTCHER, serverWorld.getBlockState(pos)),
                () -> clearChestListener(villager))) {
            return;
        }

        LOGGER.debug("Butcher {} paired chest at {} for job site {}",
                villager.getUuidAsString(),
                chestPos.toShortString(),
                jobPos.toShortString());

        ButcherMeatDistributionGoal meatDistributionGoal = upsertGoal(MEAT_DISTRIBUTION_GOALS, villager, MEAT_DISTRIBUTION_GOAL_PRIORITY,
                () -> new ButcherMeatDistributionGoal(villager, jobPos, chestPos));
        meatDistributionGoal.setTargets(jobPos, chestPos);
        meatDistributionGoal.requestImmediateDistribution();

        ButcherToLeatherworkerDistributionGoal leatherDistributionGoal = upsertGoal(LEATHER_DISTRIBUTION_GOALS, villager, LEATHER_DISTRIBUTION_GOAL_PRIORITY,
                () -> new ButcherToLeatherworkerDistributionGoal(villager, jobPos, chestPos, null));
        leatherDistributionGoal.setTargets(jobPos, chestPos, leatherDistributionGoal.getCraftingTablePos());
        leatherDistributionGoal.requestImmediateDistribution();

        ButcherSmokerGoal goal = upsertGoal(GOALS, villager, SMOKER_GOAL_PRIORITY,
                () -> new ButcherSmokerGoal(villager, jobPos, chestPos));
        goal.setTargets(jobPos, chestPos);
        goal.requestImmediateCheck();

        updateChestListener(world, villager, chestPos);

        tryConvertWithWeapon(world, villager, jobPos, chestPos);
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        if (!checkPairingPreconditions(world, villager, jobPos, chestPos,
                (serverWorld, pos) -> ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.BUTCHER, serverWorld.getBlockState(pos)),
                () -> clearChestListener(villager))) {
            return;
        }

        ButcherCraftingGoal goal = upsertGoal(CRAFTING_GOALS, villager, CRAFTING_GOAL_PRIORITY,
                () -> new ButcherCraftingGoal(villager, jobPos, chestPos, craftingTablePos));
        goal.setTargets(jobPos, chestPos, craftingTablePos);
        goal.requestImmediateCraft(world);

        ButcherMeatDistributionGoal meatDistributionGoal = upsertGoal(MEAT_DISTRIBUTION_GOALS, villager, MEAT_DISTRIBUTION_GOAL_PRIORITY,
                () -> new ButcherMeatDistributionGoal(villager, jobPos, chestPos));
        meatDistributionGoal.setTargets(jobPos, chestPos);
        meatDistributionGoal.requestImmediateDistribution();

        ButcherToLeatherworkerDistributionGoal leatherDistributionGoal = upsertGoal(LEATHER_DISTRIBUTION_GOALS, villager, LEATHER_DISTRIBUTION_GOAL_PRIORITY,
                () -> new ButcherToLeatherworkerDistributionGoal(villager, jobPos, chestPos, craftingTablePos));
        leatherDistributionGoal.setTargets(jobPos, chestPos, craftingTablePos);
        leatherDistributionGoal.requestImmediateDistribution();

        ButcherSmokerGoal smokerGoal = upsertGoal(GOALS, villager, SMOKER_GOAL_PRIORITY,
                () -> new ButcherSmokerGoal(villager, jobPos, chestPos));
        smokerGoal.setTargets(jobPos, chestPos);
        smokerGoal.requestImmediateCheck();

        updateChestListener(world, villager, chestPos);
        tryConvertWithWeapon(world, villager, jobPos, chestPos);
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

            ButcherSmokerGoal smokerGoal = GOALS.get(villager);
            if (smokerGoal != null) {
                smokerGoal.requestImmediateCheck();
            }

            ButcherCraftingGoal craftingGoal = CRAFTING_GOALS.get(villager);
            if (craftingGoal != null) {
                craftingGoal.requestImmediateCraft(world);
            }

            ButcherMeatDistributionGoal meatDistributionGoal = MEAT_DISTRIBUTION_GOALS.get(villager);
            if (meatDistributionGoal != null) {
                meatDistributionGoal.requestImmediateDistribution();
            }

            ButcherToLeatherworkerDistributionGoal leatherDistributionGoal = LEATHER_DISTRIBUTION_GOALS.get(villager);
            if (leatherDistributionGoal != null) {
                leatherDistributionGoal.requestImmediateDistribution();
            }

            VillagerConversionCandidateIndex.markCandidate(world, villager);
            ProfessionDefinitions.runConversionHooks(world);
        }
    }

    public static void tryConvertButchersWithWeapon(ServerWorld world) {
        // Use the candidate index only — no world-bounds fallback scan.
        // getWorldBounds() = entire world-border box (~60k×60k), O(all entities), called every 40 ticks.
        // The candidate index covers all newly-promoted butchers; the loop body already filters by JOB_SITE,
        // so any villager not in the index with a job site will be picked up on the next index mark cycle.
        Set<VillagerEntity> candidates = new LinkedHashSet<>(VillagerConversionCandidateIndex.pollCandidates(world, VillagerProfession.BUTCHER));

        for (VillagerEntity villager : candidates) {
            if (!villager.isAlive() || villager.isRemoved() || villager.getWorld() != world) {
                continue;
            }
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

            tryConvertWithWeapon(world, villager, jobPos, chestPos.get());
        }
    }

    private static void tryConvertWithWeapon(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!villager.isAlive() || villager.getVillagerData().getProfession() != VillagerProfession.BUTCHER) {
            return;
        }

        ButcherGuardEntity guard = GuardVillagers.BUTCHER_GUARD_VILLAGER.create(world);
        if (guard == null) {
            return;
        }

        ItemStack weaponStack = takeWeaponFromChest(world, chestPos);
        if (weaponStack.isEmpty()) {
            return;
        }

        GuardConversionHelper.initializeConvertedGuard(world, villager, guard, jobPos);
        GuardConversionHelper.applyStandardEquipmentDropChances(guard);
        clearArmorAndOffhand(guard);
        guard.equipStack(EquipmentSlot.MAINHAND, weaponStack);
        guard.setHuntOnSpawn();
        guard.setPairedChestPos(chestPos);
        guard.setPairedSmokerPos(jobPos);

        ConvertedWorkerJobSiteReservationManager.reserve(world, jobPos, guard.getUuid(), VillagerProfession.BUTCHER, "butcher conversion");

        world.spawnEntityAndPassengers(guard);
        VillageGuardStandManager.handleGuardSpawn(world, guard, villager);

        LOGGER.info("Butcher converted into guard using chest weapon ({})",
                GuardConversionHelper.buildConversionMetadata(villager, guard, jobPos, chestPos, "butcher chest weapon"));

        GuardConversionHelper.cleanupVillagerAfterConversion(villager);
    }


    private static void clearArmorAndOffhand(ButcherGuardEntity guard) {
        guard.equipStack(EquipmentSlot.HEAD, ItemStack.EMPTY);
        guard.equipStack(EquipmentSlot.CHEST, ItemStack.EMPTY);
        guard.equipStack(EquipmentSlot.LEGS, ItemStack.EMPTY);
        guard.equipStack(EquipmentSlot.FEET, ItemStack.EMPTY);
        guard.equipStack(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
    }

    private static ItemStack takeWeaponFromChest(ServerWorld world, BlockPos chestPos) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return ItemStack.EMPTY;
        }

        Inventory inventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
        if (inventory == null) {
            return ItemStack.EMPTY;
        }

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && isConvertibleWeapon(stack)) {
                ItemStack extracted = stack.split(1);
                inventory.markDirty();
                return extracted;
            }
        }

        return ItemStack.EMPTY;
    }

    private static boolean isConvertibleWeapon(ItemStack stack) {
        return stack.getItem() instanceof AxeItem || stack.getItem() instanceof SwordItem;
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
