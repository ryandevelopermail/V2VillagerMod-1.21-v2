package dev.sterner.guardvillagers.common.util;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
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

public final class WeaponsmithStandManager {
    public static final String WEAPON_STAND_MEMORY_KEY = "GuardVillagersWeaponsmithStandMemory";
    private static final int STAND_SCAN_RANGE = VillageGuardStandManager.BELL_EFFECT_RANGE;
    private static final int HAND_MASK_COMPLETE = 0b11;

    private WeaponsmithStandManager() {
    }

    public static Optional<ArmorStandEntity> findPlacementStand(ServerWorld world, VillagerEntity villager, BlockPos center, EquipmentSlot slot) {
        List<ArmorStandEntity> stands = world.getEntitiesByClass(ArmorStandEntity.class, new Box(center).expand(STAND_SCAN_RANGE),
                stand -> stand.isAlive() && stand.getCommandTags().contains(VillageGuardStandManager.GUARD_STAND_TAG));
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
                .filter(stand -> isStandAvailableForHand(stand, memory, slot))
                .findFirst();
    }

    public static boolean isStandAvailableForHand(VillagerEntity villager, ArmorStandEntity stand, EquipmentSlot slot) {
        return isStandAvailableForHand(stand, getStandMemory(villager), slot);
    }

    private static boolean isStandAvailableForHand(ArmorStandEntity stand, Map<UUID, StandProgress> memory, EquipmentSlot slot) {
        if (!isHandSlot(slot)) {
            return false;
        }
        StandProgress progress = syncProgressWithStand(stand, memory);
        if (progress.isComplete()) {
            return false;
        }
        return !progress.hasSlot(slot);
    }

    private static StandProgress syncProgressWithStand(ArmorStandEntity stand, Map<UUID, StandProgress> memory) {
        StandProgress progress = memory.computeIfAbsent(stand.getUuid(), id -> new StandProgress());
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (!isHandSlot(slot)) {
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

    public static boolean placeWeaponOnStand(ServerWorld world, VillagerEntity villager, ArmorStandEntity stand, ItemStack stack, EquipmentSlot slot) {
        if (!isHandSlot(slot)) {
            return false;
        }
        if (!stand.isAlive() || stand.getWorld() != world) {
            return false;
        }
        if (!stand.getEquippedStack(slot).isEmpty()) {
            markSlotPlaced(villager, stand.getUuid(), slot);
            return false;
        }

        StandProgress progress = syncProgressWithStand(stand, getStandMemory(villager));
        if (progress.hasSlot(slot)) {
            return false;
        }

        ItemStack toPlace = stack.copy();
        toPlace.setCount(1);
        stand.equipStack(slot, toPlace);
        markSlotPlaced(villager, stand.getUuid(), slot);
        return true;
    }

    public static Map<UUID, StandProgress> getStandMemory(VillagerEntity villager) {
        if (villager instanceof WeaponsmithStandMemoryHolder holder) {
            return holder.guardvillagers$getWeaponsmithStandMemory();
        }
        return new HashMap<>();
    }

    private static boolean isHandSlot(EquipmentSlot slot) {
        return slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND;
    }

    public static final class StandProgress {
        private int handMask;

        public int getHandMask() {
            return handMask;
        }

        public void setHandMask(int handMask) {
            this.handMask = handMask;
        }

        public boolean hasSlot(EquipmentSlot slot) {
            return (handMask & maskForSlot(slot)) != 0;
        }

        public void markSlot(EquipmentSlot slot) {
            handMask |= maskForSlot(slot);
        }

        public boolean isComplete() {
            return handMask == HAND_MASK_COMPLETE;
        }

        private int maskForSlot(EquipmentSlot slot) {
            return switch (slot) {
                case MAINHAND -> 0b0001;
                case OFFHAND -> 0b0010;
                default -> 0;
            };
        }
    }
}
