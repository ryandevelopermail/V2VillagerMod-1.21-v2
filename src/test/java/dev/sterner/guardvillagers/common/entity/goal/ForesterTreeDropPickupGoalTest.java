package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForesterTreeDropPickupGoalTest {

    @Test
    void canAbsorb_returnsTrueForFullInventoryWhenCandidateCanMergeIntoExistingStack() {
        SimpleInventory inventory = new SimpleInventory(2);
        inventory.setStack(0, new ItemStack(Items.OAK_SAPLING, 63));
        inventory.setStack(1, new ItemStack(Items.DIRT, 64));

        boolean canAbsorb = ForesterTreeDropPickupGoal.canAbsorb(inventory, new ItemStack(Items.OAK_SAPLING, 1));

        assertTrue(canAbsorb);
    }

    @Test
    void canAbsorb_returnsFalseForFullInventoryWithNoMergeableSlots() {
        SimpleInventory inventory = new SimpleInventory(2);
        inventory.setStack(0, new ItemStack(Items.DIRT, 64));
        inventory.setStack(1, new ItemStack(Items.COBBLESTONE, 64));

        boolean canAbsorb = ForesterTreeDropPickupGoal.canAbsorb(inventory, new ItemStack(Items.OAK_SAPLING, 1));

        assertFalse(canAbsorb);
    }

    @Test
    void transferInventoryToInventory_returnsFalseWhenChestHasNoCapacity() {
        SimpleInventory villagerInventory = new SimpleInventory(1);
        villagerInventory.setStack(0, new ItemStack(Items.OAK_SAPLING, 32));
        SimpleInventory chestInventory = new SimpleInventory(1);
        chestInventory.setStack(0, new ItemStack(Items.DIRT, 64));

        boolean changed = ForesterTreeDropPickupGoal.transferInventoryToInventory(villagerInventory, chestInventory);

        assertFalse(changed);
    }
}
