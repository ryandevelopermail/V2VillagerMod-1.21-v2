package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.AxeGuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import dev.sterner.guardvillagers.common.villager.LumberjackProfession;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

final class LumberjackGuardConversion {
    private static final Logger LOGGER = LoggerFactory.getLogger(LumberjackGuardConversion.class);

    private LumberjackGuardConversion() {
    }

    static void tryConvertToLumberjackGuard(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, String source) {
        if (!villager.isAlive() || villager.isRemoved() || villager.getVillagerData().getProfession() != LumberjackProfession.LUMBERJACK) {
            return;
        }
        if (!ProfessionDefinitions.isExpectedJobBlock(LumberjackProfession.LUMBERJACK, world.getBlockState(jobPos))) {
            return;
        }
        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
            return;
        }

        Optional<AxeSlotReference> triggerSlot = findAxeTriggerSlot(world, chestPos);
        if (triggerSlot.isEmpty()) {
            return;
        }

        AxeGuardEntity guard = GuardVillagers.AXE_GUARD_VILLAGER.create(world);
        if (guard == null) {
            return;
        }

        Optional<AxeTrigger> trigger = extractAxeTrigger(triggerSlot.get());
        if (trigger.isEmpty()) {
            return;
        }

        prepareConvertedGuard(guard, world, villager, jobPos, trigger.get().axe());
        world.spawnEntityAndPassengers(guard);
        VillageGuardStandManager.handleGuardSpawn(world, guard, villager);

        LOGGER.info("Lumberjack {} converted into Lumberjack Guard {} using {} from chest {} slot {} ({})",
                villager.getUuidAsString(),
                guard.getUuidAsString(),
                trigger.get().axe().getItem(),
                chestPos.toShortString(),
                trigger.get().slot(),
                source);

        villager.releaseTicketFor(MemoryModuleType.HOME);
        villager.releaseTicketFor(MemoryModuleType.JOB_SITE);
        villager.releaseTicketFor(MemoryModuleType.MEETING_POINT);
        villager.discard();
    }

    private static Optional<AxeSlotReference> findAxeTriggerSlot(ServerWorld world, BlockPos chestPos) {
        Inventory inventory = getChestInventory(world, chestPos);
        if (inventory == null) {
            return Optional.empty();
        }

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof AxeItem) {
                return Optional.of(new AxeSlotReference(inventory, slot));
            }
        }

        return Optional.empty();
    }

    private static Optional<AxeTrigger> extractAxeTrigger(AxeSlotReference reference) {
        ItemStack stack = reference.inventory().getStack(reference.slot());
        if (stack.isEmpty() || !(stack.getItem() instanceof AxeItem)) {
            return Optional.empty();
        }

        ItemStack extracted = stack.split(1);
        reference.inventory().markDirty();
        return Optional.of(new AxeTrigger(extracted, reference.slot()));
    }

    private static Inventory getChestInventory(ServerWorld world, BlockPos chestPos) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return null;
        }
        return ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
    }

    private static void prepareConvertedGuard(GuardEntity guard, ServerWorld world, VillagerEntity villager, BlockPos jobPos, ItemStack axe) {
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
        guard.equipStack(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        guard.equipStack(EquipmentSlot.MAINHAND, axe.copyWithCount(1));
    }

    private record AxeSlotReference(Inventory inventory, int slot) {
    }

    private record AxeTrigger(ItemStack axe, int slot) {
    }
}
