package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.villager.behavior.ArmorerBehavior;
import dev.sterner.guardvillagers.common.villager.behavior.ButcherBehavior;
import dev.sterner.guardvillagers.common.villager.behavior.CartographerBehavior;
import dev.sterner.guardvillagers.common.villager.behavior.LumberjackBehavior;
import dev.sterner.guardvillagers.common.villager.behavior.MasonBehavior;
import dev.sterner.guardvillagers.common.villager.behavior.ShepherdBehavior;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LootableContainerBlockEntity.class)
public class ChestBlockEntityMixin {

    @Inject(method = "setStack(ILnet/minecraft/item/ItemStack;)V", at = @At("TAIL"), require = 0)
    private void guardvillagers$onChestSetStack(int slot, ItemStack stack, CallbackInfo ci) {
        guardvillagers$notifyChestMutation();
    }

    @Inject(method = "removeStack(I)Lnet/minecraft/item/ItemStack;", at = @At("TAIL"), require = 0)
    private void guardvillagers$onChestRemoveStack(int slot, CallbackInfoReturnable<ItemStack> cir) {
        guardvillagers$notifyChestMutation();
    }

    @Inject(method = "removeStack(II)Lnet/minecraft/item/ItemStack;", at = @At("TAIL"), require = 0)
    private void guardvillagers$onChestRemoveStackAmount(int slot, int amount, CallbackInfoReturnable<ItemStack> cir) {
        guardvillagers$notifyChestMutation();
    }

    private void guardvillagers$notifyChestMutation() {
        if ((Object) this instanceof ChestBlockEntity chest && chest.getWorld() instanceof ServerWorld serverWorld) {
            ShepherdBehavior.onChestInventoryMutated(serverWorld, chest.getPos());
            MasonBehavior.onChestInventoryMutated(serverWorld, chest.getPos());
            ArmorerBehavior.onChestInventoryMutated(serverWorld, chest.getPos());
            ButcherBehavior.onChestInventoryMutated(serverWorld, chest.getPos());
            CartographerBehavior.onChestInventoryMutated(serverWorld, chest.getPos());
            LumberjackBehavior.onChestInventoryMutated(serverWorld, chest.getPos());
        }
    }
}
