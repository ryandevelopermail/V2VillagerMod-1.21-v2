package dev.sterner.guardvillagers.common.handler;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;

import java.util.Comparator;
import java.util.Optional;

public final class ArmorStandConversionHandler {
    private final GuardModifierApplicationService guardModifierService;

    public ArmorStandConversionHandler(GuardModifierApplicationService guardModifierService) {
        this.guardModifierService = guardModifierService;
    }

    public void tryConvertVillagerWithArmorStand(ServerWorld world, BlockPos placementPos) {
        boolean armorStandPlaced = !world.getEntitiesByClass(ArmorStandEntity.class, new Box(placementPos).expand(JobBlockPlacementConstants.ARMOR_STAND_PLACEMENT_CHECK_RANGE), Entity::isAlive).isEmpty();
        if (!armorStandPlaced) {
            return;
        }

        Optional<VillagerEntity> nearestVillager = findNearestUnemployedVillager(world, placementPos);
        if (nearestVillager.isEmpty()) {
            return;
        }

        VillagerEntity villager = nearestVillager.get();
        JobBlockPairingHelper.playPairingAnimation(world, placementPos, villager, placementPos);
        convertVillagerToGuard(world, villager);
    }

    private Optional<VillagerEntity> findNearestUnemployedVillager(ServerWorld world, BlockPos placementPos) {
        return world.getEntitiesByClass(VillagerEntity.class, new Box(placementPos).expand(JobBlockPlacementConstants.ARMOR_STAND_PAIRING_RANGE), ArmorStandConversionHandler::isUnemployedVillager)
                .stream()
                .min(Comparator.comparingDouble(v -> v.squaredDistanceTo(Vec3d.ofCenter(placementPos))));
    }

    private static boolean isUnemployedVillager(VillagerEntity villager) {
        VillagerProfession profession = villager.getVillagerData().getProfession();
        return profession == VillagerProfession.NONE && !villager.isBaby();
    }

    private void convertVillagerToGuard(ServerWorld world, VillagerEntity villager) {
        GuardEntity guard = GuardVillagers.GUARD_VILLAGER.create(world);
        if (guard == null) {
            return;
        }

        guard.setConvertedFromArmorStand(true);
        guard.spawnWithArmor = true;
        guard.initialize(world, world.getLocalDifficulty(villager.getBlockPos()), SpawnReason.CONVERSION, null);
        guard.refreshPositionAndAngles(villager.getX(), villager.getY(), villager.getZ(), villager.getYaw(), villager.getPitch());
        guard.headYaw = villager.headYaw;

        int variant = GuardEntity.getRandomTypeForBiome(guard.getWorld(), guard.getBlockPos());
        guard.setGuardVariant(variant);
        guard.setPersistent();
        guard.setCustomName(villager.getCustomName());
        guard.setCustomNameVisible(villager.isCustomNameVisible());
        guard.setEquipmentDropChance(EquipmentSlot.HEAD, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.CHEST, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.FEET, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.LEGS, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.MAINHAND, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.OFFHAND, 100.0F);

        world.spawnEntityAndPassengers(guard);
        VillageGuardStandManager.handleGuardSpawn(world, guard, villager);
        guard.playSound(SoundEvents.ENTITY_VILLAGER_YES, 1.0F, 1.0F);
        guardModifierService.applySpecialModifierFromNearbyBlocks(world, guard);

        villager.releaseTicketFor(MemoryModuleType.HOME);
        villager.releaseTicketFor(MemoryModuleType.JOB_SITE);
        villager.releaseTicketFor(MemoryModuleType.MEETING_POINT);
        villager.discard();
    }
}
