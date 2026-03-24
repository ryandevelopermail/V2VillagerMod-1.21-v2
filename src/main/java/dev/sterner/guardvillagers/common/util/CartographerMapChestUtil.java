package dev.sterner.guardvillagers.common.util;

import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * Computes world-coordinate map bounds for every populated FILLED_MAP stack in the chest.
     */
    public static List<VillageMappedBoundsState.MappedBounds> collectPopulatedMapBounds(ServerWorld world, BlockPos chestPos) {
        Inventory inventory = DistributionInventoryAccess.getChestInventory(world, chestPos).orElse(null);
        if (inventory == null) {
            return List.of();
        }

        List<VillageMappedBoundsState.MappedBounds> result = new ArrayList<>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isOf(Items.FILLED_MAP)) {
                continue;
            }

            MapState state = FilledMapItem.getMapState(stack, world);
            if (state == null) {
                continue;
            }

            int mapSize = 128 * (1 << state.scale);
            int half = mapSize / 2;
            result.add(new VillageMappedBoundsState.MappedBounds(
                    state.centerX - half,
                    state.centerX + half,
                    state.centerZ - half,
                    state.centerZ + half
            ));
        }
        return result;
    }
}
