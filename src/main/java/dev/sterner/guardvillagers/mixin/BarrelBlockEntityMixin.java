package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.villager.behavior.FishermanBehavior;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockEntity.class)
public class BarrelBlockEntityMixin {

    @Inject(method = "markDirty()V", at = @At("TAIL"), require = 0)
    private void guardvillagers$onBarrelInventoryChanged(CallbackInfo ci) {
        if ((Object) this instanceof BarrelBlockEntity barrel && barrel.getWorld() instanceof ServerWorld serverWorld) {
            FishermanBehavior.onBarrelInventoryMutated(serverWorld, barrel.getPos());
        }
    }
}
