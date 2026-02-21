package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.DistributionRecipientHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
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

public class ClericDistributionGoal extends AbstractInventoryDistributionGoal {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClericDistributionGoal.class);
    private static final double RECIPIENT_SCAN_RANGE = 24.0D;

    public ClericDistributionGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        super(villager, jobPos, chestPos, craftingTablePos);
    }

    @Override
    protected boolean isDistributableItem(ItemStack stack) {
        return stack.isOf(Items.POTION)
                || stack.isOf(Items.SPLASH_POTION)
                || stack.isOf(Items.LINGERING_POTION);
    }

    @Override
    protected boolean canStartWithInventory(ServerWorld world, Inventory inventory) {
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
        if (pendingItem.isEmpty() || pendingTargetPos == null) {
            return false;
        }

        Optional<Inventory> targetInventory = getChestInventory(world, pendingTargetPos);
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

    private int countHealingSplashPotions(Inventory inventory) {
        int count = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (isHealingSplashPotion(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private boolean isHealingSplashPotion(ItemStack stack) {
        return stack.isOf(Items.SPLASH_POTION)
                && stack.getOrDefault(DataComponentTypes.POTION_CONTENTS, PotionContentsComponent.DEFAULT).matches(Potions.HEALING);
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

    private Optional<Inventory> getChestInventory(ServerWorld world, BlockPos position) {
        BlockState state = world.getBlockState(position);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, position, true));
    }
}
