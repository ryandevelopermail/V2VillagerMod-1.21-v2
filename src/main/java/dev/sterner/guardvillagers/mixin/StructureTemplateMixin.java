package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.util.VillageBellChestPlacementHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StructureTemplate.class)
public class StructureTemplateMixin {

    @Inject(method = "place(Lnet/minecraft/world/StructureWorldAccess;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/structure/StructurePlacementData;Lnet/minecraft/util/math/random/Random;I)Z", at = @At("RETURN"))
    private void guardvillagers$placeVillageChestNearBell(StructureWorldAccess world, BlockPos pos, BlockPos pivot, StructurePlacementData placementData, Random random, int flags, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }

        int radius = 12;
        for (BlockPos checkPos : BlockPos.iterate(pos.add(-radius, -3, -radius), pos.add(radius, 3, radius))) {
            BlockState blockState = world.getBlockState(checkPos);
            if (!blockState.isOf(Blocks.BELL)) {
                continue;
            }

            VillageBellChestPlacementHelper.tryPlaceChestForVillageBell(world, checkPos.toImmutable(), blockState, placementData);
        }
    }
}
