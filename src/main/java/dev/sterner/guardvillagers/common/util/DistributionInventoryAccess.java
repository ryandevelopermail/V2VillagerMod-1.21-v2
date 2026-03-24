package dev.sterner.guardvillagers.common.util;

import net.minecraft.block.ChestBlock;
import net.minecraft.inventory.Inventory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

public final class DistributionInventoryAccess {
    private DistributionInventoryAccess() {
    }

    public static Optional<Inventory> getChestInventory(ServerWorld world, BlockPos chestPos) {
        if (!(world.getBlockState(chestPos).getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, world.getBlockState(chestPos), world, chestPos, false));
    }

    public static double getInventoryFullness(Inventory inventory) {
        long maxCapacity = 0L;
        long usedCapacity = 0L;
        for (int slot = 0; slot < inventory.size(); slot++) {
            var stack = inventory.getStack(slot);
            int slotLimit = Math.min(inventory.getMaxCountPerStack(), stack.isEmpty() ? 64 : stack.getMaxCount());
            maxCapacity += slotLimit;
            if (!stack.isEmpty()) {
                usedCapacity += Math.min(stack.getCount(), slotLimit);
            }
        }
        return maxCapacity > 0L ? (double) usedCapacity / (double) maxCapacity : 0.0D;
    }
}
