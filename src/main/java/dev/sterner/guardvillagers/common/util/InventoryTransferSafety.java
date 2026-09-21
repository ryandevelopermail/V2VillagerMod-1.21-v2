package dev.sterner.guardvillagers.common.util;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Shared item-transfer primitives that preserve ownership of extracted stacks.
 */
public final class InventoryTransferSafety {

    private InventoryTransferSafety() {
    }

    /**
     * Inserts as much of {@code stack} as possible and returns an owned copy of the remainder.
     */
    public static ItemStack insertStack(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < inventory.size() && !remaining.isEmpty(); slot++) {
            ItemStack existing = inventory.getStack(slot);
            if (existing.isEmpty()) {
                if (!inventory.isValid(slot, remaining)) {
                    continue;
                }
                int moved = Math.min(remaining.getCount(), remaining.getMaxCount());
                inventory.setStack(slot, remaining.copyWithCount(moved));
                remaining.decrement(moved);
            } else if (ItemStack.areItemsAndComponentsEqual(existing, remaining)
                    && inventory.isValid(slot, remaining)) {
                int space = existing.getMaxCount() - existing.getCount();
                if (space > 0) {
                    int moved = Math.min(space, remaining.getCount());
                    existing.increment(moved);
                    remaining.decrement(moved);
                }
            }
        }
        return remaining;
    }

    /**
     * Returns the number of items from {@code stack} that the inventory can accept now.
     */
    public static int insertableCount(Inventory inventory, ItemStack stack, int limit) {
        if (stack.isEmpty() || limit <= 0) {
            return 0;
        }
        int capacity = 0;
        for (int slot = 0; slot < inventory.size() && capacity < limit; slot++) {
            ItemStack existing = inventory.getStack(slot);
            if (existing.isEmpty()) {
                if (inventory.isValid(slot, stack)) {
                    capacity += Math.min(stack.getMaxCount(), limit - capacity);
                }
            } else if (ItemStack.areItemsAndComponentsEqual(existing, stack)
                    && inventory.isValid(slot, stack)) {
                int space = Math.max(0, existing.getMaxCount() - existing.getCount());
                capacity += Math.min(space, limit - capacity);
            }
        }
        return capacity;
    }

    /**
     * Materializes every carried stack using the established recovery hierarchy:
     * source inventory, villager inventory, then an intentional world drop.
     * The supplied payload is emptied only as each stack receives a concrete owner.
     */
    public static void recoverPayload(VillagerEntity villager,
                                      @Nullable Inventory sourceInventory,
                                      List<ItemStack> payload) {
        Inventory villagerInventory = villager.getInventory();
        while (!payload.isEmpty()) {
            ItemStack carried = payload.get(0);
            ItemStack remaining = carried.copy();

            if (sourceInventory != null) {
                remaining = insertStack(sourceInventory, remaining);
                payload.set(0, remaining);
            }
            if (!remaining.isEmpty() && sourceInventory != villagerInventory) {
                remaining = insertStack(villagerInventory, remaining);
                payload.set(0, remaining);
            }
            if (!remaining.isEmpty()) {
                villager.dropStack(remaining.copy());
            }
            payload.remove(0);
        }

        if (sourceInventory != null) {
            sourceInventory.markDirty();
        }
        if (sourceInventory != villagerInventory) {
            villagerInventory.markDirty();
        }
    }
}
