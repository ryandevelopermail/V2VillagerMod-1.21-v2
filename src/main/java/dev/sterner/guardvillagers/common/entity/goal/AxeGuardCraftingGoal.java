package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.AxeGuardEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;

import java.util.EnumSet;

public class AxeGuardCraftingGoal extends Goal {
    private final AxeGuardEntity guard;
    private long nextCheckTick;

    public AxeGuardCraftingGoal(AxeGuardEntity guard) {
        this.guard = guard;
        setControls(EnumSet.of(Control.MOVE));
    }

    public void requestImmediateCheck() {
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
        nextCheckTick = world.getTime() + 80L;
        return true;
    }

    @Override
    public void start() {
        if (!(guard.getWorld() instanceof ServerWorld world)) {
            return;
        }
        Inventory inventory = guard.getPairedChestInventory(world).orElse(null);
        if (inventory == null) {
            return;
        }

        int planks = guard.countMatching(inventory, stack -> stack.isIn(ItemTags.PLANKS));
        if (planks >= 2) {
            int consumed = guard.consumeMatching(inventory, stack -> stack.isIn(ItemTags.PLANKS), planks / 2);
            if (consumed > 0) {
                guard.insertIntoInventory(inventory, new ItemStack(Items.STICK, consumed * 2));
            }
        }

        int sticks = guard.countMatching(inventory, stack -> stack.isOf(Items.STICK));
        int iron = guard.countMatching(inventory, stack -> stack.isOf(Items.IRON_INGOT));
        if (sticks >= 2 && iron >= 3) {
            guard.consumeMatching(inventory, stack -> stack.isOf(Items.STICK), 2);
            guard.consumeMatching(inventory, stack -> stack.isOf(Items.IRON_INGOT), 3);
            guard.insertIntoInventory(inventory, new ItemStack(Items.IRON_AXE));
        }

        inventory.markDirty();
    }
}
