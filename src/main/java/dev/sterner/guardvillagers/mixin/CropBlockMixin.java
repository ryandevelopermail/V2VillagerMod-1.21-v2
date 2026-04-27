package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.villager.behavior.FarmerBehavior;
import net.minecraft.block.BlockState;
import net.minecraft.block.CropBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CropBlock.class)
public class CropBlockMixin {

    @Inject(method = "randomTick", at = @At("TAIL"), require = 0)
    private void guardvillagers$onRandomGrowthTick(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        BlockState newState = world.getBlockState(pos);
        FarmerBehavior.onCropStateTransitionToMature(world, pos, state, newState);
    }
}
