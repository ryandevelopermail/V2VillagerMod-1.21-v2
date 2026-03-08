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
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Map<VillagerEntity, ChestListenerRegistration> CHEST_LISTENERS = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!checkPairingPreconditions(world, villager, jobPos, chestPos,
                (serverWorld, pos) -> ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.BUTCHER, serverWorld.getBlockState(pos)),
                () -> clearChestListener(CHEST_LISTENERS, villager))) {
            return;
        }

        LOGGER.info("Butcher {} paired chest at {} for job site {}",
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

        updateChestListener(world, villager, chestPos, CHEST_LISTENERS, (serverWorld, pairedVillager) -> sender -> {
            ButcherSmokerGoal smokerGoal = GOALS.get(pairedVillager);
            if (smokerGoal != null) {
                smokerGoal.requestImmediateCheck();
            }

            ButcherCraftingGoal craftingGoal = CRAFTING_GOALS.get(pairedVillager);
            if (craftingGoal != null) {
                craftingGoal.requestImmediateCraft(serverWorld);
            }

            ButcherMeatDistributionGoal pairedMeatDistributionGoal = MEAT_DISTRIBUTION_GOALS.get(pairedVillager);
            if (pairedMeatDistributionGoal != null) {
                pairedMeatDistributionGoal.requestImmediateDistribution();
            }

            ButcherToLeatherworkerDistributionGoal pairedLeatherDistributionGoal = LEATHER_DISTRIBUTION_GOALS.get(pairedVillager);
            if (pairedLeatherDistributionGoal != null) {
                pairedLeatherDistributionGoal.requestImmediateDistribution();
            }

            VillagerConversionCandidateIndex.markCandidate(serverWorld, pairedVillager);
            ProfessionDefinitions.runConversionHooks(serverWorld);
        });

        tryConvertWithWeapon(world, villager, jobPos, chestPos);
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        if (!checkPairingPreconditions(world, villager, jobPos, chestPos,
                (serverWorld, pos) -> ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.BUTCHER, serverWorld.getBlockState(pos)),
                () -> clearChestListener(CHEST_LISTENERS, villager))) {
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

        updateChestListener(world, villager, chestPos, CHEST_LISTENERS, (serverWorld, pairedVillager) -> sender -> {
            ButcherSmokerGoal pairedSmokerGoal = GOALS.get(pairedVillager);
            if (pairedSmokerGoal != null) {
                pairedSmokerGoal.requestImmediateCheck();
            }

            ButcherCraftingGoal pairedCraftingGoal = CRAFTING_GOALS.get(pairedVillager);
            if (pairedCraftingGoal != null) {
                pairedCraftingGoal.requestImmediateCraft(serverWorld);
            }

            ButcherMeatDistributionGoal pairedMeatDistributionGoal = MEAT_DISTRIBUTION_GOALS.get(pairedVillager);
            if (pairedMeatDistributionGoal != null) {
                pairedMeatDistributionGoal.requestImmediateDistribution();
            }

            ButcherToLeatherworkerDistributionGoal pairedLeatherDistributionGoal = LEATHER_DISTRIBUTION_GOALS.get(pairedVillager);
            if (pairedLeatherDistributionGoal != null) {
                pairedLeatherDistributionGoal.requestImmediateDistribution();
            }

            VillagerConversionCandidateIndex.markCandidate(serverWorld, pairedVillager);
            ProfessionDefinitions.runConversionHooks(serverWorld);
        });
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

        Inventory inventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
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

}
