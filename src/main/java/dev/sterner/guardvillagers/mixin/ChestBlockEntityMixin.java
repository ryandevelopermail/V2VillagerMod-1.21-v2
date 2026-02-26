package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.villager.behavior.ShepherdBehavior;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChestBlockEntity.class)
public class ChestBlockEntityMixin {

    @Inject(method = "markDirty", at = @At("TAIL"))
    private void guardvillagers$onChestMarkDirty(CallbackInfo ci) {
        ChestBlockEntity chest = (ChestBlockEntity) (Object) this;
        if (chest.getWorld() instanceof ServerWorld serverWorld) {
            ShepherdBehavior.onChestInventoryMutated(serverWorld, chest.getPos());
        }
    }
}
