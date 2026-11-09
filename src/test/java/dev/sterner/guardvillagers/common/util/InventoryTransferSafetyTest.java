package dev.sterner.guardvillagers.common.util;

import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InventoryTransferSafetyTest {
    @Test
    void respectsInventoryLimitForEmptyAndOccupiedSlots() {
        SimpleInventory inventory = new SimpleInventory(2) {
            @Override
            public int getMaxCountPerStack() {
                return 16;
            }
        };
        inventory.setStack(0, new ItemStack(Items.BREAD, 10));
        ItemStack payload = new ItemStack(Items.BREAD, 30);

        assertEquals(22, InventoryTransferSafety.insertableCount(inventory, payload, 30));
        ItemStack remaining = InventoryTransferSafety.insertStack(inventory, payload);

        assertEquals(16, inventory.getStack(0).getCount());
        assertEquals(16, inventory.getStack(1).getCount());
        assertEquals(8, remaining.getCount());
        assertEquals(30, payload.getCount());
    }
}
