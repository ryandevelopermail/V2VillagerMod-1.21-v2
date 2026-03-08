package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.DistributionRecipientHelper;
import dev.sterner.guardvillagers.common.villager.CraftingCheckLogger;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
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
    private static final double RECIPIENT_SCAN_RANGE = 24.0D;

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
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isDistributableItem(stack)) {
                continue;
            }
            if (!findRecipientForStack(world, stack).isEmpty()) {
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

        List<ToolDistributionCandidate> candidates = collectCandidates(world, inventory);
        if (candidates.isEmpty()) {
            return false;
        }

        ToolDistributionCandidate selected = candidates.getFirst();
        RecipientDemand recipient = selected.rankedRecipients().getFirst();
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

        CraftingCheckLogger.report(world, "Toolsmith", selected.selectionReason());
        if (pendingItem.isOf(Items.FISHING_ROD)) {
            CraftingCheckLogger.report(world, "Toolsmith", "distributing fishing rod to " + describeStorageType(world, pendingTargetPos));
        }
        return true;
    }

    @Override
    protected boolean refreshTargetForPendingItem(ServerWorld world) {
        ToolType toolType = ToolType.fromStack(pendingItem);
        if (toolType == null) {
            return false;
        }

        List<RecipientDemand> rankedRecipients = rankRecipientsForTool(world, toolType, findRecipientForTool(world, toolType));
        if (rankedRecipients.isEmpty()) {
            return false;
        }

        if (pendingTargetId != null) {
            for (RecipientDemand recipient : rankedRecipients) {
                if (recipient.record().recipient().getUuid().equals(pendingTargetId)) {
                    pendingTargetPos = recipient.record().chestPos();
                    if (pendingItem.isOf(Items.FISHING_ROD)) {
                        CraftingCheckLogger.report(world, "Toolsmith", "distributing fishing rod to " + describeStorageType(world, pendingTargetPos));
                    }
                    return true;
                }
            }
        }

        RecipientDemand recipient = rankedRecipients.getFirst();
        pendingTargetId = recipient.record().recipient().getUuid();
        pendingTargetPos = recipient.record().chestPos();
        if (pendingItem.isOf(Items.FISHING_ROD)) {
            CraftingCheckLogger.report(world, "Toolsmith", "distributing fishing rod to " + describeStorageType(world, pendingTargetPos));
        }
        return true;
    }

    private List<ToolDistributionCandidate> collectCandidates(ServerWorld world, Inventory inventory) {
        ToolDemand demand = calculateToolDemand(world, inventory);
        List<ToolDistributionCandidate> candidates = new ArrayList<>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isDistributableItem(stack)) {
                continue;
            }

            ToolType toolType = ToolType.fromStack(stack);
            if (toolType == null) {
                continue;
            }

            List<RecipientDemand> rankedRecipients = rankRecipientsForTool(world, toolType, findRecipientForTool(world, toolType));
            if (rankedRecipients.isEmpty()) {
                continue;
            }

            int demandScore = demand.deficitFor(toolType);
            RecipientDemand topRecipient = rankedRecipients.getFirst();
            String reason = toolType.label() + " selected: "
                    + topRecipient.record().recipient().getVillagerData().getProfession() + " deficit "
                    + Math.max(demandScore, topRecipient.deficit());
            candidates.add(new ToolDistributionCandidate(slot, stack.getItem(), rankedRecipients, demandScore, reason));
        }

        candidates.sort(Comparator
                .comparingInt(ToolDistributionCandidate::demandScore).reversed()
                .thenComparing(Comparator.comparingInt((ToolDistributionCandidate candidate) -> candidate.rankedRecipients().getFirst().deficit()).reversed()));
        return candidates;
    }

    private List<RecipientDemand> rankRecipientsForTool(ServerWorld world, ToolType toolType, List<DistributionRecipientHelper.RecipientRecord> recipients) {
        List<RecipientDemand> ranked = new ArrayList<>();
        for (DistributionRecipientHelper.RecipientRecord recipient : recipients) {
            int stock = countItemInRecipientStorage(world, recipient.chestPos(), toolType);
            int deficit = Math.max(0, toolType.targetPerRecipient() - stock);
            ranked.add(new RecipientDemand(recipient, deficit));
        }

        ranked.sort(Comparator
                .comparingInt(RecipientDemand::deficit).reversed()
                .thenComparingDouble(recipient -> recipient.record().sourceSquaredDistance())
                .thenComparing(recipient -> recipient.record().recipient().getUuid(), java.util.UUID::compareTo));
        return ranked;
    }

    private ToolDemand calculateToolDemand(ServerWorld world, Inventory inventory) {
        int fishermanCount = findRecipientForTool(world, ToolType.FISHING_ROD).size();
        int masonCount = findRecipientForTool(world, ToolType.PICKAXE).size();
        int farmerCount = findRecipientForTool(world, ToolType.HOE).size();
        int shepherdCount = findRecipientForTool(world, ToolType.SHEARS).size();

        int fishingRodStock = countItemInInventory(inventory, Items.FISHING_ROD, null);
        int pickaxeStock = countItemInInventory(inventory, null, PickaxeItem.class);
        int hoeStock = countItemInInventory(inventory, null, HoeItem.class);
        int shearsStock = countItemInInventory(inventory, null, ShearsItem.class);

        return new ToolDemand(
                fishermanCount - (fishingRodStock / ToolType.FISHING_ROD.targetPerRecipient()),
                masonCount - (pickaxeStock / ToolType.PICKAXE.targetPerRecipient()),
                farmerCount - (hoeStock / ToolType.HOE.targetPerRecipient()),
                shepherdCount - (shearsStock / ToolType.SHEARS.targetPerRecipient())
        );
    }

    private int countItemInRecipientStorage(ServerWorld world, BlockPos storagePos, ToolType toolType) {
        Optional<Inventory> inventory = getChestInventory(world, storagePos);
        if (inventory.isEmpty()) {
            return 0;
        }
        return countItemInInventory(inventory.get(), toolType.exactItem(), toolType.itemClass());
    }

    private int countItemInInventory(Inventory inventory, Item exactItem, Class<?> itemClass) {
        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if ((exactItem != null && stack.isOf(exactItem))
                    || (itemClass != null && itemClass.isInstance(stack.getItem()))) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private List<DistributionRecipientHelper.RecipientRecord> findRecipientForTool(ServerWorld world, ToolType toolType) {
        return switch (toolType) {
            case HOE -> DistributionRecipientHelper.findEligibleFarmerRecipients(world, villager, RECIPIENT_SCAN_RANGE);
            case PICKAXE -> DistributionRecipientHelper.findEligibleMasonRecipients(world, villager, RECIPIENT_SCAN_RANGE);
            case SHEARS -> DistributionRecipientHelper.findEligibleShepherdRecipients(world, villager, RECIPIENT_SCAN_RANGE);
            case FISHING_ROD -> DistributionRecipientHelper.findEligibleFishermanRecipients(world, villager, RECIPIENT_SCAN_RANGE);
        };
    }

    @Override
    protected boolean executeTransfer(ServerWorld world) {
        if (pendingItem.isEmpty() || pendingTargetPos == null) {
            return false;
        }

        Optional<Inventory> targetInventory = getChestInventory(world, pendingTargetPos);
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

    private List<DistributionRecipientHelper.RecipientRecord> findRecipientForStack(ServerWorld world, ItemStack stack) {
        ToolType toolType = ToolType.fromStack(stack);
        if (toolType == null) {
            return List.of();
        }
        return findRecipientForTool(world, toolType);
    }

    private Optional<Inventory> getChestInventory(ServerWorld world, BlockPos position) {
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

    private String describeStorageType(ServerWorld world, BlockPos position) {
        if (position == null) {
            return "unknown storage";
        }
        BlockState state = world.getBlockState(position);
        if (state.getBlock() instanceof BarrelBlock) {
            return "barrel";
        }
        if (state.getBlock() instanceof ChestBlock) {
            return "chest";
        }
        return "unknown storage";
    }

    private record ToolDistributionCandidate(
            int slot,
            Item item,
            List<RecipientDemand> rankedRecipients,
            int demandScore,
            String selectionReason
    ) {
    }

    private record RecipientDemand(DistributionRecipientHelper.RecipientRecord record, int deficit) {
    }

    private record ToolDemand(int fishingRodDeficit, int pickaxeDeficit, int hoeDeficit, int shearsDeficit) {
        private int deficitFor(ToolType toolType) {
            return switch (toolType) {
                case HOE -> hoeDeficit;
                case PICKAXE -> pickaxeDeficit;
                case SHEARS -> shearsDeficit;
                case FISHING_ROD -> fishingRodDeficit;
            };
        }
    }

    private enum ToolType {
        HOE("hoe", HoeItem.class, null, 1),
        PICKAXE("pickaxe", PickaxeItem.class, null, 1),
        SHEARS("shears", ShearsItem.class, null, 1),
        FISHING_ROD("fishing rod", null, Items.FISHING_ROD, 1);

        private final String label;
        private final Class<?> itemClass;
        private final Item exactItem;
        private final int targetPerRecipient;

        ToolType(String label, Class<?> itemClass, Item exactItem, int targetPerRecipient) {
            this.label = label;
            this.itemClass = itemClass;
            this.exactItem = exactItem;
            this.targetPerRecipient = targetPerRecipient;
        }

        private static ToolType fromStack(ItemStack stack) {
            if (stack.isEmpty()) {
                return null;
            }
            if (stack.getItem() instanceof HoeItem) {
                return HOE;
            }
            if (stack.getItem() instanceof PickaxeItem) {
                return PICKAXE;
            }
            if (stack.getItem() instanceof ShearsItem) {
                return SHEARS;
            }
            if (stack.isOf(Items.FISHING_ROD)) {
                return FISHING_ROD;
            }
            return null;
        }

        private String label() {
            return label;
        }

        private Class<?> itemClass() {
            return itemClass;
        }

        private Item exactItem() {
            return exactItem;
        }

        private int targetPerRecipient() {
            return targetPerRecipient;
        }
    }
}
