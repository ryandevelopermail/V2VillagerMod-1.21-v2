package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.util.WeaponsmithStandManager;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MaceItem;
import net.minecraft.item.SwordItem;
import net.minecraft.item.TridentItem;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class WeaponsmithDistributionGoal extends AbstractInventoryDistributionGoal {
    private static final Logger LOGGER = LoggerFactory.getLogger(WeaponsmithDistributionGoal.class);
    private static final Identifier HUNTER_PROFESSION_ID = Identifier.of("morevillagers", "hunter");
    private static final Identifier HUNTING_POST_ID = Identifier.of("morevillagers", "hunting_post");
    private static final double HUNTER_RECIPIENT_SCAN_RANGE = 32.0D;
    private long adaptiveThrottleUntilTick;
    private long adaptiveScanVolumeWindow;
    private int adaptivePathRetryWindow;
    private int adaptiveFailedSessionWindow;
    private int adaptiveForcedRecoveryWindow;
    private int adaptiveSessionCount;
    private boolean pendingHunterTransfer;

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
    protected boolean canStartWithInventory(ServerWorld world, net.minecraft.inventory.Inventory inventory) {
        return hasHunterTransferCandidate(world, inventory) || super.canStartWithInventory(world, inventory);
    }

    @Override
    protected boolean selectPendingTransfer(ServerWorld world, net.minecraft.inventory.Inventory inventory) {
        if (inventory != null) {
            List<HunterRecipient> hunters = findEligibleHunterRecipients(world);
            if (!hunters.isEmpty()) {
                for (int slot = 0; slot < inventory.size(); slot++) {
                    ItemStack stack = inventory.getStack(slot);
                    if (!MoreVillagersHunterCombatGoal.hasWeapon(stack)) {
                        continue;
                    }

                    HunterRecipient recipient = hunters.getFirst();
                    ItemStack extracted = stack.split(1);
                    inventory.setStack(slot, stack);
                    inventory.markDirty();

                    pendingItem = extracted;
                    pendingTargetId = recipient.villager().getUuid();
                    pendingTargetPos = recipient.chestPos();
                    pendingUniversalRoute = false;
                    pendingOverflowTransfer = false;
                    pendingHunterTransfer = true;
                    return true;
                }
            }
        }
        pendingHunterTransfer = false;
        return super.selectPendingTransfer(world, inventory);
    }

    @Override
    protected boolean refreshTargetForPendingItem(ServerWorld world) {
        if (pendingHunterTransfer) {
            List<HunterRecipient> hunters = findEligibleHunterRecipients(world);
            if (hunters.isEmpty()) {
                return false;
            }
            if (pendingTargetId != null) {
                for (HunterRecipient recipient : hunters) {
                    if (recipient.villager().getUuid().equals(pendingTargetId)) {
                        pendingTargetPos = recipient.chestPos();
                        return true;
                    }
                }
            }
            HunterRecipient recipient = hunters.getFirst();
            pendingTargetId = recipient.villager().getUuid();
            pendingTargetPos = recipient.chestPos();
            return true;
        }
        return super.refreshTargetForPendingItem(world);
    }

    @Override
    protected boolean executeTransfer(ServerWorld world) {
        if (pendingHunterTransfer) {
            if (pendingItem.isEmpty() || pendingTargetPos == null) {
                return false;
            }
            Optional<net.minecraft.inventory.Inventory> targetInventory = getChestInventoryAt(world, pendingTargetPos);
            if (targetInventory.isEmpty()) {
                return false;
            }
            ItemStack remaining = insertStack(targetInventory.get(), pendingItem);
            targetInventory.get().markDirty();
            if (remaining.isEmpty()) {
                return true;
            }
            pendingItem = remaining;
            return false;
        }
        return super.executeTransfer(world);
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
        pendingHunterTransfer = false;
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

    private boolean hasHunterTransferCandidate(ServerWorld world, net.minecraft.inventory.Inventory inventory) {
        if (inventory == null || findEligibleHunterRecipients(world).isEmpty()) {
            return false;
        }
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (MoreVillagersHunterCombatGoal.hasWeapon(inventory.getStack(slot))) {
                return true;
            }
        }
        return false;
    }

    private List<HunterRecipient> findEligibleHunterRecipients(ServerWorld world) {
        Box scanBox = new Box(villager.getBlockPos()).expand(HUNTER_RECIPIENT_SCAN_RANGE);
        return world.getEntitiesByClass(VillagerEntity.class, scanBox, candidate -> candidate != villager
                        && candidate.isAlive()
                        && HUNTER_PROFESSION_ID.equals(Registries.VILLAGER_PROFESSION.getId(candidate.getVillagerData().getProfession())))
                .stream()
                .map(candidate -> toHunterRecipient(world, candidate))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparingDouble(HunterRecipient::sourceSquaredDistance)
                        .thenComparing(recipient -> recipient.villager().getUuid(), java.util.UUID::compareTo))
                .toList();
    }

    private Optional<HunterRecipient> toHunterRecipient(ServerWorld world, VillagerEntity hunter) {
        Optional<GlobalPos> jobSiteMemory = hunter.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
        if (jobSiteMemory.isEmpty()) {
            return Optional.empty();
        }
        GlobalPos globalPos = jobSiteMemory.get();
        if (!Objects.equals(globalPos.dimension(), world.getRegistryKey())) {
            return Optional.empty();
        }
        BlockPos jobPos = globalPos.pos();
        if (!HUNTING_POST_ID.equals(Registries.BLOCK.getId(world.getBlockState(jobPos).getBlock()))) {
            return Optional.empty();
        }
        Optional<BlockPos> chest = JobBlockPairingHelper.findNearbyChest(world, jobPos)
                .filter(pos -> jobPos.isWithinDistance(pos, 3.0D));
        if (chest.isEmpty()) {
            return Optional.empty();
        }
        if (MoreVillagersHunterCombatGoal.hasWeapon(hunter.getMainHandStack())) {
            return Optional.empty();
        }
        Optional<net.minecraft.inventory.Inventory> chestInventory = getChestInventoryAt(world, chest.get());
        if (chestInventory.isEmpty() || hasWeaponInInventory(chestInventory.get())) {
            return Optional.empty();
        }
        return Optional.of(new HunterRecipient(hunter, chest.get().toImmutable(), villager.squaredDistanceTo(hunter)));
    }

    private boolean hasWeaponInInventory(net.minecraft.inventory.Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (MoreVillagersHunterCombatGoal.hasWeapon(inventory.getStack(slot))) {
                return true;
            }
        }
        return false;
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

    private record HunterRecipient(VillagerEntity villager, BlockPos chestPos, double sourceSquaredDistance) {
    }
}
