package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.DistributionRecipientHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ArmorMaterials;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class LeatherworkerDistributionGoal extends AbstractInventoryDistributionGoal {
    private static final double RECIPIENT_SCAN_RANGE = 24.0D;

    /**
     * Crafted output whitelist for leatherworker-to-librarian distribution.
     *
     * Strategy:
     * - Include leather and common leatherworker-crafted leather products.
     * - Include book-related products expected to be useful for librarians.
     */
    private static final Set<Item> DISTRIBUTABLE_WHITELIST = Set.of(
            Items.LEATHER,
            Items.RABBIT_HIDE,
            Items.SADDLE,
            Items.ITEM_FRAME,
            Items.GLOW_ITEM_FRAME,
            Items.BOOK,
            Items.WRITABLE_BOOK,
            Items.WRITTEN_BOOK,
            Items.ENCHANTED_BOOK
    );

    private final Set<UUID> pendingRejectedRecipients = new HashSet<>();

    public LeatherworkerDistributionGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        super(villager, jobPos, chestPos, craftingTablePos);
    }

    @Override
    protected boolean isDistributableItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (DISTRIBUTABLE_WHITELIST.contains(stack.getItem())) {
            return true;
        }
        return stack.getItem() instanceof ArmorItem armorItem && armorItem.getMaterial() == ArmorMaterials.LEATHER;
    }

    @Override
    protected boolean canStartWithInventory(ServerWorld world, Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isDistributableItem(stack)) {
                continue;
            }
            if (!findRecipientForStack(world, stack, Set.of()).isEmpty()) {
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

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isDistributableItem(stack)) {
                continue;
            }

            List<DistributionRecipientHelper.RecipientRecord> recipients = findRecipientForStack(world, stack, Set.of());
            if (recipients.isEmpty()) {
                continue;
            }

            DistributionRecipientHelper.RecipientRecord recipient = recipients.getFirst();
            ItemStack extracted = stack.split(1);
            inventory.setStack(slot, stack);
            inventory.markDirty();

            pendingItem = extracted;
            pendingTargetId = recipient.recipient().getUuid();
            pendingTargetPos = recipient.chestPos();
            pendingRejectedRecipients.clear();
            return true;
        }
        return false;
    }

    @Override
    protected boolean refreshTargetForPendingItem(ServerWorld world) {
        if (pendingItem.isEmpty()) {
            return false;
        }

        List<DistributionRecipientHelper.RecipientRecord> recipients = findRecipientForStack(world, pendingItem, pendingRejectedRecipients);
        if (recipients.isEmpty()) {
            return false;
        }

        if (pendingTargetId != null && !pendingRejectedRecipients.contains(pendingTargetId)) {
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
            markPendingRecipientRejected();
            return false;
        }

        ItemStack remaining = insertStack(targetInventory.get(), pendingItem);
        targetInventory.get().markDirty();
        if (remaining.isEmpty()) {
            return true;
        }

        pendingItem = remaining;
        markPendingRecipientRejected();
        return false;
    }

    @Override
    protected void clearPendingTargetState() {
        pendingRejectedRecipients.clear();
    }

    @Override
    protected boolean matchesProfession(VillagerEntity villager) {
        return villager.getVillagerData().getProfession() == VillagerProfession.LEATHERWORKER;
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

    private List<DistributionRecipientHelper.RecipientRecord> findRecipientForStack(ServerWorld world, ItemStack stack, Set<UUID> excludedRecipients) {
        if (stack.isEmpty() || !isDistributableItem(stack)) {
            return List.of();
        }

        return DistributionRecipientHelper.findEligibleLibrarianRecipients(world, villager, RECIPIENT_SCAN_RANGE)
                .stream()
                .filter(recipient -> !excludedRecipients.contains(recipient.recipient().getUuid()))
                .filter(recipient -> canRecipientAccept(world, recipient.chestPos(), stack))
                .toList();
    }

    private boolean canRecipientAccept(ServerWorld world, BlockPos chestPosition, ItemStack stack) {
        Optional<Inventory> targetInventory = getChestInventory(world, chestPosition);
        if (targetInventory.isEmpty()) {
            return false;
        }

        for (int slot = 0; slot < targetInventory.get().size(); slot++) {
            ItemStack existing = targetInventory.get().getStack(slot);
            if (existing.isEmpty()) {
                if (targetInventory.get().isValid(slot, stack)) {
                    return true;
                }
                continue;
            }

            if (!ItemStack.areItemsAndComponentsEqual(existing, stack)) {
                continue;
            }
            if (!targetInventory.get().isValid(slot, stack)) {
                continue;
            }
            if (existing.getCount() < existing.getMaxCount()) {
                return true;
            }
        }

        return false;
    }

    private void markPendingRecipientRejected() {
        if (pendingTargetId != null) {
            pendingRejectedRecipients.add(pendingTargetId);
        }
    }

    private Optional<Inventory> getChestInventory(ServerWorld world, BlockPos position) {
        BlockState state = world.getBlockState(position);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, position, true));
    }
}
