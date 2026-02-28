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
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class MasonGuardChestDistributionGoal extends Goal {
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

    public MasonGuardChestDistributionGoal(MasonGuardEntity guard) {
        this.guard = guard;
        this.setControls(EnumSet.noneOf(Control.class));
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

        return hasPrimaryDistributionCandidate(world, sourceInventory.get())
                || (getFullness(sourceInventory.get()) >= SOURCE_CHEST_FULLNESS_TRIGGER
                && hasLibrarianDistributionCandidate(sourceInventory.get())
                && !findRecipients(world, VillagerProfession.LIBRARIAN, Blocks.LECTERN).isEmpty());
    }

    @Override
    public boolean shouldRunEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (!(guard.getWorld() instanceof ServerWorld world)) {
            return;
        }
        if (guard.age % DISTRIBUTION_INTERVAL_TICKS != 0) {
            return;
        }

        BlockPos chestPos = guard.getPairedChestPos();
        if (chestPos == null) {
            return;
        }

        Optional<Inventory> sourceInventory = getChestInventory(world, chestPos);
        if (sourceInventory.isEmpty()) {
            return;
        }

        Inventory source = sourceInventory.get();
        if (movePrimaryDistributionItem(world, source)) {
            source.markDirty();
            return;
        }

        if (getFullness(source) >= SOURCE_CHEST_FULLNESS_TRIGGER) {
            moveLibrarianOverflowItem(world, source);
            source.markDirty();
        }
    }

    private boolean hasPrimaryDistributionCandidate(ServerWorld world, Inventory source) {
        for (int slot = 0; slot < source.size(); slot++) {
            ItemStack stack = source.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            DistributionType type = classifyPrimaryDistribution(stack);
            if (type == DistributionType.NONE) {
                continue;
            }
            if (!getPrimaryRecipients(world, type).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean movePrimaryDistributionItem(ServerWorld world, Inventory source) {
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

            ItemStack extracted = stack.split(1);
            if (extracted.isEmpty()) {
                continue;
            }

            ItemStack remaining = insertStack(recipients.getFirst().inventory(), extracted);
            if (remaining.isEmpty()) {
                return true;
            }

            stack.increment(remaining.getCount());
        }
        return false;
    }

    private boolean hasLibrarianDistributionCandidate(Inventory source) {
        for (int slot = 0; slot < source.size(); slot++) {
            if (isLibrarianOverflowItem(source.getStack(slot))) {
                return true;
            }
        }
        return false;
    }

    private boolean moveLibrarianOverflowItem(ServerWorld world, Inventory source) {
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

            ItemStack remaining = insertStack(librarians.getFirst().inventory(), extracted);
            if (remaining.isEmpty()) {
                return true;
            }

            stack.increment(remaining.getCount());
        }

        return false;
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
                combined.sort(Comparator.comparingDouble(RecipientRecord::distanceSquared));
                yield combined;
            }
            case ORE -> findRecipients(world, VillagerProfession.ARMORER, Blocks.BLAST_FURNACE);
            case SEEDS -> findRecipients(world, VillagerProfession.FARMER, Blocks.COMPOSTER);
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

            recipients.add(new RecipientRecord(targetInventory.get(), guard.squaredDistanceTo(villager)));
        }

        recipients.sort(Comparator.comparingDouble(RecipientRecord::distanceSquared));
        return recipients;
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

    private record RecipientRecord(Inventory inventory, double distanceSquared) {
    }
}
