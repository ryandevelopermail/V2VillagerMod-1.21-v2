package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.professionalstorage.ButcherWorkMetrics;
import dev.sterner.guardvillagers.common.util.DistributionRecipientHelper;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class ButcherToLeatherworkerDistributionGoal extends AbstractInventoryDistributionGoal {
    private static final double RECIPIENT_SCAN_RANGE = 24.0D;
    public ButcherToLeatherworkerDistributionGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        super(villager, jobPos, chestPos, craftingTablePos);
    }

    @Override
    protected boolean isDistributableItem(ItemStack stack) {
        return isLeatherOrHide(stack);
    }

    public static boolean isLeatherOrHide(ItemStack stack) {
        boolean nonempty = !stack.isEmpty();
        return isLeatherOrHideShape(nonempty, nonempty && LeatherOutputs.ITEMS.contains(stack.getItem()));
    }

    static boolean isLeatherOrHideShape(boolean nonempty, boolean configuredOutput) {
        return nonempty && configuredOutput;
    }

    /** Defers Minecraft item-registry access until the real predicate is used. */
    private static final class LeatherOutputs {
        private static final Set<Item> ITEMS = Set.of(
                Items.LEATHER,
                Items.RABBIT_HIDE
        );
    }

    @Override
    protected boolean canStartWithInventory(ServerWorld world, Inventory inventory) {
        if (canStartOverflowTransfer(world, inventory, this::isDistributableItem)) {
            return true;
        }
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isDistributableItem(stack)) {
                continue;
            }
            if (!DistributionRecipientHelper.findEligibleLeatherworkerRecipients(world, villager, RECIPIENT_SCAN_RANGE).isEmpty()) {
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
            return true;
        }

        List<DistributionRecipientHelper.RecipientRecord> recipients = DistributionRecipientHelper.findEligibleLeatherworkerRecipients(world, villager, RECIPIENT_SCAN_RANGE);
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
        if (refreshOverflowTarget(world, this::isDistributableItem)) {
            return true;
        }
        if (!isDistributableItem(pendingItem)) {
            return false;
        }

        List<DistributionRecipientHelper.RecipientRecord> recipients = DistributionRecipientHelper.findEligibleLeatherworkerRecipients(world, villager, RECIPIENT_SCAN_RANGE);
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
        if (pendingOverflowTransfer) {
            return executeOverflowTransfer(world);
        }
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
        boolean supported = isLeatherOrHide(transferred);
        boolean storageValid = route == TransferRoute.DIRECT
                && getChestInventoryAt(world, targetPos).isPresent();
        boolean recipientValid = route == TransferRoute.DIRECT
                && isValidCompletedLeatherworker(world, targetId, targetPos);
        if (isConfirmedDirectLeatherworkerDelivery(route, supported, recipientValid, storageValid)) {
            ButcherWorkMetrics.recordLeatherHideDelivered(
                    world,
                    villager.getUuid(),
                    transferred.getCount());
        }
    }

    static boolean isConfirmedDirectLeatherworkerDelivery(
            TransferRoute route,
            boolean supportedOutput,
            boolean recipientValid,
            boolean storageValid
    ) {
        return route == TransferRoute.DIRECT && supportedOutput && recipientValid && storageValid;
    }

    private boolean isValidCompletedLeatherworker(
            ServerWorld world,
            UUID targetId,
            BlockPos targetPos
    ) {
        if (targetId == null) {
            return false;
        }
        return DistributionRecipientHelper.findEligibleLeatherworkerRecipients(
                        world,
                        villager,
                        RECIPIENT_SCAN_RANGE).stream()
                .anyMatch(recipient -> recipient.recipient() != null
                        && recipient.recipient().isAlive()
                        && recipient.recipient().getUuid().equals(targetId)
                        && recipient.chestPos().equals(targetPos));
    }

    @Override
    protected Optional<OverflowRecipientType> getOverflowRecipientType() {
        return Optional.of(OverflowRecipientType.LIBRARIAN);
    }

    @Override
    protected boolean matchesProfession(VillagerEntity villager) {
        return villager.getVillagerData().getProfession() == VillagerProfession.BUTCHER;
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
