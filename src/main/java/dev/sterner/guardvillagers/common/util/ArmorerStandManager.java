package dev.sterner.guardvillagers.common.util;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class ArmorerStandManager {
    public static final String ARMOR_STAND_MEMORY_KEY = "GuardVillagersArmorerStandMemory";
    private static final int STAND_SCAN_RANGE = VillageGuardStandManager.BELL_EFFECT_RANGE;
    private static final int ARMOR_MASK_COMPLETE = 0b1111;

    private ArmorerStandManager() {
    }

    public static boolean tryPlaceArmorOnStand(ServerWorld world, VillagerEntity villager, BlockPos craftingTablePos, ItemStack stack) {
        if (!(stack.getItem() instanceof ArmorItem armorItem)) {
            return false;
        }

        EquipmentSlot slot = armorItem.getSlotType();
        BlockPos center = craftingTablePos != null ? craftingTablePos : villager.getBlockPos();
        Optional<ArmorStandEntity> target = findPlacementStand(world, villager, center, slot);
        if (target.isEmpty()) {
            return false;
        }

        return placeArmorOnStand(world, villager, target.get(), stack);
    }

    public static Optional<ArmorStandEntity> findPlacementStand(ServerWorld world, VillagerEntity villager, BlockPos center, EquipmentSlot slot) {
        List<ArmorStandEntity> stands = world.getEntitiesByClass(ArmorStandEntity.class, new Box(center).expand(STAND_SCAN_RANGE), ArmorStandEntity::isAlive);
        if (stands.isEmpty()) {
            return Optional.empty();
        }

        Map<UUID, StandProgress> memory = getStandMemory(villager);
        Comparator<ArmorStandEntity> distanceComparator = Comparator
                .comparingDouble((ArmorStandEntity stand) -> stand.squaredDistanceTo(Vec3d.ofCenter(center)))
                .thenComparing(stand -> stand.getBlockPos().getX())
                .thenComparing(stand -> stand.getBlockPos().getY())
                .thenComparing(stand -> stand.getBlockPos().getZ());

        return stands.stream()
                .sorted(distanceComparator)
                .filter(stand -> isStandAvailableForSlot(stand, memory, slot))
                .findFirst();
    }

    public static boolean isStandAvailableForSlot(VillagerEntity villager, ArmorStandEntity stand, EquipmentSlot slot) {
        return isStandAvailableForSlot(stand, getStandMemory(villager), slot);
    }

    private static boolean isStandAvailableForSlot(ArmorStandEntity stand, Map<UUID, StandProgress> memory, EquipmentSlot slot) {
        StandProgress progress = syncProgressWithStand(stand, memory);
        if (progress.isComplete()) {
            return false;
        }
        return !progress.hasSlot(slot);
    }

    private static StandProgress syncProgressWithStand(ArmorStandEntity stand, Map<UUID, StandProgress> memory) {
        StandProgress progress = memory.computeIfAbsent(stand.getUuid(), id -> new StandProgress());
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (!slot.isArmorSlot()) {
                continue;
            }
            if (!stand.getEquippedStack(slot).isEmpty()) {
                progress.markSlot(slot);
            }
        }
        return progress;
    }

    private static void markSlotPlaced(VillagerEntity villager, UUID standId, EquipmentSlot slot) {
        Map<UUID, StandProgress> memory = getStandMemory(villager);
        StandProgress progress = memory.computeIfAbsent(standId, id -> new StandProgress());
        progress.markSlot(slot);
    }

    public static boolean placeArmorOnStand(ServerWorld world, VillagerEntity villager, ArmorStandEntity stand, ItemStack stack) {
        if (!(stack.getItem() instanceof ArmorItem armorItem)) {
            return false;
        }
        if (!stand.isAlive() || stand.getWorld() != world) {
            return false;
        }

        EquipmentSlot slot = armorItem.getSlotType();
        if (!stand.getEquippedStack(slot).isEmpty()) {
            markSlotPlaced(villager, stand.getUuid(), slot);
            return false;
        }

        ItemStack toPlace = stack.copy();
        toPlace.setCount(1);
        stand.equipStack(slot, toPlace);
        markSlotPlaced(villager, stand.getUuid(), slot);
        return true;
    }

    public static Map<UUID, StandProgress> getStandMemory(VillagerEntity villager) {
        if (villager instanceof ArmorerStandMemoryHolder holder) {
            return holder.guardvillagers$getArmorerStandMemory();
        }
        return new HashMap<>();
    }

    public static final class StandProgress {
        private int armorMask;

        public int getArmorMask() {
            return armorMask;
        }

        public void setArmorMask(int armorMask) {
            this.armorMask = armorMask;
        }

        public boolean hasSlot(EquipmentSlot slot) {
            return (armorMask & maskForSlot(slot)) != 0;
        }

        public void markSlot(EquipmentSlot slot) {
            armorMask |= maskForSlot(slot);
        }

        public boolean isComplete() {
            return armorMask == ARMOR_MASK_COMPLETE;
        }

        private int maskForSlot(EquipmentSlot slot) {
            return switch (slot) {
                case HEAD -> 0b0001;
                case CHEST -> 0b0010;
                case LEGS -> 0b0100;
                case FEET -> 0b1000;
                default -> 0;
            };
        }
    }
}
