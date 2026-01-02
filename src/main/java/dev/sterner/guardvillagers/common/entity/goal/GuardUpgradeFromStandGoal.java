package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.util.GearGradeComparator;
import dev.sterner.guardvillagers.common.util.GuardStandEquipmentSync;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

public class GuardUpgradeFromStandGoal extends Goal {
    private static final double REACH_DISTANCE = 2.0D;
    private static final List<EquipmentSlot> UPGRADE_SLOTS = List.of(
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET,
            EquipmentSlot.MAINHAND,
            EquipmentSlot.OFFHAND
    );

    private final GuardEntity guard;
    private final double speed;
    private ArmorStandEntity stand;

    public GuardUpgradeFromStandGoal(GuardEntity guard, double speed) {
        this.guard = guard;
        this.speed = speed;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        if (guard.getWorld().isClient) {
            return false;
        }

        UUID standId = guard.getPairedStandUuid();
        if (standId == null || !(guard.getWorld() instanceof ServerWorld serverWorld)) {
            return false;
        }

        Entity standEntity = serverWorld.getEntity(standId);
        if (!(standEntity instanceof ArmorStandEntity armorStand)
                || !armorStand.isAlive()
                || !armorStand.getCommandTags().contains(VillageGuardStandManager.GUARD_STAND_TAG)) {
            return false;
        }

        if (!hasUpgradeAvailable(armorStand)) {
            return false;
        }

        this.stand = armorStand;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        if (stand == null || !stand.isAlive() || stand.getWorld().isClient) {
            return false;
        }
        if (!stand.getCommandTags().contains(VillageGuardStandManager.GUARD_STAND_TAG)) {
            return false;
        }
        return hasUpgradeAvailable(stand) && !guard.getNavigation().isIdle();
    }

    @Override
    public void start() {
        if (stand != null) {
            guard.getNavigation().startMovingTo(stand, speed);
        }
    }

    @Override
    public void stop() {
        stand = null;
    }

    @Override
    public void tick() {
        if (stand == null) {
            return;
        }

        guard.getLookControl().lookAt(stand, 30.0F, 30.0F);
        guard.getNavigation().startMovingTo(stand, speed);

        if (guard.squaredDistanceTo(stand) <= REACH_DISTANCE * REACH_DISTANCE) {
            if (transferUpgrades(stand)) {
                GuardStandEquipmentSync.syncStandFromGuard(guard, stand);
            }
            guard.getNavigation().stop();
        }
    }

    private boolean hasUpgradeAvailable(ArmorStandEntity armorStand) {
        for (EquipmentSlot slot : UPGRADE_SLOTS) {
            if (isStandUpgrade(slot, armorStand)) {
                return true;
            }
        }
        return false;
    }

    private boolean transferUpgrades(ArmorStandEntity armorStand) {
        boolean upgraded = false;
        for (EquipmentSlot slot : UPGRADE_SLOTS) {
            if (!isStandUpgrade(slot, armorStand)) {
                continue;
            }

            ItemStack standStack = armorStand.getEquippedStack(slot);
            if (standStack.isEmpty()) {
                continue;
            }

            ItemStack copy = standStack.copy();
            copy.setCount(1);
            guard.equipStack(slot, copy);
            upgraded = true;
        }
        return upgraded;
    }

    private boolean isStandUpgrade(EquipmentSlot slot, ArmorStandEntity armorStand) {
        ItemStack standStack = armorStand.getEquippedStack(slot);
        ItemStack guardStack = guard.getEquippedStack(slot);
        return GearGradeComparator.isUpgrade(standStack, guardStack, slot);
    }
}
