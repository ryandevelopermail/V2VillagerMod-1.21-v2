package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.AxeGuardEntity;
import net.minecraft.block.entity.FurnaceBlockEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;

import java.util.EnumSet;

public class AxeGuardFurnaceGoal extends Goal {
    private final AxeGuardEntity guard;
    private long nextCheckTick;

    public AxeGuardFurnaceGoal(AxeGuardEntity guard) {
        this.guard = guard;
        setControls(EnumSet.of(Control.MOVE));
    }

    public void requestImmediateCheck() {
        nextCheckTick = 0L;
    }

    @Override
    public boolean canStart() {
        if (!(guard.getWorld() instanceof ServerWorld world) || guard.getPairedFurnacePos() == null || guard.getChestPos() == null) {
            return false;
        }
        if (world.getTime() < nextCheckTick) {
            return false;
        }
        nextCheckTick = world.getTime() + 20L;
        return true;
    }

    @Override
    public void start() {
        if (!(guard.getWorld() instanceof ServerWorld world)) {
            return;
        }
        Inventory chest = guard.getPairedChestInventory(world).orElse(null);
        FurnaceBlockEntity furnace = guard.getPairedFurnace(world).orElse(null);
        if (chest == null || furnace == null) {
            return;
        }

        if (furnace.getStack(0).isEmpty()) {
            int moved = guard.extractToStack(chest, stack -> stack.isIn(ItemTags.LOGS_THAT_BURN), 8, extracted -> {
                ItemStack input = furnace.getStack(0);
                if (input.isEmpty()) {
                    furnace.setStack(0, extracted);
                } else if (ItemStack.areItemsAndComponentsEqual(input, extracted)) {
                    input.increment(extracted.getCount());
                }
            });
            if (moved > 0) {
                guard.setFurnaceBatchInputLogs(moved);
            }
        }

        ItemStack output = furnace.getStack(2);
        if (output.isOf(Items.CHARCOAL) && !output.isEmpty()) {
            ItemStack rem = guard.insertIntoInventory(chest, output.copy());
            furnace.setStack(2, rem);
        }

        chest.markDirty();
        furnace.markDirty();
    }
}
