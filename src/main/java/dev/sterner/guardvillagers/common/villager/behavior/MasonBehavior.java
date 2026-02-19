package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.MasonGuardEntity;
import dev.sterner.guardvillagers.common.entity.goal.MasonCraftingGoal;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehavior;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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
import net.minecraft.item.ItemStack;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.ShovelItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

public class MasonBehavior implements VillagerProfessionBehavior {
    private static final Logger LOGGER = LoggerFactory.getLogger(MasonBehavior.class);
    private static final int CRAFTING_GOAL_PRIORITY = 4;
    private static final Map<VillagerEntity, MasonCraftingGoal> CRAFTING_GOALS = new WeakHashMap<>();
    private static final Map<VillagerEntity, ChestListener> CHEST_LISTENERS = new WeakHashMap<>();

    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        if (!villager.isAlive()) {
            clearChestListener(villager);
            return;
        }

        if (!world.getBlockState(jobPos).isOf(Blocks.STONECUTTER)) {
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
        craftingGoal.requestImmediateCraft(world);
        updateChestListener(world, villager, chestPos);
        tryConvertWithMiningTool(world, villager, jobPos, chestPos);
    }

    public static void tryConvertMasonsWithMiningTool(ServerWorld world) {
        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, JobBlockPairingHelper.getWorldBounds(world), entity -> entity.isAlive() && entity.getVillagerData().getProfession() == VillagerProfession.MASON)) {
            Optional<BlockPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE).map(net.minecraft.util.math.GlobalPos::pos);
            if (jobSite.isEmpty()) {
                continue;
            }

            BlockPos jobPos = jobSite.get();
            if (!world.getBlockState(jobPos).isOf(Blocks.STONECUTTER)) {
                continue;
            }

            Optional<BlockPos> chestPos = JobBlockPairingHelper.findNearbyChest(world, jobPos);
            if (chestPos.isEmpty() || !jobPos.isWithinDistance(chestPos.get(), 3.0D)) {
                continue;
            }

            tryConvertWithMiningTool(world, villager, jobPos, chestPos.get());
        }
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
            MasonCraftingGoal goal = CRAFTING_GOALS.get(villager);
            if (goal != null && villager.getWorld() instanceof ServerWorld serverWorld) {
                goal.requestImmediateCraft(serverWorld);
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

    private static void tryConvertWithMiningTool(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        MasonGuardEntity guard = GuardVillagers.MASON_GUARD_VILLAGER.create(world);
        if (guard == null) {
            return;
        }

        ItemStack miningTool = takeMiningToolFromChest(world, chestPos);
        if (miningTool.isEmpty()) {
            return;
        }

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
        guard.equipStack(EquipmentSlot.MAINHAND, miningTool.copy());
        guard.setExpectedMiningTool(miningTool);
        guard.setPairedChestPos(chestPos);
        guard.setPairedJobPos(jobPos);

        world.spawnEntityAndPassengers(guard);
        VillageGuardStandManager.handleGuardSpawn(world, guard, villager);

        LOGGER.info("Mason {} converted into Mason Guard {} using tool {} from chest {}",
                villager.getUuidAsString(),
                guard.getUuidAsString(),
                miningTool.getItem(),
                chestPos.toShortString());

        villager.releaseTicketFor(MemoryModuleType.HOME);
        villager.releaseTicketFor(MemoryModuleType.JOB_SITE);
        villager.releaseTicketFor(MemoryModuleType.MEETING_POINT);
        villager.discard();
    }

    private static ItemStack takeMiningToolFromChest(ServerWorld world, BlockPos chestPos) {
        BlockEntity blockEntity = world.getBlockEntity(chestPos);
        if (!(blockEntity instanceof Inventory inventory)) {
            return ItemStack.EMPTY;
        }

        ItemStack pickaxe = extractFirstMatching(inventory, stack -> stack.getItem() instanceof PickaxeItem);
        if (!pickaxe.isEmpty()) {
            return pickaxe;
        }

        return extractFirstMatching(inventory, stack -> stack.getItem() instanceof ShovelItem);
    }

    private static ItemStack extractFirstMatching(Inventory inventory, java.util.function.Predicate<ItemStack> predicate) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && predicate.test(stack)) {
                ItemStack extracted = stack.split(1);
                inventory.markDirty();
                return extracted;
            }
        }
        return ItemStack.EMPTY;
    }
}
