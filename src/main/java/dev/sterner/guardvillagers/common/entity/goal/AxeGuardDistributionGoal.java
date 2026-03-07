package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.AxeGuardEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.EnumSet;
import java.util.Optional;

public class AxeGuardDistributionGoal extends Goal {
    private final AxeGuardEntity guard;
    private long nextCheckTick;

    public AxeGuardDistributionGoal(AxeGuardEntity guard) {
        this.guard = guard;
        setControls(EnumSet.of(Control.MOVE));
    }

    public void requestImmediateDistribution() {
        nextCheckTick = 0L;
    }

    @Override
    public boolean canStart() {
        if (!(guard.getWorld() instanceof ServerWorld world) || guard.getChestPos() == null) {
            return false;
        }
        if (world.getTime() < nextCheckTick) {
            return false;
        }
        nextCheckTick = world.getTime() + 120L;
        return true;
    }

    @Override
    public void start() {
        if (!(guard.getWorld() instanceof ServerWorld world)) {
            return;
        }
        Inventory source = guard.getPairedChestInventory(world).orElse(null);
        if (source == null) {
            return;
        }

        BlockPos targetPos = findOtherChest(world, guard.getChestPos()).orElse(null);
        if (targetPos == null) {
            return;
        }
        Inventory target = getChestInventory(world, targetPos).orElse(null);
        if (target == null) {
            return;
        }

        for (int i = 0; i < source.size(); i++) {
            ItemStack stack = source.getStack(i);
            if (stack.isEmpty() || (!stack.isOf(Items.STICK) && !stack.isOf(Items.CHARCOAL))) {
                continue;
            }
            int moveCount = Math.min(16, stack.getCount());
            ItemStack extracted = stack.split(moveCount);
            ItemStack remaining = guard.insertIntoInventory(target, extracted);
            if (!remaining.isEmpty()) {
                guard.insertIntoInventory(source, remaining);
            }
            source.markDirty();
            target.markDirty();
            return;
        }
    }

    private Optional<BlockPos> findOtherChest(ServerWorld world, BlockPos sourceChest) {
        for (BlockPos pos : BlockPos.iterate(sourceChest.add(-8, -2, -8), sourceChest.add(8, 2, 8))) {
            if (pos.equals(sourceChest)) {
                continue;
            }
            if (world.getBlockState(pos).getBlock() instanceof ChestBlock) {
                return Optional.of(pos.toImmutable());
            }
        }
        return Optional.empty();
    }

    private Optional<Inventory> getChestInventory(ServerWorld world, BlockPos chestPos) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, chestPos, true));
    }
}
