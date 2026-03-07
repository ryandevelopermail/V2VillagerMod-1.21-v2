package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.AxeGuardEntity;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class AxeGuardDistributionGoal extends Goal {
    private static final double MOVE_SPEED = 0.65D;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final int CHECK_INTERVAL_TICKS = 120;
    private static final double RECIPIENT_SCAN_RANGE = 32.0D;
    private static final double LIBRARIAN_FULLNESS_TRIGGER = 0.80D;

    private static final Set<VillagerProfession> STICK_RECIPIENTS = Set.of(
            VillagerProfession.FARMER,
            VillagerProfession.FLETCHER,
            VillagerProfession.SHEPHERD,
            VillagerProfession.TOOLSMITH,
            VillagerProfession.WEAPONSMITH
    );

    private static final Set<VillagerProfession> CHARCOAL_RECIPIENTS = Set.of(
            VillagerProfession.ARMORER,
            VillagerProfession.BUTCHER,
            VillagerProfession.TOOLSMITH,
            VillagerProfession.WEAPONSMITH
    );

    private final AxeGuardEntity guard;
    private long nextCheckTick;
    private Stage stage = Stage.IDLE;

    private ItemStack pendingItem = ItemStack.EMPTY;
    private BlockPos pendingTargetPos;
    private UUID pendingTargetId;
    private boolean pendingPlacement;
    private Block pendingPlacementBlock;
    private boolean pendingFullnessTransfer;

    public AxeGuardDistributionGoal(AxeGuardEntity guard) {
        this.guard = guard;
        setControls(EnumSet.of(Control.MOVE));
    }

    public void requestImmediateDistribution() {
        nextCheckTick = 0L;
    }

    @Override
    public boolean canStart() {
        if (!(guard.getWorld() instanceof ServerWorld world) || !guard.isAlive() || guard.getChestPos() == null) {
            return false;
        }
        if (world.getTime() < nextCheckTick) {
            return false;
        }
        nextCheckTick = world.getTime() + CHECK_INTERVAL_TICKS;

        Inventory inventory = guard.getPairedChestInventory(world).orElse(null);
        return inventory != null && selectPendingTransfer(world, inventory);
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.DONE && guard.isAlive() && !pendingItem.isEmpty();
    }

    @Override
    public void start() {
        stage = Stage.MOVE_TO_TARGET;
        if (pendingTargetPos != null) {
            moveTo(pendingTargetPos);
        }
    }

    @Override
    public void stop() {
        guard.getNavigation().stop();
        clearPendingState();
        stage = Stage.DONE;
    }

    @Override
    public void tick() {
        if (!(guard.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        if (!refreshTargetForPendingItem(world)) {
            stage = Stage.DONE;
            return;
        }

        if (stage == Stage.MOVE_TO_TARGET) {
            if (isNear(pendingTargetPos)) {
                stage = Stage.TRANSFER;
            } else if (guard.getNavigation().isIdle()) {
                moveTo(pendingTargetPos);
            }
            return;
        }

        if (stage == Stage.TRANSFER) {
            executeTransfer(world);
            stage = Stage.DONE;
        }
    }

    private boolean selectPendingTransfer(ServerWorld world, Inventory inventory) {
        pendingPlacement = false;
        pendingPlacementBlock = null;
        pendingFullnessTransfer = false;

        if (isInventoryAtLeastFull(inventory, LIBRARIAN_FULLNESS_TRIGGER)) {
            Optional<TransferTarget> librarianTarget = findRecipientChests(world, Set.of(VillagerProfession.LIBRARIAN)).stream().findFirst();
            if (librarianTarget.isPresent()) {
                for (int slot = 0; slot < inventory.size(); slot++) {
                    ItemStack stack = inventory.getStack(slot);
                    if (stack.isEmpty()) continue;
                    pendingItem = stack.copy();
                    inventory.setStack(slot, ItemStack.EMPTY);
                    inventory.markDirty();
                    pendingTargetId = librarianTarget.get().villagerId();
                    pendingTargetPos = librarianTarget.get().chestPos();
                    pendingFullnessTransfer = true;
                    return true;
                }
            }
        }

        Optional<DirectedCandidate> directed = findDirectedTransferCandidate(world, inventory);
        if (directed.isPresent()) {
            DirectedCandidate candidate = directed.get();
            pendingItem = candidate.extracted();
            pendingTargetId = candidate.target().villagerId();
            pendingTargetPos = candidate.target().chestPos();
            return true;
        }

        Optional<PlacementCandidate> placement = findPlacementCandidate(world, inventory);
        if (placement.isPresent()) {
            PlacementCandidate candidate = placement.get();
            pendingItem = candidate.extracted();
            pendingTargetPos = candidate.placementPos();
            pendingTargetId = null;
            pendingPlacement = true;
            pendingPlacementBlock = candidate.block();
            return true;
        }

        return false;
    }

    private boolean refreshTargetForPendingItem(ServerWorld world) {
        if (pendingItem.isEmpty()) {
            return false;
        }

        if (pendingPlacement) {
            return pendingPlacementBlock != null
                    && pendingTargetPos != null
                    && canPlaceBlockAt(world, pendingTargetPos, pendingPlacementBlock.getDefaultState());
        }

        Set<VillagerProfession> recipients = pendingFullnessTransfer
                ? Set.of(VillagerProfession.LIBRARIAN)
                : resolveRecipientSetForStack(pendingItem);

        if (recipients.isEmpty()) {
            return false;
        }

        List<TransferTarget> targets = findRecipientChests(world, recipients);
        if (targets.isEmpty()) {
            return false;
        }

        if (pendingTargetId != null) {
            for (TransferTarget target : targets) {
                if (target.villagerId().equals(pendingTargetId)) {
                    pendingTargetPos = target.chestPos();
                    return true;
                }
            }
        }

        TransferTarget fallback = targets.getFirst();
        pendingTargetId = fallback.villagerId();
        pendingTargetPos = fallback.chestPos();
        return true;
    }

    private void executeTransfer(ServerWorld world) {
        if (pendingItem.isEmpty()) {
            return;
        }

        if (pendingPlacement) {
            if (pendingPlacementBlock == null || pendingTargetPos == null) {
                return;
            }
            if (!canPlaceBlockAt(world, pendingTargetPos, pendingPlacementBlock.getDefaultState())) {
                return;
            }

            world.setBlockState(pendingTargetPos, pendingPlacementBlock.getDefaultState());
            if (pendingPlacementBlock == Blocks.CHEST) {
                JobBlockPairingHelper.handlePairingBlockPlacement(world, pendingTargetPos, world.getBlockState(pendingTargetPos));
            } else if (pendingPlacementBlock == Blocks.CRAFTING_TABLE) {
                JobBlockPairingHelper.handleCraftingTablePlacement(world, pendingTargetPos);
            }
            return;
        }

        if (pendingTargetPos == null) {
            return;
        }

        Optional<Inventory> targetInventory = getChestInventory(world, pendingTargetPos);
        if (targetInventory.isEmpty()) {
            return;
        }

        ItemStack remaining = guard.insertIntoInventory(targetInventory.get(), pendingItem);
        targetInventory.get().markDirty();
        pendingItem = remaining;
    }

    private Optional<DirectedCandidate> findDirectedTransferCandidate(ServerWorld world, Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;

            Set<VillagerProfession> recipients = resolveRecipientSetForStack(stack);
            if (recipients.isEmpty()) continue;

            List<TransferTarget> targets = findRecipientChests(world, recipients);
            if (targets.isEmpty()) continue;

            int toMove = Math.min(stack.getCount(), Math.max(1, stack.getMaxCount() / 4));
            ItemStack extracted = stack.split(toMove);
            inventory.setStack(slot, stack);
            inventory.markDirty();
            return Optional.of(new DirectedCandidate(extracted, targets.getFirst()));
        }

        return Optional.empty();
    }

    private Set<VillagerProfession> resolveRecipientSetForStack(ItemStack stack) {
        if (stack.isOf(Items.STICK)) return STICK_RECIPIENTS;
        if (stack.isOf(Items.CHARCOAL)) return CHARCOAL_RECIPIENTS;
        if ((stack.isIn(ItemTags.LOGS_THAT_BURN) || stack.isIn(ItemTags.LOGS)) && !hasPairedFurnace()) {
            return Set.of(VillagerProfession.LIBRARIAN);
        }
        return Set.of();
    }

    private Optional<PlacementCandidate> findPlacementCandidate(ServerWorld world, Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;

            Block targetBlock;
            if (stack.isOf(Items.CHEST)) {
                targetBlock = Blocks.CHEST;
            } else if (stack.isOf(Items.CRAFTING_TABLE)) {
                targetBlock = Blocks.CRAFTING_TABLE;
            } else {
                continue;
            }

            Optional<BlockPos> location = findPlacementLocation(world, targetBlock);
            if (location.isEmpty()) continue;

            ItemStack extracted = stack.split(1);
            inventory.setStack(slot, stack);
            inventory.markDirty();
            return Optional.of(new PlacementCandidate(extracted, targetBlock, location.get()));
        }
        return Optional.empty();
    }

    private Optional<BlockPos> findPlacementLocation(ServerWorld world, Block blockToPlace) {
        List<VillagerEntity> villagers = world.getEntitiesByClass(
                VillagerEntity.class,
                new Box(guard.getBlockPos()).expand(RECIPIENT_SCAN_RANGE),
                this::isEligibleProfessionalRecipient
        );

        villagers.sort(Comparator.comparingDouble(guard::squaredDistanceTo));
        for (VillagerEntity recipient : villagers) {
            Optional<GlobalPos> jobSite = recipient.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
            if (jobSite.isEmpty() || !jobSite.get().dimension().equals(world.getRegistryKey())) continue;

            BlockPos jobPos = jobSite.get().pos();
            if (!ProfessionDefinitions.isExpectedJobBlock(recipient.getVillagerData().getProfession(), world.getBlockState(jobPos))) {
                continue;
            }

            if (blockToPlace == Blocks.CHEST && JobBlockPairingHelper.findNearbyChest(world, jobPos).isPresent()) continue;
            if (blockToPlace == Blocks.CRAFTING_TABLE && hasNearbyCraftingTable(world, jobPos)) continue;

            for (BlockPos candidate : List.of(jobPos.north(), jobPos.south(), jobPos.east(), jobPos.west())) {
                if (!jobPos.isWithinDistance(candidate, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE)) continue;
                if (canPlaceBlockAt(world, candidate, blockToPlace.getDefaultState())) {
                    return Optional.of(candidate.toImmutable());
                }
            }
        }

        return Optional.empty();
    }

    private boolean hasNearbyCraftingTable(ServerWorld world, BlockPos center) {
        int range = (int) Math.ceil(JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE);
        for (BlockPos candidate : BlockPos.iterate(center.add(-range, -range, -range), center.add(range, range, range))) {
            if (!center.isWithinDistance(candidate, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE)) continue;
            if (world.getBlockState(candidate).isOf(Blocks.CRAFTING_TABLE)) return true;
        }
        return false;
    }

    private boolean canPlaceBlockAt(ServerWorld world, BlockPos candidate, BlockState state) {
        if (!world.getBlockState(candidate).isReplaceable()) return false;
        if (!state.canPlaceAt(world, candidate)) return false;
        if (!world.getBlockState(candidate.up()).isAir()) return false;
        return world.getBlockState(candidate).getFluidState().isEmpty()
                && world.getBlockState(candidate.up()).getFluidState().isEmpty();
    }

    private boolean isEligibleProfessionalRecipient(VillagerEntity recipient) {
        VillagerProfession profession = recipient.getVillagerData().getProfession();
        return recipient.isAlive()
                && !recipient.isBaby()
                && profession != VillagerProfession.NONE
                && profession != VillagerProfession.NITWIT;
    }

    private boolean hasPairedFurnace() {
        if (guard.getPairedFurnacePos() == null || !(guard.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        return world.getBlockState(guard.getPairedFurnacePos()).isOf(Blocks.FURNACE);
    }

    private List<TransferTarget> findRecipientChests(ServerWorld world, Set<VillagerProfession> professions) {
        List<TransferTarget> targets = new ArrayList<>();
        List<VillagerEntity> villagers = world.getEntitiesByClass(
                VillagerEntity.class,
                new Box(guard.getBlockPos()).expand(RECIPIENT_SCAN_RANGE),
                candidate -> candidate.isAlive()
                        && !candidate.isBaby()
                        && professions.contains(candidate.getVillagerData().getProfession())
        );

        for (VillagerEntity recipient : villagers) {
            Optional<GlobalPos> jobSiteMemory = recipient.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
            if (jobSiteMemory.isEmpty()) continue;

            GlobalPos globalPos = jobSiteMemory.get();
            if (!globalPos.dimension().equals(world.getRegistryKey())) continue;

            BlockPos recipientJobPos = globalPos.pos();
            VillagerProfession profession = recipient.getVillagerData().getProfession();
            if (!ProfessionDefinitions.isExpectedJobBlock(profession, world.getBlockState(recipientJobPos))) continue;

            Optional<BlockPos> recipientChest = JobBlockPairingHelper.findNearbyChest(world, recipientJobPos);
            if (recipientChest.isEmpty()) continue;
            if (recipientChest.get().equals(guard.getChestPos())) continue;

            targets.add(new TransferTarget(recipient.getUuid(), recipientChest.get().toImmutable(), guard.squaredDistanceTo(recipient)));
        }

        targets.sort(Comparator.comparingDouble(TransferTarget::sourceDistance)
                .thenComparing(TransferTarget::villagerId, UUID::compareTo));
        return targets;
    }

    private Optional<Inventory> getChestInventory(ServerWorld world, BlockPos chestPos) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, chestPos, true));
    }

    private boolean isInventoryAtLeastFull(Inventory inventory, double threshold) {
        int usedSlots = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (!inventory.getStack(slot).isEmpty()) {
                usedSlots++;
            }
        }
        return usedSlots >= Math.ceil(inventory.size() * threshold);
    }

    private void moveTo(BlockPos pos) {
        if (pos == null) return;
        guard.getNavigation().startMovingTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, MOVE_SPEED);
    }

    private boolean isNear(BlockPos target) {
        return target != null && guard.squaredDistanceTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    private void clearPendingState() {
        pendingItem = ItemStack.EMPTY;
        pendingTargetPos = null;
        pendingTargetId = null;
        pendingPlacement = false;
        pendingPlacementBlock = null;
        pendingFullnessTransfer = false;
    }

    private enum Stage { IDLE, MOVE_TO_TARGET, TRANSFER, DONE }

    private record DirectedCandidate(ItemStack extracted, TransferTarget target) {}

    private record PlacementCandidate(ItemStack extracted, Block block, BlockPos placementPos) {}

    private record TransferTarget(UUID villagerId, BlockPos chestPos, double sourceDistance) {}
}
