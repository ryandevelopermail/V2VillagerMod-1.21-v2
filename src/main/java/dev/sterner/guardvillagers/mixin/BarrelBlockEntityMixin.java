package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.villager.behavior.FishermanBehavior;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BarrelBlockEntity.class)
public class BarrelBlockEntityMixin {

    @Inject(method = "setStack(ILnet/minecraft/item/ItemStack;)V", at = @At("TAIL"), require = 0)
    private void guardvillagers$onBarrelSetStack(int slot, ItemStack stack, CallbackInfo ci) {
        guardvillagers$notifyBarrelMutation();
    }

    @Inject(method = "removeStack(I)Lnet/minecraft/item/ItemStack;", at = @At("TAIL"), require = 0)
    private void guardvillagers$onBarrelRemoveStack(int slot, CallbackInfoReturnable<ItemStack> cir) {
        guardvillagers$notifyBarrelMutation();
    }

    @Inject(method = "removeStack(II)Lnet/minecraft/item/ItemStack;", at = @At("TAIL"), require = 0)
    private void guardvillagers$onBarrelRemoveStackAmount(int slot, int amount, CallbackInfoReturnable<ItemStack> cir) {
        guardvillagers$notifyBarrelMutation();
    }

    private void guardvillagers$notifyBarrelMutation() {
        if ((Object) this instanceof BarrelBlockEntity barrel && barrel.getWorld() instanceof ServerWorld serverWorld) {
            FishermanBehavior.onBarrelInventoryMutated(serverWorld, barrel.getPos());
        }
    }
}
