package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.MasonGuardEntity;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
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
    private static final Set<String> RAW_ORE_ITEM_PATHS = Set.of("raw_iron", "raw_copper", "raw_gold");
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
    private final Map<DistributionType, Integer> recipientCursorByType = new EnumMap<>(DistributionType.class);
    private Stage stage = Stage.IDLE;
    private ItemStack pendingItem = ItemStack.EMPTY;
    private BlockPos pendingTargetChestPos;
    private DistributionType pendingType = DistributionType.NONE;
    private boolean pendingOverflowTransfer;

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

        if (guard.age % DISTRIBUTION_INTERVAL_TICKS != 0) {
            return false;
        }

        return preparePendingTransfer(world, sourceInventory.get());
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
        if (chestPos == null || pendingItem.isEmpty() || pendingTargetChestPos == null) {
            stage = Stage.DONE;
            return;
        }

        if (stage == Stage.MOVE_TO_SOURCE) {
            if (isNear(chestPos)) {
                stage = Stage.MOVE_TO_TARGET;
                moveTo(pendingTargetChestPos);
            } else {
                moveTo(chestPos);
            }
            return;
        }

        if (stage == Stage.MOVE_TO_TARGET) {
            if (isNear(pendingTargetChestPos)) {
                stage = Stage.EXECUTE_TRANSFER;
            } else {
                moveTo(pendingTargetChestPos);
            }
            return;
        }

        if (stage == Stage.EXECUTE_TRANSFER) {
            executePendingTransfer(world);
            stage = Stage.DONE;
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

            ItemStack extracted = stack.split(1);
            if (extracted.isEmpty()) {
                continue;
            }

            source.setStack(slot, stack);
            source.markDirty();
            this.pendingItem = extracted;
            this.pendingTargetChestPos = selectedRecipient.chestPos();
            this.pendingType = type;
            this.pendingOverflowTransfer = false;
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

            ItemStack extracted = stack.split(1);
            if (extracted.isEmpty()) {
                continue;
            }

            source.setStack(slot, stack);
            source.markDirty();
            this.pendingItem = extracted;
            this.pendingTargetChestPos = librarians.getFirst().chestPos();
            this.pendingType = DistributionType.NONE;
            this.pendingOverflowTransfer = true;
            return true;
        }

        return false;
    }

    private void executePendingTransfer(ServerWorld world) {
        if (pendingItem.isEmpty() || pendingTargetChestPos == null) {
            return;
        }

        Optional<Inventory> targetInventory = getChestInventory(world, pendingTargetChestPos);
        if (targetInventory.isEmpty()) {
            returnPendingToSource(world);
            return;
        }

        ItemStack remaining = insertStack(targetInventory.get(), pendingItem.copy());
        if (remaining.isEmpty()) {
            if (!pendingOverflowTransfer && pendingType != DistributionType.NONE) {
                List<RecipientRecord> refreshedRecipients = getPrimaryRecipients(world, pendingType);
                RecipientRecord selected = refreshedRecipients.stream()
                        .filter(recipient -> recipient.chestPos().equals(pendingTargetChestPos))
                        .findFirst()
                        .orElse(null);
                if (selected != null) {
                    advanceRecipientCursor(pendingType, refreshedRecipients, selected);
                }
            }
            clearPendingState();
            return;
        }

        pendingItem = remaining;
        returnPendingToSource(world);
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
        this.pendingTargetChestPos = null;
        this.pendingType = DistributionType.NONE;
        this.pendingOverflowTransfer = false;
    }

    private void moveTo(BlockPos target) {
        if (target == null) {
            return;
        }
        guard.getNavigation().startMovingTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, MOVE_SPEED);
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

        return DistributionType.NONE;
    }

    private boolean isOreItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        String path = Registries.ITEM.getId(stack.getItem()).getPath();
        return path.endsWith("_ore") || RAW_ORE_ITEM_PATHS.contains(path);
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
        SEEDS
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
