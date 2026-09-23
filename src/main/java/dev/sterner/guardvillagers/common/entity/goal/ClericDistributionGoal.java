package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.professionalstorage.ClericWorkMetrics;
import dev.sterner.guardvillagers.common.util.DistributionRecipientHelper;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.Potions;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class ClericDistributionGoal extends AbstractInventoryDistributionGoal {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClericDistributionGoal.class);
    private static final double RECIPIENT_SCAN_RANGE = 24.0D;

    public ClericDistributionGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        super(villager, jobPos, chestPos, craftingTablePos);
    }

    @Override
    protected boolean isDistributableItem(ItemStack stack) {
        return isSupportedPotion(stack);
    }

    public static boolean isSupportedPotion(ItemStack stack) {
        return !stack.isEmpty() && (stack.isOf(Items.POTION)
                || stack.isOf(Items.SPLASH_POTION)
                || stack.isOf(Items.LINGERING_POTION));
    }

    static boolean isSupportedPotionShape(boolean nonempty, boolean potionItem) {
        return nonempty && potionItem;
    }

    @Override
    protected boolean canStartWithInventory(ServerWorld world, Inventory inventory) {
        if (canStartOverflowTransfer(world, inventory, this::isDistributableItem)) {
            return true;
        }
        List<DistributionRecipientHelper.RecipientRecord> recipients =
                DistributionRecipientHelper.findEligibleLibrarianRecipientsForClerics(world, villager, RECIPIENT_SCAN_RANGE);
        if (recipients.isEmpty()) {
            LOGGER.debug("Cleric {} skipped distribution: no valid librarian recipients found", villager.getUuidAsString());
            return false;
        }

        for (int slot = 0; slot < inventory.size(); slot++) {
            if (isTransferCandidate(inventory, slot)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean selectPendingTransfer(ServerWorld world, Inventory inventory) {
        if (inventory == null) {
            return false;
        }
        if (trySelectOverflowTransfer(world, inventory, this::isDistributableItem)) {
            LOGGER.info("Cleric {} started overflow distribution of {} to librarian {} at {}",
                    villager.getUuidAsString(),
                    pendingItem.getItem(),
                    pendingTargetId,
                    pendingTargetPos.toShortString());
            return true;
        }

        List<DistributionRecipientHelper.RecipientRecord> recipients =
                DistributionRecipientHelper.findEligibleLibrarianRecipientsForClerics(world, villager, RECIPIENT_SCAN_RANGE);
        if (recipients.isEmpty()) {
            LOGGER.debug("Cleric {} skipped distribution: no valid librarian recipients available", villager.getUuidAsString());
            return false;
        }

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isTransferCandidate(inventory, slot)) {
                continue;
            }

            DistributionRecipientHelper.RecipientRecord recipient = recipients.getFirst();
            ItemStack extracted = stack.split(1);
            inventory.setStack(slot, stack);
            inventory.markDirty();

            pendingItem = extracted;
            pendingTargetId = recipient.recipient().getUuid();
            pendingTargetPos = recipient.chestPos();

            LOGGER.info("Cleric {} started potion distribution of {} to librarian {} at {}",
                    villager.getUuidAsString(),
                    pendingItem.getItem(),
                    recipient.recipient().getUuidAsString(),
                    pendingTargetPos.toShortString());
            return true;
        }

        return false;
    }

    @Override
    protected boolean refreshTargetForPendingItem(ServerWorld world) {
        if (refreshOverflowTarget(world, this::isDistributableItem)) {
            return true;
        }
        if (!isDistributableItem(pendingItem)) {
            return false;
        }

        List<DistributionRecipientHelper.RecipientRecord> recipients =
                DistributionRecipientHelper.findEligibleLibrarianRecipientsForClerics(world, villager, RECIPIENT_SCAN_RANGE);
        if (recipients.isEmpty()) {
            LOGGER.debug("Cleric {} has no valid librarian target for pending {}",
                    villager.getUuidAsString(),
                    pendingItem.getItem());
            return false;
        }

        if (pendingTargetId != null) {
            for (DistributionRecipientHelper.RecipientRecord recipient : recipients) {
                if (recipient.recipient().getUuid().equals(pendingTargetId)) {
                    pendingTargetPos = recipient.chestPos();
                    return true;
                }
            }
        }

        DistributionRecipientHelper.RecipientRecord recipient = recipients.getFirst();
        pendingTargetId = recipient.recipient().getUuid();
        pendingTargetPos = recipient.chestPos();
        return true;
    }

    @Override
    protected boolean executeTransfer(ServerWorld world) {
        if (pendingOverflowTransfer) {
            return executeOverflowTransfer(world);
        }
        if (pendingItem.isEmpty() || pendingTargetPos == null) {
            return false;
        }

        Optional<Inventory> targetInventory = getChestInventoryAt(world, pendingTargetPos);
        if (targetInventory.isEmpty()) {
            LOGGER.debug("Cleric {} failed potion transfer: recipient chest at {} is unavailable",
                    villager.getUuidAsString(),
                    pendingTargetPos.toShortString());
            return false;
        }

        ItemStack remaining = insertStack(targetInventory.get(), pendingItem);
        targetInventory.get().markDirty();
        if (remaining.isEmpty()) {
            LOGGER.info("Cleric {} transferred {} to librarian chest {}",
                    villager.getUuidAsString(),
                    pendingItem.getItem(),
                    pendingTargetPos.toShortString());
            return true;
        }

        pendingItem = remaining;
        LOGGER.debug("Cleric {} could not fully transfer {} to chest {} (destination likely full)",
                villager.getUuidAsString(),
                pendingItem.getItem(),
                pendingTargetPos.toShortString());
        return false;
    }

    @Override
    protected void clearPendingTargetState() {
    }

    @Override
    protected void onTransferCompleted(
            ServerWorld world,
            ItemStack transferred,
            @org.jetbrains.annotations.Nullable UUID targetId,
            BlockPos targetPos,
            TransferRoute route
    ) {
        boolean supported = isSupportedPotion(transferred);
        boolean targetStorageValid = route == TransferRoute.DIRECT
                && getChestInventoryAt(world, targetPos).isPresent();
        boolean recipientValid = route == TransferRoute.DIRECT
                && isValidCompletedLibrarian(world, targetId, targetPos);
        boolean reserveValid = !isHealingSplashPotion(transferred)
                || getChestInventoryAt(world, chestPos)
                .map(inventory -> countHealingSplashPotions(inventory) >= 1L)
                .orElse(false);
        if (isConfirmedDirectLibrarianDelivery(
                route,
                supported,
                recipientValid,
                targetStorageValid,
                reserveValid)) {
            ClericWorkMetrics.recordPotionsDelivered(
                    world,
                    villager.getUuid(),
                    transferred.getCount());
        }
    }

    static boolean isConfirmedDirectLibrarianDelivery(
            TransferRoute route,
            boolean supportedPotion,
            boolean recipientValid,
            boolean targetStorageValid,
            boolean reserveValid
    ) {
        return route == TransferRoute.DIRECT
                && supportedPotion
                && recipientValid
                && targetStorageValid
                && reserveValid;
    }

    private boolean isValidCompletedLibrarian(ServerWorld world, UUID targetId, BlockPos targetPos) {
        if (targetId == null) {
            return false;
        }
        return DistributionRecipientHelper.findEligibleLibrarianRecipientsForClerics(
                        world,
                        villager,
                        RECIPIENT_SCAN_RANGE).stream()
                .anyMatch(recipient -> recipient.recipient() != null
                        && recipient.recipient().isAlive()
                        && recipient.recipient().getUuid().equals(targetId)
                        && recipient.chestPos().equals(targetPos));
    }

    private boolean isTransferCandidate(Inventory inventory, int slot) {
        ItemStack stack = inventory.getStack(slot);
        if (!isDistributableItem(stack)) {
            return false;
        }
        if (!isHealingSplashPotion(stack)) {
            return true;
        }
        return countHealingSplashPotions(inventory) > 1;
    }

    public static long countHealingSplashPotions(Inventory inventory) {
        long count = 0L;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (isHealingSplashPotion(stack)) {
                count = saturatingAdd(count, stack.getCount());
            }
        }
        return count;
    }

    public static boolean isHealingSplashPotion(ItemStack stack) {
        return !stack.isEmpty() && stack.isOf(Items.SPLASH_POTION)
                && stack.getOrDefault(DataComponentTypes.POTION_CONTENTS, PotionContentsComponent.DEFAULT).matches(Potions.HEALING);
    }

    static boolean isHealingSplashPotionShape(boolean nonempty, boolean splashItem, boolean healingContents) {
        return nonempty && splashItem && healingContents;
    }

    public static long countPotionsAwaitingDeliveryReadOnly(Inventory inventory) {
        long otherPotions = 0L;
        long healingSplash = 0L;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isSupportedPotion(stack)) {
                continue;
            }
            if (isHealingSplashPotion(stack)) {
                healingSplash = saturatingAdd(healingSplash, stack.getCount());
            } else {
                otherPotions = saturatingAdd(otherPotions, stack.getCount());
            }
        }
        return countAwaitingPotionUnits(otherPotions, healingSplash);
    }

    public static long countAwaitingPotionUnits(long otherPotionUnits, long healingSplashUnits) {
        long other = Math.max(0L, otherPotionUnits);
        long healing = Math.max(0L, healingSplashUnits);
        return saturatingAdd(other, Math.max(0L, healing - 1L));
    }

    private static long saturatingAdd(long current, long amount) {
        return current >= Long.MAX_VALUE - amount ? Long.MAX_VALUE : current + amount;
    }

    @Override
    protected Optional<OverflowRecipientType> getOverflowRecipientType() {
        return Optional.of(OverflowRecipientType.LIBRARIAN);
    }

    @Override
    protected boolean matchesProfession(VillagerEntity villager) {
        return villager.getVillagerData().getProfession() == VillagerProfession.CLERIC;
    }

    @Override
    protected Optional<ArmorStandEntity> findPlacementStand(ServerWorld world, ItemStack stack) {
        return Optional.empty();
    }

    @Override
    protected boolean isStandAvailableForPendingItem(ServerWorld world, ArmorStandEntity stand) {
        return false;
    }

    @Override
    protected boolean placePendingItemOnStand(ServerWorld world, ArmorStandEntity stand) {
        return false;
    }

}
