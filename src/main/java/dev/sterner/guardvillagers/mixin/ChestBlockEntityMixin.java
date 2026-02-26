package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.villager.behavior.ShepherdBehavior;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LootableContainerBlockEntity.class)
public abstract class ChestBlockEntityMixin {
    @Inject(method = "setStack", at = @At("TAIL"))
    private void guardvillagers$notifyShepherdGoalsOnSetStack(int slot, ItemStack stack, CallbackInfo ci) {
        guardvillagers$notifyShepherdGoalsOnChestMutation();
    }

    @Inject(method = "removeStack(II)Lnet/minecraft/item/ItemStack;", at = @At("TAIL"))
    private void guardvillagers$notifyShepherdGoalsOnSplitRemove(int slot, int amount, CallbackInfoReturnable<ItemStack> cir) {
        guardvillagers$notifyShepherdGoalsOnChestMutation();
    }

    @Inject(method = "removeStack(I)Lnet/minecraft/item/ItemStack;", at = @At("TAIL"))
    private void guardvillagers$notifyShepherdGoalsOnRemove(int slot, CallbackInfoReturnable<ItemStack> cir) {
        guardvillagers$notifyShepherdGoalsOnChestMutation();
    }

    @Inject(method = "clear", at = @At("TAIL"))
    private void guardvillagers$notifyShepherdGoalsOnClear(CallbackInfo ci) {
        guardvillagers$notifyShepherdGoalsOnChestMutation();
    }

    private void guardvillagers$notifyShepherdGoalsOnChestMutation() {
        if (!((Object) this instanceof ChestBlockEntity chest)) {
            return;
        }
        World world = chest.getWorld();
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        BlockPos chestPos = chest.getPos();
        ShepherdBehavior.notifyChestInventoryChanged(serverWorld, chestPos);
    }
}
