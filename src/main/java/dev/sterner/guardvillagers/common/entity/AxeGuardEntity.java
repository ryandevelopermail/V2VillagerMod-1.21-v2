package dev.sterner.guardvillagers.common.entity;

import dev.sterner.guardvillagers.common.util.GearGradeComparator;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;
import java.util.Optional;

public class AxeGuardEntity extends GuardEntity {
    private static final int AXE_UPGRADE_CHECK_INTERVAL = 20;
    private BlockPos pairedChestPos;
    private BlockPos pairedJobPos;

    public AxeGuardEntity(EntityType<? extends GuardEntity> type, World world) {
        super(type, world);
    }

    public void setPairedChestPos(BlockPos chestPos) {
        this.pairedChestPos = chestPos == null ? null : chestPos.toImmutable();
    }

    public void setPairedJobPos(BlockPos jobPos) {
        this.pairedJobPos = jobPos == null ? null : jobPos.toImmutable();
    }

    public BlockPos getPairedChestPos() {
        return pairedChestPos;
    }

    public BlockPos getPairedJobPos() {
        return pairedJobPos;
    }

    @Override
    public List<ItemStack> getStacksFromLootTable(EquipmentSlot slot, ServerWorld serverWorld) {
        return List.of();
    }

    @Override
    public void tick() {
        super.tick();
        this.setTarget(null);

        if (this.getWorld().isClient || this.age % AXE_UPGRADE_CHECK_INTERVAL != 0) {
            return;
        }

        updateMainHandAxeFromChest();
    }

    private void updateMainHandAxeFromChest() {
        if (!(this.getWorld() instanceof ServerWorld serverWorld) || this.pairedChestPos == null) {
            return;
        }

        Optional<InventorySlot> bestAxe = findBestAxeInChest(serverWorld);
        if (bestAxe.isEmpty()) {
            return;
        }

        ItemStack current = this.getMainHandStack();
        ItemStack candidate = bestAxe.get().stack;
        boolean shouldReplace = !(current.getItem() instanceof AxeItem)
                || GearGradeComparator.isUpgrade(candidate, current, EquipmentSlot.MAINHAND);

        if (!shouldReplace) {
            return;
        }

        Optional<net.minecraft.inventory.Inventory> inventoryOptional = getChestInventory(serverWorld, this.pairedChestPos);
        if (inventoryOptional.isEmpty()) {
            return;
        }

        net.minecraft.inventory.Inventory inventory = inventoryOptional.get();
        ItemStack replacement = inventory.getStack(bestAxe.get().slot).copyWithCount(1);

        ItemStack previous = current.copyWithCount(1);
        if (!previous.isEmpty() && previous.getItem() instanceof AxeItem && !tryInsertIntoInventory(inventory, previous)) {
            return;
        }

        inventory.getStack(bestAxe.get().slot).decrement(1);
        this.equipStack(EquipmentSlot.MAINHAND, replacement);
        this.equipStack(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        inventory.markDirty();
    }

    private Optional<InventorySlot> findBestAxeInChest(ServerWorld world) {
        Optional<net.minecraft.inventory.Inventory> inventoryOptional = getChestInventory(world, this.pairedChestPos);
        if (inventoryOptional.isEmpty()) {
            return Optional.empty();
        }

        net.minecraft.inventory.Inventory inventory = inventoryOptional.get();
        InventorySlot best = null;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof AxeItem)) {
                continue;
            }

            if (best == null || GearGradeComparator.isUpgrade(stack, best.stack, EquipmentSlot.MAINHAND)) {
                best = new InventorySlot(slot, stack.copyWithCount(1));
            }
        }

        return Optional.ofNullable(best);
    }

    private static Optional<net.minecraft.inventory.Inventory> getChestInventory(ServerWorld world, BlockPos chestPos) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }

        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, chestPos, true));
    }

    private static boolean tryInsertIntoInventory(net.minecraft.inventory.Inventory inventory, ItemStack stack) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack existing = inventory.getStack(slot);
            if (existing.isEmpty()) {
                inventory.setStack(slot, stack.copy());
                return true;
            }

            if (ItemStack.areItemsAndComponentsEqual(existing, stack) && existing.getCount() < existing.getMaxCount()) {
                int transferable = Math.min(stack.getCount(), existing.getMaxCount() - existing.getCount());
                existing.increment(transferable);
                stack.decrement(transferable);
                if (stack.isEmpty()) {
                    return true;
                }
            }
        }

        return stack.isEmpty();
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("AxeGuardPairedChestX")) {
            this.pairedChestPos = new BlockPos(nbt.getInt("AxeGuardPairedChestX"), nbt.getInt("AxeGuardPairedChestY"), nbt.getInt("AxeGuardPairedChestZ"));
        } else {
            this.pairedChestPos = null;
        }

        if (nbt.contains("AxeGuardPairedJobX")) {
            this.pairedJobPos = new BlockPos(nbt.getInt("AxeGuardPairedJobX"), nbt.getInt("AxeGuardPairedJobY"), nbt.getInt("AxeGuardPairedJobZ"));
        } else {
            this.pairedJobPos = null;
        }
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        if (this.pairedChestPos != null) {
            nbt.putInt("AxeGuardPairedChestX", this.pairedChestPos.getX());
            nbt.putInt("AxeGuardPairedChestY", this.pairedChestPos.getY());
            nbt.putInt("AxeGuardPairedChestZ", this.pairedChestPos.getZ());
        }

        if (this.pairedJobPos != null) {
            nbt.putInt("AxeGuardPairedJobX", this.pairedJobPos.getX());
            nbt.putInt("AxeGuardPairedJobY", this.pairedJobPos.getY());
            nbt.putInt("AxeGuardPairedJobZ", this.pairedJobPos.getZ());
        }
    }

    private record InventorySlot(int slot, ItemStack stack) {
    }
}
