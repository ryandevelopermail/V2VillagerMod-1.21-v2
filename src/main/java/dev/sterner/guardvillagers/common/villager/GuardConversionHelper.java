package dev.sterner.guardvillagers.common.villager;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class GuardConversionHelper {
    private GuardConversionHelper() {
    }

    public static void initializeConvertedGuard(ServerWorld world, VillagerEntity villager, GuardEntity guard, BlockPos difficultyPos) {
        guard.initialize(world, world.getLocalDifficulty(difficultyPos), SpawnReason.CONVERSION, null);
        guard.spawnWithArmor = false;
        copyVillagerIdentityAndPose(world, villager, guard);
    }

    public static void copyVillagerIdentityAndPose(ServerWorld world, VillagerEntity villager, GuardEntity guard) {
        guard.copyPositionAndRotation(villager);
        guard.headYaw = villager.headYaw;
        guard.refreshPositionAndAngles(villager.getX(), villager.getY(), villager.getZ(), villager.getYaw(), villager.getPitch());
        guard.setGuardVariant(GuardEntity.getRandomTypeForBiome(world, guard.getBlockPos()));
        guard.setPersistent();
        guard.setCustomName(villager.getCustomName());
        guard.setCustomNameVisible(villager.isCustomNameVisible());
    }

    public static void applyStandardEquipmentDropChances(GuardEntity guard) {
        guard.setEquipmentDropChance(EquipmentSlot.HEAD, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.CHEST, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.FEET, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.LEGS, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.MAINHAND, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.OFFHAND, 100.0F);
    }

    public static void cleanupVillagerAfterConversion(VillagerEntity villager) {
        villager.releaseTicketFor(MemoryModuleType.HOME);
        villager.releaseTicketFor(MemoryModuleType.JOB_SITE);
        villager.releaseTicketFor(MemoryModuleType.MEETING_POINT);
        villager.discard();
    }

    public static String buildConversionMetadata(VillagerEntity villager, GuardEntity guard, BlockPos jobPos, BlockPos chestPos, String source) {
        return "villager=" + villager.getUuidAsString()
                + ", guard=" + guard.getUuidAsString()
                + ", job=" + jobPos.toShortString()
                + ", chest=" + (chestPos == null ? "none" : chestPos.toShortString())
                + ", source=" + source;
    }
}
