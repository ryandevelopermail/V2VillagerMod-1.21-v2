package dev.sterner.guardvillagers.common.util;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
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
        return findPlacementStand(world, villager, center, slot, ItemStack.EMPTY);
    }

    public static Optional<ArmorStandEntity> findPlacementStand(ServerWorld world, VillagerEntity villager, BlockPos center, EquipmentSlot slot,
                                                                 ItemStack candidate) {
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
                .filter(stand -> isStandAvailableForHand(stand, memory, slot, candidate))
                .findFirst();
    }

    public static boolean isStandAvailableForHand(VillagerEntity villager, ArmorStandEntity stand, EquipmentSlot slot) {
        return isStandAvailableForHand(stand, getStandMemory(villager), slot, ItemStack.EMPTY);
    }

    public static boolean isStandAvailableForWeapon(VillagerEntity villager, ArmorStandEntity stand, EquipmentSlot slot, ItemStack candidate) {
        return isStandAvailableForHand(stand, getStandMemory(villager), slot, candidate);
    }

    private static boolean isStandAvailableForHand(ArmorStandEntity stand, Map<UUID, StandProgress> memory, EquipmentSlot slot, ItemStack candidate) {
        if (!isHandSlot(slot)) {
            return false;
        }
        StandProgress progress = syncProgressWithStand(stand, memory);
        ItemStack equipped = stand.getEquippedStack(slot);
        if (!equipped.isEmpty()) {
            return canReplaceWeapon(candidate, equipped, slot);
        }
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
        ItemStack equipped = stand.getEquippedStack(slot);
        if (!equipped.isEmpty()) {
            if (!canReplaceWeapon(stack, equipped, slot)) {
                markSlotPlaced(villager, stand.getUuid(), slot);
                return false;
            }
        }

        StandProgress progress = syncProgressWithStand(stand, getStandMemory(villager));
        if (equipped.isEmpty() && progress.hasSlot(slot)) {
            return false;
        }

        ItemStack toPlace = stack.copy();
        toPlace.setCount(1);
        if (!equipped.isEmpty()) {
            stand.dropStack(equipped.copy());
        }
        stand.equipStack(slot, toPlace);
        markSlotPlaced(villager, stand.getUuid(), slot);
        return true;
    }

    private static boolean canReplaceWeapon(ItemStack candidate, ItemStack current, EquipmentSlot slot) {
        if (candidate.isEmpty() || current.isEmpty()) {
            return false;
        }

        int tierComparison = compareMaterialTier(candidate, current);
        if (tierComparison != 0) {
            return tierComparison > 0;
        }

        return GearGradeComparator.isUpgrade(candidate, current, slot);
    }

    private static int compareMaterialTier(ItemStack candidate, ItemStack current) {
        boolean bothSwords = candidate.getItem() instanceof SwordItem && current.getItem() instanceof SwordItem;
        boolean bothAxes = candidate.getItem() instanceof AxeItem && current.getItem() instanceof AxeItem;
        if (!bothSwords && !bothAxes) {
            return 0;
        }

        int candidateTier = resolveWeaponTier(candidate);
        int currentTier = resolveWeaponTier(current);
        if (candidateTier < 0 || currentTier < 0) {
            return 0;
        }
        return Integer.compare(candidateTier, currentTier);
    }

    private static int resolveWeaponTier(ItemStack stack) {
        if (stack.isOf(Items.WOODEN_SWORD) || stack.isOf(Items.WOODEN_AXE) || stack.isOf(Items.GOLDEN_SWORD) || stack.isOf(Items.GOLDEN_AXE)) {
            return 0;
        }
        if (stack.isOf(Items.STONE_SWORD) || stack.isOf(Items.STONE_AXE)) {
            return 1;
        }
        if (stack.isOf(Items.IRON_SWORD) || stack.isOf(Items.IRON_AXE)) {
            return 2;
        }
        if (stack.isOf(Items.DIAMOND_SWORD) || stack.isOf(Items.DIAMOND_AXE)) {
            return 3;
        }
        if (stack.isOf(Items.NETHERITE_SWORD) || stack.isOf(Items.NETHERITE_AXE)) {
            return 4;
        }
        return -1;
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
