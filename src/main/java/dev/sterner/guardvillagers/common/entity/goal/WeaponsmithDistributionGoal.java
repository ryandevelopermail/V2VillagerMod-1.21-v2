package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.util.WeaponsmithStandManager;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MaceItem;
import net.minecraft.item.SwordItem;
import net.minecraft.item.TridentItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class WeaponsmithDistributionGoal extends AbstractInventoryDistributionGoal {
    private static final Logger LOGGER = LoggerFactory.getLogger(WeaponsmithDistributionGoal.class);
    private long adaptiveThrottleUntilTick;
    private long adaptiveScanVolumeWindow;
    private int adaptivePathRetryWindow;
    private int adaptiveFailedSessionWindow;
    private int adaptiveForcedRecoveryWindow;
    private int adaptiveSessionCount;

    public WeaponsmithDistributionGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        super(villager, jobPos, chestPos, craftingTablePos);
    }

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (world.getTime() < adaptiveThrottleUntilTick) {
            return false;
        }
        adaptiveScanVolumeWindow++;
        if (shouldThrottleDistributionScans(world)) {
            return false;
        }
        boolean canStart = super.canStart();
        if (canStart) {
            adaptiveSessionCount++;
            maybeLogAdaptiveSummary();
        } else {
            adaptiveFailedSessionWindow++;
        }
        return canStart;
    }

    @Override
    public void tick() {
        if (stage == Stage.GO_TO_TARGET && pendingTargetPos != null && !isNear(pendingTargetPos)) {
            adaptivePathRetryWindow++;
        }
        super.tick();
    }

    @Override
    protected boolean isDistributableItem(ItemStack stack) {
        return stack.getItem() instanceof SwordItem
                || stack.getItem() instanceof AxeItem
                || stack.getItem() instanceof BowItem
                || stack.getItem() instanceof CrossbowItem
                || stack.getItem() instanceof TridentItem
                || stack.getItem() instanceof MaceItem;
    }

    @Override
    protected Optional<ArmorStandEntity> findPlacementStand(ServerWorld world, ItemStack stack) {
        return WeaponsmithStandManager.findPlacementStand(world, villager, getDistributionCenter(), EquipmentSlot.MAINHAND, stack);
    }

    @Override
    protected boolean isStandAvailableForPendingItem(ServerWorld world, ArmorStandEntity stand) {
        return WeaponsmithStandManager.isStandAvailableForWeapon(villager, stand, EquipmentSlot.MAINHAND, pendingItem);
    }

    @Override
    protected boolean placePendingItemOnStand(ServerWorld world, ArmorStandEntity stand) {
        return WeaponsmithStandManager.placeWeaponOnStand(world, villager, stand, pendingItem, EquipmentSlot.MAINHAND);
    }

    @Override
    protected void clearPendingTargetState() {
    }

    @Override
    protected boolean matchesProfession(VillagerEntity villager) {
        return villager.getVillagerData().getProfession() == VillagerProfession.WEAPONSMITH;
    }

    private boolean shouldThrottleDistributionScans(ServerWorld world) {
        long adaptiveLoadScore = adaptiveScanVolumeWindow
                + (long) adaptivePathRetryWindow * 8L
                + (long) adaptiveFailedSessionWindow * 30L
                + (long) adaptiveForcedRecoveryWindow * 40L;
        if (adaptiveLoadScore < GuardVillagersConfig.weaponsmithAdaptiveThrottleLoadThreshold) {
            return false;
        }
        int jitter = GuardVillagersConfig.weaponsmithAdaptiveThrottleJitterTicks <= 0
                ? 0
                : villager.getRandom().nextInt(GuardVillagersConfig.weaponsmithAdaptiveThrottleJitterTicks + 1);
        int deferTicks = GuardVillagersConfig.weaponsmithAdaptiveThrottleDeferTicks + jitter;
        adaptiveThrottleUntilTick = world.getTime() + deferTicks;
        nextCheckTime = Math.max(nextCheckTime, adaptiveThrottleUntilTick);
        adaptiveScanVolumeWindow = Math.max(0L, adaptiveScanVolumeWindow / 2L);
        adaptivePathRetryWindow = Math.max(0, adaptivePathRetryWindow / 2);
        adaptiveFailedSessionWindow = Math.max(0, adaptiveFailedSessionWindow / 2);
        adaptiveForcedRecoveryWindow = Math.max(0, adaptiveForcedRecoveryWindow / 2);
        return true;
    }

    private void maybeLogAdaptiveSummary() {
        int interval = Math.max(1, GuardVillagersConfig.weaponsmithAdaptiveSummaryLogIntervalSessions);
        if (adaptiveSessionCount % interval != 0) {
            return;
        }
        LOGGER.info("Weaponsmith adaptive summary villager={} sessions={} scanVolume={} pathRetries={} failedSessions={} forcedRecoveries={} throttleUntilTick={}",
                villager.getUuidAsString(),
                adaptiveSessionCount,
                adaptiveScanVolumeWindow,
                adaptivePathRetryWindow,
                adaptiveFailedSessionWindow,
                adaptiveForcedRecoveryWindow,
                adaptiveThrottleUntilTick);
    }
}
