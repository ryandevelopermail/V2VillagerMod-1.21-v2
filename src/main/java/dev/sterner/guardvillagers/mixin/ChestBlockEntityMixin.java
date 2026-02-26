package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.villager.behavior.ShepherdBehavior;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChestBlockEntity.class)
public abstract class ChestBlockEntityMixin {
    @Inject(method = "markDirty", at = @At("TAIL"))
    private void guardvillagers$notifyShepherdGoalsOnChestMutation(CallbackInfo ci) {
        ChestBlockEntity chest = (ChestBlockEntity) (Object) this;
        World world = chest.getWorld();
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        BlockPos chestPos = chest.getPos();
        ShepherdBehavior.notifyChestInventoryChanged(serverWorld, chestPos);
    }
}
