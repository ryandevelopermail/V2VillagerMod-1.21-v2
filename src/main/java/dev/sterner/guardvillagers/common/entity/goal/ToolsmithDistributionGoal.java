package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.ToolsmithDemandPlanner;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.util.PairedStorageHelper;
import dev.sterner.guardvillagers.common.villager.CraftingCheckLogger;
import net.minecraft.block.Block;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class ToolsmithDistributionGoal extends AbstractInventoryDistributionGoal {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolsmithDistributionGoal.class);
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
        ToolsmithDemandPlanner.DemandSnapshot demandSnapshot = ToolsmithDemandPlanner.buildSnapshot(world, villager, inventory);
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isDistributableItem(stack)) {
                continue;
            }
            ToolsmithDemandPlanner.ToolType toolType = ToolsmithDemandPlanner.ToolType.fromStack(stack);
            if (toolType == null) {
                continue;
            }
            // FISHING_ROD targets FishermanGuardEntity — handled via separate entry list.
            if (toolType == ToolsmithDemandPlanner.ToolType.FISHING_ROD) {
                if (!demandSnapshot.rankedFishermanEntries().isEmpty()) {
                    return true;
                }
                continue;
            }
            if (demandSnapshot.rankedRecipientsFor(toolType).stream()
                    .anyMatch(recipient -> isValidToolRecipient(world, toolType, recipient))) {
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

        ToolsmithDemandPlanner.DemandSnapshot demandSnapshot = ToolsmithDemandPlanner.buildSnapshot(world, villager, inventory);
        List<ToolDistributionCandidate> candidates = collectCandidates(world, inventory, demandSnapshot);
        if (candidates.isEmpty()) {
            return false;
        }

        ToolDistributionCandidate selected = candidates.getFirst();
        ItemStack sourceStack = inventory.getStack(selected.slot());
        if (sourceStack.isEmpty()) {
            return false;
        }

        // FISHING_ROD: recipient is a FishermanRodEntry, not a RecipientDemand.
        if (selected.toolType() == ToolsmithDemandPlanner.ToolType.FISHING_ROD) {
            ToolsmithDemandPlanner.FishermanRodEntry fisherman = selected.fishermanEntry();
            if (fisherman == null) {
                return false;
            }
            ItemStack extracted = sourceStack.split(1);
            inventory.setStack(selected.slot(), sourceStack);
            inventory.markDirty();
            pendingItem = extracted;
            pendingTargetId = fisherman.recipientId();
            pendingTargetPos = fisherman.chestPos();
            pendingUniversalRoute = false;
            pendingOverflowTransfer = false;
            CraftingCheckLogger.report(world, "Toolsmith", () -> "distribution " + demandSnapshot.compactSummary());
            CraftingCheckLogger.report(world, "Toolsmith", selected.selectionReason());
            CraftingCheckLogger.report(world, "Toolsmith", () -> "crafted tool selected for transfer: fishing_rod"
                    + " -> " + fisherman.recipientKind().name().toLowerCase(java.util.Locale.ROOT)
                    + " storage " + fisherman.chestPos().toShortString());
            return true;
        }

        ToolsmithDemandPlanner.RecipientDemand recipient = selected.rankedRecipients().getFirst();
        ItemStack extracted = sourceStack.split(1);
        inventory.setStack(selected.slot(), sourceStack);
        inventory.markDirty();

        pendingItem = extracted;
        pendingTargetId = recipient.record().recipient().getUuid();
        pendingTargetPos = recipient.record().chestPos();
        pendingUniversalRoute = false;
        pendingOverflowTransfer = false;

        CraftingCheckLogger.report(world, "Toolsmith", () -> "distribution " + demandSnapshot.compactSummary());
        CraftingCheckLogger.report(world, "Toolsmith", selected.selectionReason());
        CraftingCheckLogger.report(world, "Toolsmith", () -> "crafted tool selected for transfer: "
                + selected.toolType().label()
                + " -> " + recipient.record().recipient().getVillagerData().getProfession()
                + " chest " + recipient.record().chestPos().toShortString());
        return true;
    }

    @Override
    protected boolean supportsUniversalRouting() {
        return false;
    }

    @Override
    protected boolean refreshTargetForPendingItem(ServerWorld world) {
        ToolsmithDemandPlanner.ToolType toolType = ToolsmithDemandPlanner.ToolType.fromStack(pendingItem);
        if (toolType == null) {
            return false;
        }

        Optional<Inventory> source = getChestInventory(world);
        if (source.isEmpty()) {
            return false;
        }

        ToolsmithDemandPlanner.DemandSnapshot demandSnapshot = ToolsmithDemandPlanner.buildSnapshot(world, villager, source.get());

        // FISHING_ROD: re-target against the FishermanRodEntry list.
        if (toolType == ToolsmithDemandPlanner.ToolType.FISHING_ROD) {
            List<ToolsmithDemandPlanner.FishermanRodEntry> entries = demandSnapshot.rankedFishermanEntries();
            if (entries.isEmpty()) {
                return false;
            }
            if (pendingTargetId != null) {
                for (ToolsmithDemandPlanner.FishermanRodEntry entry : entries) {
                    if (entry.recipientId().equals(pendingTargetId)) {
                        pendingTargetPos = entry.chestPos();
                        return true;
                    }
                }
            }
            ToolsmithDemandPlanner.FishermanRodEntry first = entries.getFirst();
            pendingTargetId = first.recipientId();
            pendingTargetPos = first.chestPos();
            return true;
        }

        List<ToolsmithDemandPlanner.RecipientDemand> rankedRecipients = demandSnapshot.rankedRecipientsFor(toolType).stream()
                .filter(recipient -> isValidToolRecipient(world, toolType, recipient))
                .toList();
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

        CraftingCheckLogger.report(world, "Toolsmith", () -> message);
        lastTargetRefreshLogMessage = message;
        lastTargetRefreshLogTick = tick;
    }

    private List<ToolDistributionCandidate> collectCandidates(ServerWorld world,
                                                             Inventory inventory,
                                                             ToolsmithDemandPlanner.DemandSnapshot demandSnapshot) {
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

            // FISHING_ROD: use the FishermanGuardEntry list directly.
            if (toolType == ToolsmithDemandPlanner.ToolType.FISHING_ROD) {
                List<ToolsmithDemandPlanner.FishermanRodEntry> fishermen = demandSnapshot.rankedFishermanEntries();
                if (fishermen.isEmpty()) {
                    continue;
                }
                ToolsmithDemandPlanner.FishermanRodEntry top = fishermen.getFirst();
                int demandScore = demandSnapshot.deficitFor(toolType);
                String reason = "fishing_rod selected: " + top.recipientKind().name().toLowerCase(java.util.Locale.ROOT)
                        + " deficit " + top.deficit(world);
                candidates.add(new ToolDistributionCandidate(slot, toolType, List.of(), demandScore, reason, top));
                continue;
            }

            List<ToolsmithDemandPlanner.RecipientDemand> rankedRecipients = demandSnapshot.rankedRecipientsFor(toolType).stream()
                    .filter(recipient -> isValidToolRecipient(world, toolType, recipient))
                    .toList();
            if (rankedRecipients.isEmpty()) {
                continue;
            }

            int demandScore = demandSnapshot.deficitFor(toolType);
            ToolsmithDemandPlanner.RecipientDemand topRecipient = rankedRecipients.getFirst();
            String reason = toolType.label() + " selected: "
                    + topRecipient.record().recipient().getVillagerData().getProfession() + " deficit "
                    + Math.max(demandScore, topRecipient.deficit());
            candidates.add(new ToolDistributionCandidate(slot, toolType, rankedRecipients, demandScore, reason, null));
        }

        candidates.sort(Comparator
                .comparingInt(ToolDistributionCandidate::demandScore).reversed()
                .thenComparing(c -> {
                    if (c.toolType() == ToolsmithDemandPlanner.ToolType.FISHING_ROD) {
                        return c.fishermanEntry() != null ? c.fishermanEntry().deficit(world) : 0;
                    }
                    return c.rankedRecipients().isEmpty() ? 0 : c.rankedRecipients().getFirst().deficit();
                }, Comparator.reverseOrder()));
        return candidates;
    }

    private boolean isValidToolRecipient(ServerWorld world,
                                         ToolsmithDemandPlanner.ToolType toolType,
                                         ToolsmithDemandPlanner.RecipientDemand recipient) {
        if (world == null) {
            logRecipientRejection("WORLD_NULL", recipient, null);
            return false;
        }

        VillagerProfession expectedProfession = expectedProfessionFor(toolType);
        if (expectedProfession == null || recipient.record().recipient().getVillagerData().getProfession() != expectedProfession) {
            logRecipientRejection("PROFESSION_MISMATCH", recipient, null);
            return false;
        }

        Block expectedJobBlock = expectedJobBlockFor(toolType);
        if (expectedJobBlock == null || !world.getBlockState(recipient.record().jobPos()).isOf(expectedJobBlock)) {
            logRecipientRejection("JOB_BLOCK_MISMATCH", recipient, null);
            return false;
        }

        BlockPos recipientChestPos = recipient.record().chestPos();
        BlockState recipientChestState = world.getBlockState(recipientChestPos);
        if (!(recipientChestState.getBlock() instanceof ChestBlock)
                && !(recipientChestState.getBlock() instanceof BarrelBlock)) {
            logRecipientRejection("RECIPIENT_STORAGE_INVALID", recipient, null);
            return false;
        }

        Optional<BlockPos> resolvedChestPos = JobBlockPairingHelper.findNearbyChest(world, recipient.record().jobPos());
        if (resolvedChestPos.isPresent()) {
            if (!PairedStorageHelper.areEquivalentStoragePositions(world, resolvedChestPos.get(), recipientChestPos)) {
                logRecipientRejection("PAIRING_MISMATCH", recipient, resolvedChestPos.get());
                return false;
            }
        }

        return true;
    }

    private void logRecipientRejection(String reasonCode,
                                       ToolsmithDemandPlanner.RecipientDemand recipient,
                                       BlockPos resolvedChestPos) {
        String resolved = resolvedChestPos == null ? "<none>" : resolvedChestPos.toShortString();
        LOGGER.debug("Toolsmith recipient rejected [{}]: villager={} profession={} jobPos={} chestPos={} resolvedChestPos={}",
                reasonCode,
                recipient.record().recipient().getUuidAsString(),
                recipient.record().recipient().getVillagerData().getProfession(),
                recipient.record().jobPos().toShortString(),
                recipient.record().chestPos().toShortString(),
                resolved);
    }

    private VillagerProfession expectedProfessionFor(ToolsmithDemandPlanner.ToolType toolType) {
        return switch (toolType) {
            case PICKAXE -> VillagerProfession.MASON;
            case HOE -> VillagerProfession.FARMER;
            case SHEARS -> VillagerProfession.SHEPHERD;
            case FISHING_ROD -> VillagerProfession.FISHERMAN;
            default -> null;
        };
    }

    private Block expectedJobBlockFor(ToolsmithDemandPlanner.ToolType toolType) {
        return switch (toolType) {
            case PICKAXE -> Blocks.STONECUTTER;
            case HOE -> Blocks.COMPOSTER;
            case SHEARS -> Blocks.LOOM;
            case FISHING_ROD -> Blocks.BARREL;
            default -> null;
        };
    }

    @Override
    protected boolean executeTransfer(ServerWorld world) {
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
            return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, position, false));
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
            ToolsmithDemandPlanner.ToolType toolType,
            List<ToolsmithDemandPlanner.RecipientDemand> rankedRecipients,
            int demandScore,
            String selectionReason,
            @org.jetbrains.annotations.Nullable ToolsmithDemandPlanner.FishermanRodEntry fishermanEntry
    ) {
    }
}
