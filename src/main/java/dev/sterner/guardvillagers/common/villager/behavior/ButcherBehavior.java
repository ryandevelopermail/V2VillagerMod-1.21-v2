package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.ButcherGuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.goal.ButcherCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.ButcherMeatDistributionGoal;
import dev.sterner.guardvillagers.common.entity.goal.ButcherSmokerGoal;
import dev.sterner.guardvillagers.common.entity.goal.ButcherToLeatherworkerDistributionGoal;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import dev.sterner.guardvillagers.common.villager.VillagerConversionCandidateIndex;
import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehavior;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
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
    private static final Map<VillagerEntity, ChestRegistration> CHEST_REGISTRATIONS = new WeakHashMap<>();
    private static final Map<BlockPos, Set<VillagerEntity>> CHEST_WATCHERS_BY_POS = new HashMap<>();

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
        goal.requestImmediateCheck();

        updateChestListener(world, villager, chestPos);
        tryConvertWithWeapon(world, villager, jobPos, chestPos);
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

        ButcherSmokerGoal smokerGoal = GOALS.get(villager);
        if (smokerGoal == null) {
            smokerGoal = new ButcherSmokerGoal(villager, jobPos, chestPos);
            GOALS.put(villager, smokerGoal);
            villager.goalSelector.add(SMOKER_GOAL_PRIORITY, smokerGoal);
        } else {
            smokerGoal.setTargets(jobPos, chestPos);
        }
        smokerGoal.requestImmediateCheck();

        updateChestListener(world, villager, chestPos);
        tryConvertWithWeapon(world, villager, jobPos, chestPos);
    }

    public static void tryConvertButchersWithWeapon(ServerWorld world) {
        Set<VillagerEntity> candidates = new LinkedHashSet<>(VillagerConversionCandidateIndex.pollCandidates(world, VillagerProfession.BUTCHER));
        Box worldBounds = JobBlockPairingHelper.getWorldBounds(world);
        candidates.addAll(world.getEntitiesByClass(
                VillagerEntity.class,
                worldBounds,
                villager -> villager.isAlive() && villager.getVillagerData().getProfession() == VillagerProfession.BUTCHER
        ));

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

    public static void onChestInventoryMutated(ServerWorld world, BlockPos chestPos) {
        Set<VillagerEntity> villagers = CHEST_WATCHERS_BY_POS.get(chestPos);
        if (villagers == null || villagers.isEmpty()) {
            return;
        }

        Set<VillagerEntity> snapshot = Set.copyOf(villagers);
        boolean shouldRunConversionHooks = false;
        for (VillagerEntity villager : snapshot) {
            if (!villager.isAlive() || villager.isRemoved() || villager.getWorld() != world) {
                clearChestListener(villager);
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
            shouldRunConversionHooks = true;
        }

        if (shouldRunConversionHooks) {
            ProfessionDefinitions.runConversionHooks(world);
        }
    }

    private static void tryConvertWithWeapon(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!villager.isAlive() || villager.getVillagerData().getProfession() != VillagerProfession.BUTCHER) {
            return;
        }

        Optional<WeaponSlotReference> triggerReference = findConvertibleWeaponSlot(world, chestPos);
        if (triggerReference.isEmpty()) {
            return;
        }

        ButcherGuardEntity guard = GuardVillagers.BUTCHER_GUARD_VILLAGER.create(world);
        if (guard == null) {
            return;
        }

        Optional<WeaponTrigger> trigger = extractWeaponTrigger(triggerReference.get());
        if (trigger.isEmpty()) {
            return;
        }

        prepareConvertedGuard(guard, world, villager, jobPos, trigger.get().weapon());
        guard.setHuntOnSpawn();
        guard.setPairedChestPos(chestPos);
        guard.setPairedSmokerPos(jobPos);

        world.spawnEntityAndPassengers(guard);
        VillageGuardStandManager.handleGuardSpawn(world, guard, villager);

        LOGGER.info("Butcher {} converted into Butcher Guard {} using {} from chest {} slot {}",
                villager.getUuidAsString(),
                guard.getUuidAsString(),
                trigger.get().weapon().getItem(),
                chestPos.toShortString(),
                trigger.get().slot());

        discardVillager(villager);
    }

    private static void prepareConvertedGuard(GuardEntity guard, ServerWorld world, VillagerEntity villager, BlockPos jobPos, ItemStack weaponStack) {
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
        clearArmorAndOffhand(guard);
        guard.equipStack(EquipmentSlot.MAINHAND, weaponStack.copyWithCount(1));
    }

    private static void discardVillager(VillagerEntity villager) {
        villager.releaseTicketFor(MemoryModuleType.HOME);
        villager.releaseTicketFor(MemoryModuleType.JOB_SITE);
        villager.releaseTicketFor(MemoryModuleType.MEETING_POINT);
        villager.discard();
    }

    private static void clearArmorAndOffhand(GuardEntity guard) {
        guard.equipStack(EquipmentSlot.HEAD, ItemStack.EMPTY);
        guard.equipStack(EquipmentSlot.CHEST, ItemStack.EMPTY);
        guard.equipStack(EquipmentSlot.LEGS, ItemStack.EMPTY);
        guard.equipStack(EquipmentSlot.FEET, ItemStack.EMPTY);
        guard.equipStack(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
    }

    private static Optional<WeaponSlotReference> findConvertibleWeaponSlot(ServerWorld world, BlockPos chestPos) {
        Inventory inventory = getChestInventory(world, chestPos);
        if (inventory == null) {
            return Optional.empty();
        }

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && (stack.getItem() instanceof AxeItem || stack.getItem() instanceof SwordItem)) {
                return Optional.of(new WeaponSlotReference(inventory, slot));
            }
        }

        return Optional.empty();
    }

    private static Optional<WeaponTrigger> extractWeaponTrigger(WeaponSlotReference reference) {
        ItemStack stack = reference.inventory().getStack(reference.slot());
        if (stack.isEmpty() || (!(stack.getItem() instanceof AxeItem) && !(stack.getItem() instanceof SwordItem))) {
            return Optional.empty();
        }

        ItemStack extracted = stack.split(1);
        reference.inventory().markDirty();
        return Optional.of(new WeaponTrigger(extracted, reference.slot()));
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

    private static Inventory getChestInventory(ServerWorld world, BlockPos chestPos) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return null;
        }

        return ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
    }

    private record ChestRegistration(VillagerEntity villager, Set<BlockPos> observedChestPositions) {
        private ChestRegistration(VillagerEntity villager, Set<BlockPos> observedChestPositions) {
            this.villager = villager;
            this.observedChestPositions = Set.copyOf(observedChestPositions);
        }
    }

    private record WeaponSlotReference(Inventory inventory, int slot) {
    }

    private record WeaponTrigger(ItemStack weapon, int slot) {
    }
}