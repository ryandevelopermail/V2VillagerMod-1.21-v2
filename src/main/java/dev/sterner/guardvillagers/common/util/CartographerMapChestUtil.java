package dev.sterner.guardvillagers.common.util;

import net.minecraft.inventory.Inventory;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CartographerMapChestUtil {
    private CartographerMapChestUtil() {
    }

    static final class MapSignature {
        private final int centerX;
        private final int centerZ;
        private final int scale;

        private MapSignature(int centerX, int centerZ, int scale) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.scale = scale;
        }

        static MapSignature of(int centerX, int centerZ, int scale) {
            return new MapSignature(centerX, centerZ, scale);
        }

        VillageMappedBoundsState.MappedBounds asBounds() {
            int mapSize = 128 * (1 << this.scale);
            int half = mapSize / 2;
            return new VillageMappedBoundsState.MappedBounds(
                    this.centerX - half,
                    this.centerX + half,
                    this.centerZ - half,
                    this.centerZ + half
            );
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof MapSignature other)) {
                return false;
            }
            return this.centerX == other.centerX
                    && this.centerZ == other.centerZ
                    && this.scale == other.scale;
        }

        @Override
        public int hashCode() {
            int result = Integer.hashCode(this.centerX);
            result = 31 * result + Integer.hashCode(this.centerZ);
            result = 31 * result + Integer.hashCode(this.scale);
            return result;
        }
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
     * Computes world-coordinate map bounds for every unique populated map signature in the chest.
     *
     * <p>Duplicate map copies (same centerX/centerZ/scale) are deduplicated.
     */
    public static List<VillageMappedBoundsState.MappedBounds> collectPopulatedMapBounds(ServerWorld world, BlockPos chestPos) {
        Inventory inventory = DistributionInventoryAccess.getChestInventory(world, chestPos).orElse(null);
        if (inventory == null) {
            return List.of();
        }

        List<MapSignature> signatures = new ArrayList<>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isOf(Items.FILLED_MAP)) {
                continue;
            }

            MapState state = FilledMapItem.getMapState(stack, world);
            if (state == null) {
                continue;
            }

            signatures.add(MapSignature.of(state.centerX, state.centerZ, state.scale));
        }

        return deduplicateMapBounds(signatures);
    }

    static List<VillageMappedBoundsState.MappedBounds> deduplicateMapBounds(List<MapSignature> signatures) {
        if (signatures.isEmpty()) {
            return List.of();
        }

        List<VillageMappedBoundsState.MappedBounds> result = new ArrayList<>(signatures.size());
        Set<MapSignature> seen = new HashSet<>();
        for (MapSignature signature : signatures) {
            if (!seen.add(signature)) {
                continue;
            }
            result.add(signature.asBounds());
        }
        return result;
    }
}
