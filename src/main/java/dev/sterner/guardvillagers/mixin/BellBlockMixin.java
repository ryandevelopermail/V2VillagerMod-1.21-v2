package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.util.VillagerBellTracker;
import net.minecraft.block.BellBlock;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BellBlock.class)
public class BellBlockMixin {

    @Inject(method = "ring(Lnet/minecraft/entity/Entity;Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/Direction;)Z", at = @At("RETURN"))
    private void guardvillagers$onBellRing(@Nullable Entity entity, World world, BlockPos pos, @Nullable Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() && world instanceof ServerWorld serverWorld) {
            VillagerBellTracker.handleBellRung(serverWorld, pos);
        }
    }
}
