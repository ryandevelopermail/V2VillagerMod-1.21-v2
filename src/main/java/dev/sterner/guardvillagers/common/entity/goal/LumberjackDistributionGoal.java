package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.villager.LumberjackProfession;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.decoration.ArmorStandEntity;
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
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class LumberjackDistributionGoal extends AbstractInventoryDistributionGoal {
    private static final Logger LOGGER = LoggerFactory.getLogger(LumberjackDistributionGoal.class);
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

    private @Nullable BlockPos pairedFurnacePos;

    private boolean pendingPlacement;
    private @Nullable Block pendingPlacementBlock;
    private boolean pendingFullnessTransfer;

    private long lastNoTargetChestLogTick;
    private long lastNoTargetTableLogTick;

    public LumberjackDistributionGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, @Nullable BlockPos craftingTablePos) {
        super(villager, jobPos, chestPos, craftingTablePos);
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setPairedFurnacePos(@Nullable BlockPos furnacePos) {
        this.pairedFurnacePos = furnacePos == null ? null : furnacePos.toImmutable();
    }

    @Override
    protected boolean isDistributableItem(ItemStack stack) {
        return !stack.isEmpty();
    }

    @Override
    protected boolean canStartWithInventory(ServerWorld world, Inventory inventory) {
        if (!hasDistributableItem(inventory)) {
            return false;
        }

        if (isInventoryAtLeastFull(inventory, LIBRARIAN_FULLNESS_TRIGGER)
                && !findRecipientChests(world, Set.of(VillagerProfession.LIBRARIAN)).isEmpty()) {
            return true;
        }

        if (findDirectedTransferCandidate(world, inventory).isPresent()) {
            return true;
        }

        return findPlacementCandidate(world, inventory).isPresent();
    }

    @Override
    protected boolean selectPendingTransfer(ServerWorld world, Inventory inventory) {
        if (inventory == null) {
            return false;
        }

        pendingPlacement = false;
        pendingPlacementBlock = null;
        pendingFullnessTransfer = false;

        if (isInventoryAtLeastFull(inventory, LIBRARIAN_FULLNESS_TRIGGER)) {
            Optional<TransferTarget> librarianTarget = findRecipientChests(world, Set.of(VillagerProfession.LIBRARIAN)).stream().findFirst();
            if (librarianTarget.isPresent()) {
                for (int slot = 0; slot < inventory.size(); slot++) {
                    ItemStack stack = inventory.getStack(slot);
                    if (stack.isEmpty()) {
                        continue;
                    }
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
        if (placement.isEmpty()) {
            return false;
        }

        PlacementCandidate candidate = placement.get();
        pendingItem = candidate.extracted();
        pendingTargetPos = candidate.placementPos();
        pendingTargetId = null;
        pendingPlacement = true;
        pendingPlacementBlock = candidate.block();
        return true;
    }

    @Override
    protected boolean refreshTargetForPendingItem(ServerWorld world) {
        if (pendingItem.isEmpty()) {
            return false;
        }

        if (pendingPlacement) {
            if (pendingPlacementBlock == null) {
                return false;
            }
            if (pendingTargetPos != null && canPlaceBlockAt(world, pendingTargetPos, pendingPlacementBlock)) {
                return true;
            }

            Optional<PlacementLocation> newLocation = findPlacementLocation(world, pendingPlacementBlock);
            if (newLocation.isEmpty()) {
                return false;
            }

            pendingTargetPos = newLocation.get().placementPos();
            return true;
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

    @Override
    protected boolean executeTransfer(ServerWorld world) {
        if (pendingItem.isEmpty()) {
            return false;
        }

        if (pendingPlacement) {
            if (pendingPlacementBlock == null || pendingTargetPos == null) {
                return false;
            }

            if (!canPlaceBlockAt(world, pendingTargetPos, pendingPlacementBlock)) {
                return false;
            }

            world.setBlockState(pendingTargetPos, pendingPlacementBlock.getDefaultState());
            if (pendingPlacementBlock == Blocks.CHEST) {
                JobBlockPairingHelper.handlePairingBlockPlacement(world, pendingTargetPos, world.getBlockState(pendingTargetPos));
                LOGGER.info("Lumberjack {} placed distributed chest at {}", villager.getUuidAsString(), pendingTargetPos.toShortString());
            } else if (pendingPlacementBlock == Blocks.CRAFTING_TABLE) {
                JobBlockPairingHelper.handleCraftingTablePlacement(world, pendingTargetPos);
                LOGGER.info("Lumberjack {} placed distributed crafting table at {}", villager.getUuidAsString(), pendingTargetPos.toShortString());
            }
            return true;
        }

        if (pendingTargetPos == null) {
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
        pendingPlacement = false;
        pendingPlacementBlock = null;
        pendingFullnessTransfer = false;
    }

    @Override
    protected boolean matchesProfession(VillagerEntity villager) {
        return villager.getVillagerData().getProfession() == LumberjackProfession.LUMBERJACK;
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

    private Optional<DirectedCandidate> findDirectedTransferCandidate(ServerWorld world, Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }

            Set<VillagerProfession> recipients = resolveRecipientSetForStack(stack);
            if (recipients.isEmpty()) {
                continue;
            }

            List<TransferTarget> targets = findRecipientChests(world, recipients);
            if (targets.isEmpty()) {
                continue;
            }

            int toMove = Math.min(stack.getCount(), Math.max(1, stack.getMaxCount() / 4));
            ItemStack extracted = stack.split(toMove);
            inventory.setStack(slot, stack);
            inventory.markDirty();
            return Optional.of(new DirectedCandidate(extracted, targets.getFirst()));
        }

        return Optional.empty();
    }

    private Set<VillagerProfession> resolveRecipientSetForStack(ItemStack stack) {
        if (stack.isOf(Items.STICK)) {
            return STICK_RECIPIENTS;
        }

        if (stack.isOf(Items.CHARCOAL)) {
            return CHARCOAL_RECIPIENTS;
        }

        if (isBurnableLog(stack) && !hasPairedFurnace()) {
            return Set.of(VillagerProfession.LIBRARIAN);
        }

        return Set.of();
    }

    private Optional<PlacementCandidate> findPlacementCandidate(ServerWorld world, Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }

            Block targetBlock;
            if (stack.isOf(Items.CHEST)) {
                targetBlock = Blocks.CHEST;
            } else if (stack.isOf(Items.CRAFTING_TABLE)) {
                targetBlock = Blocks.CRAFTING_TABLE;
            } else {
                continue;
            }

            Optional<PlacementLocation> location = findPlacementLocation(world, targetBlock);
            if (location.isEmpty()) {
                logNoPlacementTarget(world, targetBlock);
                continue;
            }

            ItemStack extracted = stack.split(1);
            inventory.setStack(slot, stack);
            inventory.markDirty();
            return Optional.of(new PlacementCandidate(extracted, targetBlock, location.get().placementPos()));
        }

        return Optional.empty();
    }

    private void logNoPlacementTarget(ServerWorld world, Block block) {
        long now = world.getTime();
        if (block == Blocks.CHEST) {
            if (now - lastNoTargetChestLogTick >= 200L) {
                lastNoTargetChestLogTick = now;
                LOGGER.info("Lumberjack {} has chest to distribute but found no eligible professional job sites", villager.getUuidAsString());
            }
            return;
        }

        if (block == Blocks.CRAFTING_TABLE && now - lastNoTargetTableLogTick >= 200L) {
            lastNoTargetTableLogTick = now;
            LOGGER.info("Lumberjack {} has crafting table to distribute but found no eligible professional job sites", villager.getUuidAsString());
        }
    }

    private Optional<PlacementLocation> findPlacementLocation(ServerWorld world, Block blockToPlace) {
        List<VillagerEntity> villagers = world.getEntitiesByClass(
                VillagerEntity.class,
                new Box(villager.getBlockPos()).expand(RECIPIENT_SCAN_RANGE),
                this::isEligibleProfessionalRecipient
        );

        villagers.sort(Comparator.comparingDouble(villager::squaredDistanceTo));
        for (VillagerEntity recipient : villagers) {
            Optional<GlobalPos> jobSite = recipient.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
            if (jobSite.isEmpty() || !jobSite.get().dimension().equals(world.getRegistryKey())) {
                continue;
            }

            BlockPos jobPos = jobSite.get().pos();
            if (!ProfessionDefinitions.isExpectedJobBlock(recipient.getVillagerData().getProfession(), world.getBlockState(jobPos))) {
                continue;
            }

            if (blockToPlace == Blocks.CHEST && JobBlockPairingHelper.findNearbyChest(world, jobPos).isPresent()) {
                continue;
            }

            if (blockToPlace == Blocks.CRAFTING_TABLE && hasNearbyCraftingTable(world, jobPos)) {
                continue;
            }

            for (BlockPos candidate : List.of(jobPos.north(), jobPos.south(), jobPos.east(), jobPos.west())) {
                if (!jobPos.isWithinDistance(candidate, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE)) {
                    continue;
                }
                if (canPlaceBlockAt(world, candidate, blockToPlace)) {
                    return Optional.of(new PlacementLocation(recipient.getUuid(), candidate.toImmutable()));
                }
            }
        }

        return Optional.empty();
    }

    private boolean hasNearbyCraftingTable(ServerWorld world, BlockPos center) {
        int range = (int) Math.ceil(JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE);
        for (BlockPos candidate : BlockPos.iterate(center.add(-range, -range, -range), center.add(range, range, range))) {
            if (!center.isWithinDistance(candidate, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE)) {
                continue;
            }
            if (world.getBlockState(candidate).isOf(Blocks.CRAFTING_TABLE)) {
                return true;
            }
        }
        return false;
    }

    private boolean canPlaceBlockAt(ServerWorld world, BlockPos candidate, Block block) {
        return canPlaceBlockAt(world, candidate, block.getDefaultState());
    }

    private boolean canPlaceBlockAt(ServerWorld world, BlockPos candidate, BlockState state) {
        if (!world.getBlockState(candidate).isReplaceable()) {
            return false;
        }
        if (!state.canPlaceAt(world, candidate)) {
            return false;
        }
        if (!world.getBlockState(candidate.up()).isAir()) {
            return false;
        }
        return world.getBlockState(candidate).getFluidState().isEmpty()
                && world.getBlockState(candidate.up()).getFluidState().isEmpty();
    }

    private boolean isEligibleProfessionalRecipient(VillagerEntity recipient) {
        VillagerProfession profession = recipient.getVillagerData().getProfession();
        return recipient.isAlive()
                && recipient != villager
                && !recipient.isBaby()
                && profession != VillagerProfession.NONE
                && profession != VillagerProfession.NITWIT
                && profession != LumberjackProfession.LUMBERJACK;
    }

    private boolean isBurnableLog(ItemStack stack) {
        return stack.isIn(ItemTags.LOGS_THAT_BURN) || stack.isIn(ItemTags.LOGS);
    }

    private boolean hasPairedFurnace() {
        if (pairedFurnacePos == null) {
            return false;
        }
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        return world.getBlockState(pairedFurnacePos).isOf(Blocks.FURNACE);
    }

    private List<TransferTarget> findRecipientChests(ServerWorld world, Set<VillagerProfession> professions) {
        List<TransferTarget> targets = new ArrayList<>();

        List<VillagerEntity> villagers = world.getEntitiesByClass(
                VillagerEntity.class,
                new Box(villager.getBlockPos()).expand(RECIPIENT_SCAN_RANGE),
                candidate -> candidate != villager
                        && candidate.isAlive()
                        && !candidate.isBaby()
                        && professions.contains(candidate.getVillagerData().getProfession())
        );

        for (VillagerEntity recipient : villagers) {
            Optional<GlobalPos> jobSiteMemory = recipient.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
            if (jobSiteMemory.isEmpty()) {
                continue;
            }

            GlobalPos globalPos = jobSiteMemory.get();
            if (!globalPos.dimension().equals(world.getRegistryKey())) {
                continue;
            }

            BlockPos recipientJobPos = globalPos.pos();
            VillagerProfession profession = recipient.getVillagerData().getProfession();
            if (!ProfessionDefinitions.isExpectedJobBlock(profession, world.getBlockState(recipientJobPos))) {
                continue;
            }

            Optional<BlockPos> recipientChest = JobBlockPairingHelper.findNearbyChest(world, recipientJobPos);
            if (recipientChest.isEmpty()) {
                continue;
            }
            if (recipientChest.get().equals(chestPos)) {
                continue;
            }

            targets.add(new TransferTarget(
                    recipient.getUuid(),
                    recipientChest.get().toImmutable(),
                    villager.squaredDistanceTo(recipient)
            ));
        }

        targets.sort(Comparator.comparingDouble(TransferTarget::sourceDistance)
                .thenComparing(TransferTarget::villagerId, UUID::compareTo));
        return targets;
    }

    private Optional<Inventory> getChestInventory(ServerWorld world, BlockPos position) {
        BlockState state = world.getBlockState(position);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }

        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, position, true));
    }

    private record DirectedCandidate(ItemStack extracted, TransferTarget target) {
    }

    private record PlacementLocation(UUID villagerId, BlockPos placementPos) {
    }

    private record PlacementCandidate(ItemStack extracted, Block block, BlockPos placementPos) {
    }

    private record TransferTarget(UUID villagerId, BlockPos chestPos, double sourceDistance) {
    }
}

