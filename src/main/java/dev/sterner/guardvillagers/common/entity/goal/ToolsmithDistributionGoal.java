package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.ToolsmithDemandPlanner;
import dev.sterner.guardvillagers.common.villager.CraftingCheckLogger;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.HoeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.ShearsItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class ToolsmithDistributionGoal extends AbstractInventoryDistributionGoal {
    private static final int TARGET_REFRESH_LOG_INTERVAL_TICKS = 100;
    private long lastTargetRefreshLogTick = Long.MIN_VALUE;
    private String lastTargetRefreshLogMessage = "";

    public ToolsmithDistributionGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        super(villager, jobPos, chestPos, craftingTablePos);
    }

    @Override
    protected boolean isDistributableItem(ItemStack stack) {
        return stack.getItem() instanceof PickaxeItem
                || stack.getItem() instanceof HoeItem
                || stack.getItem() instanceof ShearsItem
                || stack.isOf(Items.FISHING_ROD);
    }

    @Override
    protected boolean canStartWithInventory(ServerWorld world, Inventory inventory) {
        if (canForwardSeeds(world, inventory)) {
            return true;
        }

        ToolsmithDemandPlanner.DemandSnapshot demandSnapshot = ToolsmithDemandPlanner.buildSnapshot(world, villager, inventory);
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isDistributableItem(stack)) {
                continue;
            }
            ToolsmithDemandPlanner.ToolType toolType = ToolsmithDemandPlanner.ToolType.fromStack(stack);
            if (toolType != null && !demandSnapshot.rankedRecipientsFor(toolType).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean selectPendingTransfer(ServerWorld world, Inventory inventory) {
        if (selectSeedForwardingTransfer(world, inventory)) {
            return true;
        }

        if (inventory == null) {
            return false;
        }

        ToolsmithDemandPlanner.DemandSnapshot demandSnapshot = ToolsmithDemandPlanner.buildSnapshot(world, villager, inventory);
        List<ToolDistributionCandidate> candidates = collectCandidates(inventory, demandSnapshot);
        if (candidates.isEmpty()) {
            return false;
        }

        ToolDistributionCandidate selected = candidates.getFirst();
        ToolsmithDemandPlanner.RecipientDemand recipient = selected.rankedRecipients().getFirst();
        ItemStack sourceStack = inventory.getStack(selected.slot());
        if (sourceStack.isEmpty()) {
            return false;
        }

        ItemStack extracted = sourceStack.split(1);
        inventory.setStack(selected.slot(), sourceStack);
        inventory.markDirty();

        pendingItem = extracted;
        pendingTargetId = recipient.record().recipient().getUuid();
        pendingTargetPos = recipient.record().chestPos();

        CraftingCheckLogger.report(world, "Toolsmith", "distribution " + demandSnapshot.compactSummary());
        CraftingCheckLogger.report(world, "Toolsmith", selected.selectionReason());
        return true;
    }

    @Override
    protected boolean refreshTargetForPendingItem(ServerWorld world) {
        if (pendingSeedForwarding) {
            return refreshSeedForwardingTarget(world);
        }

        ToolsmithDemandPlanner.ToolType toolType = ToolsmithDemandPlanner.ToolType.fromStack(pendingItem);
        if (toolType == null) {
            return false;
        }

        Optional<Inventory> source = getChestInventory(world);
        if (source.isEmpty()) {
            return false;
        }

        ToolsmithDemandPlanner.DemandSnapshot demandSnapshot = ToolsmithDemandPlanner.buildSnapshot(world, villager, source.get());
        List<ToolsmithDemandPlanner.RecipientDemand> rankedRecipients = demandSnapshot.rankedRecipientsFor(toolType);
        if (rankedRecipients.isEmpty()) {
            return false;
        }

        if (pendingTargetId != null) {
            for (ToolsmithDemandPlanner.RecipientDemand recipient : rankedRecipients) {
                if (recipient.record().recipient().getUuid().equals(pendingTargetId)) {
                    pendingTargetPos = recipient.record().chestPos();
                    maybeLogTargetRefresh(world, toolType, recipient);
                    return true;
                }
            }
        }

        ToolsmithDemandPlanner.RecipientDemand recipient = rankedRecipients.getFirst();
        pendingTargetId = recipient.record().recipient().getUuid();
        pendingTargetPos = recipient.record().chestPos();
        maybeLogTargetRefresh(world, toolType, recipient);
        return true;
    }

    private void maybeLogTargetRefresh(ServerWorld world,
                                       ToolsmithDemandPlanner.ToolType toolType,
                                       ToolsmithDemandPlanner.RecipientDemand recipient) {
        String message = "distribution target refresh " + toolType.label()
                + " -> " + recipient.record().recipient().getVillagerData().getProfession()
                + " deficit " + recipient.deficit();
        long tick = world.getTime();
        boolean sameAsLast = message.equals(lastTargetRefreshLogMessage);
        if (sameAsLast && tick - lastTargetRefreshLogTick < TARGET_REFRESH_LOG_INTERVAL_TICKS) {
            return;
        }

        CraftingCheckLogger.report(world, "Toolsmith", message);
        lastTargetRefreshLogMessage = message;
        lastTargetRefreshLogTick = tick;
    }

    private List<ToolDistributionCandidate> collectCandidates(Inventory inventory, ToolsmithDemandPlanner.DemandSnapshot demandSnapshot) {
        List<ToolDistributionCandidate> candidates = new ArrayList<>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isDistributableItem(stack)) {
                continue;
            }

            ToolsmithDemandPlanner.ToolType toolType = ToolsmithDemandPlanner.ToolType.fromStack(stack);
            if (toolType == null) {
                continue;
            }

            List<ToolsmithDemandPlanner.RecipientDemand> rankedRecipients = demandSnapshot.rankedRecipientsFor(toolType);
            if (rankedRecipients.isEmpty()) {
                continue;
            }

            int demandScore = demandSnapshot.deficitFor(toolType);
            ToolsmithDemandPlanner.RecipientDemand topRecipient = rankedRecipients.getFirst();
            String reason = toolType.label() + " selected: "
                    + topRecipient.record().recipient().getVillagerData().getProfession() + " deficit "
                    + Math.max(demandScore, topRecipient.deficit());
            candidates.add(new ToolDistributionCandidate(slot, rankedRecipients, demandScore, reason));
        }

        candidates.sort(Comparator
                .comparingInt(ToolDistributionCandidate::demandScore).reversed()
                .thenComparing(Comparator.comparingInt((ToolDistributionCandidate candidate) -> candidate.rankedRecipients().getFirst().deficit()).reversed()));
        return candidates;
    }

    @Override
    protected boolean executeTransfer(ServerWorld world) {
        if (pendingSeedForwarding) {
            return executeSeedForwardingTransfer(world);
        }

        if (pendingItem.isEmpty() || pendingTargetPos == null) {
            return false;
        }

        Optional<Inventory> targetInventory = getRecipientInventory(world, pendingTargetPos);
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
    protected boolean matchesProfession(VillagerEntity villager) {
        return villager.getVillagerData().getProfession() == VillagerProfession.TOOLSMITH;
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


    private Optional<Inventory> getRecipientInventory(ServerWorld world, BlockPos position) {
        BlockState state = world.getBlockState(position);
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, position, true));
        }
        if (state.getBlock() instanceof BarrelBlock) {
            BlockEntity blockEntity = world.getBlockEntity(position);
            if (blockEntity instanceof Inventory inventory) {
                return Optional.of(inventory);
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    private record ToolDistributionCandidate(
            int slot,
            List<ToolsmithDemandPlanner.RecipientDemand> rankedRecipients,
            int demandScore,
            String selectionReason
    ) {
    }
}
