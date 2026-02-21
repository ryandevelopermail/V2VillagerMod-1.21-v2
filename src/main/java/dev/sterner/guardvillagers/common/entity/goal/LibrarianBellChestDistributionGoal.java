package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.VillageBellChestPlacementHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.village.VillagerProfession;

import java.util.Optional;

public class LibrarianBellChestDistributionGoal extends AbstractInventoryDistributionGoal {
    private static final int DISTRIBUTION_INTERVAL_TICKS = 600;

    public LibrarianBellChestDistributionGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        super(villager, jobPos, chestPos, craftingTablePos);
    }

    @Override
    protected void scheduleNextCooldown(ServerWorld world) {
        nextCheckTime = world.getTime() + DISTRIBUTION_INTERVAL_TICKS;
        immediateCheckPending = false;
    }

    @Override
    protected boolean isDistributableItem(ItemStack stack) {
        return !stack.isEmpty();
    }

    @Override
    protected boolean canStartWithInventory(ServerWorld world, Inventory inventory) {
        return hasDistributableItem(inventory) && resolveBellChestTarget(world).isPresent();
    }

    @Override
    protected boolean selectPendingTransfer(ServerWorld world, Inventory inventory) {
        if (inventory == null) {
            return false;
        }

        Optional<BlockPos> targetChest = resolveBellChestTarget(world);
        if (targetChest.isEmpty()) {
            return false;
        }

        ItemStack extracted = extractSingleDistributableItem(inventory);
        if (extracted.isEmpty()) {
            return false;
        }

        pendingItem = extracted;
        pendingTargetPos = targetChest.get();
        pendingTargetId = null;
        return true;
    }

    @Override
    protected boolean refreshTargetForPendingItem(ServerWorld world) {
        if (pendingItem.isEmpty()) {
            return false;
        }

        Optional<BlockPos> targetChest = resolveBellChestTarget(world);
        if (targetChest.isEmpty()) {
            return false;
        }

        pendingTargetPos = targetChest.get();
        return true;
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
        return villager.getVillagerData().getProfession() == VillagerProfession.LIBRARIAN;
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

    private Optional<BlockPos> resolveBellChestTarget(ServerWorld world) {
        Optional<GlobalPos> meetingPoint = villager.getBrain().getOptionalMemory(MemoryModuleType.MEETING_POINT);
        if (meetingPoint.isEmpty()) {
            return Optional.empty();
        }

        GlobalPos globalPos = meetingPoint.get();
        if (!globalPos.dimension().equals(world.getRegistryKey())) {
            return Optional.empty();
        }

        BlockPos bellPos = globalPos.pos();
        Optional<BlockPos> mappedChest = VillageBellChestPlacementHelper.getMappedChestPos(world, bellPos);
        if (mappedChest.isPresent() && !mappedChest.get().equals(chestPos)) {
            return mappedChest;
        }

        Optional<BlockPos> reconciledChest = VillageBellChestPlacementHelper.reconcileBellChestForBell(world, bellPos);
        if (reconciledChest.isPresent() && !reconciledChest.get().equals(chestPos)) {
            return reconciledChest;
        }

        return Optional.empty();
    }

    private Optional<Inventory> getChestInventory(ServerWorld world, BlockPos position) {
        BlockState state = world.getBlockState(position);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, position, true));
    }
}
