package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.MasonGuardEntity;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class MasonGuardChestDistributionGoal extends Goal {
    private static final double MOVE_SPEED = 0.6D;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final double RECIPIENT_SCAN_RANGE = 24.0D;
    private static final double SOURCE_CHEST_FULLNESS_TRIGGER = 0.80D;
    private static final int DISTRIBUTION_INTERVAL_TICKS = 20;
    private static final int PATH_RETRY_INTERVAL_TICKS = 20;
    private static final Set<String> RAW_ORE_ITEM_PATHS = Set.of("raw_iron", "raw_copper", "raw_gold");
    private static final Set<String> ADDITIONAL_ORE_ITEM_PATHS = Set.of("ancient_debris");
    private static final Set<net.minecraft.item.Item> LIBRARIAN_COLLECTED_MATERIALS = Set.of(
            net.minecraft.item.Items.STONE,
            net.minecraft.item.Items.COBBLESTONE,
            net.minecraft.item.Items.COBBLED_DEEPSLATE,
            net.minecraft.item.Items.DEEPSLATE,
            net.minecraft.item.Items.CLAY_BALL,
            net.minecraft.item.Items.CLAY,
            net.minecraft.item.Items.QUARTZ,
            net.minecraft.item.Items.SMOOTH_STONE,
            net.minecraft.item.Items.ANDESITE,
            net.minecraft.item.Items.DIORITE,
            net.minecraft.item.Items.GRANITE,
            net.minecraft.item.Items.TUFF,
            net.minecraft.item.Items.CALCITE,
            net.minecraft.item.Items.DRIPSTONE_BLOCK
    );

    private final MasonGuardEntity guard;
    private BlockPos currentNavigationTarget;
    private long lastPathRequestTick = Long.MIN_VALUE;
    private final Map<DistributionType, Integer> recipientCursorByType = new EnumMap<>(DistributionType.class);
    private Stage stage = Stage.IDLE;
    private ItemStack pendingItem = ItemStack.EMPTY;
    private int pendingSourceSlot = -1;
    private net.minecraft.item.Item pendingSourceItem;
    private BlockPos pendingTargetChestPos;
    private DistributionType pendingType = DistributionType.NONE;
    private boolean pendingOverflowTransfer;
    private int pendingExtractCount = 1;
    private boolean pendingCoalBatchMode;
    private final List<BlockPos> pendingCoalTargets = new ArrayList<>();
    private int pendingCoalRecipientCount;
    private int pendingCoalTargetIndex;
    private int pendingCoalFailedAttempts;

    public MasonGuardChestDistributionGoal(MasonGuardEntity guard) {
        this.guard = guard;
        this.setControls(EnumSet.of(Control.MOVE));
    }

    @Override
    public boolean canStart() {
        if (!(guard.getWorld() instanceof ServerWorld world) || !guard.isAlive()) {
            return false;
        }
        BlockPos chestPos = guard.getPairedChestPos();
        if (chestPos == null) {
            return false;
        }
        Optional<Inventory> sourceInventory = getChestInventory(world, chestPos);
        if (sourceInventory.isEmpty()) {
            return false;
        }

        Inventory source = sourceInventory.get();
        boolean continuousDistributionDemand = hasContinuousDistributionDemand(world, source);

        if (guard.age % DISTRIBUTION_INTERVAL_TICKS != 0 && !continuousDistributionDemand) {
            return false;
        }

        return preparePendingTransfer(world, source);
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.DONE && guard.isAlive();
    }

    @Override
    public void start() {
        this.stage = Stage.MOVE_TO_SOURCE;
        moveTo(guard.getPairedChestPos());
    }

    @Override
    public void stop() {
        guard.getNavigation().stop();
        currentNavigationTarget = null;
        lastPathRequestTick = Long.MIN_VALUE;
        clearPendingState();
        this.stage = Stage.DONE;
    }

    @Override
    public void tick() {
        if (!(guard.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        BlockPos chestPos = guard.getPairedChestPos();
        if (chestPos == null || pendingTargetChestPos == null || pendingSourceSlot < 0 || pendingSourceItem == null) {
            stage = Stage.DONE;
            return;
        }

        if (stage == Stage.MOVE_TO_SOURCE) {
            if (isNear(chestPos)) {
                if (!extractPendingItemFromSource(world, chestPos)) {
                    stage = Stage.DONE;
                    return;
                }
                stage = Stage.MOVE_TO_TARGET;
                moveTo(pendingTargetChestPos);
            } else {
                moveTo(chestPos);
            }
            return;
        }

        if (stage == Stage.MOVE_TO_TARGET) {
            if (pendingItem.isEmpty()) {
                stage = Stage.DONE;
                return;
            }
            if (isNear(pendingTargetChestPos)) {
                stage = Stage.EXECUTE_TRANSFER;
            } else {
                moveTo(pendingTargetChestPos);
            }
            return;
        }

        if (stage == Stage.EXECUTE_TRANSFER) {
            executePendingTransfer(world);
            if (stage == Stage.EXECUTE_TRANSFER) {
                stage = Stage.DONE;
            }
        }
    }

    private boolean preparePendingTransfer(ServerWorld world, Inventory source) {
        for (int slot = 0; slot < source.size(); slot++) {
            ItemStack stack = source.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }

            DistributionType type = classifyPrimaryDistribution(stack);
            if (type == DistributionType.NONE) {
                continue;
            }

            List<RecipientRecord> recipients = getPrimaryRecipients(world, type);
            if (recipients.isEmpty()) {
                continue;
            }

            RecipientRecord selectedRecipient = selectRecipient(type, recipients);
            if (selectedRecipient == null) {
                continue;
            }

            this.pendingItem = ItemStack.EMPTY;
            this.pendingSourceSlot = slot;
            this.pendingSourceItem = stack.getItem();
            this.pendingType = type;
            this.pendingOverflowTransfer = false;
            this.pendingExtractCount = 1;
            this.pendingCoalBatchMode = false;
            this.pendingCoalTargets.clear();
            this.pendingCoalRecipientCount = 0;
            this.pendingCoalTargetIndex = 0;
            this.pendingCoalFailedAttempts = 0;

            if ((type == DistributionType.COAL || type == DistributionType.ORE) && recipients.size() > 1) {
                int cursor = recipientCursorByType.getOrDefault(type, 0);
                for (int i = 0; i < recipients.size(); i++) {
                    RecipientRecord recipient = recipients.get(Math.floorMod(cursor + i, recipients.size()));
                    pendingCoalTargets.add(recipient.chestPos());
                }
                this.pendingCoalBatchMode = !pendingCoalTargets.isEmpty();
                this.pendingCoalRecipientCount = recipients.size();
                this.pendingExtractCount = Math.max(1, stack.getCount());
                this.pendingTargetChestPos = pendingCoalBatchMode ? pendingCoalTargets.getFirst() : selectedRecipient.chestPos();
            } else {
                if (type == DistributionType.COBBLESTONE) {
                    this.pendingExtractCount = Math.min(stack.getCount(), 8);
                }
                this.pendingTargetChestPos = selectedRecipient.chestPos();
            }
            return true;
        }

        if (getFullness(source) < SOURCE_CHEST_FULLNESS_TRIGGER) {
            return false;
        }

        List<RecipientRecord> librarians = findRecipients(world, VillagerProfession.LIBRARIAN, Blocks.LECTERN);
        if (librarians.isEmpty()) {
            return false;
        }

        for (int slot = 0; slot < source.size(); slot++) {
            ItemStack stack = source.getStack(slot);
            if (!isLibrarianOverflowItem(stack)) {
                continue;
            }

            this.pendingItem = ItemStack.EMPTY;
            this.pendingSourceSlot = slot;
            this.pendingSourceItem = stack.getItem();
            RecipientRecord selectedLibrarian = selectRecipient(DistributionType.NONE, librarians);
            if (selectedLibrarian == null) {
                continue;
            }

            this.pendingTargetChestPos = selectedLibrarian.chestPos();
            this.pendingType = DistributionType.NONE;
            this.pendingOverflowTransfer = true;
            this.pendingExtractCount = 1;
            this.pendingCoalBatchMode = false;
            this.pendingCoalTargets.clear();
            this.pendingCoalRecipientCount = 0;
            this.pendingCoalTargetIndex = 0;
            this.pendingCoalFailedAttempts = 0;
            return true;
        }

        return false;
    }

    private void executePendingTransfer(ServerWorld world) {
        if (pendingItem.isEmpty() || pendingTargetChestPos == null) {
            return;
        }

        if (pendingCoalBatchMode) {
            executeCoalBatchTransfer(world);
            return;
        }

        Optional<Inventory> targetInventory = getChestInventory(world, pendingTargetChestPos);
        if (targetInventory.isEmpty()) {
            returnPendingToSource(world);
            return;
        }

        ItemStack remaining = insertStack(targetInventory.get(), pendingItem.copy());
        if (remaining.isEmpty()) {
            List<RecipientRecord> refreshedRecipients = pendingOverflowTransfer
                    ? findRecipients(world, VillagerProfession.LIBRARIAN, Blocks.LECTERN)
                    : getPrimaryRecipients(world, pendingType);
            RecipientRecord selected = refreshedRecipients.stream()
                    .filter(recipient -> recipient.chestPos().equals(pendingTargetChestPos))
                    .findFirst()
                    .orElse(null);
            if (selected != null) {
                advanceRecipientCursor(pendingOverflowTransfer ? DistributionType.NONE : pendingType, refreshedRecipients, selected);
            }
            clearPendingState();
            return;
        }

        pendingItem = remaining;
        returnPendingToSource(world);
    }

    private void executeCoalBatchTransfer(ServerWorld world) {
        if (pendingCoalTargets.isEmpty()) {
            returnPendingToSource(world);
            return;
        }

        Optional<Inventory> targetInventory = getChestInventory(world, pendingTargetChestPos);
        if (targetInventory.isPresent()) {
            ItemStack singleItem = pendingItem.copyWithCount(1);
            ItemStack remaining = insertStack(targetInventory.get(), singleItem);
            if (remaining.isEmpty()) {
                pendingItem.decrement(1);
                pendingCoalFailedAttempts = 0;
                advanceBatchCursor();
            } else {
                pendingCoalFailedAttempts++;
            }
        } else {
            pendingCoalFailedAttempts++;
        }

        if (pendingItem.isEmpty()) {
            clearPendingState();
            return;
        }

        if (pendingCoalFailedAttempts >= pendingCoalTargets.size()) {
            returnPendingToSource(world);
            return;
        }

        pendingCoalTargetIndex = (pendingCoalTargetIndex + 1) % pendingCoalTargets.size();
        pendingTargetChestPos = pendingCoalTargets.get(pendingCoalTargetIndex);
        stage = Stage.MOVE_TO_TARGET;
    }

    private void advanceBatchCursor() {
        int recipientCount = pendingCoalRecipientCount;
        if (recipientCount <= 0 || pendingType == DistributionType.NONE) {
            return;
        }
        int cursor = recipientCursorByType.getOrDefault(pendingType, 0);
        recipientCursorByType.put(pendingType, (cursor + 1) % recipientCount);
    }

    private void returnPendingToSource(ServerWorld world) {
        if (pendingItem.isEmpty()) {
            return;
        }

        Optional<Inventory> sourceInventory = getChestInventory(world, guard.getPairedChestPos());
        Inventory fallback = sourceInventory.orElse(guard.guardInventory);
        ItemStack remaining = insertStack(fallback, pendingItem.copy());
        if (!remaining.isEmpty()) {
            ItemStack leftover = guard.guardInventory.addStack(remaining);
            if (!leftover.isEmpty()) {
                guard.dropStack(leftover);
            }
            guard.guardInventory.markDirty();
        }
        clearPendingState();
    }

    private void clearPendingState() {
        this.pendingItem = ItemStack.EMPTY;
        this.pendingSourceSlot = -1;
        this.pendingSourceItem = null;
        this.pendingTargetChestPos = null;
        this.pendingType = DistributionType.NONE;
        this.pendingOverflowTransfer = false;
        this.pendingExtractCount = 1;
        this.pendingCoalBatchMode = false;
        this.pendingCoalTargets.clear();
        this.pendingCoalRecipientCount = 0;
        this.pendingCoalTargetIndex = 0;
        this.pendingCoalFailedAttempts = 0;
    }

    private boolean extractPendingItemFromSource(ServerWorld world, BlockPos sourceChestPos) {
        if (pendingSourceSlot < 0 || pendingSourceItem == null) {
            return false;
        }

        Optional<Inventory> sourceInventory = getChestInventory(world, sourceChestPos);
        if (sourceInventory.isEmpty()) {
            return false;
        }

        Inventory inventory = sourceInventory.get();
        if (pendingSourceSlot >= inventory.size()) {
            return false;
        }

        ItemStack sourceStack = inventory.getStack(pendingSourceSlot);
        if (sourceStack.isEmpty() || sourceStack.getItem() != pendingSourceItem) {
            return false;
        }

        ItemStack extracted = sourceStack.split(Math.max(1, pendingExtractCount));
        if (extracted.isEmpty()) {
            return false;
        }

        inventory.setStack(pendingSourceSlot, sourceStack);
        inventory.markDirty();
        pendingItem = extracted;
        return true;
    }

    private void moveTo(BlockPos target) {
        if (target == null) {
            return;
        }

        long currentTick = guard.getWorld().getTime();
        boolean shouldRequestPath = !target.equals(currentNavigationTarget)
                || guard.getNavigation().isIdle()
                || currentTick - lastPathRequestTick >= PATH_RETRY_INTERVAL_TICKS;
        if (!shouldRequestPath) {
            return;
        }

        guard.getNavigation().startMovingTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, MOVE_SPEED);
        currentNavigationTarget = target.toImmutable();
        lastPathRequestTick = currentTick;
    }

    private boolean isNear(BlockPos target) {
        if (target == null) {
            return false;
        }
        return guard.squaredDistanceTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    private DistributionType classifyPrimaryDistribution(ItemStack stack) {
        if (stack.isIn(ItemTags.COALS)) {
            return DistributionType.COAL;
        }

        if (isOreItem(stack)) {
            return DistributionType.ORE;
        }

        if (stack.isIn(ItemTags.VILLAGER_PLANTABLE_SEEDS)) {
            return DistributionType.SEEDS;
        }

        if (stack.isOf(Items.COBBLESTONE) || stack.isOf(Items.COBBLED_DEEPSLATE)) {
            return DistributionType.COBBLESTONE;
        }

        return DistributionType.NONE;
    }

    private boolean isOreItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        String path = Registries.ITEM.getId(stack.getItem()).getPath();
        return path.endsWith("_ore") || RAW_ORE_ITEM_PATHS.contains(path) || ADDITIONAL_ORE_ITEM_PATHS.contains(path);
    }

    private boolean isLibrarianOverflowItem(ItemStack stack) {
        return !stack.isEmpty() && (LIBRARIAN_COLLECTED_MATERIALS.contains(stack.getItem()) || classifyPrimaryDistribution(stack) != DistributionType.NONE);
    }

    private List<RecipientRecord> getPrimaryRecipients(ServerWorld world, DistributionType type) {
        return switch (type) {
            case COAL -> {
                List<RecipientRecord> combined = new ArrayList<>(findRecipients(world, VillagerProfession.BUTCHER, Blocks.SMOKER));
                combined.addAll(findRecipients(world, VillagerProfession.ARMORER, Blocks.BLAST_FURNACE));
                yield normalizeRecipients(combined);
            }
            case ORE -> normalizeRecipients(findRecipients(world, VillagerProfession.ARMORER, Blocks.BLAST_FURNACE));
            case SEEDS -> normalizeRecipients(findRecipients(world, VillagerProfession.FARMER, Blocks.COMPOSTER));
            case COBBLESTONE -> {
                Block lumberjackJobBlock = ProfessionDefinitions.get(VillagerProfession.NONE)
                        .flatMap(definition -> definition.expectedJobBlocks().stream().findFirst())
                        .orElse(Blocks.CRAFTING_TABLE);
                yield normalizeRecipients(findRecipients(world, VillagerProfession.NONE, lumberjackJobBlock));
            }
            default -> List.of();
        };
    }

    private List<RecipientRecord> findRecipients(ServerWorld world, VillagerProfession profession, Block expectedJobBlock) {
        List<RecipientRecord> recipients = new ArrayList<>();
        Box scanBox = new Box(guard.getBlockPos()).expand(RECIPIENT_SCAN_RANGE);

        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, scanBox,
                candidate -> candidate.isAlive() && !candidate.isBaby() && candidate.getVillagerData().getProfession() == profession)) {
            Optional<GlobalPos> jobSiteMemory = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
            if (jobSiteMemory.isEmpty() || !Objects.equals(jobSiteMemory.get().dimension(), world.getRegistryKey())) {
                continue;
            }

            BlockPos jobPos = jobSiteMemory.get().pos();
            BlockState jobState = world.getBlockState(jobPos);
            if (!jobState.isOf(expectedJobBlock)) {
                continue;
            }

            Optional<BlockPos> chestPos = JobBlockPairingHelper.findNearbyChest(world, jobPos);
            if (chestPos.isEmpty()) {
                continue;
            }

            Optional<Inventory> targetInventory = getChestInventory(world, chestPos.get());
            if (targetInventory.isEmpty()) {
                continue;
            }

            recipients.add(new RecipientRecord(targetInventory.get(), chestPos.get().toImmutable(), guard.squaredDistanceTo(villager)));
        }
        return recipients;
    }

    private List<RecipientRecord> normalizeRecipients(List<RecipientRecord> recipients) {
        if (recipients.isEmpty()) {
            return List.of();
        }

        Map<BlockPos, RecipientRecord> nearestByChest = new HashMap<>();
        for (RecipientRecord recipient : recipients) {
            RecipientRecord existing = nearestByChest.get(recipient.chestPos());
            if (existing == null || recipient.distanceSquared() < existing.distanceSquared()) {
                nearestByChest.put(recipient.chestPos(), recipient);
            }
        }

        List<RecipientRecord> normalized = new ArrayList<>(nearestByChest.values());
        normalized.sort(Comparator.comparingInt((RecipientRecord value) -> value.chestPos().getX())
                .thenComparingInt(value -> value.chestPos().getY())
                .thenComparingInt(value -> value.chestPos().getZ())
                .thenComparingDouble(RecipientRecord::distanceSquared));
        return normalized;
    }

    private RecipientRecord selectRecipient(DistributionType type, List<RecipientRecord> recipients) {
        if (recipients.isEmpty()) {
            return null;
        }

        int cursor = recipientCursorByType.getOrDefault(type, 0);
        int index = Math.floorMod(cursor, recipients.size());
        return recipients.get(index);
    }

    private void advanceRecipientCursor(DistributionType type, List<RecipientRecord> recipients, RecipientRecord selected) {
        if (recipients.isEmpty()) {
            recipientCursorByType.remove(type);
            return;
        }

        int selectedIndex = recipients.indexOf(selected);
        if (selectedIndex < 0) {
            selectedIndex = 0;
        }

        recipientCursorByType.put(type, (selectedIndex + 1) % recipients.size());
    }

    private Optional<Inventory> getChestInventory(ServerWorld world, BlockPos position) {
        BlockState state = world.getBlockState(position);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, position, true));
    }


    private boolean hasContinuousDistributionDemand(ServerWorld world, Inventory source) {
        for (int slot = 0; slot < source.size(); slot++) {
            ItemStack stack = source.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }

            DistributionType type = classifyPrimaryDistribution(stack);
            if (type != DistributionType.COAL && type != DistributionType.ORE) {
                continue;
            }

            List<RecipientRecord> recipients = getPrimaryRecipients(world, type);
            if (!recipients.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private double getFullness(Inventory inventory) {
        int max = 0;
        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            max += stack.getMaxCount();
            count += stack.getCount();
        }
        return max <= 0 ? 0.0D : (double) count / (double) max;
    }

    private ItemStack insertStack(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (remaining.isEmpty()) {
                return ItemStack.EMPTY;
            }

            ItemStack existing = inventory.getStack(slot);
            if (existing.isEmpty()) {
                if (!inventory.isValid(slot, remaining)) {
                    continue;
                }

                int moved = Math.min(remaining.getCount(), remaining.getMaxCount());
                ItemStack toInsert = remaining.copy();
                toInsert.setCount(moved);
                inventory.setStack(slot, toInsert);
                remaining.decrement(moved);
                continue;
            }

            if (!ItemStack.areItemsAndComponentsEqual(existing, remaining)) {
                continue;
            }
            if (!inventory.isValid(slot, remaining)) {
                continue;
            }

            int space = existing.getMaxCount() - existing.getCount();
            if (space <= 0) {
                continue;
            }

            int moved = Math.min(space, remaining.getCount());
            existing.increment(moved);
            remaining.decrement(moved);
        }

        inventory.markDirty();
        return remaining;
    }

    private enum DistributionType {
        NONE,
        COAL,
        ORE,
        SEEDS,
        COBBLESTONE
    }

    private enum Stage {
        IDLE,
        MOVE_TO_SOURCE,
        MOVE_TO_TARGET,
        EXECUTE_TRANSFER,
        DONE
    }

    private record RecipientRecord(Inventory inventory, BlockPos chestPos, double distanceSquared) {
    }
}
