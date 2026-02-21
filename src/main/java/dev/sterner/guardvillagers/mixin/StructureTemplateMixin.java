package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.util.VillageBellChestPlacementHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.ServerWorldAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(StructureTemplate.class)
public class StructureTemplateMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger(StructureTemplateMixin.class);

    @Inject(method = "place(Lnet/minecraft/world/ServerWorldAccess;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/structure/StructurePlacementData;Lnet/minecraft/util/math/random/Random;I)Z", at = @At("RETURN"))
    private void guardvillagers$placeVillageChestNearBell(ServerWorldAccess world, BlockPos pos, BlockPos pivot, StructurePlacementData placementData, Random random, int flags, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }

        StructureTemplate structureTemplate = (StructureTemplate) (Object) this;
        List<StructureTemplate.StructureBlockInfo> bells = structureTemplate.getInfosForBlock(pos, placementData, Blocks.BELL);
        LOGGER.debug("Structure placement at {} resolved {} bell(s) in template output", pos.toShortString(), bells.size());

        for (StructureTemplate.StructureBlockInfo bellInfo : bells) {
            BlockPos bellPos = bellInfo.pos().toImmutable();
            BlockState bellState = bellInfo.state();
            LOGGER.debug("Passing resolved village bell {} to chest placement helper", bellPos.toShortString());
            VillageBellChestPlacementHelper.tryPlaceChestForVillageBell(world, bellPos, bellState, placementData);
        }
    }
}
