package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.util.BellChestMappingState;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
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
        if (!cir.getReturnValue() || !(world instanceof ServerWorld serverWorld)) {
            return;
        }

        // Register this bell and resolve its primary.
        // If this bell is a secondary (within 300 blocks of an existing primary), redirect
        // the ring event to the primary bell instead so only one village report is generated
        // and one set of guard/villager assignments fires.
        BellChestMappingState mappingState = BellChestMappingState.get(serverWorld.getServer());
        BlockPos effectiveBellPos = mappingState.registerBellAndGetPrimary(
                serverWorld, pos, VillageGuardStandManager.BELL_EFFECT_RANGE);

        GuardVillagers.onBellRung(serverWorld, effectiveBellPos);
    }
}
