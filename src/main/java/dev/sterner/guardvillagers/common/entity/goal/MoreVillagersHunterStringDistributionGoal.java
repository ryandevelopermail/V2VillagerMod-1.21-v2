package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.DistributionRecipientHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class MoreVillagersHunterStringDistributionGoal extends AbstractInventoryDistributionGoal {
    private static final Identifier HUNTER_PROFESSION_ID = Identifier.of("morevillagers", "hunter");
    private static final double RECIPIENT_SCAN_RANGE = 24.0D;

    public MoreVillagersHunterStringDistributionGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        super(villager, jobPos, chestPos, craftingTablePos);
    }

    @Override
    protected boolean isDistributableItem(ItemStack stack) {
        return stack.isOf(Items.STRING);
    }

    @Override
    protected boolean canStartWithInventory(ServerWorld world, Inventory inventory) {
        return hasString(inventory) && !findStringRecipients(world).isEmpty();
    }

    @Override
    protected boolean selectPendingTransfer(ServerWorld world, Inventory inventory) {
        if (inventory == null) {
            return false;
        }

        List<DistributionRecipientHelper.RecipientRecord> recipients = findStringRecipients(world);
        if (recipients.isEmpty()) {
            return false;
        }

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isDistributableItem(stack)) {
                continue;
            }
            DistributionRecipientHelper.RecipientRecord recipient = recipients.getFirst();
            ItemStack extracted = stack.split(1);
            inventory.setStack(slot, stack);
            inventory.markDirty();
            pendingItem = extracted;
            pendingTargetId = recipient.recipient().getUuid();
            pendingTargetPos = recipient.chestPos();
            pendingUniversalRoute = false;
            pendingOverflowTransfer = false;
            return true;
        }
        return false;
    }

    @Override
    protected boolean refreshTargetForPendingItem(ServerWorld world) {
        List<DistributionRecipientHelper.RecipientRecord> recipients = findStringRecipients(world);
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

        Optional<Inventory> targetInventory = getTargetInventoryAt(world, pendingTargetPos);
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

    private Optional<Inventory> getTargetInventoryAt(ServerWorld world, BlockPos position) {
        BlockState state = world.getBlockState(position);
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, position, false));
        }
        BlockEntity blockEntity = world.getBlockEntity(position);
        if (blockEntity instanceof Inventory inventory) {
            return Optional.of(inventory);
        }
        return Optional.empty();
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
        return HUNTER_PROFESSION_ID.equals(Registries.VILLAGER_PROFESSION.getId(villager.getVillagerData().getProfession()));
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

    private List<DistributionRecipientHelper.RecipientRecord> findStringRecipients(ServerWorld world) {
        List<DistributionRecipientHelper.RecipientRecord> recipients = new ArrayList<>();
        recipients.addAll(DistributionRecipientHelper.findEligibleFishermanRecipients(world, villager, RECIPIENT_SCAN_RANGE));
        recipients.addAll(DistributionRecipientHelper.findEligibleRecipients(world, villager, RECIPIENT_SCAN_RANGE, VillagerProfession.TOOLSMITH, net.minecraft.block.Blocks.SMITHING_TABLE));
        recipients.sort(Comparator.comparingDouble(DistributionRecipientHelper.RecipientRecord::sourceSquaredDistance)
                .thenComparing(recipient -> recipient.recipient().getUuid(), java.util.UUID::compareTo));
        return recipients;
    }

    private boolean hasString(Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (isDistributableItem(inventory.getStack(slot))) {
                return true;
            }
        }
        return false;
    }
}
