package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.DistributionRecipientHelper;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.List;
import java.util.Optional;

public class MoreVillagersOceanographerPaperDistributionGoal extends AbstractInventoryDistributionGoal {
    private static final Identifier OCEANOGRAPHER_PROFESSION_ID = Identifier.of("morevillagers", "oceanographer");
    private static final double RECIPIENT_SCAN_RANGE = 24.0D;

    public MoreVillagersOceanographerPaperDistributionGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        super(villager, jobPos, chestPos, craftingTablePos);
    }

    @Override
    protected boolean isDistributableItem(ItemStack stack) {
        return stack.isOf(Items.PAPER);
    }

    @Override
    protected boolean canStartWithInventory(ServerWorld world, Inventory inventory) {
        return hasDistributableItem(inventory) && !findPaperRecipients(world).isEmpty();
    }

    @Override
    protected boolean selectPendingTransfer(ServerWorld world, Inventory inventory) {
        if (inventory == null) {
            return false;
        }

        List<DistributionRecipientHelper.RecipientRecord> recipients = findPaperRecipients(world);
        if (recipients.isEmpty()) {
            return false;
        }

        ItemStack extracted = extractSingleDistributableItem(inventory);
        if (extracted.isEmpty()) {
            return false;
        }

        DistributionRecipientHelper.RecipientRecord recipient = recipients.getFirst();
        pendingItem = extracted;
        pendingTargetId = recipient.recipient().getUuid();
        pendingTargetPos = recipient.chestPos();
        pendingUniversalRoute = false;
        pendingOverflowTransfer = false;
        return true;
    }

    @Override
    protected boolean refreshTargetForPendingItem(ServerWorld world) {
        if (!isDistributableItem(pendingItem)) {
            return false;
        }

        List<DistributionRecipientHelper.RecipientRecord> recipients = findPaperRecipients(world);
        if (recipients.isEmpty()) {
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

        Optional<Inventory> targetInventory = getChestInventoryAt(world, pendingTargetPos);
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

    @Override
    protected boolean supportsUniversalRouting() {
        return false;
    }

    @Override
    protected void clearPendingTargetState() {
    }

    @Override
    protected boolean matchesProfession(VillagerEntity villager) {
        return OCEANOGRAPHER_PROFESSION_ID.equals(Registries.VILLAGER_PROFESSION.getId(villager.getVillagerData().getProfession()));
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

    private List<DistributionRecipientHelper.RecipientRecord> findPaperRecipients(ServerWorld world) {
        return DistributionRecipientHelper.findEligibleCartographerRecipients(world, villager, RECIPIENT_SCAN_RANGE);
    }
}
