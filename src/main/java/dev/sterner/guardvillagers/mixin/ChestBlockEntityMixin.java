package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.villager.behavior.ChestInventoryChangeDispatcher;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChestBlockEntity.class)
public abstract class ChestBlockEntityMixin {
    @Inject(method = {
            "setStack(ILnet/minecraft/item/ItemStack;)V",
            "clear()V"
    }, at = @At("TAIL"))
    private void guardvillagers$notifyShepherdListenersOnVoidMutation(CallbackInfo ci) {
        guardvillagers$notifyShepherdListeners();
    }

    @Inject(method = {
            "removeStack(II)Lnet/minecraft/item/ItemStack;",
            "removeStack(I)Lnet/minecraft/item/ItemStack;"
    }, at = @At("TAIL"))
    private void guardvillagers$notifyShepherdListenersOnReturnMutation(CallbackInfoReturnable<ItemStack> cir) {
        guardvillagers$notifyShepherdListeners();
    }

    private void guardvillagers$notifyShepherdListeners() {
        BlockEntity self = (BlockEntity) (Object) this;
        World world = self.getWorld();
        if (world instanceof ServerWorld serverWorld) {
            ChestInventoryChangeDispatcher.notifyChestMarkedDirty(serverWorld, self.getPos());
        }
    }
}
