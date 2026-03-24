package dev.sterner.guardvillagers.common.util;

import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class CartographerMapChestUtil {
    private CartographerMapChestUtil() {
    }

    public static int countFilledMapsInChest(ServerWorld world, BlockPos chestPos) {
        Inventory inventory = DistributionInventoryAccess.getChestInventory(world, chestPos).orElse(null);
        if (inventory == null) {
            return 0;
        }

        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(Items.FILLED_MAP)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public static boolean hasAtLeastOneFilledMapInChest(ServerWorld world, BlockPos chestPos) {
        return countFilledMapsInChest(world, chestPos) >= 1;
    }
}
