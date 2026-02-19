package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.DistributionRecipientHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.HoeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.ShovelItem;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ToolsmithDistributionGoal extends AbstractInventoryDistributionGoal {
    private static final double RECIPIENT_SCAN_RANGE = 48.0D;
    private static final int MAX_TRANSFERS_PER_RUN = 1;
    private static final long ATTEMPT_TTL_TICKS = 40L;

    private final Map<String, Long> recentAttemptExpiries = new HashMap<>();
    private List<DistributionRecipientHelper.RecipientRecord> pendingRecipients = List.of();
    private int pendingRecipientIndex;
    private int transfersThisRun;

    public ToolsmithDistributionGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        super(villager, jobPos, chestPos, craftingTablePos);
    }

    @Override
    public void start() {
        super.start();
        transfersThisRun = 0;
    }

    @Override
    protected boolean isDistributableItem(ItemStack stack) {
        return !stack.isEmpty() && (stack.getItem() instanceof PickaxeItem || stack.getItem() instanceof ShovelItem || stack.getItem() instanceof HoeItem);
    }

    @Override
    protected boolean canStartWithInventory(ServerWorld world, Inventory inventory) {
        pruneExpiredAttempts(world.getTime());
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isDistributableItem(stack)) {
                continue;
            }
            if (firstEligibleRecipientIndex(world, recipientsForItem(world, stack), stack) >= 0) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean selectPendingTransfer(ServerWorld world, Inventory inventory) {
        if (inventory == null || transfersThisRun >= MAX_TRANSFERS_PER_RUN) {
            return false;
        }

        pruneExpiredAttempts(world.getTime());
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isDistributableItem(stack)) {
                continue;
            }

            List<DistributionRecipientHelper.RecipientRecord> recipients = recipientsForItem(world, stack);
            int eligibleIndex = firstEligibleRecipientIndex(world, recipients, stack);
            if (eligibleIndex < 0) {
                continue;
            }

            ItemStack extracted = stack.split(1);
            inventory.setStack(slot, stack);
            inventory.markDirty();

            pendingItem = extracted;
            pendingRecipients = recipients;
            pendingRecipientIndex = eligibleIndex;
            DistributionRecipientHelper.RecipientRecord recipient = recipients.get(eligibleIndex);
            pendingTargetId = recipient.recipient().getUuid();
            pendingTargetPos = recipient.chestPos();
            return true;
        }
        return false;
    }

    @Override
    protected boolean refreshTargetForPendingItem(ServerWorld world) {
        if (pendingItem.isEmpty() || pendingRecipients.isEmpty()) {
            return false;
        }

        pruneExpiredAttempts(world.getTime());

        while (pendingRecipientIndex < pendingRecipients.size()) {
            DistributionRecipientHelper.RecipientRecord recipient = pendingRecipients.get(pendingRecipientIndex);
            if (isRecipientValid(world, recipient) && canRecipientChestAccept(world, recipient.chestPos(), pendingItem) && !wasRecentlyAttempted(world, recipient, pendingItem)) {
                pendingTargetId = recipient.recipient().getUuid();
                pendingTargetPos = recipient.chestPos();
                return true;
            }
            pendingRecipientIndex++;
        }

        return false;
    }

    @Override
    protected boolean executeTransfer(ServerWorld world) {
        if (pendingItem.isEmpty() || transfersThisRun >= MAX_TRANSFERS_PER_RUN || pendingRecipients.isEmpty() || pendingRecipientIndex >= pendingRecipients.size()) {
            return false;
        }

        DistributionRecipientHelper.RecipientRecord recipient = pendingRecipients.get(pendingRecipientIndex);
        recordAttempt(world, recipient, pendingItem);

        if (!isRecipientValid(world, recipient)) {
            pendingRecipientIndex++;
            return false;
        }

        Optional<Inventory> recipientChest = getChestInventoryAt(world, recipient.chestPos());
        if (recipientChest.isEmpty()) {
            pendingRecipientIndex++;
            return false;
        }

        ItemStack remaining = insertStack(recipientChest.get(), pendingItem);
        recipientChest.get().markDirty();
        if (remaining.isEmpty()) {
            transfersThisRun++;
            return true;
        }

        pendingItem = remaining;
        pendingRecipientIndex++;
        return false;
    }

    @Override
    protected void clearPendingTargetState() {
        pendingRecipients = List.of();
        pendingRecipientIndex = 0;
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

    @Override
    protected boolean matchesProfession(VillagerEntity villager) {
        return villager.getVillagerData().getProfession() == VillagerProfession.TOOLSMITH;
    }

    private List<DistributionRecipientHelper.RecipientRecord> recipientsForItem(ServerWorld world, ItemStack stack) {
        if (stack.getItem() instanceof HoeItem) {
            return DistributionRecipientHelper.findEligibleFarmerRecipients(world, villager, RECIPIENT_SCAN_RANGE);
        }
        if (stack.getItem() instanceof PickaxeItem || stack.getItem() instanceof ShovelItem) {
            return DistributionRecipientHelper.findEligibleMasonRecipients(world, villager, RECIPIENT_SCAN_RANGE);
        }
        return List.of();
    }

    private int firstEligibleRecipientIndex(ServerWorld world, List<DistributionRecipientHelper.RecipientRecord> recipients, ItemStack stack) {
        for (int i = 0; i < recipients.size(); i++) {
            DistributionRecipientHelper.RecipientRecord recipient = recipients.get(i);
            if (!isRecipientValid(world, recipient)) {
                continue;
            }
            if (!canRecipientChestAccept(world, recipient.chestPos(), stack)) {
                continue;
            }
            if (wasRecentlyAttempted(world, recipient, stack)) {
                continue;
            }
            return i;
        }
        return -1;
    }

    private boolean isRecipientValid(ServerWorld world, DistributionRecipientHelper.RecipientRecord recipient) {
        if (!recipient.recipient().isAlive()) {
            return false;
        }
        if (!(world.getEntity(recipient.recipient().getUuid()) instanceof VillagerEntity)) {
            return false;
        }
        return getChestInventoryAt(world, recipient.chestPos()).isPresent();
    }

    private boolean canRecipientChestAccept(ServerWorld world, BlockPos recipientChestPos, ItemStack stack) {
        Optional<Inventory> inventory = getChestInventoryAt(world, recipientChestPos);
        if (inventory.isEmpty()) {
            return false;
        }
        Inventory recipientInventory = inventory.get();
        for (int slot = 0; slot < recipientInventory.size(); slot++) {
            if (!recipientInventory.isValid(slot, stack)) {
                continue;
            }

            ItemStack existing = recipientInventory.getStack(slot);
            if (existing.isEmpty()) {
                return true;
            }

            if (ItemStack.areItemsAndComponentsEqual(existing, stack) && existing.getCount() < existing.getMaxCount()) {
                return true;
            }
        }
        return false;
    }

    private Optional<Inventory> getChestInventoryAt(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, pos, true));
    }

    private boolean wasRecentlyAttempted(ServerWorld world, DistributionRecipientHelper.RecipientRecord recipient, ItemStack stack) {
        Long expiry = recentAttemptExpiries.get(attemptSignature(recipient, stack));
        return expiry != null && expiry > world.getTime();
    }

    private void recordAttempt(ServerWorld world, DistributionRecipientHelper.RecipientRecord recipient, ItemStack stack) {
        recentAttemptExpiries.put(attemptSignature(recipient, stack), world.getTime() + ATTEMPT_TTL_TICKS);
    }

    private String attemptSignature(DistributionRecipientHelper.RecipientRecord recipient, ItemStack stack) {
        return recipient.recipient().getUuidAsString() + "|" + Registries.ITEM.getId(stack.getItem());
    }

    private void pruneExpiredAttempts(long worldTime) {
        if (recentAttemptExpiries.isEmpty()) {
            return;
        }
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Long> entry : recentAttemptExpiries.entrySet()) {
            if (entry.getValue() <= worldTime) {
                expired.add(entry.getKey());
            }
        }
        for (String key : expired) {
            recentAttemptExpiries.remove(key);
        }
    }
}
