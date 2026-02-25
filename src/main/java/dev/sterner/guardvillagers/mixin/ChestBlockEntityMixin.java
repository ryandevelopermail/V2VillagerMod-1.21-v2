package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.villager.behavior.ChestInventoryChangeDispatcher;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChestBlockEntity.class)
public abstract class ChestBlockEntityMixin {
    @Inject(method = "markDirty", at = @At("TAIL"))
    private void guardvillagers$notifyShepherdListeners(CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        World world = self.getWorld();
        if (world instanceof ServerWorld serverWorld) {
            ChestInventoryChangeDispatcher.notifyChestMarkedDirty(serverWorld, self.getPos());
        }
    }
}
