package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.ButcherGuardEntity;
import dev.sterner.guardvillagers.common.entity.goal.ButcherCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.ButcherMeatDistributionGoal;
import dev.sterner.guardvillagers.common.entity.goal.ButcherSmokerGoal;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehavior;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
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
    private static final int DISTRIBUTION_GOAL_PRIORITY = 6;
    private static final Map<VillagerEntity, ButcherSmokerGoal> GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ButcherCraftingGoal> CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ButcherMeatDistributionGoal> DISTRIBUTION_GOALS = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!villager.isAlive()) {
            return;
        }

        if (!world.getBlockState(jobPos).isOf(Blocks.SMOKER)) {
            return;
        }

        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
            return;
        }

        LOGGER.info("Butcher {} paired chest at {} for job site {}",
                villager.getUuidAsString(),
                chestPos.toShortString(),
                jobPos.toShortString());

        ButcherMeatDistributionGoal distributionGoal = DISTRIBUTION_GOALS.get(villager);
        if (distributionGoal == null) {
            distributionGoal = new ButcherMeatDistributionGoal(villager, jobPos, chestPos);
            DISTRIBUTION_GOALS.put(villager, distributionGoal);
            villager.goalSelector.add(DISTRIBUTION_GOAL_PRIORITY, distributionGoal);
        } else {
            distributionGoal.setTargets(jobPos, chestPos);
        }

        ButcherSmokerGoal goal = GOALS.get(villager);
        if (goal == null) {
            goal = new ButcherSmokerGoal(villager, jobPos, chestPos);
            GOALS.put(villager, goal);
            GoalSelector selector = villager.goalSelector;
            selector.add(SMOKER_GOAL_PRIORITY, goal);
        } else {
            goal.setTargets(jobPos, chestPos);
        }

        tryConvertWithAxe(world, villager, jobPos, chestPos);
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        if (!villager.isAlive()) {
            return;
        }

        if (!world.getBlockState(jobPos).isOf(Blocks.SMOKER)) {
            return;
        }

        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
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
    }

    public static void tryConvertButchersWithAxe(ServerWorld world) {
        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, JobBlockPairingHelper.getWorldBounds(world), entity -> entity.isAlive() && entity.getVillagerData().getProfession() == VillagerProfession.BUTCHER)) {
            Optional<BlockPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE).map(net.minecraft.util.math.GlobalPos::pos);
            if (jobSite.isEmpty()) {
                continue;
            }

            BlockPos jobPos = jobSite.get();
            if (!world.getBlockState(jobPos).isOf(Blocks.SMOKER)) {
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
}
